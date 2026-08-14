import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "dev.fritze.skyward"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.fritze.skyward"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // §17.5: the instrumented smoke tests run against BOTH flavours, so the
        // runner is configured on defaultConfig rather than per-flavour.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // §15.1 / D13: the ONLY difference between the two flavours is the exact-alarm
    // permission (§10.2). No BuildConfig fields, no dependency differences, no
    // source-set Kotlin code — see androidApp/src/foss and src/play, which
    // each contain exactly one file (AndroidManifest.xml).
    flavorDimensions += "store"
    productFlavors {
        create("foss") { dimension = "store" }   // F-Droid + GitHub releases
        create("play") { dimension = "store" }   // Google Play
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Window/transition animations make Compose's idling resource wait on
        // frames that never settle deterministically on an emulator; disabling
        // them is what keeps connected tests from flaking under CI load. The
        // CI emulator also disables them device-wide (belt and braces), and it
        // does not affect the recorded video: the screen still renders, it just
        // does not animate between states.
        animationsDisabled = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Supplies the debug-only manifest entry for ComponentActivity that
    // createAndroidComposeRule<MainActivity>() launches into.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// §17.5b / R14: the manifest-diff CI check. fossDebug and playDebug must merge
// to identical manifests except for the exact-alarm permission entries
// (§10.2) — any other divergence means D13's "flavours differ only in the
// exact-alarm permission" invariant has silently broken. Compares
// (name, maxSdkVersion) pairs, not bare names, because the foss/play
// SCHEDULE_EXACT_ALARM entries differ only by maxSdkVersion (§10.2) and a
// bare-name comparison would miss that attribute entirely.
run {
    val androidComponents = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    val mergedManifests = mutableMapOf<String, org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>>()
    androidComponents.onVariants { variant ->
        if (variant.name == "fossDebug" || variant.name == "playDebug") {
            mergedManifests[variant.name] = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        }
    }

    val exactAlarmPermissionNames = setOf(
        "android.permission.USE_EXACT_ALARM",
        "android.permission.SCHEDULE_EXACT_ALARM",
    )

    tasks.register("checkFlavourManifestParity") {
        group = "verification"
        description = "Fails if the foss/play merged manifests diverge anywhere but the exact-alarm permissions (D13, §17.5b)."

        // Providers are resolved lazily at task-graph time, after onVariants has run for both.
        inputs.files(project.provider { mergedManifests.values.toList() })

        doLast {
            val fossFile = mergedManifests["fossDebug"]?.get()?.asFile
                ?: error("fossDebug variant was not configured — check androidApp's productFlavors")
            val playFile = mergedManifests["playDebug"]?.get()?.asFile
                ?: error("playDebug variant was not configured — check androidApp's productFlavors")

            fun permissionPairs(manifestFile: File): Set<Pair<String, String?>> {
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(manifestFile)
                val androidNs = "http://schemas.android.com/apk/res/android"
                val nodes = doc.getElementsByTagName("uses-permission")
                return buildSet {
                    for (i in 0 until nodes.length) {
                        val el = nodes.item(i) as org.w3c.dom.Element
                        val name = el.getAttributeNS(androidNs, "name")
                        val maxSdk = el.getAttributeNS(androidNs, "maxSdkVersion").ifBlank { null }
                        add(name to maxSdk)
                    }
                }
            }

            val fossPermissions = permissionPairs(fossFile)
            val playPermissions = permissionPairs(playFile)
            val onlyInFoss = fossPermissions - playPermissions
            val onlyInPlay = playPermissions - fossPermissions
            val divergent = onlyInFoss + onlyInPlay

            val unexpected = divergent.filterNot { (name, _) -> name in exactAlarmPermissionNames }
            if (unexpected.isNotEmpty()) {
                throw GradleException(
                    "androidApp flavours diverge on permissions beyond the exact-alarm exception (D13):\n" +
                        "  foss-only: $onlyInFoss\n  play-only: $onlyInPlay\n" +
                        "Unexpected divergent entries: $unexpected"
                )
            }
            logger.lifecycle("checkFlavourManifestParity: OK — flavours diverge only on ${divergent.map { it.first }.toSet()}")
        }
    }
}

tasks.named("check") {
    dependsOn("checkFlavourManifestParity")
}
