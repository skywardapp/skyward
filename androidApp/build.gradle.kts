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
    // each contain exactly one file (AndroidManifest.xml). Keep these two
    // blocks bare: a per-flavour buildConfigField or resValue written here is
    // the one D13 divergence §17.5b's checks below cannot see.
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

// §17.5b (a) / R14: the manifest-diff CI check. fossDebug and playDebug must
// merge to identical manifests except for the exact-alarm permission entries
// (§10.2) — any other divergence means D13's "flavours differ only in the
// exact-alarm permission" invariant has silently broken.
//
// The comparison is over the *whole* merged manifest, not just its
// <uses-permission> elements: R14's drift risk is a flavour quietly gaining a
// receiver, a provider, an <application> attribute or an extra <queries>
// entry, none of which a permission-only diff can see. Permissions are still
// compared as (name, maxSdkVersion) pairs — that falls out of comparing every
// attribute of every element, and it is what makes the foss/play
// SCHEDULE_EXACT_ALARM entries (identical in name, differing in
// maxSdkVersion="32", §10.2) distinguishable at all.
run {
    val androidComponents = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    val mergedManifests = mutableMapOf<String, org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>>()
    androidComponents.onVariants { variant ->
        if (variant.name == "fossDebug" || variant.name == "playDebug") {
            mergedManifests[variant.name] = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        }
    }

    val androidNs = "http://schemas.android.com/apk/res/android"
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

            fun parse(manifestFile: File): org.w3c.dom.Document =
                javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(manifestFile)

            fun isExactAlarmPermission(el: org.w3c.dom.Element): Boolean =
                el.localName == "uses-permission" && el.getAttributeNS(androidNs, "name") in exactAlarmPermissionNames

            // Attributes keyed by namespace URI rather than by prefix, so a
            // flavour manifest that bound `android:` to a different prefix
            // would still compare equal — the merger normalises prefixes, but
            // relying on it would make this check's correctness depend on an
            // AGP implementation detail.
            fun attributeSignature(el: org.w3c.dom.Element): String =
                (0 until el.attributes.length)
                    .map { el.attributes.item(it) as org.w3c.dom.Attr }
                    // xmlns declarations are scoping, not content: two manifests
                    // that declare the same namespace at different depths are
                    // the same manifest.
                    .filterNot { it.namespaceURI == "http://www.w3.org/2000/xmlns/" }
                    .map { attr ->
                        val name = when (attr.namespaceURI) {
                            null -> attr.name
                            androidNs -> "android:${attr.localName}"
                            else -> "{${attr.namespaceURI}}${attr.localName}"
                        }
                        "$name=\"${attr.value}\""
                    }
                    .sorted()
                    .joinToString(", ")

            // A multiset of root-anchored element paths: order-insensitive
            // (the merger's output order is not a contract) but duplicate- and
            // attribute-sensitive. Comment and text nodes are skipped —
            // manifests carry no meaningful character data, and the flavour
            // manifests' explanatory comments are exactly the kind of
            // difference that must not fail this check.
            fun elementPathCounts(manifestFile: File): Map<String, Int> {
                val counts = mutableMapOf<String, Int>()
                fun walk(el: org.w3c.dom.Element, parentPath: String) {
                    if (isExactAlarmPermission(el)) return
                    val attributes = attributeSignature(el)
                    val path = "$parentPath/${el.localName ?: el.tagName}" + if (attributes.isEmpty()) "" else "[$attributes]"
                    counts.merge(path, 1, Int::plus)
                    val children = el.childNodes
                    for (i in 0 until children.length) {
                        val child = children.item(i)
                        if (child is org.w3c.dom.Element) walk(child, path)
                    }
                }
                walk(parse(manifestFile).documentElement, "")
                return counts
            }

            // Every path is rooted at the <manifest> element and prefixed by
            // it, so the shortest key is that element's own signature.
            fun rootSignature(counts: Map<String, Int>): String =
                counts.keys.minByOrNull { it.length } ?: error("merged manifest parsed to no elements at all")

            val fossPaths = elementPathCounts(fossFile)
            val playPaths = elementPathCounts(playFile)
            val fossRoot = rootSignature(fossPaths)
            val playRoot = rootSignature(playPaths)

            // A divergent <manifest> element (a flavour-specific
            // applicationIdSuffix or versionName, say) re-roots every path
            // below it, so reporting the whole diff would bury the one line
            // that matters under every element in the file.
            if (fossRoot != playRoot) {
                throw GradleException(
                    "androidApp flavours' merged manifests diverge on the <manifest> element itself (D13):\n" +
                        "  foss: $fossRoot\n  play: $playRoot"
                )
            }

            val divergentPaths = (fossPaths.keys + playPaths.keys)
                .filter { fossPaths[it] != playPaths[it] }
                .sorted()

            if (divergentPaths.isNotEmpty()) {
                throw GradleException(
                    "androidApp flavours' merged manifests diverge beyond the exact-alarm exception (D13):\n" +
                        divergentPaths.joinToString("\n") { path ->
                            "  ${path.removePrefix(fossRoot)} — foss: ${fossPaths[path] ?: 0}×, play: ${playPaths[path] ?: 0}×"
                        } + "\n" +
                        "Only the ${exactAlarmPermissionNames.joinToString(" / ")} entries may differ (§10.2)."
                )
            }

            // Report what the flavours legitimately differ on, so the log says
            // the exception is still in use rather than merely that nothing
            // failed — an accidentally *empty* delta (both flavours losing the
            // permission) is a §10.2 bug this check cannot see.
            fun exactAlarmEntries(manifestFile: File): Set<Pair<String, String?>> {
                val nodes = parse(manifestFile).getElementsByTagName("uses-permission")
                return buildSet {
                    for (i in 0 until nodes.length) {
                        val el = nodes.item(i) as org.w3c.dom.Element
                        if (!isExactAlarmPermission(el)) continue
                        add(el.getAttributeNS(androidNs, "name") to el.getAttributeNS(androidNs, "maxSdkVersion").ifBlank { null })
                    }
                }
            }

            val fossExactAlarm = exactAlarmEntries(fossFile)
            val playExactAlarm = exactAlarmEntries(playFile)
            val divergentPermissions = (fossExactAlarm - playExactAlarm) + (playExactAlarm - fossExactAlarm)
            logger.lifecycle(
                "checkFlavourManifestParity: OK — ${fossPaths.values.sum()} manifest elements identical; " +
                    "flavours diverge only on ${divergentPermissions.map { it.first }.toSet()}"
            )
        }
    }
}

// §17.5b (b) / D13: flavour source-set purity. The manifest diff above sees
// only what reaches a merged manifest, and the dependency-set check below
// only what reaches a resolved classpath; neither can see a `.kt` file
// compiled into one flavour and not the other, which is R14's real drift
// risk. §15.1's layout gives each flavour exactly one file — its
// AndroidManifest.xml — so the guard is simply that nothing else is there.
//
// Any file, not just `.kt`: a flavour-only drawable, string resource, asset
// or `.java` file diverges the two APKs just as effectively, and none of them
// is harder to add by accident.
run {
    val srcRoot = layout.projectDirectory.dir("src").asFile
    val manifestOnlyDirs = listOf("foss", "play")

    tasks.register("checkFlavourSourceSetsManifestOnly") {
        group = "verification"
        description = "Fails if a foss/play source set holds anything but its AndroidManifest.xml (D13, §17.5b)."

        inputs.files(fileTree(srcRoot) { include("foss*/**", "play*/**") })
            .withPropertyName("flavourSourceSets")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

        doLast {
            fun relativeFiles(dir: File): List<String> =
                dir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(srcRoot).invariantSeparatorsPath }
                    .sorted()
                    .toList()

            val violations = mutableListOf<String>()

            for (name in manifestOnlyDirs) {
                val dir = File(srcRoot, name)
                if (!dir.isDirectory) {
                    violations += "src/$name/ is missing — each flavour owns exactly one AndroidManifest.xml (§15.1)"
                    continue
                }
                val contents = relativeFiles(dir)
                if (contents != listOf("$name/AndroidManifest.xml")) {
                    violations += "src/$name/ must contain AndroidManifest.xml and nothing else, but holds: $contents"
                }
            }

            // Variant source sets (src/fossDebug/, src/playRelease/, …) are
            // flavour-specific too, and src/fossRelease/ in particular is
            // invisible to the manifest diff above, which compares the *debug*
            // variants. None of them may exist at all.
            //
            // Test source sets (src/testFoss/, src/androidTestFoss/) are named
            // the other way round and so fall outside this sweep, deliberately:
            // §17.5 runs the same instrumented tests against both flavours, and
            // a test that pins one flavour's behaviour ships in no APK.
            val strayFlavourSourceSets = (srcRoot.listFiles() ?: emptyArray())
                .sortedBy { it.name }
                .filter { it.isDirectory && it.name !in manifestOnlyDirs }
                .filter { it.name.startsWith("foss") || it.name.startsWith("play") }
                .filter { relativeFiles(it).isNotEmpty() }
                .map { "src/${it.name}/ holds flavour-specific sources: ${relativeFiles(it)}" }
            violations += strayFlavourSourceSets

            if (violations.isNotEmpty()) {
                throw GradleException(
                    "androidApp flavours differ by more than the exact-alarm permission (D13, §15.1):\n" +
                        violations.joinToString("\n") { "  $it" } + "\n" +
                        "Flavour-specific code belongs in src/main; if it genuinely cannot, that is an ADR, not a source set."
                )
            }
            logger.lifecycle("checkFlavourSourceSetsManifestOnly: OK — ${manifestOnlyDirs.size} flavour source sets, manifests only.")
        }
    }
}

// §17.5b (c) / D13: dependency-set parity between flavours. The two checks
// above catch manifest and source-set drift; a flavour pulling in an extra
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

// All three parts of §17.5b's D13 guard run in `check`, so the invariant is
// enforced by the same command a contributor runs locally, not only by CI.
tasks.named("check") {
    dependsOn(
        "checkFlavourManifestParity",
        "checkFlavourSourceSetsManifestOnly",
        "checkFlavourDependencyParity",
    )
}
