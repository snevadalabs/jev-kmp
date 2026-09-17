# Recon Ktor transport mechanics

Researched: 2026-09-17

Source of truth: `ktorio/ktor` master @ `37a29f9` (2026-09-15, CHANGELOG top = 3.5.2), shallow clone at `/tmp/ktor`.
All paths below are relative to the repo root. Docs quotes are from `https://ktor.io/docs/client-timeout.html`
(Ktor 3.5.2 site build, page last updated 20 October 2025).

Where I could not prove something from source I say so explicitly and mark it **[unconfirmed]**.

## Source map (the files that matter)

| File | Why |
|---|---|
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpTimeout.kt` | request/connect/socket timeout plugin, lines 118-197 |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpRequestLifecycle.kt` | where `executionContext` is created/completed — decides how long the timer stays armed |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt` | `execute()`, `execute {}`, `body()`; buffered (`fetchResponse`) vs streaming (`fetchStreamingResponse`) |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/SaveBody.kt` | auto-buffering of non-streaming responses |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/call/SavedCall.kt` | `save()`, `allowDoubleReceive = true` |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/call/HttpClientCall.kt` | `body<T>()`, `DoubleReceiveException`, `isSaved` gate |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpResponse.kt` | `bodyAsText/bodyAsBytes/bodyAsChannel`, `headers`, `status` |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClientConfig.kt` | `expectSuccess = false` default (line 125), `followRedirects = true` (line 95) |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpCallValidator.kt` | where `expectSuccess` is read/applied |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/DefaultResponseValidation.kt` | `ClientRequestException` etc., error body text |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpRequestRetry.kt` | built-in retry; `Retry-After` handling |
| `ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpRedirect.kt` | automatic redirects |
| `ktor-http/common/src/io/ktor/http/DateUtils.kt` | `fromHttpToGmtDate()` / `toHttpDate()` |
| `ktor-client/ktor-client-mock/common/src/io/ktor/client/engine/mock/MockEngine.kt`, `MockEngineConfig.kt`, `MockUtils.kt` | test API |
| `ktor-client/ktor-client-tests/common/test/io/ktor/client/tests/HttpTimeoutTest.kt`, `HttpRequestRetryTest.kt`, `SaveBodyTest.kt` | Ktor's own behavioural spec |
| `ktor-client/ktor-client-okhttp|darwin|cio/...` engine sources | per-engine differences |

---

## 1. Per-request timeout — does `HttpTimeout` cover a body that stalls *after* a 200?

### 1a. What the plugin actually does

`HttpTimeout.kt:134-171` (Send-phase interceptor) only does two things: attach a `HttpTimeoutConfig` capability to the
request, and call `applyRequestTimeout`. The real mechanic is a coroutine timer:

```kotlin
// HttpTimeout.kt:178-192
private fun CoroutineScope.applyRequestTimeout(request: HttpRequestBuilder, requestTimeout: Long?) {
    if (requestTimeout == null || requestTimeout == HttpTimeoutConfig.INFINITE_TIMEOUT_MS) return
    val executionContext = request.executionContext
    val killer = launch(CoroutineName("request-timeout")) {
        delay(requestTimeout.milliseconds)
        val cause = HttpRequestTimeoutException(request)
        executionContext.cancel(cause.message!!, cause)     // <-- cancel the whole request job
    }
    request.executionContext.invokeOnCompletion { killer.cancel() }
}
```

So the request timeout is **not** a per-read socket deadline. It is "cancel the request's execution context after N ms".
Everything therefore depends on (a) what `executionContext` covers, and (b) whether the engine ties its connection /
body channel to that job.

`applyRequestTimeout` is only called when `requestTimeoutMillis` is non-null:
`hasNotNullTimeouts()` (`HttpTimeout.kt:145-147`) is false when `install(HttpTimeout)` is called with no values, in
which case **no capability is set and no timer is armed**. Explicit configuration is required. Per-request
`timeout { requestTimeoutMillis = … }` (`HttpTimeout.kt:194`) overrides the install-level value.

### 1b. How long the timer stays armed — the crucial detail

`HttpRequestLifecycle.kt:22-36` wraps the **entire request pipeline** in the execution context and completes it in
`finally`:

```kotlin
on(SetupRequestContext) { request, proceed ->
    val executionContext = SupervisorJob(request.executionContext)
    attachToClientEngineJob(executionContext, client.coroutineContext[Job]!!)
    try { request.executionContext = executionContext; proceed() }
    catch (cause: Throwable) { executionContext.completeExceptionally(cause); throw cause }
    finally { executionContext.complete() }          // <-- only after the pipeline returns
}
```

Two different public call paths return from that pipeline at different moments:

* **Buffered path — `client.get(...)`, `client.request { }`, `client.prepareRequest {}.execute()`**
  `HttpStatement.fetchResponse()` (`HttpStatement.kt:202-212`) calls `client.execute(builder)` **and then**
  `call.save()`. Critically, the whole body is already read *inside* the pipeline: `SaveBody.kt:34-53` intercepts
  `HttpReceivePipeline.Before` and does `call.save().response`, and `SavedCall.save()` (`SavedCall.kt:35-38`) is
  `response.rawContent.readBuffer().readByteArray()`. The receive pipeline runs inside `HttpSendPipeline.Receive`
  inside the request pipeline's `Send` phase (`HttpClient.kt:1370-1375`) — i.e. **inside** the `SetupRequestContext`
  `try`, which is also inside `HttpRequestRetry`'s `on(Send) { proceed(subRequest) }`.
  ⇒ **On the buffered path the request timer is still armed while the body is being read, so a 200-then-stall body is
  cut off by `requestTimeoutMillis`.** Ktor's own test agrees:
  `HttpTimeoutTest.kt:285-297 testGetStreamRequestTimeout` — `requestTimeoutMillis = 1000`, endpoint streams a body
  whose chunks are 4000 ms apart, request is `.body<ByteArray>()`, asserts failure with `IOException`
  (`HttpRequestTimeoutException` is itself an `IOException`).
* **Streaming path — `client.prepareRequest {}.execute { }` / `body<ByteReadChannel>()`**
  `fetchStreamingResponse()` (`HttpStatement.kt:189-198`) sets `skipSaveBody()` and returns after
  `client.execute(builder)`; the `finally` in `HttpRequestLifecycle` has already completed the execution context, so
  the killer coroutine is cancelled (`invokeOnCompletion { killer.cancel() }`) **before** your block reads the body.
  ⇒ **After headers, the request timeout no longer applies to the streaming body.** Only the engine socket timeout
  (OkHttp `readTimeout`, CIO socket timeout, Darwin `NSURLRequest.timeoutInterval`) is left, and on JS/Wasm there is
  no socket timeout at all (§6). **[inferred from source; no Ktor test proves the negative — verify empirically if we
  ever use the streaming path.]**

### 1c. Per attempt, with retries

Yes, per attempt — but only if the plugin ordering rule is respected. `HttpRequestRetry.kt:117` and `:135`:

> "Note that, to retry on timeout exceptions, `HttpTimeout` plugin should be installed after `HttpRequestRetry`."

Because both are Send-phase interceptors, the retry loop's `proceed(subRequest)` re-enters the pipeline with a fresh
`subRequest` and `HttpTimeout`'s interceptor arms a new timer for it (that is exactly why the ordering matters).
Proven by `HttpRequestRetryTest.kt:440-464 testRetryOnExceptionRetriesTimeoutIfSet` (first handler `delay(5000)`,
`requestTimeoutMillis = 1000`, `retryOnException(3, retryOnTimeout = true)` → second handler answers OK) and its
negative twin at `:415-438`. The exception type observed by the caller is `HttpRequestTimeoutException`.

### 1d. What we get, in one line

* Configure `requestTimeoutMillis` (non-null) and use the **buffered** `client.get/post/request` path: a body that
  stalls after a 200 fails the call, twice-covered by `socketTimeoutMillis` if we also set it.
* Do not assume the request timeout protects a streaming body, and **do not assume we can tell "died before headers"
  from "died after a 200 body"** on the buffered path — the response object is never handed to us when the buffered
  read fails. If we need that distinction we must use the streaming path and add our own `withTimeout` around the
  body read.

---

## 2. Body consumption and the correct buffering strategy

* `HttpClient.execute(builder)` is **`internal`** in 3.x (`HttpClient.kt:1417`). The public entry points are
  `HttpStatement.execute()` (buffered), `HttpStatement.execute { }` (streaming), `HttpStatement.body<T>()`,
  and `client.plugin(HttpSend).execute(builder)` (`HttpSend.kt:34`, raw `HttpClientCall`, no SaveBody).
* `HttpStatement.execute()` → `fetchResponse()` → **the body is read fully into memory before it returns**;
  the returned `HttpResponse` is a saved one. Comment at `HttpStatement.kt:207-208`: "Save the body again to make sure
  that it is replayable after pipeline execution".
* `HttpResponse.body<T>()` (`HttpClientCall.kt:159` → `bodyNullable` at `:89-112`) is the single gate that forbids
  double reads:

```kotlin
// HttpClientCall.kt:89-99
public suspend fun bodyNullable(info: TypeInfo): Any? {
    if (response.instanceOf(info.type)) return response
    if (!allowDoubleReceive && !response.isSaved && !received.compareAndSet(false, true)) {
        throw DoubleReceiveException(this)
    }
    val responseData = attributes.getOrNull(CustomResponse) ?: getResponseContent()
    ...
}
```

* A saved response is marked in the receive pipeline: `SaveBody.kt:53 attributes.put(RESPONSE_BODY_SAVED, Unit)`, and
  `isSaved` (`SaveBody.kt:150`) is exactly that flag. So **for `client.get(...)`-style calls the body may be read
  multiple times**; for streaming calls the second read throws `DoubleReceiveException`. Ktor's own spec:
  `SaveBodyTest.kt:27-35` (two `bodyAsText()` on `client.get("/")` both return `"Test"`) vs
  `SaveBodyTest.kt:37-46` (`prepareGet(...).execute { }` → second `bodyAsText()` throws `DoubleReceiveException`).
* `rawContent` is not the API to use for buffered reads: `DefaultHttpResponse.rawContent` is
  `responseData.body as? ByteReadChannel ?: ByteReadChannel.Empty` (`DefaultHttpResponse.kt:23-25`) — for a saved
  response `SavedHttpResponse.rawContent` returns a **new** channel per access (`SavedCall.kt:72-73`), but for a live
  response it's the same single channel.

**Strategy for us:** use the buffered path and read through the pipeline helpers, never `rawContent`:

```kotlin
val response: HttpResponse = client.request {          // fully buffered + replayable
    method = HttpMethod.Post
    url(apiBase + "/v1/system-one")
    contentType(ContentType.Application.Json)
    setBody(jsonBody)                                  // String → ByteArrayContent → replay-safe on retry
}
val status    = response.status.value
val requestId = response.headers["x-typesafe-request-id"]   // Headers are case-insensitive (Headers.kt:52)
val rawBody   = response.bodyAsText()                       // safe on a saved response, call it as often as needed
val parsed    = Json.decodeFromString<Wire>(rawBody)        // or response.body<Wire>() — also allowed, isSaved
```

`bodyAsText()` (`HttpResponse.kt:122-127`) goes through the response pipeline as `body<Source>()` (so charset
conversion via `Content-Type` applies, UTF-8 fallback) and returns the raw text of a non-2xx body unchanged — no
status check happens inside it.

Constraint to respect: the request *body* must be replayable across attempts. `HttpRequestBuilder.takeFrom`
(`HttpRequest.kt:183-193`) copies `body = builder.body` by reference, so a `String`/`ByteArray` body
(`TextContent`/`ByteArrayContent`) is fine; a `ReadChannelContent`/`WriteChannelContent` body would be consumed by
attempt 1 and fail on attempt 2. Always `setBody(String)`/`setBody(ByteArray)`.

---

## 3. Non-2xx handling — status + raw error body + headers on a 429

* Default is `expectSuccess = false` (`HttpClientConfig.kt:125`), and `addDefaultResponseValidation()`
  (`DefaultResponseValidation.kt:24-31`) short-circuits when the request attribute is false:

```kotlin
// DefaultResponseValidation.kt:29-33
val expectSuccess = response.call.attributes[ExpectSuccessAttributeKey]
if (!expectSuccess) { LOGGER.trace { "Skipping default response validation for ${...}" }; return@validateResponse }
```

⇒ With the default config, a 429 is **just a response**: `response.status.value == 429`,
`response.headers["x-typesafe-request-id"]` and `response.bodyAsText()` (the raw error JSON) all work with **no
exception and no double-read problem** (the response is saved, §2). This is exactly what we need. Set it explicitly
in our client config so a future refactor can't flip it.

* Per-request override exists (`HttpCallValidator.kt:215-217`): `HttpRequestBuilder.expectSuccess = true`.
* With `expectSuccess = true`, the throw happens in `HttpCallValidator`'s Send interceptor
  (`HttpCallValidator.kt:142-146`: `val call = proceed(request); validateResponse(call.response); call`) — i.e. from
  `client.get(...)` itself, before the response reaches us. The exception carries a **saved**, replayable response:
  `DefaultResponseValidation.kt:41-60` does `originCall.save()`, reads `exceptionResponse.bodyAsText()`, and throws
  `ClientRequestException` (400..499) / `ServerResponseException` (500..599). `ResponseException` only exposes
  `response` (`:82`) — the cached text is *not* a public property and only appears truncated in `message`
  (200-char-ish message assembly is on the JS side; Ktor just embeds the whole text in the message). So under
  `expectSuccess = true` you recover the error body via `(e as ClientRequestException).response.bodyAsText()`.
* Consequence for our loop: keep `expectSuccess = false`. It avoids the exception dance entirely, keeps the error
  body as data (which we must log and possibly surface in `TypeSafeError.body`), and keeps the status inspectable.
* Note `bodyAsText()` on an *unsaved* response (streaming path) would be a one-shot; on a 4xx from the buffered path
  it is saved, so it's re-readable. Also note `MalformedInputException` is the only decode failure Ktor swallows
  internally (`:46-50`, replaced by `"<body failed decoding>"`) — a wrong-charset body will still surface as an
  exception from our own `bodyAsText()`.

---

## 4. `Retry-After`

* Headers surface as `HttpResponse.headers: Headers` (case-insensitive; `Headers.kt:52 caseInsensitiveName = true`),
  and `HttpHeaders.RetryAfter = "Retry-After"` (`ktor-http/.../HttpHeaders.kt:86`). So:
  `response.headers[HttpHeaders.RetryAfter]` → `String?`.
* **There is no `parseRetryAfter` in Ktor.** The only place Ktor looks at the header is the built-in retry plugin,
  and it handles **integer seconds only**:

```kotlin
// HttpRequestRetry.kt:178-189
public fun delayMillis(respectRetryAfterHeader: Boolean = true, block: HttpRetryDelayContext.(retry: Int) -> Long) {
    delayMillis = {
        if (respectRetryAfterHeader) {
            val retryAfter = response?.headers?.get(HttpHeaders.RetryAfter)?.toLongOrNull()?.times(1000)
            maxOf(block(it), retryAfter ?: 0)
        } else block(it)
    }
}
```

  `grep -rni "retry-after"` over the whole repo returns exactly two hits: the header constant and this snippet.
  HTTP-date form is ignored, and there is no `maxRetryAfterMs` cap.
* Ktor *does* ship HTTP-date parsing we can reuse instead of hand-writing the date branch:
  `ktor-http/common/src/io/ktor/http/DateUtils.kt:22-33` `public fun String.fromHttpToGmtDate(): GMTDate`
  (public, part of `ktor-http`, transitively available to `ktor-client-core`). It tries ~11 formats and calls
  `error("Failed to parse date: …")` on garbage, so wrap it. `GMTDate.timestamp` (`ktor-utils/.../Date.kt:112`) and
  `getTimeMillis()` (`:185`) let us compute a delay:

```kotlin
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.getTimeMillis

fun parseRetryAfterMs(raw: String?, now: Long = getTimeMillis()): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }              // delta-seconds
    return runCatching { value.fromHttpToGmtDate().timestamp - now }.getOrNull()?.coerceAtLeast(0)
}
```

  We still have to hand-write: the non-standard `retry-after-ms` extension header, the clamp policy, and the
  "server delay > our cap → fall back to our own backoff" rule. Ktor gives us nothing there.

---

## 5. Built-in `HttpRequestRetry` — honest comparison

Everything the ticket asks about **is** supported, with caveats:

| Capability | Built-in support | Evidence |
|---|---|---|
| Max retries | `maxRetries` / per policy | `HttpRequestRetry.kt:73`, `:106`, `:139` |
| Custom predicate on response | `retryIf { request, response -> … }` | `:106-114`, sample at `:265-275` |
| Custom predicate on exception | `retryOnExceptionIf { request, cause -> … }` | `:121-129` |
| Header mutation per attempt | `modifyRequest { it.headers["X"] = retryCount.toString() }` | `:65`, applied only on retries at `:344-355`; proven by `HttpRequestRetryTest.kt:73-104 testModifyRequest` (asserts attempt 0 has no header, then `"1"`, `"2"`, `"3"`) |
| Injectable delay | `delay { block }` (used exactly this way in tests to collect delays) | `:244-247`, `HttpRequestRetryTest.kt:25-50` |
| Per-request policy | `HttpRequestBuilder.retry { }` | `:397-406`, `HttpRequestRetryTest.kt:353` |
| Delay sees `Retry-After`/headers | `delayMillis(respectRetryAfterHeader) { … }` receives `HttpRetryDelayContext(response, cause)` | `:178-189`, test `:198-229` asserts `[2000, 1234, 1234]` |
| Observability | `HttpRequestRetryEvent` monitor event with `retryCount`, `response`, `cause` | `:17`, `:372-374`, test `:383` |
| Retry a stalled buffered body | Implicitly yes — the plugin's `on(Send)` wraps `proceed()` and the body buffering happens inside it (§1b), and it deliberately awaits body integrity via `call.response.throwOnInvalidResponseBody()` before accepting an attempt | `:361-375` |

Trap in that last row and in the predicate signature: `retryIf`'s block is **not `suspend`** (`:29`,
`HttpRetryShouldRetryContext.(HttpRequest, HttpResponse) -> Boolean`), so it **cannot read the error body** to decide
whether a 429/5xx is retryable, and it cannot await a header-derived date. It also cannot re-create the request (only
mutate the copied builder), which blocks anything like re-resolving a token into the body.

Verdict: the built-in is more capable than I expected, and it *would* cover most of our loop. The reasons to stay
hand-rolled (and the price we pay) are:

1. We need the error body text on **every** attempt for logging/`TypeSafeError.body`, and we need it *from the
   failure path*, not from a non-suspend predicate. Ktor gives us only the last attempt's data.
2. We need the exact delay policy we shipped in JS (`backoffInitialMs`, `backoffMaxMs`, `backoffJitter`, 
   `maxRetryAfterMs`, `respectRetryAfter`, plus the HTTP-date and `retry-after-ms` forms) and a *unit-testable pure
   function*. `HttpRequestRetry`'s delay is a lambda behind a plugin config, and its `Retry-After` support is
   numeric-only.
3. We need a return value (`TypeSafeError` carrying `status/headers/body/requestId`) rather than "throw or return".
4. We need deterministic, injectable time in tests *and* an explicit attempt counter we own.
5. Cost of hand-rolling is small and we already own the semantics: `client.request { ... }` per attempt, one
   `try/catch` per attempt, our own `sleep`. Timeouts are still Ktor's (per attempt, §1c) because each attempt is a
   separate call.
   What we give up: the built-in's `HttpRequestRetryEvent` monitor hook and its automatic body-integrity await
   (`throwOnInvalidResponseBody`). We can inspect the body ourselves on the buffered path anyway.

If we ever flip to the built-in, the correct config would be (install `HttpRequestRetry` **before** `HttpTimeout`):

```kotlin
install(HttpRequestRetry) {
    maxRetries = 2
    retryIf { _, response -> response.status.value in setOf(408, 429) || response.status.value in 500..599 }
    retryOnExceptionIf { _, cause -> cause is IOException && cause !is CancellationException }
    retryOnException(maxRetries = 2, retryOnTimeout = true)   // requires HttpTimeout installed after
    modifyRequest { it.headers["X-TypeSafe-Retry-Count"] = retryCount.toString() }
    delayMillis { /* our policy, response?.headers available */ }
    delay { /* injectable */ }
}
install(HttpTimeout) { requestTimeoutMillis = 30_000 }
```

(`headers[name] = value` is `StringValuesBuilder.set`, `ktor-utils/.../StringValues.kt:113` — it replaces rather
than appends, unlike the `append` used in Ktor's own sample.)

---

## 6. Engine differences that change our code

* **OkHttp** (`ktor-client-okhttp/jvm/src/.../OkHttpEngine.kt`)
  * A separate `OkHttpClient` is built **per distinct `HttpTimeoutConfig`** (`clientCache = createLRUCache(::createOkHttpClient, {}, config.clientCacheSize)`, `:41`; looked up by capability at `:65`), and when no preconfigured
    client is given each one gets its **own `Dispatcher()`** (`:158-170`). Per-request `timeout { }` values therefore
    churn clients/thread pools (LRU size 10). **Keep one timeout config per client.**
  * `socketTimeoutMillis` → `readTimeout` + `writeTimeout`; `connectTimeoutMillis` → `connectTimeout`
    (`:260-273`). `requestTimeoutMillis` is handled by the plugin, not OkHttp.
  * `OkHttpConfig` defaults (`OkHttpConfig.kt:18-21`): `followRedirects(false)`, `followSslRedirects(false)`,
    `retryOnConnectionFailure(true)` → redirects are Ktor's plugin's responsibility, and OkHttp may silently retry a
    connection failure (relevant if we count attempts: a transport-level retry is invisible to us).
  * Cancellation is wired to the coroutine: `callContext[Job]!!.invokeOnCompletion(true) { call.cancel() }`
    (`OkUtils.kt:29-30`), and the body is piped by `GlobalScope.writer(callContext, channel)` with
    `while (… && context.isActive)` and cancellationCause mapping (`OkHttpEngine.kt:181-205`) → the request timer
    kills a stalled body and the reader sees a mapped exception (SocketTimeout/`HttpRequestTimeoutException`-alike).
  * **[unconfirmed]** OkHttp's own default read timeout (10 s) applies when `socketTimeoutMillis` is not set, because
    Ktor only calls `readTimeout(...)` when the capability has a value (`:268-271`). That is OkHttp's documented
    default, not something I can prove from Ktor's source; measure it before relying on either behaviour.
* **Darwin** (`ktor-client-darwin/darwin/src/...`)
  * No `NSURLSession` configuration is *required*: the engine creates
    `NSURLSessionConfiguration.defaultSessionConfiguration()` itself (`internal/DarwinSession.kt:79-95`) and clears
    cookies (`setHTTPCookieStorage(null)`). `configureSession { }` and `configureRequest { }` exist if we need proxy,
    cellular, etc. If a preconfigured session is supplied it must have a `KtorNSURLSessionDelegate`
    (`DarwinClientEngineConfig.kt:110-140` `usePreconfiguredSession` requires a non-null delegate).
  * `socketTimeoutMillis` → `NSMutableURLRequest.setTimeoutInterval` (seconds, documented as the socket/inactivity
    timeout: `TimeoutUtils.kt:14-27`). **`connectTimeoutMillis` is ignored** — no code path reads it.
  * Request timeout is enforced by the coroutine cancellation, and cancellation turns into `task.cancel()`
    (`internal/DarwinSession.kt:52-54 callContext.job.invokeOnCompletion { cause -> if (cause != null) task.cancel() }`)
    → a stalled body after a 200 is cancelled on the buffered path.
  * Ktor's own tests exclude Darwin from `testGetAfterTimeout` (reuse after timeout) and from
    `testGetRequestTimeoutWithSeparateReceivePerRequestAttributes` (`HttpTimeoutTest.kt:232`, `:254`) →
    **[unconfirmed] request-timeout behaviour on Darwin is not fully trusted upstream**; treat Apple targets as
    "probably fine for our 30 s case, worth one real-network smoke test".
  * `close()` → `session.finishTasksAndInvalidate()` (`DarwinSession.kt:63-68`) → graceful, does not cancel in-flight
    tasks; the coroutine cancel path is what aborts.
* **CIO** (`ktor-client-cio/common/src/...`)
  * Engine-level `requestTimeout` **defaults to 15000 ms** (`CIOEngineConfig.kt:47`) and is used *only when no
    `HttpTimeoutCapability` is on the request* (`Endpoint.kt:347-364`: if the capability exists → `INFINITE_TIMEOUT_MS`;
    `setupTimeout` skips `0` and `INFINITE`). So: installing `HttpTimeout` with a real `requestTimeoutMillis`
    replaces CIO's 15 s default; installing `HttpTimeout` *without* setting anything leaves the 15 s silent deadline
    in place. Our config must therefore always set `requestTimeoutMillis`.
  * `connectTimeoutMillis` → connect attempt timeout, `socketTimeoutMillis` → socket read/write inactivity
    (`Endpoint.kt:198-235`, `retrieveTimeouts` at `:302-312`). Timeout is enforced by cancelling `callContext`
    (`setupTimeout`, `Endpoint.kt:329-340`), which closes socket + input/output (`Endpoint.kt:111-124`) — so a body
    stall after a 200 is aborted on both the buffered and the streaming path (the connection dies, not just the
    reader).
  * Connection pooling: `maxConnectionsCount = 1000` (`CIOEngineConfig.kt:33`), per-route cap in
    `endpoint.maxConnectionsPerRoute`; `close()` closes every `Endpoint` and completes `requestsJob`
    (`CIOEngine.kt:82-90`) → in-flight requests are terminated on engine close.
  * No SOCKS proxy support (`CIOEngine.kt:41-47` throws for non-HTTP proxy types).
* **MockEngine** — supported capabilities include `HttpTimeoutCapability` (`MockEngine.kt:29-33`) but the docs
  limitations table lists MockEngine as `Request Timeout ✅ / Connect Timeout ✖️ / Socket Timeout ✅`.
* **Redirects are Ktor's plugin, on every engine** (`HttpRedirect.kt:50-117`): auto-follows 301/302/303/307/308,
  only when `checkHttpMethod` (default true) allows the method (`ALLOWED_FOR_REDIRECT = {Get, Head}`, `:17`) — i.e.
  **a 307 on our POST is not followed and is returned to us as a 307**, strips `Authorization` on cross-authority
  hops (`:98-103`), and refuses HTTPS→HTTP unless `allowHttpsDowngrade` (`:91-95`). `followRedirects = true` is the
  `HttpClientConfig` default (`:95`). Note there is **no redirect-count limit** in the loop (`grep -rni maxredirect`
  → no hits) — a redirect cycle would spin until the request timeout fires. [`unconfirmed` as an intended design;
  the code has no counter.]
* **`close()` semantics**: `HttpClient.close()` completes `clientJob` and closes plugins (`HttpClient.kt:1476-1491`);
  the engine is closed only when the client job actually completes, and the KDoc explicitly says
  `close()` is non-blocking and that `client.coroutineContext.cancel()` is what terminates ongoing tasks
  (`HttpClient.kt:1455-1470`). Engines also cancel in-flight work via `attachToClientEngineJob`
  (`HttpRequestLifecycle.kt:50-66`) only when the **engine** job fails. ⇒ **Closing the client does not abort an
  in-flight request**; our SDK must cancel the calling scope.
* Docs table, `https://ktor.io/docs/client-timeout.html#limitations` (verbatim, Ktor 3.5.2):
  > "HttpTimeout has some limitations for specific engines. The table below shows which timeouts are supported by
  > those engines."
  > Engine | Request Timeout | Connect Timeout | Socket Timeout
  > Darwin | ✅️ | ✖️ | ✅️
  > JavaScript | ✅ | ✖️ | ✖️
  > Curl | ✅ | ✅️ | ✖️
  > MockEngine | ✅ | ✖️ | ✅
  (The table only lists engines with limitations — OkHttp/CIO/WinHttp aren't in it.)
  ⇒ On the **Wasm/JS target** there is no socket timeout at all, so the buffered path + `requestTimeoutMillis` is
  our *only* protection against a stalled body. That is a strong argument for the buffered path in a KMP SDK.

---

## 7. MockEngine API, exactly

Public surface (`ktor-client-mock/common/src/io/ktor/client/engine/mock/`):

```kotlin
// MockEngineConfig.kt:15  — handler type
public typealias MockRequestHandler = suspend MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData
public class MockRequestHandleScope(internal val callContext: CoroutineContext)   // callContext is INTERNAL
public class MockEngineConfig : HttpClientEngineConfig() {
    public val requestHandlers: MutableList<MockRequestHandler>
    public var reuseHandlers: Boolean = true
    public fun addHandler(handler: MockRequestHandler)
}
```

```kotlin
// MockEngine.kt — construction + history
public open class MockEngine public constructor(config: MockEngineConfig)
public class MockEngine.Queue(                       // :111  reuseHandlers = false by default
    override val config: MockEngineConfig = MockEngineConfig().apply { reuseHandlers = false }
) : MockEngine(config, throwIfEmptyConfig = false) {
    public fun enqueue(handler: MockRequestHandler): Boolean
    public operator fun plusAssign(handler: MockRequestHandler)
}
public val requestHistory: List<HttpRequestData>     // :56
public val responseHistory: List<HttpResponseData>   // :63
public companion object : HttpClientEngineFactory<MockEngineConfig> {
    public operator fun invoke(handler: MockRequestHandler): MockEngine   // MockEngine { ... }
}
```

`MockUtils.kt` response builders (all `MockRequestHandleScope` extensions):

```kotlin
fun respond(content: String, status: HttpStatusCode = OK, headers: Headers = headersOf()): HttpResponseData   // :99
fun respond(content: ByteArray, status, headers): HttpResponseData                                              // :111
fun respond(content: ByteReadChannel, status, headers): HttpResponseData                                        // :121
fun respondOk(content: String = ""): HttpResponseData
fun respondError(status: HttpStatusCode, content: String = status.description, headers: Headers = headersOf())
fun respondBadRequest(); fun respondRedirect(location: String = "")
suspend fun OutgoingContent.toByteArray(): ByteArray   // :18 — for asserting the outgoing request body
```

**Asserting on the outgoing request.** Two ways, both used in Ktor's tests:
(a) inside a handler (`HttpRequestRetryTest.kt:75-90` asserts `it.headers["X-RETRY-COUNT"]` per attempt), or
(b) after the fact via `engine.requestHistory` (`MockEngineTest.kt:20-33`, `MockEngineExtendedTests.kt:40-58`):
`requestHistory[i].method`, `.url` (`Url` → `.toString()`/`.encodedPath`), `.headers["name"]`,
`.attributes`, and the body via `suspend request.body.toByteArray()` (import
`io.ktor.client.engine.mock.toByteArray`; `MockEngineTests.kt:68-84` uses
`(request.body as OutgoingContent.ByteArrayContent).bytes()`). `requestHistory` is only appended after a handler
returns (`MockEngine.kt:84-87`), so a throwing handler records nothing.

**Scripting a sequence for retry tests** — `config { engine { addHandler { … } … } }` with handlers consumed in
order, or `MockEngine.Queue()` + `enqueue`/`+=` for per-test scripting
(`MockEngineTests.kt:86-107`, `MockEngineExtendedTests.kt:100-121` asserts
`"Unhandled http://localhost/unhandled"` when the queue runs dry — that message is
`error("Unhandled ${data.url}")` at `MockEngine.kt:70`, and it is a free "we made exactly N attempts" assertion).
`MockEngine.Queue()` starts empty (`throwIfEmptyConfig = false`) so a client can be built in `@BeforeTest`.

**Returning a response that fails at body-reading time** — the channel form is what you want; `callContext` is
`internal`, so construct the channel yourself and hand it to `respond(channel, …)` (exactly the pattern in
`ktor-client-logging/jvm/test/.../OkHttpFormatTest.kt:570-573` `respond(channel, headers = …)`):

```kotlin
engine {
    addHandler {
        val channel = ByteChannel(autoFlush = true)
        GlobalScope.launch {                       // or pass the test scope in via a captured reference
            channel.writeStringUtf8("""{"partial":""")
            channel.flush()
            delay(50)
            channel.close(IOException("body reset by peer"))
        }
        respond(
            content = channel,                     // status 200 + headers are handed out immediately
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
    }
}
```
On the **buffered** path the failure surfaces from the `client.get/post` call itself (SaveBody reads the whole body
inside the pipeline, §2), not from a later `body<T>()`. That is the behaviour our retry loop must be written against.
[`unconfirmed`: I did not find an existing Ktor test that scripts a failing body channel through MockEngine; the
mechanism is read from `respond(ByteReadChannel, …)` + `SaveBody.save()` + `readBuffer().readByteArray()`.]

Timeouts in tests: `MockEngine` advertises `HttpTimeoutCapability`, so `install(HttpTimeout) { requestTimeoutMillis = … }`
works against MockEngine, and Ktor's own retry tests use exactly that pairing
(`HttpRequestRetryTest.kt:415-464` uses a handler that `delay(5000)`s). A handler-side `delay()` runs in
`withContext(dispatcher + callContext)` (`MockEngine.kt:75-77`), so the request timer *does* cancel it.

---

## Things Ktor does not do for us

1. **Tell "no response" apart from "200 then dead body".** On the buffered path a body failure throws out of
   `client.get()/request{}` with no `HttpResponse` in hand; the response object never exists for us. If the SDK's
   error taxonomy or retry policy needs that distinction (the JS SDK's "stalled after 200" test file), we must build
   it: e.g. streaming path plus our own `withTimeout` around the body read, or accept the loss.
2. **Any timeout on a streaming body.** `requestTimeoutMillis` is disarmed the moment the request pipeline returns
   (`HttpRequestLifecycle.kt:34`, timer killed at `HttpTimeout.kt:189`). Streaming reads are protected only by the
   engine socket timeout — and on Wasm/JS by nothing at all. Our own body deadline is ours to write.
3. **Parsing `Retry-After`.** Ktor parses integer seconds only, and only inside `HttpRequestRetry`
   (`HttpRequestRetry.kt:184`). No HTTP-date, no `retry-after-ms`, no clamp/cap. `String.fromHttpToGmtDate()` saves
   us the date branch, nothing else.
4. **A `maxRetryAfterMs` cap / policy.** Ktor's `delayMillis` takes `maxOf(ourDelay, retryAfter)` — it cannot express
   "ignore an absurd server delay and fall back to our backoff".
5. **The error body as data on every attempt, including the final failure.** We own reading/logging the raw error
   text, attaching it to `TypeSafeError`, and extracting `x-typesafe-request-id` from the final response's headers.
6. **A typed, thrown error carrying `status` + `headers` + `body`.** Without `expectSuccess`, Ktor throws nothing and
   gives no error class; with `expectSuccess = true` you get `ClientRequestException`/`ServerResponseException` whose
   only public field is `response` (the cached text is unreachable except through the message string).
7. **Jitter and injectable time.** Ktor's `constantDelay`/`exponentialDelay` only add `0..randomizationMs`; there is
   no symmetric jitter, no `random` injection, and no clock injection. Our `sleep`/`backoff`/`retryDelayMs` (and their
   unit tests) carry all of it.
8. **Attempt observation.** No hook that lets us log/inspect each attempt's timing and intermediate bodies: the only
   signal is the `HttpRequestRetryEvent` monitor event, which we don't get with a hand-rolled loop.
9. **Aborting an in-flight request on `client.close()`.** `close()` only starts a graceful shutdown and does not
   cancel running tasks (`HttpClient.kt:1455-1470`); our SDK must cancel the calling `CoroutineScope` and, if we want
   a hard stop, keep track of per-request jobs ourselves.
10. **Redacted, structured logging of requests/responses** (headers, attempt numbers, bodies) — the `Logging` plugin
    is a formatting layer, not our log contract; the redaction policy and the `[typesafe-sdk]` shape are ours.
11. **A redirect bound.** The `HttpRedirect` loop has no max-redirect counter; only the request timeout stops a cycle.
12. **Request-body replayability guarantees.** `takeFrom` copies the body object; a streamed/one-shot request body
    silently breaks attempt 2. Our transport must constrain bodies to `String`/`ByteArray` and document it.
13. **A single cross-platform timeout story.** Engine quirks are real: OkHttp rebuilds an `OkHttpClient` (and its
    dispatcher) per distinct timeout config; CIO silently applies a 15 s engine-level deadline when the HttpTimeout
    capability is absent; Darwin ignores `connectTimeoutMillis`; Wasm/JS has no socket timeout. Our config must pin
    `requestTimeoutMillis` + `socketTimeoutMillis` explicitly and keep one timeout config per client, and this is
    ours to enforce/verify per target.
