pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jev-kmp"

// Ticket 06's throwaway probe. Never published; see prototype/build.gradle.kts.
include(":prototype")
