# Recon Ktor transport mechanics

Type: research
Status: resolved
Blocked by:

## Question

What exactly does Ktor 3.x give us for the transport layer, and where are the traps that bit the JS SDK?

Answer all of:

1. **Per-request timeout.** With `HttpTimeout` installed, what is the plugin's real behaviour across engines — does it need explicit configuration to fire per attempt, and does it apply to the response *body* read or only to headers? The JS SDK has an entire test file about bodies that stall *after* a `200`; we must know whether Ktor covers that or whether we do.
2. **Body consumption.** Does `HttpClient.execute()` consume the body on the path we need, and what is the correct buffering strategy so we hold a parsed result while still reading headers? Look specifically at what `HttpResponse.body<T>()` does, whether `bodyAsChannel`/`readBytes` is required to be safe under retries, and whether a response can be read twice.
3. **Non-2xx handling.** `expectSuccess = false` vs the default: which one gives us the status and the raw error body without an exception, and what does `HttpResponse.bodyAsText()` do on a failed response. We need the error body text and the `x-typesafe-request-id` header from a 429, so this must be exact.
4. **`Retry-After`.** Where does Ktor surface response headers, and is there an existing `parseRetryAfter` we can reuse or is hand-writing it required (including the HTTP-date form)?
5. **Retries.** Does Ktor's built-in `HttpRequestRetry` plugin allow the header mutation we need on each attempt (`X-TypeSafe-Retry-Count`), an injectable delay, and a custom retry predicate? Compare it honestly against ~60 lines of hand-rolled loop. We already chose hand-rolled — this question is only to confirm that choice was not made in ignorance, and to record what we are giving up.
6. **Engine differences.** Per engine (OkHttp, Darwin, CIO) — anything that changes our code: connection pooling, whether `close()` closes in-flight requests, redirect behaviour, whether the Darwin engine requires a `NSURLSession` configuration, and whether any engine handles a request timeout differently enough to matter.
7. **MockEngine.** The exact API surface of `ktor-client-mock` for asserting on the outgoing request (method, URL, headers, body) and for returning a sequence of responses so retry behaviour is testable, including returning responses that error at the body-reading stage.

Deliverable: a written summary with copy-pasteable snippets, plus an explicit list of "things Ktor does not do for us".

## Answer

Full source-level findings against `ktorio/ktor` @ `37a29f9`: [research/04-ktor-transport-mechanics.md](../research/04-ktor-transport-mechanics.md). The researcher marked anything it could not prove from source as **[unconfirmed]** rather than inferring it, which is why this one is usable.

**The headline contradicts our reasoning for going hand-rolled.** Ktor's built-in `HttpRequestRetry` is *far* more capable than assumed. It supports per-attempt header mutation (`modifyRequest`, with a Ktor test asserting attempt 0 has no header then `1`, `2`, `3` — exactly our `X-TypeSafe-Retry-Count`), an **injectable `delay`** (which was my stated justification for hand-rolling to get deterministic tests), per-request policy via `HttpRequestRetry.request {}`, and `Retry-After` through `delayMillis(respectRetryAfterHeader)`. That is a real capability overlap and the decision deserves re-examination → *Reconcile retry against Ktor's built-in HttpRequestRetry*.

Where the built-in genuinely falls short: `retryIf`'s predicate is **not `suspend`**, so it cannot read the error body to decide retryability, and Ktor parses **integer seconds only** for `Retry-After` with no HTTP-date, no `retry-after-ms`, and no way to express "ignore an absurd server delay and fall back to our backoff". It also gives us only the last attempt's data, where we want the error body on every attempt.

**The timeout finding is the one that would have bitten us.** `HttpTimeout` is not a per-read socket deadline — it cancels the request's execution context after N ms, and the timer is **disarmed the moment the pipeline returns**. So a body that stalls *after* a `200` is protected only by the engine's socket timeout, and on Wasm/JS by nothing at all. The JS SDK has an entire test file about exactly this; we own that deadline ourselves.

**Thirteen things Ktor does not do for us** are enumerated at the end of the research file. The ones that land on other tickets: no redirect bound (the loop has no max counter), request bodies must be constrained to `String`/`ByteArray` or attempt 2 silently breaks on a one-shot stream, `close()` starts a graceful shutdown but does **not** cancel in-flight requests, and engine quirks are real (CIO applies a silent 15s deadline when the capability is absent; Darwin ignores `connectTimeoutMillis`; OkHttp rebuilds its dispatcher per distinct timeout config).

**Confirmed usable:** `expectSuccess = false` is already the default, so a `429` throws nothing and we read status, headers, and body ourselves — which is what we need. `MockEngine`'s API for asserting outgoing requests and scripting a response sequence is documented with exact signatures. The `Retry-After` date branch is covered for free by `String.fromHttpToGmtDate()`.
