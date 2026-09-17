# Implement transport and retry

Type: task
Status: open
Blocked by: 09, 17

## Question

Read [research/04-ktor-transport-mechanics.md](../research/04-ktor-transport-mechanics.md) §1–§4 and its closing "Things Ktor does not do for us" list before starting. **Retry is settled by *Reconcile retry against Ktor's built-in HttpRequestRetry***: the loop is Ktor's `HttpRequestRetry`, installed by the client factory *after* the caller's `httpClientConfig` block and *before* `HttpTimeout`, with our policy living in `delayMillis(respectRetryAfterHeader = false)` and our `Retry-After` parsing inside that block. There is no hand-rolled loop to write. Read that ticket's `## Answer` before starting — it corrects three claims in recon §5.

Findings from that recon this ticket must honour:

- **`HttpTimeout` disarms when the request pipeline returns.** A body that stalls *after* a `200` is covered only by the engine's socket timeout — on Wasm/JS by nothing. That deadline is ours to write.
- **Request bodies must be constrained to `String`/`ByteArray`.** A one-shot streamed body silently breaks attempt 2. Document the constraint.
- **`close()` does not cancel in-flight requests** — it only starts a graceful shutdown. Cancelling a caller's scope is our job.
- **Pin `requestTimeoutMillis` *and* `socketTimeoutMillis` explicitly, one timeout config per client.** CIO applies a silent 15s deadline when the capability is absent; Darwin ignores `connectTimeoutMillis`; OkHttp rebuilds its dispatcher per distinct timeout config.
- **`close()` on the engine we didn't create** — Ktor's own rule is `manageEngine`, and the survey formalises it the same way. Close only what we own.
- The `Retry-After` HTTP-date branch comes free from `String.fromHttpToGmtDate()`.

Nothing to decide — build the layer every call goes through, against the recon in *Recon Ktor transport mechanics* and the locked retry design.

**Transport.** One internal entry point that both the client and the models resource call. It owns:

- The outgoing request: method, path joined onto `baseUrl`, headers, JSON body.
- Header assembly with the siblings' precedence rules: default headers, then caller headers, then **force-set** the protected ones — `Authorization: Bearer <key>`, `Accept`, `X-TypeSafe-SDK: typesafe-sdk-kotlin/<version>`, `X-TypeSafe-Runtime: <platform>/<version>`. A caller-supplied `X-TypeSafe-Retry-Count` is stripped, not honoured.
- The `X-TypeSafe-Retry-Count: <n>` header on retries only, never on the first attempt.
- Timeout handling and translation of Ktor/engine failures into our error types **at this single boundary**, so no other file needs to know what an `IOException` is.
- Response buffering such that the body is intact for decoding under retry, and the status plus `x-typesafe-request-id` are readable without consuming it. The JS SDK has a whole class of bugs here; recon answers the Ktor specifics.

**Retry.** Ktor's `HttpRequestRetry`, configured from `RetryPolicy` per brief §10. There is no loop to write — the work is the mapping, the pure delay policy, and the tests.

- `RetryPolicy` maps into the plugin: `maxRetries`, `retryIf` on `httpStatuses`, `retryOnExceptionIf` on the timeout/`IOException` split with `CancellationException` excluded, `modifyRequest` for `X-TypeSafe-Retry-Count`, and `delayMillis(respectRetryAfterHeader = false) { retry -> retryDelayMs(retry - 1, response?.headers, policy, random) }`. Ktor's own `isTimeoutException()` is `private`, so the three-class check is reimplemented.
- The delay policy is a pure function matching JS exactly: subtractive jitter, whole-ms rounding, exponent base 0 at the first retry, injectable `random`.
- `Retry-After` is honoured up to `maxRetryAfter`, parsed from `retry-after-ms` then `retry-after` (seconds or HTTP-date) by the same internal parser `RateLimitError.retryAfterMs` uses, rejecting non-finite and negative values. The HTTP-date branch comes free from `String.fromHttpToGmtDate()`.
- Per-call `retry: RetryPolicy? = null` writes **all five** per-request fields on every request, so a partial override can never silently restore Ktor's defaults.
- One log line per retry, from `client.monitor.subscribe(HttpRequestRetryEvent)`; never headers, never bodies.

**Tests, written first.** Deterministic through Ktor's `delay { }` seam (an `internal` injection point), the injected jitter, and `MockEngine.requestHistory` — no fake clock, no process-wide monkeypatching, nothing reaching into a third party's internals:

- A scripted sequence of responses plus a recorded list of delays, asserting exactly which attempts happened and exactly which delays were requested, including that attempt 0 carries no `X-TypeSafe-Retry-Count`.
- Status-code matrix across the configured set, plus the non-retryable statuses.
- `Retry-After` in both forms **through `MockEngine`, asserting the recorded delay** — this doubles as the regression test that pins `respectRetryAfterHeader = false`: anyone who flips it back to Ktor's own handling goes red on the HTTP-date case. Plus garbage, negative and above-cap values.
- Backoff bounds with the jitter source pinned.
- Per-call policy override isolation: concurrent calls with different policies do not share state. The Python suite needed an entire white-box test for this; here it is a plain assertion over `requestHistory`.
- Cancellation during the delay propagates and does not retry.
- Final-error fidelity: when retries are exhausted, the surfaced error is the last one, with its cause intact.

Deliverable: the transport and retry source plus their tests, green in `commonTest` against `MockEngine`.

## Answer
