import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.native.tray)
    implementation(libs.two.slices)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dev.fritze.skyward.desktop.MainKt"

        nativeDistributions {
            // §15.5: jpackage targets. No AppImage support in the Compose plugin —
            // Flatpak (primary) repackages the jlinked createReleaseDistributable
            // tree separately; see flatpak/.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "skyward"
            packageVersion = "0.1.0"
            description = "Location-based reminders for natural & sky events"
            vendor = "Skyward contributors"

            linux {
                packageName = "skyward"
                menuGroup = "Science;Education;"
            }
        }
    }
}
