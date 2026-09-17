import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
}

// `conformance/` is the cross-language fixture set, and a KMP `commonTest` cannot read files on Native. The
// JSON is baked into a generated Kotlin constant so every target's tests read the same bytes. See
// docs/adr/0005-conformance-fixture-format.md.
val generateConformanceFixtures by tasks.registering {
    val source = layout.projectDirectory.dir("conformance")
    val output = layout.buildDirectory.dir("generated/conformance/kotlin")
    inputs.dir(source).withPropertyName("fixtures")
    outputs.dir(output).withPropertyName("sources")

    doLast {
        // Each fixture is a raw string, so its own line lengths are the generated file's line lengths and the
        // result satisfies ktlint without an exclusion. `prependIndent` sets up the `trimIndent` that follows.
        val entries =
            source.asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.invariantSeparatorsPath }
                .joinToString(",\n") { file ->
                    val path = file.relativeTo(source.asFile).invariantSeparatorsPath
                    val body = file.readText().trimEnd('\n')
                    require(!body.contains("\"\"\"")) { "$path contains a triple quote" }
                    val indented = body.replace("$", "\${'$'}").prependIndent("            ")
                    "        \"$path\" to\n            \"\"\"\n$indented\n            \"\"\".trimIndent()"
                }

        val target = output.get().file("ConformanceFixtures.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            |// Generated from conformance/ by the generateConformanceFixtures task — do not edit.
            |package com.sierranevadalabs.jev.sdk.conformance
            |
            |internal val FIXTURE_FILES: Map<String, String> =
            |    mapOf(
            |$entries,
            |    )
            |
            """.trimMargin(),
        )
    }
}

// The README's fenced ```kotlin blocks are compiled, so a snippet that no longer matches the public API breaks
// the build instead of the reader. This is what Python's doc tests do with Sybil and what the JS repo lacks.
// Each block must therefore be self-contained and must carry the imports a reader would copy. Kotlin allows
// `import` only at file level, so the imports are hoisted to the top of the generated file — which also makes a
// stale package or class name in a README import line a compile error.
val generateReadmeSnippets by tasks.registering {
    val readme = layout.projectDirectory.file("README.md")
    val output = layout.buildDirectory.dir("generated/readme/kotlin")
    inputs.file(readme).withPropertyName("readme")
    outputs.dir(output).withPropertyName("sources")

    doLast {
        val blocks =
            Regex("```kotlin\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
                .findAll(readme.asFile.readText())
                .map { it.groupValues[1].trimIndent().trimEnd() }
                .toList()
        // Without this, a README with no blocks would compile an empty file and the doc test would be vacuous.
        check(blocks.isNotEmpty()) { "README.md has no ```kotlin blocks to compile" }

        val imports =
            blocks
                .flatMap { block -> block.lineSequence().filter { it.startsWith("import ") } }
                .distinct()
                .sorted()
                .joinToString("\n")
        val snippets =
            blocks.mapIndexed { index, block ->
                val body =
                    block
                        .lineSequence()
                        .filterNot { it.startsWith("import ") }
                        .toList()
                        .dropWhile { it.isBlank() }
                        .dropLastWhile { it.isBlank() }
                        .joinToString("\n") { line -> if (line.isBlank()) "" else "    " + line.trimEnd() }
                "internal suspend fun readmeSnippet${index + 1}() {\n$body\n}"
            }

        val target = output.get().file("ReadmeSnippets.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            |// Generated from README.md's ```kotlin blocks by generateReadmeSnippets — do not edit.
            |// Compiling this file is the doc test: a snippet that no longer matches the public API fails the build.
            |package com.sierranevadalabs.jev.sdk.docs
            |
            |$imports
            |
            |${snippets.joinToString("\n\n")}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    // AGP 9 requires com.android.kotlin.multiplatform.library; androidTarget() is going away. Host (unit)
    // tests are off by default in this plugin and must be opted into, or commonTest never runs on Android.
    android {
        namespace = "com.sierranevadalabs.jev.sdk"
        compileSdk = 36 // what Ktor's own Android artifacts are built with
        minSdk = 28 // Ktor's floor as of 3.5.x; see the AAR-metadata check in CI
        withHostTestBuilder {}.configure {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    linuxX64()

    // macosX64 is deliberately absent. Kotlin deprecated the x86_64 macOS target in 2.3.20 and KGP 2.3.21
    // raises the declaration to an error, so it cannot be compiled against our pinned toolchain. Apple desktop
    // coverage is macosArm64. iosX64 is Tier 3 but not deprecated, so it stays as a compile-only simulator
    // target. See the toolchain ticket for the corrected tier list.

    // The default hierarchy template builds appleMain/appleTest, iosMain, macosMain, nativeMain and linuxMain
    // from the target list above. Do not add manual dependsOn() edges — one of them silently disables the
    // whole template. JVM and Android cannot share an intermediate source set.

    compilerOptions {
        // Consumer reach: JVM consumers on Kotlin 2.0.x+, other platforms on 2.1+. Deliberately below the
        // compiler's own 2.3 so we never gate consumers on a language version we do not need.
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
        explicitApi = ExplicitApiMode.Strict
    }

    sourceSets {
        commonMain.dependencies {
            // `JsonElement` is part of the public surface (questions, answers, state), so consumers need it on
            // their compile classpath rather than only at runtime.
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest {
            kotlin.srcDir(generateConformanceFixtures)
            kotlin.srcDir(generateReadmeSnippets)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.mock)
            }
        }
        // The default engines, so a caller configures nothing. Ktor publishes all four as plain JVM JARs for
        // Android as well as the JVM, which is why androidMain can reuse the OkHttp artifact.
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmTest.dependencies {
            // Tier 2 binds CIO to a loopback socket, and CIO is the engine the ticket names for it.
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        appleMain.dependencies { implementation(libs.ktor.client.darwin) }
        linuxMain.dependencies { implementation(libs.ktor.client.cio) }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "jev-kmp"
        description = "Kotlin Multiplatform SDK for the TypeSafe / Jev System One API"
        inceptionYear = "2026"
        url = "https://github.com/snevadalabs/jev-kmp"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "snevadalabs"
                name = "Sierra Nevada Labs"
                url = "https://github.com/snevadalabs"
            }
        }
        scm {
            url = "https://github.com/snevadalabs/jev-kmp"
            connection = "scm:git:git://github.com/snevadalabs/jev-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/snevadalabs/jev-kmp.git"
        }
    }
}

// `explicitApi(ExplicitApiMode.Strict)` does **not** require KDoc: an undocumented public declaration compiles
// clean, which the typed-question prototype measured by deleting a doc comment. Dokka is the gate that does
// catch it. `reportUndocumented` logs `Undocumented: <signature>` once per declaration Dokka would publish
// undocumented, and the publication's `failOnWarning` turns any Dokka warning into a build failure. It reuses
// the Dokka run the Apple CI lane already performs, so it needs no new dependency and no new task, and the
// warning list is the work list. Do not turn either flag off to make a build pass.
dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
    }
    dokkaPublications.configureEach {
        failOnWarning.set(true)
    }
}

// The version and the changelog are two sources of truth that must not drift. SNAPSHOT versions require an
// Unreleased heading; release versions must name themselves at the top.
val checkVersion by tasks.registering {
    group = "verification"
    description = "Asserts the gradle.properties version matches the top CHANGELOG.md heading."

    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    val sources = layout.projectDirectory.dir("src")
    val declaredVersion = version.toString()
    inputs.file(changelog)
    inputs.dir(sources)
    inputs.property("version", declaredVersion)

    doLast {
        val headings = changelog.asFile.readLines().filter { it.startsWith("## ") }
        check(headings.isNotEmpty()) { "CHANGELOG.md has no '## ' headings" }
        val top = headings.first().removePrefix("## ").trim()

        if (declaredVersion.endsWith("-SNAPSHOT")) {
            check(top.contains("Unreleased", ignoreCase = true)) {
                "version $declaredVersion is a snapshot but CHANGELOG.md's top heading is '$top'"
            }
        } else {
            check(top.contains(declaredVersion)) {
                "version $declaredVersion does not match CHANGELOG.md's top heading '$top'"
            }
        }

        // The wire header `X-TypeSafe-SDK` carries a version, and a Kotlin constant cannot read
        // gradle.properties. Assert the two agree rather than letting them drift.
        val expected = declaredVersion.removeSuffix("-SNAPSHOT")
        val declaredInSource =
            sources.asFile
                .walkTopDown()
                .filter { it.extension == "kt" }
                .mapNotNull { file ->
                    // `explicitApi(Strict)` forces an explicit return type on the public constant, so the type
                    // annotation is optional here rather than absent.
                    Regex("SDK_VERSION\\s*(?::\\s*\\w+)?\\s*=\\s*\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1)
                }.toList()
        check(declaredInSource == listOf(expected)) {
            "SDK_VERSION in src/ is $declaredInSource, expected [$expected] from gradle.properties"
        }
    }
}

// Java 8 bytecode is the level the pinned toolchain promises consumers, and `jvmTarget` alone does not prove it.
// The class file's major version does: 52 is Java 8.
val checkJvmBytecode by tasks.registering {
    group = "verification"
    description = "Asserts the compiled JVM classes are Java 8 (class file major version 52)."

    val classesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    dependsOn("compileKotlinJvm")
    inputs.dir(classesDir)

    doLast {
        val classes =
            classesDir
                .get()
                .asFile
                .walkTopDown()
                .filter { it.extension == "class" }
                .toList()
        check(classes.isNotEmpty()) { "no compiled JVM classes under $classesDir" }
        for (classFile in classes) {
            val bytes = classFile.readBytes()
            val major = (bytes[6].toInt() and 0xFF shl 8) or (bytes[7].toInt() and 0xFF)
            check(major == 52) { "$classFile is class file major version $major, expected 52 (Java 8)" }
        }
    }
}

tasks.named("check") {
    // The coverage floor and the KDoc gate are part of the check, not reports someone remembers to open.
    // `dokkaGenerate` is host-neutral here: it compiles Kotlin metadata only, never an Apple klib.
    dependsOn(checkVersion, checkJvmBytecode, "koverVerify", "dokkaGenerate")
}

// The live tier is opt-in through this property; a default `check` can never reach the network or spend money.
// The API key is checked inside the tests, so `-Ptypesafe.live=true` with no key fails rather than skips.
tasks.named<Test>("jvmTest") {
    systemProperty("typesafe.live", providers.gradleProperty("typesafe.live").getOrElse("false"))
}

// Coverage over the SDK source, measured by the JVM tests: Kover supports JVM and Android only, so this is the
// JVM compilation of commonMain + jvmMain (Apple and Linux source sets are not measurable). The code measures
// 94.6% line coverage; the floor is set at what it actually achieves. It is a gate, not a target — do not lower
// it to make a build pass.
kover {
    reports {
        verify {
            rule {
                minBound(94)
            }
        }
    }
}
