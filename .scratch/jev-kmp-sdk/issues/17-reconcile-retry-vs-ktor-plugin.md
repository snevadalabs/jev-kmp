# Reconcile retry against Ktor's built-in HttpRequestRetry

Type: grilling
Status: resolved
Blocked by:

## Question

The design brief (§10) locks a **hand-rolled** retry loop, and my stated justification for that was getting deterministic tests via an injectable sleeper — the fix for Python's white-box coupling to Tenacity.

Two independent research findings have since pulled the rug out from under that reasoning:

1. **Ktor's built-in `HttpRequestRetry` supports an injectable `delay`.** `HttpRequestRetryConfig.delay { }` is a public override, and Ktor's own tests use it to collect delays and assert exact values. The determinism argument for hand-rolling was wrong.
2. **The built-in covers more than assumed**: per-attempt header mutation (`modifyRequest` — with a Ktor test asserting attempt 0 carries no retry header, then `1`, `2`, `3`, which is precisely our `X-TypeSafe-Retry-Count`), per-request policy (`HttpRequestRetry.request { }`), and `Retry-After` via `delayMillis(respectRetryAfterHeader)`.
3. **The survey's verdict agrees** from an independent direction: openai-kotlin does this in 12 lines — use the library's plugin, expose only `maxRetries`/`base`/`maxDelay`, and add `delay` as a config field *because* that one lambda is the whole seam.

Read [research/04-ktor-transport-mechanics.md](../research/04-ktor-transport-mechanics.md) §5 and §"Things Ktor does not do for us", plus [research/05-established-kotlin-sdk-patterns.md](../research/05-established-kotlin-sdk-patterns.md) §3.

### Where the built-in genuinely falls short

- **`retryIf`'s predicate is not `suspend`.** It cannot read the error body to decide whether a response is retryable, and cannot re-create the request (only mutate the copied builder).
- **`Retry-After` support is integer seconds only** — no HTTP-date, no `retry-after-ms`, and no way to express our `maxRetryAfterMs` cap. Ktor's `delayMillis` takes `maxOf(ourDelay, retryAfter)`, so an absurd server-supplied delay wins by design.
- **We want the error body on every attempt**, for logging and for the error we eventually throw. The plugin surfaces only the final attempt's data.
- We'd give up our own attempt counter and the `HttpRequestRetryEvent` monitor hook (we get neither both ways).

### The options

1. **Stay hand-rolled** as the brief says. ~60 lines, we own the semantics, the `Retry-After` forms, the cap, the error body, and the attempt counter. We take on per-attempt timeouts as separate Ktor calls (`requestTimeoutMillis` is per attempt because each attempt is its own call), and we can still use an injectable sleeper so the tests stay deterministic.
2. **Switch to the built-in.** Far less code, gets Ktor's automatic body-integrity await, and inherits `HttpRequestRetryEvent`. Costs us the HTTP-date and `retry-after-ms` forms, the `maxRetryAfterMs` cap, and error-body access in the retry predicate — we'd have to accept integer-seconds-only `Retry-After` or add a pre-parse step.
3. **Hybrid**: built-in plugin for the loop and header mutation, plus our own `Retry-After` normalisation before the plugin sees the response, plus our own error translation. More moving parts than either.

### What to weigh

Overengineering is a real risk in the hand-rolled option, but so is shipping an SDK that silently mishandles a `Retry-After` HTTP-date or honours a server's absurd delay — both of which the siblings handle and the built-in cannot. Note also that the brief's per-attempt semantics (fresh timeout budget per attempt, fresh error body) may be easier to guarantee in a loop we own than in a plugin whose internals we'd be relying on.

Decide, and state explicitly which of the four capability gaps above you are accepting, because each one is a behaviour our siblings have and we would not.

## Answer

**Decision: adopt Ktor's built-in `HttpRequestRetry`.** Brief §10's hand-rolled loop is rejected, and so is the hybrid.

Every claim this ticket was opened on was re-verified against the pinned source (`ktorio/ktor` @ `37a29f9` = 3.5.2, clone at `/tmp/ktor`). Three of the four "gaps" do not exist.

### Corrected findings

1. **The `maxRetryAfter` cap gap is not real.** `HttpRequestRetryConfig.delayMillis` has no raw setter, but the two-arg overload stores the block *verbatim* when `respectRetryAfterHeader = false`:

   ```kotlin
   public fun delayMillis(respectRetryAfterHeader: Boolean = true, block: HttpRetryDelayContext.(retry: Int) -> Long) {
       delayMillis = { if (respectRetryAfterHeader) { …maxOf(block(it), retryAfter ?: 0) } else { block(it) } }
   }
   ```

   `HttpRetryDelayContext` carries `response: HttpResponse?`, so `retry-after-ms`, HTTP-date, `maxRetryAfter` and the above-cap fallback are all reachable, and the whole JS `retryDelayMs(attempt, headers, policy, random)` drops in unchanged. Recon §5's "no way to express `maxRetryAfterMs`" is **wrong**.
2. **The non-`suspend` predicate is not a gap for us.** Neither sibling reads the error body to decide retryability — the policy is status codes plus exception class. Nothing in `retryIf` needs to suspend.
3. **"The error body on every attempt" is contradicted by our own brief.** Recon §5 justified it for logging; brief §5 says logging *never* logs headers or bodies. The only body we need is the final attempt's, and on the built-in the final response comes back to us intact (saved, `bodyAsText()` re-readable). Only *intermediate* attempt bodies are lost, and nothing in the design consumes them.
4. **Per-request `retry { }` is a footgun, not an inheritance.** `HttpRequestBuilder.retry { }` builds a fresh `HttpRequestRetryConfig()`, whose `init` installs `retryOnExceptionOrServerErrors(3)` and `exponentialDelay()`, then copies only `shouldRetry`, `shouldRetryOnException`, `delayMillis`, `maxRetries` and `modifyRequest` — and **never `delay`**. A partial per-call override therefore silently replaces our predicate and delay curve with Ktor's defaults. Consequence: we translate *our* complete policy on every request rather than expose that builder.

Confirmed as designed: `modifyRequest` is guarded by `lastRetryData != null`, so attempt 0 never carries a header and `retryCount` is 1-based (exactly §13); `retryIf` and `delayMillis` see the saved response; per-attempt timeout works only with `HttpTimeout` installed *after* `HttpRequestRetry` (source-documented, test-proven); `HttpClient.monitor.subscribe(HttpRequestRetryEvent)` gives per-retry `retryCount` + `response`/`cause`, which a hand-rolled loop **cannot** provide; `HttpSend`'s `maxSendCount = 20` ceiling is raised to `maxRetries + 1` per request.

### The accepted-gap ledger

| Gap named by the recon | Outcome |
|---|---|
| Error body on every attempt | **Accepted loss.** §5 forbids logging bodies, and the final body is intact. |
| `Retry-After` forms + `maxRetryAfter` cap | **Not a gap.** Our own `delayMillis(respectRetryAfterHeader = false)` block. |
| Owning the attempt counter | **Not a gap.** `modifyRequest` hands us `retryCount`. |
| `HttpRequestRetryEvent` monitor hook | **Inverted** — we gain it. |

### Configuration

`HttpRequestRetry` installed before `HttpTimeout`, both inside the factory, after the caller's `httpClientConfig` block. `RetryPolicy` — siblings' name and field set, `Duration`-typed, `data class`, `require`-validated in `init`, `httpStatuses` copied — maps in as:

- `maxRetries = policy.maxRetries`
- `retryIf { _, response -> response.status.value in policy.httpStatuses }`
- `retryOnExceptionIf` — `CancellationException` never; the three timeout classes (`HttpRequestTimeoutException` from `io.ktor.client.plugins`, `ConnectTimeoutException` and `SocketTimeoutException` from `io.ktor.client.network.sockets`, all public `IOException` subtypes in common code) gated by `apiTimeoutError`; every other `IOException` gated by `apiConnectionError`. Ktor's own `isTimeoutException()` is `private`, so the check is reimplemented.
- `modifyRequest { it.headers[X-TypeSafe-Retry-Count] = retryCount.toString() }`
- `delayMillis(respectRetryAfterHeader = false) { retry -> retryDelayMs(retry - 1, response?.headers, policy, random) }` — the JS-exact policy: subtractive jitter, `round(min(initial * 2^attempt, max) * (1 - random * jitter))`, whole-ms, exponent base 0 at the first retry; server delay honoured only when `respectRetryAfter` and `≤ maxRetryAfter`, otherwise our backoff; `retry-after-ms` before `retry-after` (seconds or HTTP-date)
- `delay(sleeper)` — the `internal` injection seam, defaulting to `kotlinx.coroutines.delay`

Per-call `retry: RetryPolicy? = null` is translated into those same five per-request fields on **every** request, so there is one code path and no partial-override divergence. Ktor's `HttpRequestRetry.request { }` is not exposed.

### Recorded divergences

- A server `Retry-After` above `maxRetryAfter` falls back to our backoff (JS), rather than clamping or honouring it (Python).
- OkHttp's `retryOnConnectionFailure(true)` can re-send a POST after a non-timeout `IOException` without the plugin seeing it. Suppressing it needs `preconfigured`, which forfeits Ktor's per-timeout-config client cache, so it is **accepted and documented in KDoc** rather than disabled.
- The attempt count is not caller-visible; §12's response fields are unchanged.

### Consequences

Amended: **§4** (escape-hatch ordering), **§5** (per-call retry is in; retries log one line per attempt), **§10** (rewritten), **§11** (one internal parser shared with `RateLimitError.retryAfterMs`), **§13** (the retry header is written by `modifyRequest`). Ticket 08's ADR 0004 is rewritten in place — no ADR exists on disk, so nothing is superseded. Ticket 10's retry section is rewritten: the tests port to Ktor's `delay { }` seam, and the HTTP-date conformance fixture through `MockEngine` becomes the regression test for the header's date form. **[amended by *Implement transport and retry*]** It does **not** pin `respectRetryAfterHeader = false`, as this Answer claimed: flipping the flag leaves it green, because a date does not parse as seconds and Ktor falls back to `maxOf(ourDelay, 0)`. The above-cap case is the real pin.
