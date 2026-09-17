# Pin the KMP toolchain and target matrix

Researched: 2026-09-16 (all versions verified against primary sources on this date; source URLs per pin below)

## Summary

Pin **Kotlin 2.3.21** — the exact version Ktor 3.5.2 and 3.6.0 build against (confirmed in both release POMs), not the newer 2.4.20 — with `languageVersion`/`apiVersion` 2.1 so the widest set of consumers can compile against us. The coherent build set is Gradle 9.3.0 + AGP 9.0.0 via the `com.android.kotlin.multiplatform.library` plugin (mandatory for AGP 9; `androidTarget()` is being removed), Ktor 3.5.2, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, vanniktech 0.37.0, BCV 0.18.2, ktlint-gradle 14.2.0, Dokka 2.2.0. Android floor is `minSdk 28` / `compileSdk 36` (what Ktor's own artifacts are built with), JVM bytecode target 1.8 (the intersection with Ktor's Java-8 engines).

## Findings

### Q1 — Version matrix (known-coherent as of 2026-09-16)

1. **Claim:** The pinned set Kotlin 2.3.21 / Ktor 3.5.2 / kotlinx.serialization 1.11.0 / kotlinx.coroutines 1.11.0 / Gradle 9.3.0 / AGP 9.0.0 / vanniktech 0.37.0 is internally coherent and is (a subset of) the set Ktor itself builds and publishes with.
   **Sources:**
   - Ktor 3.5.2 tag `gradle/libs.versions.toml`: `kotlin = "2.3.21"`, `coroutines = "1.11.0"`, `serialization = "1.11.0"`, `mavenPublishing = "0.37.0"`, `dokka = "2.2.0"`, `android-gradlePlugin = "9.3.0"` — [source](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml)
   - `ktor-client-core-jvm:3.5.2` POM depends on `kotlin-stdlib:2.3.21`, `kotlinx-coroutines-core-jvm:1.11.0`, `slf4j-api:2.0.18` — [source](https://repo1.maven.org/maven2/io/ktor/ktor-client-core-jvm/3.5.2/ktor-client-core-jvm-3.5.2.pom)
   - `ktor-client-core-jvm:3.6.0` POM: same (`kotlin-stdlib:2.3.21`, coroutines 1.11.0, slf4j 2.0.19) — [source](https://repo1.maven.org/maven2/io/ktor/ktor-client-core-jvm/3.6.0/ktor-client-core-jvm-3.6.0.pom)
   **Support:** direct evidence. **Confidence:** high.
2. **Claim:** Latest versions as of check date (all read from Maven Central / Google Maven / Gradle metadata, not search summaries): Kotlin **2.4.20** (2026-09-07), Ktor **3.6.0** (metadata updated 2026-09-16 — released ~today; previous line 3.5.2), kotlinx.serialization stable **1.11.0** (1.12.0 only at RC), kotlinx.coroutines **1.11.0**, Gradle current **9.7.1**, AGP stable **9.4.0**, vanniktech **0.37.0** (2026-06-21), BCV **0.18.2** (2026-09-02), Dokka stable **2.2.0** (2.3.0-Beta exists — do not pin a Beta), ktlint-gradle (jlleitschuh) **14.2.0** (2026-03-12).
   **Sources:** [kotlin-gradle-plugin metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml), [ktor-client-core metadata](https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml), [kotlinx-serialization-json metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml), [kotlinx-coroutines-core metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml), [https://services.gradle.org/versions/current](https://services.gradle.org/versions/current), [AGP metadata (Google Maven)](https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml), [vanniktech metadata](https://repo1.maven.org/maven2/com/vanniktech/gradle-maven-publish-plugin/maven-metadata.xml), [BCV metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/binary-compatibility-validator/maven-metadata.xml), [Dokka metadata](https://repo1.maven.org/maven2/org/jetbrains/dokka/dokka-gradle-plugin/maven-metadata.xml), [ktlint plugin-portal metadata](https://plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint/org.jlleitschuh.gradle.ktlint.gradle.plugin/maven-metadata.xml)
   **Support:** direct evidence. **Confidence:** high.
3. **Claim:** Coherence constraints that bind the matrix: KGP 2.3.21 fully supports Gradle 7.6.3–**9.3.0** and AGP 8.2.2–**9.0.0** (Xcode ≤ 26.0); AGP 9.0 requires Gradle ≥ **9.1.0**; the AGP KMP library plugin requires AGP ≥ 8.10.0 / KGP ≥ 2.0.0; `org.jetbrains.kotlin.plugin.serialization` must be the same version as the Kotlin compiler.
   **Sources:** [KGP compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html), [multiplatform compatibility guide table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [AGP↔Gradle table](https://developer.android.com/build/releases/about-agp), [Android-KMP plugin prerequisites](https://developer.android.com/kotlin/multiplatform/plugin), [serialization README](https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.11.0/README.md)
   **Support:** direct evidence. **Confidence:** high.

### Q2 — Kotlin version choice and apiVersion/languageVersion policy

1. **Claim:** Pin the **compiler at 2.3.21** (Ktor's build version), not latest 2.4.20. A KMP library should track the version its dependency core is built with; the serialization plugin must match it exactly; and the published metadata language version is what gates consumers.
   **Sources:** Ktor 3.5.2 / 3.6.0 POMs (above); [KGP compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html).
   **Support:** direct evidence + researcher inference (that "match Ktor" maximizes coherence — the docs don't mandate it). **Confidence:** high for the facts, medium-high for the inference.
2. **Claim:** Set **`languageVersion = 2.1` and `apiVersion = 2.1`** at the `kotlin { compilerOptions {} }` extension level. Documented rule (Kotlin library-author guidelines): "The language version determines which Kotlin compiler versions can compile code that directly uses your library… On the JVM, consumers can use any compiler version from the previous language version onwards. For example, if your library uses language version 2.2, consumers can use compiler version 2.1.x… On other platforms, consumers can use a compiler version the same as the library's configured language version or any later version." So LV 2.1 → JVM consumers on Kotlin 2.0.x+, Native/Android consumers on 2.1+. The K2 compiler 2.3.21 accepts language versions 2.0–2.3 (2.4-era docs list 2.0–2.5-exp).
   **Sources:** [Backward compatibility guidelines for library authors](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html), [Gradle compiler options (values for apiVersion/languageVersion and jvmTarget)](https://kotlinlang.org/docs/gradle-compiler-options.html), [+1-minor metadata reading rule](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata.jvm/-jvm-metadata-version/-companion/-l-a-t-e-s-t_-s-t-a-b-l-e_-s-u-p-p-o-r-t-e-d.html)
   **Support:** direct evidence (quoted doc text); the choice of 2.1 specifically is researcher inference from those rules. **Confidence:** high.
3. **Claim (researcher inference):** The *effective* consumer floor is set by our dependencies, not by us: Ktor 3.5.x/3.6.x carries Kotlin 2.3.21 metadata, so consumers need a compiler that reads 2.3 metadata — JVM ≥ 2.2 via the documented +1-minor skew; for K/N klibs treat ≥ 2.2 (conservatively 2.3) as the practical floor. Going below LV 2.1 (e.g. 2.0) therefore buys no real reach; 2.1 is the sweet spot. **Fallback:** if we adopt 2.2+/2.3+ language features in the public API (e.g. context parameters), bump `languageVersion` to the lowest version containing them; never above Ktor's 2.3. **Confidence:** medium (skew rule documented for metadata readers; exact klib reading behavior on Native is not spelled out in the docs I verified).
4. **Claim:** Keep the stdlib current (2.3.21 rides in automatically with the plugin); `apiVersion 2.1` means consumers need a stdlib ≥ 2.1 at runtime.
   **Sources:** [library-author guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html). **Support:** direct evidence. **Confidence:** high.

### Q3 — Target declarations and intermediate source sets

1. **Claim:** With the default hierarchy template (on by default since 1.9.20), declaring the Apple targets creates **`appleMain`/`appleTest` automatically**: "If you have `iosArm64` and `macosArm64` targets, the `appleMain` and `appleTest` source sets are created." The template also creates `iosMain`, `macosMain`, `nativeMain` (all native incl. linuxX64), and `linuxMain`. **`linuxX64` needs no custom intermediate** — `nativeMain` already covers it (`linuxMain` exists but is trivial). **JVM + Android cannot share an intermediate source set** — explicitly unsupported: "Kotlin doesn't currently support sharing a source set for: … JVM + Android targets." Do not add manual `dependsOn` edges anywhere; one manual edge silently cancels the whole default template.
   **Sources:** [default hierarchy template](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html), [compatibility guide (appleMain quote, unsupported combos)](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
   **Support:** direct evidence. **Confidence:** high.
2. **Claim:** The Android target must be declared with AGP's `com.android.kotlin.multiplatform.library` plugin (`kotlin { android { … } }`), not `androidTarget()`: "When used along with Android Gradle plugin 9.0 or newer, the Kotlin Multiplatform Gradle plugin stops being compatible with the `com.android.application` and the `com.android.library` plugins", and KGP 2.3.0 reintroduced a deprecation warning on `androidTarget` (reverted only in 2.3.10 with AGP 8.x). AGP's own page says the legacy com.android.library path requires opt-in on AGP 9.0 and the APIs are expected to be removed in AGP 10.0 (H2 2026).
   **Sources:** [multiplatform compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [AGP-9 migration doc](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html), [developer.android.com KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin)
   **Support:** direct evidence. **Confidence:** high.
3. **Claim:** `jvm()` must be a plain target; there is no supported jvm+android sharing, and the Apple/Linux engine split falls out of the source sets: Darwin engine for `appleMain` (has macosx64 artifact per Ktor docs), OkHttp for Android+JVM, CIO for JVM and `linuxX64Main` (Ktor documents CIO as available on JVM, Android, and Native; Curl is the alternative on linuxX64).
   **Sources:** [Ktor client engines](https://ktor.io/docs/client-engines.html). **Support:** direct evidence for engine availability; source-set wiring is our design. **Confidence:** high.

### Q4 — JVM/Android build parameters (the intersection, not our preference)

1. **Claim:** **JVM bytecode target = 1.8.** Ktor's JVM/Android engines require "Java 8+" (OkHttp, CIO, Android engine; the `Java` engine needs 11 but we don't ship it); Kotlin's minimum `jvmTarget` is still `"1.8"` (values: 1.8…26). 1.8 is the intersection and the widest-reach choice for a Maven Central library.
   **Sources:** [Ktor engine table](https://ktor.io/docs/client-engines.html), [compiler options jvmTarget values](https://kotlinlang.org/docs/gradle-compiler-options.html)
   **Support:** direct evidence. **Confidence:** high.
2. **Claim:** **Android `compileSdk = 36`, `minSdk = 28`.** Ktor builds *all* its Android artifacts with `android-compileSdk = "36"`, `android-minSdk = "28"` (tag 3.5.2 and main), and a library consuming those AARs must not declare a lower floor. Their docs' engine table is looser (OkHttp engine: Android 5.0+; CIO: Android 7.0+ without Java-8 API desugaring, `Android` engine: 1.x+), and KTOR-9607 ("Crash on Android 7: NoSuchMethodError getInstanceStrong() since 3.5.0") corroborates that Ktor ≥ 3.5 no longer runs on old API levels. AGP 9.0 supports up to API 36.1, so compileSdk 36 is fine on the pinned AGP.
   **Sources:** [Ktor 3.5.2 toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml), [engine table](https://ktor.io/docs/client-engines.html), [KTOR-9607 via changelog](https://github.com/ktorio/ktor/blob/main/CHANGELOG.md), [AGP release notes](https://developer.android.google.cn/build/releases/agp-9-0-0-release-notes)
   **Support:** direct evidence for Ktor's build values; the "therefore our minSdk must be 28" step is interpretation (see Missing evidence — I could not unzip the published AAR to read its embedded `minSdkVersion`/`minCompileSdk`). **Confidence:** medium-high.
3. **Claim:** Gradle 9.3.0 + AGP 9.0.0 is the newest corner fully inside KGP 2.3.21's supported matrix (Gradle ≤ 9.3.0, AGP ≤ 9.0.0) while satisfying AGP 9.0's Gradle ≥ 9.1.0 requirement and the Android-KMP plugin's AGP ≥ 8.10.0. Ktor itself runs AGP 9.3.0 (Gradle ≥ 9.5.0) with Kotlin 2.3.21 — *beyond* the officially supported matrix; we deliberately don't.
   **Sources:** [KGP table](https://kotlinlang.org/docs/gradle-configure-project.html), [AGP↔Gradle table](https://developer.android.com/build/releases/about-agp), [Ktor 3.5.2 toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml)
   **Support:** direct evidence + inference for "safest corner". **Confidence:** high.
4. **Claim:** Kotlin toolchain: build (Gradle daemon) on JDK 17+ (Gradle 9 requires JVM 17+); publish Java 8 bytecode via `jvmTarget = JVM_1_8` on the `jvm` and `android` targets. Beware the documented trap: the published Gradle module metadata `org.gradle.jvm.version` follows the Java `targetCompatibility`/toolchain, not `jvmTarget` — if it lands on 17, set `jvmToolchain(8)` (auto-provisioned) so the artifact declares Java 8.
   **Sources:** [Gradle 9 upgrade notes](https://docs.gradle.org/9.0.0/release-notes.html), [KGP JVM target compatibility doc](https://kotlinlang.org/docs/gradle-configure-project.html)
   **Support:** direct evidence for the trap and requirements. **Confidence:** high (verify the `.module` attribute in CI).
5. **Claim:** iOS deployment target 13.0 (Ktor's own value — don't go below what the Darwin engine is built for).
   **Source:** [Ktor 3.5.2 toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml). **Support:** direct evidence (Ktor's build value; consumer-side floor is interpretation). **Confidence:** medium-high.

### Q5 — Runtime identification (`X-TypeSafe-Runtime`)

1. **Claim:** Cheap, permission-free, bundle-free identifiers exist on every target we ship:
   - **JVM:** `System.getProperty("java.version")` (+ `java.vendor`, `os.name`). **Trap:** format varies across versions (`1.8.0_392` vs `17.0.2`); on Android this property is *not* the API level.
   - **Android:** `Build.VERSION.SDK_INT` (authoritative int) + `Build.VERSION.RELEASE`, `Build.MANUFACTURER`/`MODEL`. Static final fields on `android.os.Build`; no permission, no binder call. **Trap:** `RELEASE` is a spoofable/freeform string — key off `SDK_INT`.
   - **iOS + macOS (one `appleMain` actual):** `NSProcessInfo.processInfo.operatingSystemVersion` (structured major/minor/patch) from Foundation — works in both macOS and iOS processes; no permission. **Traps:** `operatingSystemVersionString` is human-formatted ("Version 26.0 (Build …)") — don't parse it; device marketing name ("iPhone 17 Pro") needs bundle/MobileGestalt territory — ship the `utsname.machine` identifier instead; app version needs `NSBundle.mainBundle`, which is nil in CLI tools and Kotlin tests; anything user-permissioned (IDFA/ATT, location) is off-limits for a header value. On Apple Silicon macOS an iOS-app-on-Mac process can be disambiguated with `processInfo.isiOSAppOnMac`/`isMacCatalystApp`.
   - **Linux (`linuxX64`):** POSIX `uname()` via `platform.posix` (`sysname`, `release` = kernel version, `machine` = arch), optionally `/etc/os-release` `PRETTY_NAME` (plain file read). **Traps:** don't shell out to the `uname` binary; glibc-vs-musl isn't cheaply detectable (matters only if we ever ship native binaries).
   **Sources:** [Apple NSProcessInfo](https://developer.apple.com/documentation/foundation/nsprocessinfo) (`operatingSystemVersion`, `isiOSAppOnMac`), [Android Build](https://developer.android.com/reference/android/os/Build) & [Build.VERSION](https://developer.android.com/reference/android/os/Build.VERSION), [uname(2)](https://man7.org/linux/man-pages/man2/uname.2.html), Ktor engine docs for platform shapes.
   **Support:** documented platform APIs (direct); "no runtime permission required" for each is researcher assessment — reading OS version is not a permissioned operation on any of these platforms. **Confidence:** high for APIs existing and being permission-free; medium for edge behaviors (e.g., Android `java.version` value) — verify in the SDK's own platform tests.
2. **Claim:** Nothing in the SDK needs a *permissioned* runtime identifier; the header should be a static string computed once per process (e.g. `jev-kmp/1.0 (kotlin/2.3.21; ios/18.2)`) — all sources above are cheap constant reads or single syscalls.

### Q6 — First-party Gradle plugins

1. **Claim:** **binary-compatibility-validator `org.jetbrains.kotlinx.binary-compatibility-validator`: 0.18.2** (2026-09-02; KLIB cross-compilation support since 0.18.0 which requires Kotlin 2.1+; 0.18.2 fixes a config crash on hosts unknown to Kotlin/Native). Fallback/alternative: KGP has built-in binary-compatibility validation since **KGP 2.2.0**, so on KGP 2.3.21 we can use the built-in and skip the standalone plugin if 0.18.x ever conflicts.
   **Sources:** [BCV metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/binary-compatibility-validator/maven-metadata.xml), [BCV releases](https://github.com/Kotlin/binary-compatibility-validator/releases), [library-author guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
   **Support:** direct evidence. **Confidence:** high.
2. **Claim:** **ktlint: `org.jlleitschuh.gradle.ktlint` 14.2.0** (latest, 2026-03-12, Gradle Plugin Portal). Fallback: `org.jmailen.gradle:kotlinter-gradle` **5.6.0/5.7.0** — the wrapper Ktor itself uses against Kotlin 2.3.21, so it is the proven-coherent alternative if jlleitschuh 14.x misbehaves with KGP 2.3.21.
   **Sources:** [ktlint plugin metadata](https://plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint/org.jlleitschuh.gradle.ktlint.gradle.plugin/maven-metadata.xml), [Ktor 3.5.2 toml (`kotlinter = "5.6.0"`)](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml)
   **Support:** direct evidence for versions; coherence claim for kotlinter is inference from Ktor's usage. **Confidence:** high (versions), medium (ktlint↔KGP edge cases untested here).
3. **Claim:** **Dokka: 2.2.0** (latest stable; 2.3.0-Beta published 2026-09-15 — do not pin a Beta). Dokka 2.2.0 is exactly what Ktor 3.5.2/3.6.0 run against Kotlin 2.3.21, which is the strongest available coherence evidence.
   **Sources:** [Dokka metadata](https://repo1.maven.org/maven2/org/jetbrains/dokka/dokka-gradle-plugin/maven-metadata.xml), [Ktor toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml)
   **Support:** direct evidence. **Confidence:** high.
4. **Claim:** **vanniktech `com.vanniktech.maven.publish` 0.37.0** (2026-06-21): supports `org.jetbrains.kotlin.multiplatform` and `com.android.kotlin.multiplatform.library` outputs (both in its supported-plugin list), and is the exact version Ktor publishes with. Fallback: 0.36.0 (previous stable) if 0.37.0 shows a publishing regression with AGP 9.0.
   **Sources:** [vanniktech metadata](https://repo1.maven.org/maven2/com/vanniktech/gradle-maven-publish-plugin/maven-metadata.xml), [README supported plugins](https://raw.githubusercontent.com/vanniktech/gradle-maven-publish-plugin/main/README.md), [Ktor toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml)
   **Support:** direct evidence. **Confidence:** high.

## Deliverable A — pinned `gradle/libs.versions.toml`

```toml
[versions]
# -- language & runtime (Ktor 3.5.2/3.6.0 build exactly against these; verified in release POMs) --
kotlin = "2.3.21"                    # = what Ktor builds against; 2.4.20 exists but is a minor ahead
coroutines = "1.11.0"                # shipped by Ktor 3.5.x/3.6.0
serialization = "1.11.0"             # latest STABLE (1.12.0 only RC); built with Kotlin 2.3.20
ktor = "3.5.2"                       # mature patch line; 3.6.0 is day-old -> fallback, not pin
slf4j = "2.0.18"                     # only if we log via slf4j directly; Ktor pins 2.0.18/2.0.19

# -- build toolchain (all inside KGP 2.3.21's fully-supported matrix: Gradle 7.6.3–9.3.0, AGP 8.2.2–9.0.0) --
gradle = "9.3.0"                     # KGP 2.3.21 max; >= AGP 9.0's Gradle 9.1.0 minimum
agp = "9.0.0"                        # requires com.android.kotlin.multiplatform.library (>=8.10 ok)
vanniktechPublish = "0.37.0"         # supports kotlin-multiplatform + android-kmp-library; Ktor's version

# -- quality --
binaryCompatibilityValidator = "0.18.2"  # KLIB cross-compilation needs Kotlin 2.1+ (we're on 2.3.21)
ktlint = "14.2.0"                    # jlleitschuh wrapper; fallback: kotlinter 5.6.0 (Ktor's choice)
dokka = "2.2.0"                      # latest stable; 2.3.0-Beta must NOT be pinned

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }  # MUST match compiler
android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechPublish" }
binary-compatibility-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "binaryCompatibilityValidator" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
dokka = { id = "org.jetbrains.dokka", version.ref = "dokka" }
```

`gradle/wrapper/gradle-wrapper.properties`: `distributionUrl=gradle-9.3.0-bin.zip`. Per-pin justification + fallback:

| Pin | Why | Safe fallback |
|---|---|---|
| Kotlin 2.3.21 | Ktor 3.5.2 & 3.6.0 both ship stdlib 2.3.21 (POM); serialization plugin must match | 2.2.21 (only if an AGP ≤ 8.x legacy path is needed) |
| Ktor 3.5.2 | Latest mature patch line; 3.6.0 is hours old | 3.4.3 (previous line) |
| serialization 1.11.0 | Latest stable; Ktor's own pin; 1.12.0 is RC | 1.9.0 |
| coroutines 1.11.0 | What Ktor 3.5.x/3.6.0 itself depends on | 1.10.2 |
| Gradle 9.3.0 | KGP 2.3.21 max fully-supported; ≥ AGP 9.0's 9.1.0 floor | 9.1.0 |
| AGP 9.0.0 | KGP 2.3.21 max fully-supported AGP; compiles SDK 36 | AGP 8.13.2 + Kotlin 2.3.10 (`androidTarget()` path) |
| vanniktech 0.37.0 | Supports our exact plugin pair; Ktor's version | 0.36.0 |
| BCV 0.18.2 | Latest; Kotlin 2.1+ features only used optionally | KGP built-in API validation (KGP ≥ 2.2.0) |
| ktlint 14.2.0 | Latest wrapper | kotlinter 5.6.0 |
| Dokka 2.2.0 | Latest stable, Ktor-verified against Kotlin 2.3.21 | none needed (skip 2.3.0-Beta) |

## Deliverable B — `kotlin { }` target/source-set block (real)

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)   // com.android.kotlin.multiplatform.library — REQUIRED for AGP 9
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // -- Android: AGP's KMP plugin (androidTarget() is deprecated in KGP 2.3 and incompatible with AGP 9) --
    android {
        namespace = "com.sierranevadalabs.jev"
        compileSdk = 36                        // = what Ktor's Android artifacts are built with
        minSdk = 28                            // = Ktor's own floor; see fallback note below
        compilerOptions { jvmTarget = JvmTarget.JVM_1_8 }
        // no withJava(): pure-Kotlin SDK; Java compilation stays off by default
    }

    jvm() {
        compilerOptions { jvmTarget = JvmTarget.JVM_1_8 }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()

    // Default hierarchy template (automatic) creates, for our target list:
    //   appleMain / appleTest  (ios* + macos*)
    //   iosMain, macosMain     (per-platform intermediates)
    //   nativeMain / nativeTest (all of apple* + linuxX64)
    //   linuxMain              (trivial — only linuxX64 under it; no custom intermediate needed)
    // DO NOT add manual dependsOn() edges anywhere: one manual edge silently disables the
    // whole default template. JVM+Android intermediate source sets are not supported by KMP.

    compilerOptions {
        // Library consumer-reach policy: JVM consumers on Kotlin 2.0.x+, other platforms on 2.1+
        // (see Q2). Keep <= the language version Ktor 3.x is built with (2.3).
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)             // HttpClient type is part of our public API
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        appleMain.dependencies {
            api(libs.ktor.client.darwin)           // NSURLSession engine; covers iOS + macOS incl. macosx64
        }
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)           // primary Android engine
            api(libs.ktor.client.android)          // HttpURLConnection fallback engine
        }
        jvmMain.dependencies {
            api(libs.ktor.client.okhttp)           // primary desktop/server engine
            api(libs.ktor.client.cio)              // pure-Kotlin fallback (Java 8+)
        }
        linuxX64Main.dependencies {
            api(libs.ktor.client.cio)              // CIO ships native targets; Curl is the alternative
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

Notes that belong next to this block:
- **Android floor fallback:** if `minSdk 28` is unacceptable for the product, the documented fallback is `minSdk 24` (CIO's floor without Java-8 API desugaring; OkHttp's floor is 21) — but only after CI unzips `ktor-client-core-*` / `ktor-client-okhttp-*` AARs and confirms their embedded `minSdkVersion`/`minCompileSdk` ≤ the chosen value (see Missing evidence: I could not read the binary AARs here). `compileSdk 36` is not negotiable while depending on Ktor 3.5.x/3.6.x.
- **Xcode on CI:** KGP 2.3.21 supports Xcode up to 26.0 (per the KMP compatibility table).
- **JVM metadata check:** after first publish, assert the `.module` file says `org.gradle.jvm.version=8`; if it says 17, add `jvmToolchain(8)` (toolchain ≠ daemon JDK; Gradle 9 daemon needs 17+, compilation can target 8).

## Contradictions

- **Ktor's docs vs Ktor's build config on the Android floor:** the engine table in the docs says the OkHttp engine works on Android 5.0+ and the Android engine on 1.x+, but Ktor 3.5.2/3.6.0 build all Android artifacts with `android-minSdk = 28`. One of the two is stale; the build config is the binding one for consumers if AAR metadata enforces it. Recorded, not silently resolved — verify via the AAR check above. ([engine table](https://ktor.io/docs/client-engines.html) vs [3.5.2 toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml); corroborating: KTOR-9607, a crash on Android 7 caused by a ≥API-26 method call entering in 3.5.0.)
- **Ktor's own toolchain sits outside the officially supported matrix:** Ktor 3.5.2 pairs Kotlin 2.3.21 with AGP 9.3.0 (KGP 2.3.21's documented AGP max is 9.0.0, and AGP 9.3.0 needs Gradle ≥ 9.5.0 vs KGP's 9.3.0 max). It evidently works for them; our pin (AGP 9.0.0 / Gradle 9.3.0) deliberately stays inside the documented matrix. ([KGP table](https://kotlinlang.org/docs/gradle-configure-project.html) vs [Ktor toml](https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml))
- **Latest Kotlin vs ecosystem:** Kotlin 2.4.20 is stable, but Ktor 3.5.x/3.6.0, serialization 1.11.0 (2.3.20) and coroutines 1.11.0 (2.2.20) all build with ≤ 2.3.21 — this is the ticket's premise, confirmed by primary artifacts.

## Missing evidence

- **Embedded AAR metadata of Ktor's published Android artifacts** (`minSdkVersion`, `minCompileSdk` inside `ktor-client-core`/`ktor-client-okhttp`/`ktor-client-android` 3.5.2 `.aar`): binary zips I couldn't unpack with the tools available here. The `android-compileSdk = 36` / `android-minSdk = 28` values in Ktor's tag toml are the build inputs; one CI command (`unzip -p ktor-client-core-3.5.2.aar META-INF/com/android/build/gradle/aar-metadata.properties`) settles it.
- **Exact klib metadata-reading skew on Native targets** (whether consumers on Kotlin 2.2 can consume libraries carrying 2.3 klib metadata): the +1-minor rule is documented for metadata readers generally, and the library-author doc states the non-JVM consumer rule conservatively ("same as the library's language version or later"). The practical floor for iOS consumers of Ktor 3.5.x should be confirmed with one smoke test on Kotlin 2.2.
- **`org.gradle.jvm.version` behavior with KGP 2.3.21 + JDK 17 daemon + `jvmTarget 1.8`** (whether the attribute now follows `jvmTarget` or the toolchain): docs describe the toolchain behavior; verify on first publish.
- **vanniktech 0.37.0's declared minimum Gradle/AGP** (README lists none): coherence evidenced by Ktor's usage with AGP 9.3.0, not by a documented floor.
- **ktor-client-cio linuxX64 artifact presence in 3.5.2**: documented ("CIO … available on JVM, Android, Native"), and klibs.io listings agree, but the Gradle resolution should confirm it on first build (fall back to `ktor-client-curl` on linuxX64 if not).

## Sources

- Kept:
  - Ktor 3.5.2 `gradle/libs.versions.toml` (https://raw.githubusercontent.com/ktorio/ktor/3.5.2/gradle/libs.versions.toml) — the single most authoritative coherence anchor: exact Kotlin/AGP/coroutines/serialization/Dokka/vanniktech/minSdk/compileSdk/iOS-target Ktor ships with
  - Ktor main `gradle/libs.versions.toml` (https://raw.githubusercontent.com/ktorio/ktor/main/gradle/libs.versions.toml) — current dev toolchain (Kotlin 2.3.21, AGP 9.4.0)
  - `ktor-client-core-jvm:3.5.2` and `:3.6.0` POMs (https://repo1.maven.org/maven2/io/ktor/ktor-client-core-jvm/3.5.2/ktor-client-core-jvm-3.5.2.pom, …/3.6.0/…) — proof of the exact stdlib/coroutines/slf4j versions in published artifacts
  - Maven Central / Google Maven / Plugin Portal / Gradle metadata for kotlin-gradle-plugin, ktor-client-core, kotlinx-serialization-json, kotlinx-coroutines-core, AGP, vanniktech, BCV, Dokka, ktlint, Gradle current (URLs in Q1) — version ground truth, checked 2026-09-16
  - Kotlin docs: [KGP compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html), [compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html), [multiplatform compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [hierarchy template](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html), [library-author backward-compatibility guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html), [kotlinx-metadata version rule](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata.jvm/-jvm-metadata-version/-companion/-l-a-t-e-s-t_-s-t-a-b-l-e_-s-u-p-p-o-r-t-e-d.html)
  - Android docs: [KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin), [AGP↔Gradle table](https://developer.android.com/build/releases/about-agp), [AGP 9.0 release notes](https://developer.android.google.cn/build/releases/agp-9-0-0-release-notes)
  - [Ktor client engines](https://ktor.io/docs/client-engines.html) — per-engine Java/Android floors and platform availability
  - kotlinx.serialization v1.11.0 README and kotlinx.coroutines 1.11.0 release notes — built-with Kotlin versions (2.3.20 / 2.2.20)
  - BCV releases page — 0.18.x Kotlin-version notes
- Rejected/deprioritized:
  - Stack Overflow "module was compiled with an incompatible version of Kotlin" thread — background only; the metadata rule is cited from official docs instead
  - klibs.io, newreleases.io, code-examples.net, supabase-kt issue — aggregators/mirrors/secondary anecdotes; used only to spot the KTOR-9607 changelog entry, which is cited from the Ktor changelog
  - Search-engine "current version" claims generally — contradicted or unverifiable; every pin was re-derived from repo metadata

## Next steps

1. In CI, unzip `ktor-client-core`/`ktor-client-okhttp`/`ktor-client-android` 3.5.2 AARs and read `aar-metadata.properties` to settle the `minSdk 28` vs docs-table contradiction (decides whether minSdk 24/21 fallback is even possible).
2. Smoke-test a tiny consumer app on Kotlin 2.2 consuming our skeleton (Ktor 3.5.2 + LV 2.1) on JVM and an iOS simulator build — validates the metadata-skew assumptions in Q2.
3. First `apiDump` + publish dry-run: assert `.module` `org.gradle.jvm.version=8` and that `linuxX64Main` resolves `ktor-client-cio` (else wire `ktor-client-curl`).
4. When Ktor 3.6.x accumulates a patch release (3.6.1+) or serialization 1.12.0 goes stable, re-run this matrix; 3.6.0's pins (same Kotlin 2.3.21) make that a drop-in bump.
