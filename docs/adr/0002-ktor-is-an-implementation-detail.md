# 0002. Ktor is an implementation detail behind an injectable engine seam

- **Status:** Accepted
- **Date:** 2026-09-17
- **Source:** [Lock the v0.1 design brief](../../.scratch/jev-kmp-sdk/issues/01-lock-v0.1-design-brief.md) §4; [Reconcile the client shape and answer access with the Kotlin SDK survey](../../.scratch/jev-kmp-sdk/issues/16-reconcile-client-shape-and-answer-access.md)

## Context

Ktor is the transport, not the product. Two requirements pull in opposite directions:

- The shared test suite must exercise every behaviour against `MockEngine` in `commonTest`, which
  means the engine has to be injectable — and `HttpClientEngine` is a Ktor type.
- A caller must be able to install `Logging`, custom auth, or a bespoke engine without us shipping an
  option for each one.

Hiding Ktor completely would satisfy the principle and cost us the `MockEngine` test tier that the
whole conformance plan rests on.

## Decision

Ktor appears in exactly two opt-in configuration parameters and nowhere else:

```kotlin
engine: HttpClientEngine? = null
httpClientConfig: HttpClientConfig<*>.() -> Unit = {}
```

No `HttpResponse`, `HttpClient`, `HttpRequestBuilder`, or `HttpStatusCode` appears in any public
signature. A default engine ships — OkHttp on JVM and Android, Darwin on Apple, CIO on Linux — via
`expect`/`actual`, so a caller configures nothing.

`TypeSafeClient` is `AutoCloseable` and closes the engine it created; it never closes an engine it was
handed. The caller's `httpClientConfig` block runs **before** our own plugin installs.

## Alternatives

- **Ktor fully hidden**, with our own engine abstraction. Rejected: it costs the `MockEngine`-in-
  `commonTest` tier, and it is an abstraction with one implementation — Ktor.
- **Ktor exposed freely.** Rejected: our public surface would inherit Ktor's semver, every Ktor update
  would be an API-dump decision, and a caller would have to learn Ktor to use a decision-model client.

## Consequences

Our compatibility promise covers the two parameters' signatures, not Ktor's behaviour.

Because the caller's block is applied first, `HttpRequestRetry` and `HttpTimeout` are installed last
and the required `HttpRequestRetry`-before-`HttpTimeout` ordering cannot be broken from a caller's
configuration. The accepted cost: a caller-installed `Logging` sits **outside** the retry loop and
logs one line per request. Per-attempt lines are ours, from `HttpRequestRetryEvent`.

## What would change our mind

A stable Ktor-agnostic engine abstraction emerges that still permits `MockEngine` in `commonTest`, or
`HttpClientEngine` ceases to be a supported public seam in Ktor — either would remove the reason the
seam is Ktor-shaped.
