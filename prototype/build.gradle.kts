// Throwaway probe for ticket 06 (Prototype the typed question API). JVM-only and never published: its
// only job is to make the ticket's seven open questions checkable by compiling and running them.
//
// ponytail: standalone module, no ktlint/Dokka/publishing/BCV wiring; delete it when tickets 11 and 12
// land the real question/answer model and client. The language level is pinned to 2.1 to match the
// consumer-facing pin in the root build, so the verdict cannot rely on 2.2/2.3-only resolution rules.
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
        // Question 7 asks whether the chosen shape documents cleanly under the real module's settings.
        explicitApi = ExplicitApiMode.Strict
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
