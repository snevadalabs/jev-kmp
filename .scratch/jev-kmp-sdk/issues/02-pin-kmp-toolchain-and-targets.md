# Pin the KMP toolchain and target matrix

Type: research
Status: resolved
Blocked by:

## Question

What exact versions and target declarations does the build use, and what does each platform actually report?

Answer all of:

1. **Version matrix.** Which combination of Kotlin, Ktor, kotlinx.serialization, kotlinx.coroutines, Gradle, the Android Gradle Plugin, and the vanniktech publish plugin is known-coherent as of today? The repo must pin one working set in `libs.versions.toml`, not "latest of everything".
2. **Kotlin version choice.** Latest stable is newer than what Ktor builds against. Which Kotlin version should a *multiplatform library* target so the widest set of consumer projects can depend on it, and what is the `apiVersion`/`languageVersion` policy that follows?
3. **Target declarations.** The exact `kotlin { }` target block for JVM + Android + `iosArm64`/`iosSimulatorArm64`/`iosX64`/`macosArm64`/`macosX64` + `linuxX64`, including which source sets the Apple targets share (a single `appleMain`/`appleTest` intermediate set?) and whether `linuxX64` needs its own intermediate.
4. **JVM/Android build parameters.** JVM target bytecode level, Android `minSdk`/`compileSdk`, and what Ktor 3.x itself requires — the answer must be the intersection, not our preference.
5. **Runtime identification.** For each of JVM, Android, iOS, macOS, Linux, what can the SDK actually report in `X-TypeSafe-Runtime`? Which `expect/actual` values are cheap (JVM `System.getProperty("java.version")`, Android `Build.VERSION.SDK_INT`, Apple `NSProcessInfo.operatingSystemVersion`) and which are a trap (anything needing a runtime permission or a bundle lookup)?
6. **First-party Gradle plugins.** Current versions and any known-coherence constraints for `binary-compatibility-validator`, ktlint, and Dokka against the chosen Kotlin version.

Deliverable: a concrete, pinned `libs.versions.toml` block and the target/source-set block, with a one-line justification per pin and a documented fallback for anything where the newest version is not the safe choice.

## Answer

Full findings, pinned `libs.versions.toml`, and the target/source-set block: [research/02-kmp-toolchain-and-targets.md](../research/02-kmp-toolchain-and-targets.md).

**The pinned set** (every version re-derived from Maven Central metadata and Ktor's own release POMs on 2026-09-16 — *not* search summaries, which is how we caught Ktor **3.6.0** where search still claimed 3.5.2 was latest):

- **Kotlin 2.3.21** with `languageVersion`/`apiVersion` **2.1** — the version Ktor actually builds against, *not* the newer 2.4.20. The lower language level widens who can consume us.
- **Gradle 9.3.0 + AGP 9.0.0** — the newest corner *inside* KGP 2.3.21's documented support matrix. Ktor itself runs AGP 9.3.0, which is outside it; we deliberately don't follow.
- **Ktor 3.5.2** (3.6.0 shipped during the research and is a drop-in later), kotlinx.serialization **1.11.0**, kotlinx.coroutines **1.11.0**.
- vanniktech **0.37.0**, BCV **0.18.2** (or KGP's built-in BCV, available since 2.2.0), ktlint-gradle **14.2.0**, Dokka **2.2.0** (2.3.0 is a Beta — do not pin it).
- Android **minSdk 28 / compileSdk 36**; JVM bytecode **1.8**; iOS deployment target **13.0**.

**The one finding that changes an existing ticket.** AGP 9 makes the Android target mandatory via the `com.android.kotlin.multiplatform.library` plugin; `androidTarget()` is being removed. *Scaffold the repo and its CI gate* must use the new plugin, not the familiar `androidTarget()` block.

**A trap worth carrying forward:** the published Gradle module metadata's `org.gradle.jvm.version` follows `targetCompatibility`/the toolchain, not `jvmTarget`. If it lands on 17 instead of 8, set `jvmToolchain(8)` so the artifact declares Java 8 and stays consumable.

**`X-TypeSafe-Runtime` is cheap and permission-free on every target we ship:** JVM `java.version` + `os.name`; Android `Build.VERSION.SDK_INT` (key off the int, not the spoofable `RELEASE` string); Apple `NSProcessInfo.operatingSystemVersion` (never parse `operatingSystemVersionString`); Linux `uname()` via `platform.posix`. One static string computed once per process.

**Three verifications carried forward — all cheap, none on the critical path, all belong in CI rather than in this ticket:**

1. ~~Unzip Ktor's published Android AARs and read `aar-metadata.properties`, to settle a **genuine contradiction** the researcher refused to paper over: Ktor's engine docs say the OkHttp engine works on Android 5.0+, but Ktor builds every Android artifact with `android-minSdk = 28`. This decides whether a lower floor is even possible.~~ **[amended by *Scaffold the repo and its CI gate*] There is no AAR to unzip.** `ktor-client-core:3.5.2` publishes 23 target modules and none is an Android one — Android resolves the plain `ktor-client-core-jvm` JAR, and the engines are JARs too (`ktor-client-android`, `ktor-client-okhttp`). No dependency therefore enforces a `minSdk` on us, the "contradiction" was an artefact of assuming an AAR that does not exist, and `minSdk 28` is a deliberate choice (Ktor's own build value, plus KTOR-9607) rather than a floor imposed by our dependencies.
2. ~~Assert `.module` declares `org.gradle.jvm.version=8` on first publish.~~ **[amended by *Scaffold the repo and its CI gate*] The attribute is absent, not wrong.** The generated module metadata carries `org.gradle.jvm.environment=standard-jvm` and `org.jetbrains.kotlin.platform.type=jvm` but **no** `org.gradle.jvm.version`, so the Java-17 failure mode cannot occur and no `jvmToolchain(8)` is needed. The bytecode level itself is still unasserted, because no class exists yet.
3. Confirm `ktor-client-cio` resolves on `linuxX64` — fall back to `ktor-client-curl` if not. Still open: it needs a declared dependency, so it belongs to the transport ticket.

**Two corrections to the target list itself** `[amended by *Scaffold the repo and its CI gate*]`. `macosX64` **cannot be declared**: Kotlin deprecated the x86_64 macOS target in 2.3.20 (its [target-tier table](https://kotlinlang.org/docs/native-target-support.html) lists it under Deprecated targets) and KGP 2.3.21 raises the DSL call to an error, so the pinned toolchain refuses it outright. It is dropped and `macosArm64` carries Apple desktop. And `iosX64` is **Tier 3**, not Tier 1 — not deprecated, so it stays, but compile-only, because no arm64 runner can execute it. The corrected split: Tier 1 `macosArm64`, `iosSimulatorArm64`, `iosArm64`; Tier 2 `linuxX64`; Tier 3 `iosX64`; plus JVM and Android.
