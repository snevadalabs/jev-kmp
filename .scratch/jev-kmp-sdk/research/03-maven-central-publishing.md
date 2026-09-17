# Establish Maven Central publishing

Researched: June 2026 (latest verified primary-source date: 2026-06-21)

Resolves: `.scratch/jev-kmp-sdk/issues/03-establish-maven-central-publishing.md`
Target: publish `com.sierranevadalabs:jev-kmp` (Kotlin Multiplatform) from GitHub Actions to Maven Central. We do not own `typesafe.ai` and will not claim their groupId.

## Summary

`com.sierranevadalabs` can only be registered on the Sonatype Central Portal by proving control of `sierranevadalabs.com` with a DNS TXT record — a GitHub-org-based namespace claim is **not** a supported path (GitHub verification is limited to personal `io.github.<username>` namespaces). Everything after the human does (Portal account, namespace TXT verification, PGP key, user token, repo secrets) is automatable: the vanniktech plugin 0.37.0 publishes to the Central Portal directly, signs in-memory, and can release automatically via `publishAndReleaseToMavenCentral`. OSSRH / issues.sonatype.org / staging-repository flows referenced by older tutorials are dead (OSSRH EOL 2025-06-30).

## Findings

### 1. Namespace: `com.sierranevadalabs` requires DNS control of `sierranevadalabs.com`

**Claim:** The only supported way to get `com.sierranevadalabs` is the DNS path: the Portal issues a Verification Key, a human adds it as a TXT record on `sierranevadalabs.com`, and the Portal verifies (minutes). The record can be deleted after verification.

**Sources:** [Register a Namespace — Central Portal docs](https://central.sonatype.org/register/namespace/). **Support:** direct evidence.

- The automated check inspects the **exact reversed domain**: `com.sierranevadalabs` → checks `sierranevadalabs.com` (not a subdomain, not `com.example.com`-style variants).
- Don't click "Verify Namespace" before the TXT record resolves — a failed check leaves NXDOMAIN cached and delays verification.
- If the org controls `sierranevadalabs.com` DNS, this is a ~15-minute human task.

**Claim:** A GitHub-org-based claim (`io.github.sierranevadalabs` or similar) is **not** available as a self-service path. **Sources:** same page. **Support:** direct evidence: "Currently, we only support the GitHub username that you used to sign up, so `io.github.<github organization name>` is not available as an automatically registered namespace." The supported code-hosting path is described throughout as a *personal* groupId (`io.github.<username>`) for "personal accounts" ([Coordinates docs](https://central.sonatype.org/publish/requirements/coordinates/)) — verification there is a temporary public repo named after the Verification Key.

- *Researcher inference (flagged):* the docs don't explicitly forbid getting `io.github.<orgname>` via a support email to central-support@sonatype.com, but it is not documented or promised. Do not build the plan on it.
- Practical consequence: since we're not claiming a GitHub-based namespace anyway, signing up for the Portal with a GitHub account (or anything else) doesn't matter; the DNS TXT path is what we use. The existence of the GitHub org/repo is irrelevant to namespace verification.

**Claim:** Registering `com.sierranevadalabs` grants publishing rights for all sub-groups (`com.sierranevadalabs.jev`, etc.). **Support:** direct evidence (Coordinates docs: "if you register `com.example` you will be able to publish any component under that groupId or any sub-group").

### 2. Plugin: vanniktech `gradle-maven-publish-plugin` 0.37.0

**Claim:** Current version is **0.37.0** (released 2026-06-21). Minimum supported: JDK 17, Gradle 9.0.0, AGP 8.13.0, Kotlin Gradle Plugin 2.2.0. (0.36.0 was the breaking bump of minimums.) **Sources:** [changelog](https://vanniktech.github.io/gradle-maven-publish-plugin/changelog/), [releases](https://github.com/vanniktech/gradle-maven-publish-plugin/releases), [official Kotlin tutorial](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries-to-maven.html) (also pins 0.37.0). **Support:** direct evidence.

Configuration for the KMP library module (`jev-kmp/build.gradle.kts`):

```kotlin
plugins {
    id("com.vanniktech.maven.publish") version "0.37.0"
}

mavenPublishing {
    publishToMavenCentral()          // targets the Central Portal
    signAllPublications()            // GPG signing, a Central requirement

    coordinates(group.toString(), "jev-kmp", version.toString())

    pom {
        name = "Jev KMP"
        description = "..."          // required by Central
        inceptionYear = "2026"
        url = "https://github.com/<org>/jev-kmp/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "sierranevadalabs"
                name = "Sierra Nevada Labs"
                url = "https://github.com/<org>/"
            }
        }
        scm {
            url = "https://github.com/<org>/jev-kmp/"
            connection = "scm:git:git://github.com/<org>/jev-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/<org>/jev-kmp.git"
        }
    }
}
```

- Sources/javadoc jars are **automatic**: the plugin detects the Kotlin Multiplatform plugin and publishes a sources jar (always enabled for KMP) and a javadoc jar built from Dokka when the Dokka plugin is applied ([what to publish](https://vanniktech.github.io/gradle-maven-publish-plugin/what/)). For KMP you can override with `configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaHtml"), sourcesJar = SourcesJar.Sources()))`; the third parameter (`androidVariantsToPublish`) defaults to `"release"`.
- All POM fields are also settable via Gradle properties (`GROUP`, `POM_ARTIFACT_ID`, `VERSION_NAME`, `POM_NAME`, `POM_SCM_URL`, …) — same outcome, pick one.
- Local pre-flight checks: `./gradlew checkSigningConfiguration` (verifies the public key is on a keyserver) and `./gradlew checkPomFileForMavenPublication`.

### 3. Signing: PGP still required for releases; in-memory key in CI

**Claim:** Yes, the Central Portal requires a GPG signature on every release artifact; snapshots (`-SNAPSHOT`) are the exception (signed only if configured). **Sources:** [Central docs](https://central.sonatype.org/publish/requirements/gpg/), [plugin central.md prerequisites](https://github.com/vanniktech/gradle-maven-publish-plugin/blob/main/docs/central.md). **Support:** direct evidence.

- **Human:** generate a keypair (`gpg --full-generate-key`, or the KGP task `./gradlew -Psigning.password=… generatePgpKeys`), then **distribute the public key** to `keyserver.ubuntu.com` or `keys.openpgp.org` — Central refuses artifacts whose key can't be fetched. This is a human step, once per key lifetime.
- **CI:** the plugin supports an in-memory armored key via Gradle properties:

```
ORG_GRADLE_PROJECT_mavenCentralUsername   # Portal user token username
ORG_GRADLE_PROJECT_mavenCentralPassword   # Portal user token password
ORG_GRADLE_PROJECT_signingInMemoryKey     # output of: gpg --export-secret-keys --armor <KEY_ID>
ORG_GRADLE_PROJECT_signingInMemoryKeyId   # last 8 chars of the key ID (optional)
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword  # key passphrase (omit if key has none)
```

- The Central Portal username/password in CI is **not** the login password — a human must generate a **User Token** at `central.sonatype.com/usertoken` ([generate-portal-token docs](https://central.sonatype.org/publish/generate-portal-token/)). Tokens can't be recovered later, only regenerated.

### 4. Release path: Central Portal only; OSSRH is dead

**Claim:** The vanniktech plugin's `publishToMavenCentral` / `publishAndReleaseToMavenCentral` targets the Central Portal. Legacy OSSRH (issues.sonatype.org JIRA tickets, oss.sonatype.org / s01.oss.sonatype.org, Nexus staging repos, close-and-release) reached end-of-life on **June 30, 2025**; issues.sonatype.org itself was decommissioned **March 12, 2024**. Any tutorial mentioning those is historical. **Sources:** [OSSRH sunset announcement](https://central.sonatype.org/news/20250326_ossrh_sunset/), [issues.sonatype.org deprecation](https://central.sonatype.org/news/20240109_issues_sonatype_org_deprecation/), [plugin central.md](https://github.com/vanniktech/gradle-maven-publish-plugin/blob/main/docs/central.md). **Support:** direct evidence.

Sequence from git tag → released artifact with `publishAndReleaseToMavenCentral`:

| Step | Who | What |
|---|---|---|
| 1 | Human/CI | Tag push (e.g. `v1.0.0`) triggers the publish workflow |
| 2 | Plugin | Uploads artifacts to a new Portal deployment |
| 3 | Portal | Validates the deployment (plugin polls every 5 s, fails the build on `FAILED`, 60 min timeout) |
| 4 | Plugin | Releases the deployment automatically (`publishAndReleaseToMavenCentral`) |
| 5 | Portal | Artifacts appear on Maven Central **10–30 minutes** later |

- Manual alternative: `publishToMavenCentral`, then a human clicks **Publish** in the [Deployments UI](https://central.sonatype.com/publishing/deployments). Recommendation: use the automatic task; no human in the release loop.
- `mavenCentralAutomaticPublishing=true` or `publishToMavenCentral(automaticRelease = true)` achieve the same for the plain `publishToMavenCentral` task.

### 5. CI shape for a KMP library: one macOS job for Apple simulator tests; Linux for everything else

**Claim:** Apple-target *tests* and *framework/XCFramework linking* require a macOS host (Xcode + Apple SDK). Linux runners can run JVM/JS/wasm/Android tests and `linuxX64Test`, but Apple K/N test binaries can't execute there, and iOS builds fail without Apple tooling. **Sources:** [official KMP CI guide](https://kotlinlang.org/docs/multiplatform/github-actions-for-kmp.html) (iOS job on `macos-latest`), plus real projects below. **Support:** direct evidence.

What real KMP projects do (primary evidence — their CI files):

- **Kaluga** ([splendo/kaluga `.github/workflows/apple.yaml`](https://github.com/splendo/kaluga/blob/a30e2f277337d69f3a492b1d49a053ecf6099ee2/.github/workflows/apple.yaml)): separate Apple workflow on macOS runners (`macos-26`), with a reusable `setup_macos.yaml` workflow called per Gradle task (`compileKotlinIosSimulatorArm64`); JVM/Android checks live elsewhere.
- **kuri** ([dexpace/kuri `.github/workflows/ci.yml`](https://github.com/dexpace/kuri/blob/4e402ec1d7ac1db45e84bb519930c3615791f59b/.github/workflows/ci.yml)): matrix by host capability — JVM/JS/wasm + `linuxX64` on Linux, `mingwX64` on Windows, macOS arm64 host target + iOS/tvOS/watchOS **simulator** targets on macOS. Device targets (`iosArm64`/`tvosArm64`/`watchosArm64`) have no executable host on any GitHub-hosted runner — compile-only.
- **negentropy-kmp** ([commit a0b34ac](https://github.com/vitorpamplona/negentropy-kmp/commit/a0b34acd71b645bbb7596bdb92093e96d32d0ae1)): real bug — `macos-latest` runners are Apple Silicon, so `iosX64Test` was **silently disabled** by architecture mismatch and CI stayed green running zero iOS tests. Use `iosSimulatorArm64Test` on arm64 macOS runners.
- **kuilt** ([`.github/workflows/apple-nightly.yml`](https://github.com/tractat-us/kuilt/blob/68f5ce29fa54087b500673966098f6a2f15d8d6f/.github/workflows/apple-nightly.yml)): deliberately keeps the Apple lane out-of-band (nightly) because a single ubuntu job's `ci-required` check goes green while Apple `*Test` tasks are silently skipped on Linux.

Recommended shape (matches the real projects above):

- **ci.yml** — `on: [push, pull_request]`, no secrets needed:
  - Job `linux-tests` on `ubuntu-latest`: `jvmTest`, JS/wasm tests, Android unit tests, `linuxX64Test`.
  - Job `apple-tests` on `macos-latest` (arm64): `macosArm64Test iosSimulatorArm64Test` (+ watchos/tvos simulator tests if targets exist). Keep it to **one** macOS job — batch the simulator targets — and never use `iosX64Test` on arm64 runners (silently no-ops).
  - Device targets: compile/link checks only (`assemble`/`link*`), or nothing; they can't be executed on hosted runners. *Researcher inference from the kuri workflow's documented target/host matrix.*
- **publish.yml** — on tag push / GitHub release; runs on `macos-latest` (the official Kotlin tutorial's publish job runs on `macOS-latest` — the safe host for building all KMP artifacts including Apple ones; whether a Linux host can publish Apple klibs without an Apple SDK is not officially documented, so don't gamble the release job on it).
- **Cost:** standard GitHub-hosted runners are **free for public repositories** ("GitHub Actions usage is free for standard GitHub-hosted runners in public repositories" — [GitHub billing docs](https://docs.github.com/en/actions/concepts/billing-and-usage)). If the repo is private, macOS is the most expensive tier ($0.062/min vs Linux $0.006/min on the [current pricing page](https://docs.github.com/en/billing/reference/actions-runner-pricing) — the historical 10× multiplier lives on in the rates), which is exactly why the matrix above confines Apple work to one job.

### 6. Secret hygiene for PRs: never `pull_request_target`; gate live-API tests to main/schedule

**Claim:** Workflows triggered by `pull_request` from forks run **without access to any secrets** (including environment secrets) — by design. Workflows triggered by `pull_request_target` run with the **base repository's `GITHUB_TOKEN` and all repo/org secrets** — the "pwn request" pattern, root cause of real supply-chain incidents (tj-actions/changed-files, March 2025). **Sources:** [GitHub Docs — Securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target), [GitHub Changelog 2026-06-18 — Safer pull_request_target defaults](https://github.blog/changelog/2026-06-18-safer-pull_request_target-defaults-for-github-actions-checkout/). **Support:** direct evidence.

- GitHub shipped two mitigations without changing the trust model: environment branch-protection changes effective 2025-12-08 ([changelog 2025-11-07](https://github.blog/changelog/2025-11-07-actions-pull_request_target-and-environment-branch-protections-changes/)) and `actions/checkout` now refusing head-of-PR checkout in `pull_request_target` runs unless you opt out with `allow-unsafe-pr-checkout`. The event still grants fork authors privileged execution context — **avoid it entirely** for running project code.
- Scheduled (`cron`) workflows and `push`-to-main workflows run only code from the default branch with full secrets access; fork PRs cannot influence what they execute.

**Recommendation:** keep live-API integration tests in a **separate workflow** triggered by `push` to `main` + a nightly `schedule` + `workflow_dispatch`. Store the API key as a normal repository secret. Fork PRs run the plain `pull_request` CI without the secret; the integration job is either in the separate workflow (so it never runs for PRs at all — simplest) or gated with `if: ${{ secrets.JEV_API_KEY != '' }}`. Optionally put the secret in a GitHub **Environment** with required reviewers as an extra gate. Publishing secrets are already safe: the publish workflow triggers on tags/releases, events only the base repo can fire. `pull_request_target`: do not use, anywhere.

### Workflow shape and secret names

```
.github/workflows/ci.yml          # pull_request + push; no secrets
  linux-tests  (ubuntu-latest): jvmTest, js/wasm tests, androidUnitTest, linuxX64Test
  apple-tests  (macos-latest):  macosArm64Test iosSimulatorArm64Test

.github/workflows/publish.yml     # on: push tags 'v*' (and/or release: published)
  runs-on: macos-latest
  steps: checkout@v4 → setup-java (JDK 17+, zulu) →
         ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
  env:
    ORG_GRADLE_PROJECT_mavenCentralUsername:      ${{ secrets.MAVEN_CENTRAL_USERNAME }}
    ORG_GRADLE_PROJECT_mavenCentralPassword:      ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
    ORG_GRADLE_PROJECT_signingInMemoryKeyId:      ${{ secrets.SIGNING_KEY_ID }}
    ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
    ORG_GRADLE_PROJECT_signingInMemoryKey:        ${{ secrets.GPG_KEY_CONTENTS }}

.github/workflows/integration.yml # schedule (nightly) + workflow_dispatch + push to main
  runs-on: ubuntu-latest
  env: JEV_API_KEY: ${{ secrets.JEV_API_KEY }}   # never on the pull_request path
```

Repository secrets to create: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `GPG_KEY_CONTENTS`, `JEV_API_KEY`. (Secret names match the official Kotlin tutorial's publish example.)

## Contradictions

- **Coordinates page is partially stale:** [central.sonatype.org/publish/requirements/coordinates](https://central.sonatype.org/publish/requirements/coordinates/) still says ownership is proved by "a TXT record … referencing your **OSSRH ticket number**" and a repo named after an "OSSRH-TICKETNUMBER". The current [namespace page](https://central.sonatype.org/register/namespace/) and the Kotlin tutorial describe the same mechanism but with a **Verification Key** issued by the Portal (no JIRA ticket exists anymore). Treat "ticket" as an old name for the Verification Key; follow the namespace page.
- Otherwise none found. The vanniktech docs, Kotlin tutorial, and Central Portal docs are mutually consistent on the Portal flow.

## Missing evidence

- Whether `io.github.<orgname>` can be obtained at all via a support request to Sonatype — undocumented; community reports vary. Not needed for this plan.
- Whether a Linux host can compile/publish Apple-target klibs for Maven Central without an Apple SDK — not officially documented; the official tutorial runs the publish job on macOS, and this research recommends matching that rather than experimenting in a release job.
- Whether `sierranevadalabs.com` is actually owned/manageable by the org — internal question, not verifiable from public sources. **It is the single hard dependency of the whole plan.**

## Sources

Kept:
- [Register a Namespace — Central Portal](https://central.sonatype.org/register/namespace/) — definitive answer to Q1 (DNS path, GitHub-org limitation).
- [Choosing your Coordinates — Central Portal](https://central.sonatype.org/publish/requirements/coordinates/) — reverse-DNS rule, sub-group rights, hosting-service namespaces.
- [Maven Central — gradle-maven-publish-plugin docs](https://github.com/vanniktech/gradle-maven-publish-plugin/blob/main/docs/central.md) / [site](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) — Q2/Q3/Q4 config, secrets, release steps.
- [What to publish — plugin docs](https://vanniktech.github.io/gradle-maven-publish-plugin/what/) — KMP-specific `KotlinMultiplatform` config, sources/javadoc jar behavior.
- [Publish your library to Maven Central — Kotlin tutorial](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries-to-maven.html) — official end-to-end flow, secret names, publish workflow (updated April 2026, pins 0.37.0).
- [OSSRH Sunset Announcement](https://central.sonatype.org/news/20250326_ossrh_sunset/) — EOL 2025-06-30, kills legacy tutorials.
- [issues.sonatype.org Deprecation](https://central.sonatype.org/news/20240109_issues_sonatype_org_deprecation/) — 2024-03-12 decommission.
- [GitHub Actions for KMP — Kotlin docs](https://kotlinlang.org/docs/multiplatform/github-actions-for-kmp.html) — official runner guidance (iOS job on macOS).
- Kaluga / kuri / negentropy-kmp / kuilt CI files (GitHub) — real-world KMP CI practice for Q5.
- [Securely using pull_request_target — GitHub Docs](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target) + [changelogs 2025-11-07, 2026-06-18](https://github.blog/changelog/2026-06-18-safer-pull_request_target-defaults-for-github-actions-checkout/) — Q6 fork-secret facts and mitigations.
- [GitHub Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage) + [runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing) — public-repo free runners, macOS rate.

Rejected/deprioritized:
- Medium posts, gists, and blog tutorials on "publish KMP to Maven Central" — SEO-heavy and frequently pre-Portal (OSSRH-era instructions).
- `kotlinlang.org/docs/mpp-publish-to-central.html` — 404; superseded by the multiplatform-docs URL above.

## Next steps

- Confirm (internal) that the org controls `sierranevadalabs.com` DNS; if not, that's a domain purchase before anything else.
- Optional: dry-run the plugin locally with `publishToMavenCentral` against a `1.0.0-SNAPSHOT` to validate signing before the first tag.

---

# Human-action checklist (hand to the owner)

Everything a **human** must do once; CI does the rest automatically after this.

1. **Confirm domain control.** The org must be able to add a DNS TXT record on `sierranevadalabs.com`. Nothing else in the plan works without this.
2. **Create a Central Portal account** at https://central.sonatype.com (sign up by any method; the GitHub-vs-email choice doesn't matter since we're using the DNS path, not a GitHub namespace).
3. **Register the namespace** `com.sierranevadalabs` (central.sonatype.com → top-right menu → View Namespaces → Add Namespace).
4. **Verify the namespace:** copy the Verification Key, add it as a **TXT record on `sierranevadalabs.com`**, then click **Verify Namespace** (only after the record resolves). Verification takes minutes; delete the TXT record afterward.
5. **Generate a PGP keypair** (`gpg --full-generate-key` or `./gradlew -Psigning.password=… generatePgpKeys`). Note the key ID and passphrase.
6. **Publish the public key** to `keyserver.ubuntu.com` (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`); verify with `./gradlew checkSigningConfiguration`.
7. **Generate a Central Portal User Token** at https://central.sonatype.com/usertoken (not the login password). Store it immediately — it can't be retrieved again.
8. **Add repository secrets** (GitHub → Settings → Secrets and variables → Actions):
   - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — user token from step 7
   - `GPG_KEY_CONTENTS` — full armored private key (`gpg --export-secret-keys --armor <KEY_ID>`)
   - `SIGNING_PASSWORD` — key passphrase
   - `SIGNING_KEY_ID` — last 8 characters of the key ID
   - `JEV_API_KEY` — live-API key for integration tests (add to the nightly/main-only workflow only)
9. **First release:** push tag `v1.0.0` → watch the publish workflow → artifacts appear on Maven Central within 10–30 minutes of the run going green. (Only if you chose the manual variant: click **Publish** in the Portal's Deployments UI.)

That's it — ~1 hour of human work, all one-time. Everything after step 9 (test, build, sign, upload, validate, release) is CI.
