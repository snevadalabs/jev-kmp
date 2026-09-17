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
    alias(libs.plugins.maven.publish)
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // The default engines, so a caller configures nothing. Ktor publishes all four as plain JVM JARs for
        // Android as well as the JVM, which is why androidMain can reuse the OkHttp artifact.
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
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
                    Regex("SDK_VERSION\\s*=\\s*\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1)
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
    dependsOn(checkVersion, checkJvmBytecode)
}
