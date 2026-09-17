# Establish Maven Central publishing

Type: research
Status: resolved
Blocked by:

## Question

What does it take to publish `com.sierranevadalabs:jev-kmp` to Maven Central from GitHub Actions, and what must a human do versus what a script can do?

Answer all of:

1. **Namespace.** Can `com.sierranevadalabs` be registered on the Sonatype Central Portal without owning the domain? What is the actual verification path (DNS TXT on `sierranevadalabs.com`, a GitHub-org-based claim, a personal-domain fallback)? If a GitHub-org-based claim is available, which org/repo must exist and what is the exact proof the portal requires?
2. **The publish plugin.** Current vanniktech `gradle-maven-publish-plugin` version and the exact configuration for a KMP library: coordinates, POM metadata (`name`, `description`, `url`, license, developer, SCM), sources and Dokka javadoc jar, signing.
3. **Signing.** Does the Central Portal still require a PGP signature on every artifact? Where does the key live in CI, what secrets are needed, and how is an in-memory key handled?
4. **Release path.** Central Portal vs the legacy OSSRH staging flow — which does the vanniktech plugin's current default target, and what is the actual sequence from a git tag to a released artifact (staging, manual release, or fully automatic)?
5. **CI shape for a KMP library.** Which GitHub-hosted runners are needed for the Apple targets (macOS runners only, or is cross-compilation from Linux viable)? Is there a matrix strategy that runs `allTests` for every target without burning an unreasonable number of macOS minutes, and what do real KMP libraries do?
6. **Secret hygiene for PRs.** How do live-API integration tests get a secret on `main`/tags without exposing it to fork pull requests? Compare: repository secrets with an environment gate, `pull_request_target` (and why that is dangerous), a separate scheduled workflow. Recommend one.

Deliverable: a precise human-action checklist (which the publish ticket will hand to the owner) plus the workflow shape and secret names.

## Answer

Full findings, secrets layout, workflow shape, and the one-hour human checklist: [research/03-maven-central-publishing.md](../research/03-maven-central-publishing.md).

**The finding that gates the whole destination.** `com.sierranevadalabs` is obtainable **only** by proving DNS control of `sierranevadalabs.com` with a TXT record. The code-hosting verification path is limited to *personal* `io.github.<username>` namespaces — a GitHub **org**-based claim is not offered. So the coordinates locked in the design brief are contingent on the org controlling that domain. That dependency is a fact only the owner knows, so it is now its own ticket: *Confirm the Maven Central namespace*.

**Everything else is settled and automatable.** Legacy OSSRH and `issues.sonatype.org` are dead — OSSRH reached end-of-life 2025-06-30 — so every staging-repository tutorial in circulation is obsolete. The Central Portal is the only path. vanniktech **0.37.0** publishes to it directly, signs in-memory, and releases via `publishAndReleaseToMavenCentral`; a PGP key is still required.

**CI shape**, verified against what real KMP projects do rather than guessed:

- One `linux-tests` job (JVM, Android unit, `linuxX64Test`) and **one** `macos-latest` job batched across the Apple simulator targets. GitHub-hosted runners are free for public repos, which is why the macOS minutes are confined to a single job.
- **Never `iosX64Test` on an arm64 macOS runner** — it silently no-ops, and a real project shipped while running zero iOS tests because of it. Use `iosSimulatorArm64Test`. Device targets cannot execute on hosted runners at all; they are compile-only checks.
- **The live-API tier goes in a separate `integration.yml`** triggered by `schedule` + `workflow_dispatch` + push to `main`. `pull_request` from a fork gets no secrets by design, and `pull_request_target` grants fork authors the base repo's token and secrets — it is the documented root cause of real supply-chain incidents and is not to be used anywhere in this repo.

Secret names: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `GPG_KEY_CONTENTS`, `JEV_API_KEY`.
