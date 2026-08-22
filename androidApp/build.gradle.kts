import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
}

// §15.4 (D11): the production signing key is generated and held by the owner
// locally — it must never be committed. It reaches this build either via a
// gitignored `keystore.properties` at the repo root (see keystore.properties.example)
// or via env vars, so CI/release automation can supply it without a file on disk.
// Its absence must not break anything: assembleFossRelease/assemblePlayRelease
// and the reproducibility check (§17.5b) both work fine against an *unsigned*
// release APK, since that check strips the signing block before comparing
// anyway. A real key only matters at actual publish time.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(propertyKey: String, envVar: String): String? =
    keystoreProperties.getProperty(propertyKey)?.ifBlank { null } ?: System.getenv(envVar)?.ifBlank { null }

val releaseStoreFile = releaseSigningValue("storeFile", "SKYWARD_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "SKYWARD_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "SKYWARD_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "SKYWARD_RELEASE_KEY_PASSWORD")

// All four or none: a *partial* config (e.g. a typo'd property key silently
// dropping just the password) must fail loudly rather than quietly falling
// back to an unsigned release build — that failure mode would only surface
// at actual publish time, the worst possible place to discover it.
val suppliedReleaseSigningInputs = mapOf(
    "storeFile" to releaseStoreFile,
    "storePassword" to releaseStorePassword,
    "keyAlias" to releaseKeyAlias,
    "keyPassword" to releaseKeyPassword,
).filterValues { it != null }
val hasReleaseSigningConfig = suppliedReleaseSigningInputs.size == 4
check(suppliedReleaseSigningInputs.isEmpty() || hasReleaseSigningConfig) {
    "Partial release signing configuration: only ${suppliedReleaseSigningInputs.keys} supplied " +
        "(via keystore.properties or SKYWARD_RELEASE_* env vars) — storeFile, storePassword, " +
        "keyAlias and keyPassword are required together, or none at all (§15.4)."
}

// §16: "`NOTICE` file enumerates the rows below; About screen renders them."
// Packaging the repository's own NOTICE as an asset (rather than retyping a
// condensed copy into Kotlin) is what keeps the About screen from drifting
// away from the file the repository ships — there is exactly one copy of the
// text, and the desktop app packages the same file the same way.
val bundleNotice by tasks.registering(Copy::class) {
    description = "Packages the repository NOTICE file as an Android asset for the About screen."
    from(rootProject.layout.projectDirectory.file("NOTICE"))
    into(layout.buildDirectory.dir("generated/notice"))
}

android {
    namespace = "dev.fritze.skyward"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.fritze.skyward"
        minSdk = 26
        targetSdk = 36
        // Derived from the latest vMAJOR.MINOR.PATCH git tag, not hardcoded —
        // see the version block in the root build.gradle.kts for the scheme and
        // for the -PskywardVersion* overrides. Releasing is "push a tag";
        // nothing here needs editing to match it.
        versionCode = rootProject.extra["skywardVersionCode"] as Int
        versionName = rootProject.extra["skywardVersionName"] as String
        // §17.5: the instrumented smoke tests run against BOTH flavours, so the
        // runner is configured on defaultConfig rather than per-flavour.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Both flavours pick this up from `main`, so the About screen — and the
    // §16 obligation behind it — is flavour-invariant, as D13 requires.
    sourceSets.getByName("main").assets.srcDir(bundleNotice.map { it.destinationDir })

    // §15.1 / D13: the ONLY difference between the two flavours is the exact-alarm
    // permission (§10.2). No BuildConfig fields, no dependency differences, no
    // source-set Kotlin code — see androidApp/src/foss and src/play, which
    // each contain exactly one file (AndroidManifest.xml).
    flavorDimensions += "store"
    productFlavors {
        create("foss") { dimension = "store" }   // F-Droid + GitHub releases
        create("play") { dimension = "store" }   // Google Play
    }

    signingConfigs {
        // Same signing config for both flavours (§15.4 step 3) — created only
        // when the owner's key is actually available, so a signingConfigs
        // block with unresolved nulls never reaches AGP.
        if (hasReleaseSigningConfig) {
            create("release") {
                // rootProject.file, not the module-local `file()`: keystore.properties.example
                // and RELEASE.md both document storeFile as relative to the repo root (where
                // `keytool -genkey ... -keystore skyward-release.jks` is run from), not to
                // androidApp/.
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core.ktx)
    // TestListenableWorkerBuilder, so §17.5's tests run the real workers'
    // bodies through the real SkywardWorkerFactory. Deliberately *not*
    // WorkManagerTestInitHelper: ADR 0006 has WorkManager initialising lazily
    // from AppContainer.scheduleBackgroundWork() during Application.onCreate,
    // so by the time any test runs it is already initialised and
    // initializeTestWorkManager() would throw. See ADR 0018.
    androidTestImplementation(libs.androidx.work.testing)
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

// §17.5b (c) / D13: dependency-set parity between flavours. The manifest-diff
// check above catches permission drift; a flavour pulling in an extra
// dependency would be a subtler D13 violation that manifest diffing can't
// see (there is currently no such source — both flavours share the same
// `dependencies {}` block — but this is the automated enforcement called for
// by §17.5b, not just a manual invariant to remember).
run {
    val fossConfigName = "fossReleaseRuntimeClasspath"
    val playConfigName = "playReleaseRuntimeClasspath"

    tasks.register("checkFlavourDependencyParity") {
        group = "verification"
        description = "Fails if the foss/play release variants resolve to different dependency sets (§17.5b, D13)."
        outputs.upToDateWhen { false }

        doLast {
            // Every resolved component is encoded, not just external Maven modules:
            // dropping project(":core")-style dependencies here would leave a future
            // flavour-specific *project* dependency (unlike today, where both flavours
            // pull the same :core) invisible to this check.
            fun resolvedCoordinates(configName: String): Set<String> {
                val configuration = configurations.findByName(configName)
                    ?: error("$configName not found — check androidApp's productFlavors/buildTypes")
                return configuration.incoming.resolutionResult.allComponents
                    .map { component ->
                        when (val id = component.id) {
                            is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                            is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> "project:${id.projectPath}"
                            else -> "other:${id.displayName}"
                        }
                    }
                    .toSet()
            }

            val fossDeps = resolvedCoordinates(fossConfigName)
            val playDeps = resolvedCoordinates(playConfigName)
            val onlyInFoss = fossDeps - playDeps
            val onlyInPlay = playDeps - fossDeps

            if (onlyInFoss.isNotEmpty() || onlyInPlay.isNotEmpty()) {
                throw GradleException(
                    "androidApp flavours resolve to different dependency sets (D13):\n" +
                        "  foss-only: $onlyInFoss\n  play-only: $onlyInPlay"
                )
            }
            logger.lifecycle(
                "checkFlavourDependencyParity: OK — ${fossDeps.size} shared dependencies, no flavour-specific drift."
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkFlavourManifestParity", "checkFlavourDependencyParity")
}
