# Publish 0.1.0 to Maven Central

Type: task
Status: resolved
Blocked by: 13, 14, 18

## Question

Nothing to decide — everything is built and gated. This ticket is the human-in-the-loop release, and it is the only ticket on the map that cannot be finished by an agent alone.

**[noted by the parent before dispatch]** Two prerequisites are missing and one is stale. The repo's GitHub Actions minutes are exhausted — every job fails in 9s without starting, and `check.yml` is disabled — and the Portal credentials and signing key are not repository secrets. **So do not tag or push `v0.1.0`:** `publish.yml` still triggers on `v*`, the run cannot start, and the tag would be an orphan pointing at a commit no CI ever verified. A tag is cheap to make and socially expensive to walk back. Do the parts that are safe — cut the `0.1.0` CHANGELOG section, commit the API dump, run the gate locally with `./gradlew check` — and report exactly what the human must do first. Steps 2 and 4 lose their CI half until billing is restored. Also stale: prerequisite 4 ("create the remote repository") is done, `snevadalabs/jev-kmp` exists and is pushed.

**Human prerequisites** (from *Establish Maven Central publishing* — fill in the exact steps there, do not re-derive them here):

1. Register and verify the `com.sierranevadalabs` namespace on the Sonatype Central Portal.
2. Generate a PGP signing key and publish the public half to a keyserver.
3. Add the portal credentials and signing key as repository secrets on the GitHub repo.
4. Create the remote repository and push `main`.

**Agent work, once the above exist:**

1. Cut the `0.1.0` section in `CHANGELOG.md` with the real release date, replacing `Unreleased`. The version-consistency check in CI must pass.
2. Verify the full gate green on `main`: `ktlintCheck`, `apiCheck`, `allTests`, coverage floor, doc-compilation test. Nothing ships on a red gate.
3. Commit the generated `api/jev-kmp.api` dump — it is the 0.1.0 compatibility baseline that every future release is checked against.
4. Tag `v0.1.0` and let the publish workflow run.
5. Verify the artifact actually resolves from a clean, unrelated project — not just that the upload succeeded. `implementation("com.sierranevadalabs:jev-kmp:0.1.0")`, one call, one answer. Both siblings have a distribution smoke test for exactly this reason; Python's is shallow (it checks the version and that the client constructs) and JS's is not (it installs the tarball and round-trips). Do the JS version.
6. Create the GitHub Release with the changelog section as its body.

**Then, the point of the effort:**

7. Open a pull request upstream against `typesafe-ai/typesafe-sdk-python` and `typesafe-sdk-js` offering the `conformance/` fixtures, with the ADR as the spec. This is the only deliverable on the map that outlives the SDK itself — three SDKs agreeing on the wire is worth more than a fourth one existing.

Deliverable: `0.1.0` resolving from Maven Central, verified from a clean project, with the GitHub Release published and the fixtures offered upstream.

## Answer

The agent half is done; the release is blocked on the human prerequisites and on one POM value nobody has chosen.
Branch `issue-15-publish-to-maven-central`, PR https://github.com/snevadalabs/jev-kmp/pull/9. Nothing is tagged
or published, per the note above.

**Landed**

- `CHANGELOG.md` — `## [Unreleased]` replaced by `## [0.1.0] - 2026-09-17`, expanded from one bullet to the
  actual 0.1.0 surface (typed questions for all three primitives, the JS retry policy plus per-call overrides,
  the target list, `conformance/`, the api baseline). This section becomes the GitHub Release body. The date
  is the preparation date; if the owner tags on a different day, fix that one line first.
- `gradle.properties` — `version=0.1.0-SNAPSHOT` → `version=0.1.0`, which is what makes `checkVersion` pass
  against the release heading. `SDK_VERSION` in `Headers.kt` is already `0.1.0` and needed no change.
- `api/jvm/jev-kmp.api` needed no change: *Implement the client and error tree* already committed it, and
  `apiCheck` is green against it.

**Gate**

`ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew check --rerun-tasks` → **BUILD SUCCESSFUL in 39s, 69/69
tasks executed** (deliberately `--rerun-tasks`: the plain run was green in 9s almost entirely from cache).
341 tests, 0 failures, 0 skipped across `jvmTest`, `testAndroidHostTest`, `iosSimulatorArm64Test` and
`macosArm64Test`; `ktlintCheck`, `apiCheck`, `checkVersion`, `checkJvmBytecode`, `koverVerify` (94% floor) and
`dokkaGenerate` all executed green. `linuxX64Test` and `iosX64Test` are SKIPPED on a macOS host — a Linux ELF
cannot execute here and `iosX64` is compile-only — which is why the Linux lane runs the former.

**Found — a release blocker the gate does not cover**

```
$ ./gradlew checkPomFileForKotlinMultiplatformPublication
> There was a problem with the Maven POM file configuration.
      Missing tags in POM:
      * <developers> - <developer> - <email>
      * <developers> - <developer> - <organization>
      * <developers> - <developer> - <organizationUrl>
```

`build.gradle.kts:204-210` sets only `id`, `name` and `url` on the developer. Central requires all four tags
(central.sonatype.org/publish/requirements), which is why KGP ships `checkPomFileFor*Publication` — and that
task is in neither `check` nor `publishToMavenCentral`'s graph (verified with `--dry-run`), so nothing catches
this before the Portal rejects the deployment. Two values are already in the POM and can be filled
mechanically: `organization = "Sierra Nevada Labs"`, `organizationUrl = "https://github.com/snevadalabs"`.
**The developer email appears nowhere in the repo, so it is an owner decision and was not invented** — this is
the one thing on the ticket that needs the parent.

**Human prerequisites, in order** (details and secret names: ticket 03's checklist; 18 settled the namespace)

1. Portal namespace `com.sierranevadalabs` via the DNS TXT Verification Key on `sierranevadalabs.com`; do not
   click *Verify Namespace* before the record resolves.
2. PGP keypair, public half on a keyserver.
3. Repository secrets — **verified absent today**: `gh secret list -R snevadalabs/jev-kmp` prints nothing and
   exits 0, and recent runs on the repo still fail in 4–8s (billing blocked).
4. Give the developer email for the POM and add the other two developer tags. New here; not in ticket 03.
   Note: this machine's `~/.gradle/gradle.properties` already holds Portal credentials and an armored PGP key
   (values not echoed), so step 3 may be a copy rather than a fresh setup.
5. Remote repository and pushed `main`: already done (`snevadalabs/jev-kmp`).

**Left undone deliberately**

Steps 4–7: no `v0.1.0` tag (none exists locally or on the remote), no publish, no clean-project resolution
smoke test, no GitHub Release, no upstream PR offering `conformance/`.

**For a later ticket**

- The POM developer tags above — they must be green before the owner tags.
- `publish.yml` gates the release on `checkVersion` alone, not on `check` and not on the POM check, so it can
  upload a POM the Portal rejects. Worth widening while the release path is open.
- Post-release: the clean-project resolution check and the upstream conformance-fixture PR.
