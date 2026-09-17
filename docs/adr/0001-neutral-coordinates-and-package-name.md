# 0001. Neutral coordinates and package name over upstream `ai.typesafe:*`

- **Status:** Accepted
- **Date:** 2026-09-17
- **Source:** [Lock the v0.1 design brief](../../.scratch/jev-kmp-sdk/issues/01-lock-v0.1-design-brief.md) §2; [Confirm the Maven Central namespace](../../.scratch/jev-kmp-sdk/issues/18-confirm-maven-central-namespace.md); [Establish Maven Central publishing](../../.scratch/jev-kmp-sdk/issues/03-establish-maven-central-publishing.md)

## Context

Nothing in the TypeSafe organisation is ours to publish into. The Python and JS SDKs live under
`typesafe-sdk` on PyPI and `@typesafe-ai/sdk` on npm, and there is no existing Maven coordinate to
inherit. A namespace we do not control would make the first release depend on a third party granting
access, and would tie our release cadence to theirs.

## Decision

Ship under `com.sierranevadalabs:jev-kmp`, package `com.sierranevadalabs.jev.sdk`. The group id is
reachable through DNS-TXT control of `sierranevadalabs.com`, which Sierra Nevada Labs owns, so the
Central Portal verification route is open today. The package mirrors the coordinate as closely as
Kotlin allows; `com.sierranevadalabs.jev-sdk` is not a legal package, because `-` is not a valid
package segment.

## Alternatives

- **`ai.typesafe:*`.** The natural-looking coordinate, but not ours to publish. It would block day one
  on an upstream grant and couple our releases to theirs.
- **`io.github.snevadalabs:*`.** The standard fallback for an organisation without a domain. Rejected
  once the DNS route was confirmed open: it encodes a GitHub org where a stable identity is available.
- **`com.sierranevadalabs.jev-sdk` as the package.** Rejected as illegal; hyphen to dot preserves the
  intent and matches the artifact name.

## Consequences

The coordinate and package are ours from the first release and need no permission to update.

A handover to upstream is cheap but not literally Maven-only, and the difference is worth stating
precisely for whoever plans it:

- The group id and artifact are a Maven change — republish plus a relocation POM.
- The **package name is source-visible**. `com.sierranevadalabs.jev.sdk` appears in every consumer
  import. Upstream can keep our package unchanged (legal, zero consumer churn) or rename it to their
  own, which costs consumers a mechanical import rewrite. No logic changes either way.

The brief's §2 phrase "a Maven-only change" is accurate for the coordinates and optimistic for the
package. This ADR records the honest cost rather than the slogan.

## What would change our mind

Upstream offers the `ai.typesafe` namespace with publish credentials and a commitment to maintain it
before `0.1.0` ships — or `sierranevadalabs.com` DNS control is lost, which would invalidate the
Portal route and force the `io.github.*` fallback.
