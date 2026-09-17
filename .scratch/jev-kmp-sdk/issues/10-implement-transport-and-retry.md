# Implement transport and retry

Type: task
Status: resolved
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

**Built, gated, and green.** Branch `issue-10-implement-transport-and-retry`. Nothing was ambiguous enough to stop on — no question to report.

### What landed

`src/commonMain/kotlin/com/sierranevadalabs/jev/sdk/`

- **`RetryPolicy.kt`** — the ticket's one new public declaration. `data class`, `Duration`-typed, `require`-validated in `init`, siblings' field set and defaults (`maxRetries = 2`, 500 ms → 5 s, jitter `0.25`, `{408, 429, 500..599}`, `maxRetryAfter = 60 s`, both error flags on). The status set is copied where it is consumed, not in the constructor — see *Judgement calls*.
- **`Headers.kt`** — `SDK_NAME`/`SDK_VERSION`, the header-name constants, and `assembleHeaders`: client defaults → caller headers (last wins, case-insensitive) → force-set `Authorization`, `Accept`, `Content-Type` (body present only), `X-TypeSafe-SDK`, `X-TypeSafe-Runtime`; a caller-supplied `X-TypeSafe-Retry-Count` is removed before the protected names go in.
- **`Retry.kt`** — `transportFailureKind()` (the reimplemented three-class timeout check, `CancellationException` never), `isRetriedBy(policy)`, `parseRetryAfterMs`, `retryDelayMs`, and `HttpRequestRetryConfig.applyPolicy` writing **all five** per-request fields.
- **`Transport.kt`** — `Transport`/`TransportResponse`/`TransportException` and `createTransport`. `expectSuccess = false`, caller `httpClientConfig` first, then `HttpRequestRetry` (with the injected sleeper) and `HttpTimeout`. Request timeout **and socket timeout** pinned at the client, per-call override of the request timeout only. `close()` closes the client and the engine only when `createDefaultEngine()` was called for it.
- **`Platform.kt`** + `jvmMain`/`androidMain`/`appleMain`/`nativeMain`/`linuxMain` actuals — `createDefaultEngine()` (OkHttp / OkHttp / Darwin / CIO) and `runtimeIdentity` (`jvm/<java.version>`, `android/<SDK_INT>`, `<apple osFamily>/<major>.<minor>` from `NSProcessInfo.operatingSystemVersion`, `linux/<uname release>`).

`src/commonTest` — 31 tests, all through `MockEngine`: scripted attempt sequence with recorded delays and the per-attempt retry header (`[null, "1", "2"]`, `[500, 1000]`), status matrix (408/429/500/502/599 retried; 200/400/401/403/404/422 not), `Retry-After` seconds and HTTP-date through Ktor, the above-cap fallback, the `retry-after-ms` preference, unusable values, capped exponential backoff, pinned jitter, per-call policy isolation across concurrent calls, cancellation during the delay, cancellation thrown by the engine, final-failure fidelity (`cause.message == "fail-3"` after three attempts), URL joining, header precedence and the retry-header strip, request-id/status/body surfacing, per-call timeout, the retry log line, and idempotent close. Plus `RetryDelayTest` pinning the pure policy and parser, and `RetryPolicyTest` pinning defaults and the `require` set.

### Gate

`./gradlew checkVersion ktlintCheck checkJvmBytecode jvmTest apiCheck` → **BUILD SUCCESSFUL**. `api/jvm/jev-kmp.api` now carries `RetryPolicy` (committed as a deliberate diff). `./gradlew check` also passes with the suite green on JVM, `macosArm64`, `iosSimulatorArm64` and the Android host tests; `linuxX64` compiles and links here and the test task is skipped on a macOS host, so CI's Linux lane is where it executes. `dokkaGenerate` is clean.

### Two carried-forward verifications closed

1. **`ktor-client-cio` resolves on `linuxX64`** (toolchain ticket, item 3) — declared in `linuxMain`, `compileKotlinLinuxX64` green. No fallback to `ktor-client-curl` needed.
2. **The Java 8 bytecode assertion** (scaffold ticket) — new `checkJvmBytecode` task reads the class file's major version and asserts 52 for every JVM class, wired into `check` and into the CI Linux lane. This is the first compiled class the scaffold ticket was waiting for.

### A finding, not acted on: the HTTP-date test does **not** pin `respectRetryAfterHeader = false`

The ticket (and the retry-reconciliation ticket after it) says the HTTP-date case "doubles as the regression test that pins `respectRetryAfterHeader = false`". Mechanically it does not: with the flag flipped to `true`, Ktor computes `maxOf(ourDelay, retryAfter)` where `retryAfter = headers["Retry-After"].toLongOrNull()?.times(1000)`, and an HTTP-date yields `null` → `maxOf(ourDelay, 0)` → our parsed delay. The test stays green. The **above-cap** case is the real pin: with the flag `true`, `Retry-After: 120` makes Ktor return 120 000 and the assertion on 500 goes red. Both cases are in the suite; only the second one fails if anyone flips the flag. Reporting it rather than editing the claim.

### Judgement calls where the brief is silent (all deliberate, none a behaviour change)

- **`kotlinx-coroutines-test` added to the catalog.** `kotlin.test` ships no coroutine runner and `runBlocking` is absent from coroutines' common source set, so `commonTest` cannot launch a suspend call without it. It is the runner, not a new library, and it reuses the existing `coroutines` version pin.
- **`X-TypeSafe-SDK` needs a version string and `gradle.properties` cannot be read from common Kotlin.** `SDK_VERSION` is a constant, and the existing `checkVersion` task now asserts it equals `gradle.properties` minus `-SNAPSHOT`, so "the version lives in exactly one place" stays true rather than becoming a comment.
- **`Content-Type: application/json` is force-set alongside the four protected names** in the ticket. A caller-supplied `Content-Type` on a JSON body is a real bug and JavaScript protects this header too; the forced list gains one entry rather than a hole.
- **`kotlinx.io.IOException` is imported without a catalog entry.** It is an `api`-transitive of `ktor-client-core` (`ktor-io` → `api(libs.kotlinx.io.core)`), it is already visible in Ktor's own public signatures (`HttpRequestTimeoutException : IOException`), and pinning it here would create a second source of truth for a version Ktor owns. Compiles and links on every declared target.
- **`RetryPolicy.httpStatuses` is copied at the point of use** (`applyPolicy` does `policy.httpStatuses.toSet()`), not in `init`. A `data class` cannot defensively copy a primary-constructor `val` without either breaking `copy()`/`equals()` (body property) or making the constructor private; the copy that matters is the one the predicate closes over.
- **No CHANGELOG line.** `RetryPolicy` is public API but nothing is reachable yet — there is no client to pass it to and `0.1.0` is still in development. The `Unreleased` section stays as the scaffold ticket left it; the 0.1.0 notes are the publish ticket's job.
- **`X-TypeSafe-Runtime` on Linux** uses `uname()` via `platform.posix` as the toolchain ticket specified, with `OsFamily` (not `OSFamily`) and `ExperimentalNativeApi`/`ExperimentalForeignApi` opted in.

### Handoffs to the client ticket

- **The failure seam.** The transport throws an internal `TransportException.Timeout`/`.Connection` with the original Ktor cause intact and rethrows `CancellationException` untouched. The public 12-class tree, the status→class mapping and `RateLimitError.retryAfterMs` stay owned by the client ticket, which maps `TransportException` rather than seeing any `IOException`.
- **`TransportResponse` already carries `retryAfterMs`**, computed with the effective call's `respectRetryAfter` via the shared parser — so `RateLimitError.retryAfterMs` is a field read, and `respectRetryAfter = false` disables both header forms in one place.
- **Logging.** The transport takes `log: (String) -> Unit` (default no-op) and emits one line per retry from `HttpRequestRetryEvent`. The per-call line §5 wants (method, path, status, duration, request id) is not in this ticket's list; the transport has the status, path and request id, but duration needs a clock seam, so the client ticket should decide where that line and the `TYPESAFE_LOG_LEVEL` gate live.
- **`close()` is graceful, not an abort** — the recon's point 9. `Transport.close()` only closes; aborting an in-flight call remains the caller's scope, which is what the cancellation test pins. The public `close()` KDoc should say so.
- **`createTransport(engine: HttpClientEngine? = null, …)`** takes the caller's engine and never closes it, and creates + owns the platform default when `null`. The public `TypeSafeClient(config)` needs only to pass `config.engine` through.
