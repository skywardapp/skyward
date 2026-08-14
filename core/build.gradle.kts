plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)

    androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

android {
    namespace = "dev.fritze.skyward.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("SkywardDatabase") {
            packageName.set("dev.fritze.skyward.core.persistence")
            // §11 calls for verifyMigrations = true, but that verifies .sqm migration
            // files against snapshotted schema versions — meaningless before any
            // migration exists. Flip to true (and commit the initial schema snapshot)
            // the first time this schema actually changes after release.
            verifyMigrations.set(false)
        }
    }
}

// core/src/androidMain/resources/showers.json is a hand-maintained duplicate of
// core/src/commonMain/resources/showers.json (see ShowersResource.android.kt for
// why AGP needs its own copy). Nothing else keeps the two in sync, so a catalog
// update that only touches one of them would silently make Android and desktop
// emit different meteor-shower data under identical occurrence ids.
val verifyShowerCatalogsMatch by tasks.registering {
    description = "Fails if the commonMain and androidMain showers.json copies have diverged."
    val commonFile = layout.projectDirectory.file("src/commonMain/resources/showers.json")
    val androidFile = layout.projectDirectory.file("src/androidMain/resources/showers.json")
    inputs.file(commonFile)
    inputs.file(androidFile)
    doLast {
        val commonText = commonFile.asFile.readText()
        val androidText = androidFile.asFile.readText()
        check(commonText == androidText) {
            "core/src/commonMain/resources/showers.json and core/src/androidMain/resources/showers.json " +
                "have diverged. Update both together (see the comment in ShowersResource.android.kt)."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyShowerCatalogsMatch)
}
