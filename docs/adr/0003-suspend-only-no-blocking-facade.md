# 0003. Suspend-only, with no blocking facade

- **Status:** Accepted
- **Date:** 2026-09-17
- **Source:** [Lock the v0.1 design brief](../../.scratch/jev-kmp-sdk/issues/01-lock-v0.1-design-brief.md) §6; [reference recon of the Python SDK](../../.scratch/jev-kmp-sdk/research/reference-typesafe-sdk-python-recon.md) §2

## Context

Kotlin's concurrency model is coroutines, and every engine behind the Ktor seam is suspend-based. A
blocking facade would be a second concurrency model shipped on top.

The reference implementation is the counterexample. Python ships `TypeSafeClient` and
`AsyncTypeSafeClient`: two near-verbatim ~190-line clients and two `Models` resources, kept in step by
hand. That duplication is the single largest maintenance cost in that SDK. A blocking API is also a
deadlock footgun on Android, where blocking the main thread is a user-visible hang, and this SDK
targets Android as a Tier 1 platform.

## Decision

Every I/O operation is `suspend`. No blocking entry point ships, in any target. A JVM caller who wants
one writes `runBlocking { }` themselves; that is one line in their code and a documented pattern, not
our public surface.

## Alternatives

- **Ship a JVM blocking facade over `runBlocking`.** Rejected: it is the footgun, not the fix. A
  caller who writes `runBlocking` has made a deliberate choice in their own code; a method named
  `systemOneBlocking` invites the call from `onCreate`.
- **Mirror Python with a sync and an async client.** Rejected: it is the duplication above, bought for
  no capability the suspend client lacks.
- **A Java-interop blocking shim.** Out of scope for `0.1.0`. Java callers can bridge with
  `runBlocking` or a `CompletableFuture`; if a real Java consumer base appears, this returns as a
  JVM-only addition rather than a second core client.

## Consequences

`close()` is not `suspend`, and closing does not cancel in-flight caller coroutines. The KDoc says so
explicitly rather than implying otherwise.

Targets whose tests cannot execute suspend code are covered by compilation, not by example code.

## What would change our mind

A demonstrated consumer base that cannot use coroutines at all — a Java-only or thread-per-request
integration — and needs a blocking entry point. That would add a **JVM-only** facade delegating to the
one suspend client; it would never fork the core client.
