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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    // :core exposes these as `implementation`, so they're on the runtime
    // classpath but not the compile one — and DesktopContainer drives the
    // schema create/migrate by hand (§11), which needs both.
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.compose.native.tray)
    implementation(libs.two.slices)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// §16: "the About screen renders them". Packaging the repository's own NOTICE
// as a resource (rather than retyping it into Kotlin) is what keeps the two
// from drifting — there is exactly one copy of the text.
val bundleNotice by tasks.registering(Copy::class) {
    description = "Packages the repository NOTICE file as a desktop app resource for the About screen."
    from(rootProject.layout.projectDirectory.file("NOTICE"))
    into(layout.buildDirectory.dir("generated/notice"))
}

sourceSets.named("main") {
    resources.srcDir(bundleNotice.map { it.destinationDir })
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
