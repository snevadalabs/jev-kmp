# 0004. Ktor's built-in `HttpRequestRetry`, with our own delay policy

- **Status:** Accepted. Supersedes the hand-rolled retry loop the design brief originally locked in §10; no ADR on disk ever recorded that loop.
- **Date:** 2026-09-17
- **Source:** [Reconcile retry against Ktor's built-in HttpRequestRetry](../../.scratch/jev-kmp-sdk/issues/17-reconcile-retry-vs-ktor-plugin.md); [Recon Ktor transport mechanics](../../.scratch/jev-kmp-sdk/research/04-ktor-transport-mechanics.md) §5

## Context

The brief originally locked a hand-rolled retry loop, justified by a deterministic sleeper for tests.
Direct reading of the pinned Ktor source (`3.5.2`) collapsed that justification: `HttpRequestRetryConfig`
exposes a public `delay { }` override, and Ktor's own tests use it to assert exact delay values.

The reference implementation is the other counterexample. Python's retry policy is reachable only
through [Tenacity](https://tenacity.readthedocs.io) internals; its tests monkeypatch `tenacity.time`,
build `RetryCallState` from `policy._build_tenacity()`, and reach into `_wait`/`_stop`. A Tenacity
upgrade breaks those tests without any behavioural change. We want a seam that is public and stays put.

## Decision

Use Ktor's built-in `HttpRequestRetry` for the loop, and supply **our** predicate, backoff, and
`Retry-After` handling through its public seams:

- `retryIf { _, response -> response.status.value in policy.httpStatuses }`, plus
  `retryOnExceptionIf` — `CancellationException` never; the three timeout classes gated by
  `apiTimeoutError`, every other `IOException` by `apiConnectionError`.
- `delayMillis(respectRetryAfterHeader = false) { retry -> retryDelayMs(retry - 1, response?.headers, policy, random) }`.
  Because `respectRetryAfterHeader = false`, Ktor stores our block verbatim instead of taking
  `maxOf(ourDelay, retryAfter)`, and `HttpRetryDelayContext.response` gives us the headers. The whole
  JS delay policy drops in: `round(min(initial * 2^attempt, max) * (1 - random * jitter))`, whole
  milliseconds, exponent base 0 at the first retry, with `random` injectable.
- `Retry-After` is parsed as `retry-after-ms` first, then `retry-after` in seconds or HTTP-date.
- `modifyRequest { it.headers[X-TypeSafe-Retry-Count] = retryCount.toString() }` — the header is
  written only on retries, by construction, because `modifyRequest` runs only when a retry has begun.
- `delay(sleeper)` is the internal injection seam for deterministic tests; it defaults to
  `kotlinx.coroutines.delay`.
- `HttpRequestRetry` is installed **before** `HttpTimeout`, inside the factory, after the caller's
  `httpClientConfig` block.

`RetryPolicy` stays our public, `Duration`-typed `data class`, validated in `init`. The transport
translates it into a complete per-request `HttpRequestRetry` configuration on **every** request. Ktor's
`HttpRequestRetry.request { }` is not exposed.

## Alternatives

- **The hand-rolled loop from brief §10.** Rejected: its justification was falsified, it is more code,
  and a loop we own cannot produce `HttpRequestRetryEvent` for the per-attempt log lines.
- **Hybrid — the plugin plus our own `Retry-After` normalisation.** Rejected: more moving parts than
  either option, to fix a problem that does not exist.
- **Python's Tenacity coupling.** Rejected: white-box test coupling to a dependency's internals, for a
  `Retry-After` forms and cap we implement ourselves anyway.

Exposing Ktor's `HttpRequestRetry.request { }` was considered and rejected: it builds a fresh config
with Ktor's own defaults (`retryOnExceptionOrServerErrors(3)`, `exponentialDelay()`) and copies only
some fields, never `delay`. A partial per-call override would therefore silently restore Ktor's
predicate and delay curve. Per-call `retry: RetryPolicy?` is instead translated into the same complete
configuration as the client default, so there is one code path.

## Consequences and accepted divergences

- **Above-cap server delay falls back to our backoff.** When `respectRetryAfter` is on and the server
  sends a `Retry-After` no greater than `maxRetryAfter`, we honour it exactly. Above the cap we ignore
  it and use our own backoff. This matches JS and deliberately diverges from Python, which honours an
  uncapped server delay.
- **OkHttp can retry invisibly.** `retryOnConnectionFailure(true)` may re-send a POST after a
  non-timeout `IOException` without the plugin seeing it, so our attempt count and `Retry-After`
  handling do not observe that attempt. Suppressing it needs `preconfigured`, which forfeits Ktor's
  per-timeout-configuration client cache. We accept the invisibility and document it in KDoc.
  `ponytail:` accepted ceiling — if a duplicate-POST incident traces here, disable
  `retryOnConnectionFailure` via `preconfigured` and pay the lost client cache.
- **The attempt count is not caller-visible.** Nothing in the response surface exposes how many
  attempts a call took.
- **Only the final attempt's error body is available.** Intermediate bodies are lost. Nothing consumes
  them: logging never logs bodies, and only the final failure is thrown.

## What would change our mind

Ktor removes or changes `delayMillis(respectRetryAfterHeader = false)` or stops exposing
`HttpRetryDelayContext.response` — either would send us back to a loop we own. Or a real incident where
OkHttp's invisible connection retry produced a duplicate write, which would buy back the cost of
suppressing it.
