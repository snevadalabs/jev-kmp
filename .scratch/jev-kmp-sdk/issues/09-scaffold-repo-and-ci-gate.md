# Scaffold the repo and its CI gate

Type: task
Status: resolved
Blocked by:

## Question

Read [research/02-kmp-toolchain-and-targets.md](../research/02-kmp-toolchain-and-targets.md) for the pinned matrix, and [research/03-maven-central-publishing.md](../research/03-maven-central-publishing.md) for the CI shape and secret names.

Two things the research changed since this ticket was written:

- **AGP 9 makes `com.android.kotlin.multiplatform.library` mandatory** for the Android target — `androidTarget()` is being removed. Use the new plugin.
- **The Apple lane specifics**: keep Apple tests to a single `macos-latest` job using `iosSimulatorArm64Test`. `iosX64Test` silently no-ops on an arm64 runner, and a real KMP project shipped with zero iOS tests running because of it. Device targets are compile-only on hosted runners.
- The coordinates in step 5 may change if *Confirm the Maven Central namespace* comes back negative. Keep the group id in one place.

Nothing to decide — stands up the build skeleton and the gate that the whole effort exists to prove is possible.

1. **Initialise the repository.** `git init`, `.gitignore` suited to Gradle + KMP + Xcode artifacts, MIT `LICENSE` (matching both siblings), `CHANGELOG.md` with an `Unreleased` section, `README.md` stub.
2. **Gradle build.** Single module. `libs.versions.toml` with the matrix from *Pin the KMP toolchain and target matrix*. `kotlin { }` block declaring JVM + Android + Apple + Linux x64 with the intermediate source sets. `explicitApi()`. `jvmToolchain` / Java bytecode level set consistently.
3. **Quality plugins.** ktlint, with a config file only if a rule genuinely needs overriding — the default ruleset is the point. Dokka configured to emit HTML. Binary-compatibility-validator configured with an `api/` directory, and the initial dump generated once sources exist.
4. **CI workflow.** `.github/workflows/check.yml` running on push and pull request: `ktlintCheck + apiCheck + allTests`, with the Apple targets on a macOS runner and JVM on Linux. Cache Gradle properly. This workflow is the deliverable that neither predecessor has; it must fail the build on a formatting error, an API-dump mismatch, or a failing test.
5. **Publish workflow skeleton.** `.github/workflows/publish.yml`, tag-driven, wired to the secret names and publishing config from *Establish Maven Central publishing*. It may not publish successfully yet — signing and namespace are human prerequisites — but the shape and the version/changelog consistency check should be in place.
6. **Version discipline.** Version declared once, in `gradle.properties`. A small `check-version` task asserting it matches the top `CHANGELOG.md` heading, run in CI. Both siblings do a version of this; neither's is wired into a gate that runs, so ours is.

Deliverable: a repo where `./gradlew check` passes on the empty skeleton, and CI is green on the first push. Do not implement any SDK behaviour here.

## Answer

**The repo is scaffolded, gated, pushed, and CI is green on both lanes** — `snevadalabs/jev-kmp` commits `1b66947` and `f705a43`, run [35220157989](https://github.com/snevadalabs/jev-kmp/actions/runs/35220157989). `./gradlew check` passes locally on the empty skeleton.

**What is there.** `git init` + `LICENSE` (MIT, Sierra Nevada Labs) + `CHANGELOG.md` (Keep a Changelog, top heading `Unreleased`) + a README stub; `gradle/libs.versions.toml` carrying the whole pinned matrix with the justification pointer; a single Gradle module (root project, `rootProject.name = "jev-kmp"`) with JVM, Android, `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64` and `linuxX64`; ktlint (no config file — the default ruleset is the point), Dokka, binary-compatibility-validator, and vanniktech publishing configured against the Central Portal; `explicitApi()` and `languageVersion`/`apiVersion` 2.1; `group` and `version` in `gradle.properties` only; the `check-version` task wired into `check` and into the publish workflow.

The Android target uses AGP's `com.android.kotlin.multiplatform.library` plugin and **opts in to host tests** — the new plugin disables them by default, so without `withHostTestBuilder {}.configure {}` `commonTest` would silently never run on Android.

**CI.** Two lanes in `.github/workflows/check.yml`: Linux runs `ktlintCheck checkVersion jvmTest linuxX64Test`; macOS runs `apiCheck iosSimulatorArm64Test macosArm64Test dokkaGenerate` plus the HTML artifact. The API-dump check and Dokka live on the macOS lane because it is the only host that can build every declared target. The publish workflow is tag-driven with no `pull_request` path, so the signing secrets cannot reach a fork, and it refuses to publish a version the changelog does not describe.

### Two corrections to the toolchain ticket, both enforced by the compiler rather than argued

1. **`macosX64` cannot be declared at all.** KGP 2.3.21 fails the build: `'fun macosX64(): KotlinNativeTargetWithHostTests' is deprecated. Target is no longer available.` Kotlin's target-tier table ([native-target-support](https://kotlinlang.org/docs/native-target-support.html), updated 2026-08-19) lists `macosX64` under **Deprecated targets, deprecation start Kotlin 2.3.20** — our pinned compiler is 2.3.21, one release past the start. It is dropped; Apple desktop coverage is `macosArm64`.
2. **`iosX64` is Tier 3, not Tier 1.** The same table puts `iosSimulatorArm64` and `iosArm64` in Tier 1 and `iosX64` in Tier 3 ("not guaranteed to be tested on CI"). It is *not* deprecated, so it stays — as a compile-only target, since no arm64 runner can execute it. The corrected tier list for our targets: **Tier 1** `macosArm64`, `iosSimulatorArm64`, `iosArm64`; **Tier 2** `linuxX64`; **Tier 3** `iosX64`. Anything stronger than "compile-only" for `iosX64` would be a promise the compiler does not make.

`androidTarget()` is confirmed gone in favour of the AGP plugin, as the toolchain ticket said. The `compilerOptions` DSL inside `kotlin { android { } }` is the lambda form; the `compilerOptions.configure { }` form in AGP's own documentation snippet does not compile.

### Both carried-forward verifications answered — and one premise was false

**The AAR floor does not exist.** The toolchain ticket asked CI to unzip Ktor's published Android AARs and read `aar-metadata.properties`. There are no such AARs. `ktor-client-core:3.5.2`'s Gradle module metadata declares 23 target modules and **none is `ktor-client-core-android`**; Android resolves `jvmApiElements-published`, which `available-at` maps to the plain `ktor-client-core-jvm`. The same holds for the engines — `ktor-client-android-3.5.2.jar` and `ktor-client-okhttp-3.5.2.jar` both exist while every `.aar` URL for them is a 404. So **no dependency enforces a `minSdk` on us**: `minSdk 28` is our deliberate choice, kept because it is Ktor's own build value and because Ktor ≥ 3.5 no longer runs on older API levels (KTOR-9607). A lower floor is technically available; nobody has asked for one. The docs-vs-build-config "contradiction" the researcher refused to paper over was an artefact of assuming an AAR that does not exist.

**The `org.gradle.jvm.version` trap does not trigger.** The generated module metadata for the JVM variants carries `org.gradle.category`, `org.gradle.jvm.environment=standard-jvm`, `org.gradle.libraryelements=jar`, `org.gradle.usage` and `org.jetbrains.kotlin.platform.type=jvm` — and **no `org.gradle.jvm.version` at all**. The failure mode the toolchain ticket warned about (metadata declaring Java 17) cannot happen, and no `jvmToolchain(8)` is needed. The attribute's absence means no JDK floor is imposed on consumers, which is the permissive direction. The actual bytecode level is still unverified, because there is no class file yet — that check belongs with the first real code.

CI does not yet assert either of these, because until Ktor is a declared dependency there is no AAR or artifact to inspect. The bytecode assertion is the piece worth adding, and it belongs to the transport ticket, which introduces the first compiled class.

### Deliberate choices in the skeleton

- **`.scratch/` is gitignored.** The planning tracker stays local and out of the published SDK history. One line to reverse if it should be committed instead.
- **`api/jvm/jev-kmp.api` is committed and empty** — zero bytes, because nothing is public yet. That is the baseline the next ticket's first public declaration will show up as a deliberate diff against.
- **The Dokka artifact is tolerant of an empty output** (`if-no-files-found: ignore`). Dokka's html directory is empty until there is a public API to document, and the *gate* is the `dokkaGenerate` task failing, not the upload. Once KDoc exists it starts delivering with no workflow change.
- **The app's `appleMain`/`nativeMain` intermediates come from the default hierarchy template**; no manual `dependsOn` edges anywhere, since one would silently disable the whole template.
