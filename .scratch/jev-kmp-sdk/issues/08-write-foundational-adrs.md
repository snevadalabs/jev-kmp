# Write the foundational ADRs

Type: task
Status: resolved
Blocked by:

## Question

Nothing to decide — the decisions are locked in *Lock the v0.1 design brief* and must be written down where a future contributor (or the upstream TypeSafe team) will find them.

Write `docs/adr/` ADRs for the decisions that are **hard to reverse**, **surprising without context**, and **the result of a real trade-off**. At minimum:

- `0001` — Neutral coordinates and package name over upstream `ai.typesafe:*`, and what a handover would cost.
- `0002` — Ktor is an implementation detail: internal types, injectable engine seam, shipped default engine.
- `0003` — Suspend-only, no blocking facade, and why Python's dual-client design is the counterexample.
- `0004` — Ktor's built-in `HttpRequestRetry` with our own delay policy, over a hand-rolled loop and over Python's Tenacity coupling. This one was rewritten by [Reconcile retry against Ktor's built-in HttpRequestRetry](17-reconcile-retry-vs-ktor-plugin.md) before any ADR existed on disk, so nothing is superseded — but it must carry the accepted divergences: the above-`maxRetryAfter` fallback to our own backoff, OkHttp's invisible connection retries, and the attempt count not being caller-visible.
- `0005` — Conformance fixtures live in this repo as declarative JSON rather than in a shared artifacts repo.
- `0006` — Score criteria validation enforces at least two levels, diverging from Python on purpose.

Also write `CONTEXT.md` — the glossary, and nothing else. Terms to pin down, since they are already overloaded across the three SDKs and the docs: *state*, *question*, *answer*, *primitive*, *noul*, *choice*, *score*, *legend*, *confidence*, *probability*, *calibration*, *System One*, *typed question key*. Sharp definitions only — no implementation detail, no spec, no design notes. If a term has a different meaning in the TypeSafe docs than in our API, say so explicitly.

Deliverable: `docs/adr/0001..0006*.md` and `CONTEXT.md`. Keep each ADR short — the decision, the alternatives, and what would make us change our mind.

## Answer

Built `docs/adr/0001`, `0002`, `0003`, `0004`, `0006` and `CONTEXT.md`. **`0005` is deliberately absent**: by this effort's ADR-numbering rule it belongs to [Settle the conformance fixture format](07-settle-conformance-fixture-format.md), which is still open.

- `0001` **Neutral coordinates and package name** — the `ai.typesafe:*` and `io.github.*` alternatives, the illegal-hyphen package, and the handover cost. **Finding:** brief §2's "transferable upstream later as a Maven-only change" is accurate for the coordinates and optimistic for the package — `com.sierranevadalabs.jev.sdk` is source-visible, so a handover is Maven-only only if upstream keeps our package. Recorded in the ADR as the honest cost; the brief was not edited.
- `0002` **Ktor behind the engine seam** — the two config parameters, the shipped default engine, engine ownership, and the caller-block-first ordering that makes `HttpRequestRetry` → `HttpTimeout` unbreakable from caller config.
- `0003` **Suspend-only** — Python's two near-verbatim clients as the counterexample; no blocking facade, `runBlocking` documented for JVM callers.
- `0004` **Ktor's `HttpRequestRetry` with our own delay policy** — carries all three accepted divergences from ticket 17: above-`maxRetryAfter` fallback to our backoff, OkHttp's invisible connection retries (marked `ponytail:` with the `preconfigured` upgrade path), and the attempt count not being caller-visible. Records `delayMillis(respectRetryAfterHeader = false)` as the seam that lets the JS policy drop in, and that `HttpRequestRetry.request { }` is not exposed because a partial override silently restores Ktor's defaults.
- `0006` **Two-level score criteria** — follows JS, diverges from Python on purpose.
- `CONTEXT.md` — the thirteen terms, each stating the docs-vs-our-API difference where one exists (`answer`/`UnknownAnswer`, question-id placement, `primitive`, `typed question key`, `System One` as an endpoint).

**Evidence.** `./gradlew checkVersion ktlintCheck jvmTest apiCheck` → `BUILD SUCCESSFUL` (`jvmTest NO-SOURCE`: the repo has no `src/` yet, so the gate is green because there is nothing to test, not because tests passed). `api/jvm/jev-kmp.api` unchanged.

Deliberately undone: no README link to `docs/adr/` (ticket 14 owns the README); no CHANGELOG entry (nothing a consumer of the artifact would notice); ADR `0005` (ticket 07).

Later tickets must pick up: ticket 07 writes `0005`; ticket 10 has to make ADR 0004's divergences true in code, including the KDoc note about OkHttp's invisible connection retries.

Shipped but unmerged: https://github.com/snevadalabs/jev-kmp/pull/1