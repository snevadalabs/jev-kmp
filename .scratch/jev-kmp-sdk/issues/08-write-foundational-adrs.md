# Write the foundational ADRs

Type: task
Status: open
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
