import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

val bundleBuildInfo by tasks.registering {
    description = "Packages the desktop build/version metadata needed by the About screen."
    val outputDirectory = layout.buildDirectory.dir("generated/buildInfo")
    val appVersion = rootProject.extra["skywardVersionName"] as String
    val releaseTag = rootProject.extra["skywardReleaseTag"] as String
    inputs.property("appVersion", appVersion)
    inputs.property("releaseTag", releaseTag)
    outputs.dir(outputDirectory)

    doLast {
        val target = outputDirectory.get().asFile.also { it.mkdirs() }.resolve("skyward-build.properties")
        target.writeText(
            buildString {
                appendLine("appVersion=$appVersion")
                appendLine("releaseTag=$releaseTag")
            }
        )
    }
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
    resources.srcDir(bundleBuildInfo)
    resources.srcDir(bundleNotice.map { it.destinationDir })
}

compose.desktop {
    application {
        mainClass = "dev.fritze.skyward.desktop.MainKt"

        buildTypes.release.proguard {
            // §15.5 asks for release minification. It is OFF, deliberately, and
            // this is the deviation's record.
            //
            // ProGuard 7.7.0 (and 7.4.2, checked) cannot produce a *working*
            // build of this dependency set. Three separate miscompilations, each
            // reproduced by running the packaged binary:
            //
            //   1. The optimizer's local-variable reallocation breaks
            //      `EclipseSource.refresh` (§7.1.3's sampler — a big suspend
            //      function full of live doubles):
            //      "VerifyError: Instruction type does not match stack map".
            //      Avoidable only with -dontoptimize; the narrower
            //      !code/allocation/variable is not enough.
            //   2. The shrinker deletes `kotlinx.coroutines.Job` from
            //      `JobSupport`'s *direct* interface list as redundant (ChildJob
            //      already extends it). That breaks the invokespecial its
            //      JVM-default `cancel()` compiles to:
            //      "VerifyError: Bad invokespecial instruction: interface method
            //      reference is in an indirect superinterface", thrown as soon as
            //      the app builds its application scope. `-keep class
            //      kotlinx.coroutines.** { *; }` does not prevent it — the
            //      redundant-interface removal is unconditional — and only
            //      -dontshrink does.
            //   3. Service-loaded providers (Ktor's engine and serialization
            //      extensions) get shrunk away while their META-INF/services
            //      entries survive. That one *is* fixable with keep rules, and
            //      proguard-rules.pro has them.
            //
            // -dontoptimize plus -dontshrink leaves only renaming, which buys no
            // size and adds reflection risk. A packaged app that does not start
            // fails M6's own acceptance criterion ("runs under Flatpak
            // locally"), so minification waits for M7 — by which time a
            // coroutines or ProGuard bump may simply fix (2).
            isEnabled.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            // §15.5: jpackage targets. No AppImage support in the Compose plugin —
            // Flatpak (primary) repackages the jlinked createReleaseDistributable
            // tree separately; see flatpak/.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)

            // jlink strips the runtime image down to what it can *see* being
            // used, and it cannot see through service loading or reflection.
            // Without these, the packaged app builds cleanly and then dies on
            // first launch — which is exactly how it failed before they were
            // listed:
            //   java.sql       JDBC service lookup for Xerial/SQLite (§11);
            //                  NoClassDefFoundError: java/sql/DriverManager
            //   java.naming    pulled in by the JDBC stack
            //   jdk.crypto.ec  ECDHE cipher suites — without it every HTTPS
            //                  call to SWPC/JPL/EONET fails the handshake (§7)
            //   jdk.unsupported  sun.misc.Unsafe, used by skiko and coroutines
            modules("java.sql", "java.naming", "jdk.crypto.ec", "jdk.unsupported")

            packageName = "skyward"
            // Same git tag as the Android version (root build.gradle.kts), but
            // the bare numeric MAJOR.MINOR.PATCH form: jpackage rejects a
            // version carrying describe's "-3-gabc1234" suffix.
            packageVersion = rootProject.extra["skywardPackageVersion"] as String
            description = "Location-based reminders for natural & sky events"
            vendor = "Skyward contributors"

            linux {
                packageName = "skyward"
                menuGroup = "Science;Education;"
            }
        }
    }
}
