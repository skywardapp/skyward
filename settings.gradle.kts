pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-provision a JDK 17 toolchain (§15.2: "Pin the JDK via
    // Gradle toolchains (Java 17)") on machines that don't already have one —
    // including, notably, the F-Droid build server (§15.4).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "skyward"

include(":core")
include(":androidApp")
include(":desktopApp")
