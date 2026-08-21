plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// §15.4: the shipped version is derived from the latest vMAJOR.MINOR.PATCH git
// tag rather than hardcoded in a module. A release is cut by pushing a tag
// (.github/workflows/auto-tag-main.yml does that on every push to main), and
// every build — CI's, F-Droid's rebuild, a local one — recovers the same
// version from that same tag. There is no version field to bump in a commit,
// so nothing can silently disagree with the tag it shipped under.
//
// Reproducibility (§15.4/§17.5b) requires this to be a pure function of the
// checked-out commit, and it is: `git describe` reads committed refs only, and
// working-tree dirtiness is deliberately NOT part of the version — otherwise
// two builds of one commit could disagree.
//
// Escape hatch for build environments with no git history (a source tarball, a
// pinned F-Droid recipe): -PskywardVersionName / -PskywardVersionCode override
// the derivation entirely.
//
// The results are exposed as extra properties rather than a shared function
// because each *.gradle.kts is its own compilation unit and cannot see this
// file's top-level declarations (same reason as licenseCheckedConfigurations
// below).
run {
    // --match takes a glob, which cannot express "digits only": 'v[0-9]*.[0-9]*.[0-9]*'
    // also matches prerelease forms like v0.1.0-rc.1. --exclude '*-*' drops those,
    // and this anchored regex is what actually decides what counts as a release
    // tag. It matches the one in .github/workflows/auto-tag-main.yml, leading-zero
    // rejection included, so both ends agree on which tags are releases.
    val releaseTagGlob = "v[0-9]*.[0-9]*.[0-9]*"
    val releaseTagPattern = Regex("""^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

    fun git(vararg args: String): String? = runCatching {
        val output = providers.exec {
            workingDir = rootDir
            commandLine(*args)
            isIgnoreExitValue = true
        }
        if (output.result.get().exitValue != 0) {
            null
        } else {
            output.standardOutput.asText.get().trim().ifBlank { null }
        }
    }.getOrNull()

    // Bare nearest reachable release tag ("v0.1.0"), for the version code.
    val baseTag = git(
        "git", "describe", "--tags", "--abbrev=0",
        "--match", releaseTagGlob, "--exclude", "*-*",
    )?.takeIf { releaseTagPattern.matches(it) }

    // Same tag, but with "-<commits>-g<sha>" appended once HEAD has moved past
    // it — which is exactly what marks a build as "not the release itself".
    val describedVersion = if (baseTag == null) {
        null
    } else {
        git("git", "describe", "--tags", "--match", releaseTagGlob, "--exclude", "*-*")
    }

    val (major, minor, patch) = baseTag
        ?.let { releaseTagPattern.find(it) }
        ?.destructured
        ?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
        ?: Triple(0, 0, 0)

    // Monotonic and human-decodable: 0.1.0 -> 1000, 1.2.3 -> 1002003.
    //
    // The encoding is positional, so each component has to stay inside its
    // field or it carries into the next one and collides: v0.0.1000 and v0.1.0
    // both encode to 1000. That is not a hypothetical here — auto-tag-main
    // increments the patch on every push to main, so the patch component climbs
    // steadily on its own. Major is bounded too, by Android's 2100000000 ceiling.
    //
    // Failing the build is the only safe response. A versionCode is permanent
    // once published — a store will not accept a re-used or lowered one — so a
    // collision discovered after the fact cannot be corrected, only worked
    // around forever. The fix when this trips is to cut the next minor or major
    // tag by hand, which resets the patch field.
    val maxComponent = 999
    val maxMajor = 2100
    if (baseTag != null) {
        val problems = buildList {
            if (major > maxMajor) add("major $major exceeds $maxMajor (Android's versionCode ceiling)")
            if (minor > maxComponent) add("minor $minor exceeds $maxComponent")
            if (patch > maxComponent) add("patch $patch exceeds $maxComponent — cut a minor release to reset it")
            if (major == 0 && minor == 0 && patch == 0) add("v0.0.0 encodes to 0, but Android requires versionCode >= 1")
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Release tag $baseTag cannot be encoded as an Android versionCode: " +
                    problems.joinToString("; ") + ".\n" +
                    "The MAJOR*1000000 + MINOR*1000 + PATCH encoding needs each component " +
                    "in range or two different releases can encode to the same versionCode."
            )
        }
    }

    // An untagged build gets 1 — deliberately lower than any real release, so a
    // version-less build that reaches a store is rejected as a downgrade rather
    // than silently published over a real one.
    val derivedVersionCode = if (baseTag != null) major * 1_000_000 + minor * 1_000 + patch else 1
    val derivedVersionName = describedVersion?.removePrefix("v") ?: "0.0.0-dev"

    val overrideVersionName = providers.gradleProperty("skywardVersionName").orNull
    val overrideVersionCode = providers.gradleProperty("skywardVersionCode").orNull?.toInt()

    extra["skywardVersionName"] = overrideVersionName ?: derivedVersionName
    extra["skywardVersionCode"] = overrideVersionCode ?: derivedVersionCode
    // jpackage rejects anything but numeric MAJOR.MINOR.PATCH, so the desktop
    // packaging gets the bare tag numbers with no "-3-gabc1234" suffix. Untagged
    // desktop builds keep 0.1.0 rather than 0.0.0, since some jpackage targets
    // reject an all-zero version outright.
    extra["skywardPackageVersion"] =
        if (baseTag != null) "$major.$minor.$patch" else "0.1.0"

    logger.lifecycle(
        "Skyward version: ${extra["skywardVersionName"]} " +
            "(versionCode ${extra["skywardVersionCode"]}" +
            (if (baseTag == null) ", no release tag found — using untagged fallback" else "") + ")"
    )
}

// §17.5b / P6 / §16: "A dependency-licence report fails the build on any
// dependency whose licence is not on an allowlist." Resolves each shipped
// app's runtime classpath, fetches the POM for every external module, and
// checks the declared <license><name> text — following the <parent> chain
// when a POM declares none itself, which is how most "missing" licence
// metadata in practice reads (slf4j-api, two-slices and Guava's
// listenablefuture stub all inherit theirs). Anything still unidentified
// after that walk fails the build; a genuine one-off goes in
// `licenseUnknownExceptions` below, with the manual §16 verdict written
// down, so an unknown can never pass silently.
//
// Two independent constraints, both enforced here:
//  - P6/D12 — commercial use must stay possible, so NonCommercial,
//    ShareAlike-without-GPL, SSPL, Commons Clause and friends hard-fail.
//    That is the class of mistake D12 exists to prevent from recurring as
//    dependencies are added.
//  - D8 — the app is GPL-3.0-or-later, so a bundled dependency's licence must
//    also be *GPL-compatible*. This is why the allowlist is narrower than
//    "any OSI-approved licence": EPL-1.0 and MPL-1.1 are commercial-use-clean
//    but GPL-incompatible, and GPL-2.0-*only* is incompatible with GPL-3
//    (hence the "or later" in the GPL patterns). EPL is deliberately absent:
//    EPL-2.0 *can* be GPL-compatible, but only when the copyright holder
//    elected the secondary-licence option, and that election lives in the
//    LICENSE file rather than the POM's <name> text — so an EPL dependency
//    must be reviewed by hand and recorded in `licenseUnknownExceptions`
//    rather than pattern-matched through.
val licenseAllowlistPatterns = listOf(
    "apache.*2", "mit license", "^mit$", "bsd.*2.claus", "bsd.*3.claus", "^bsd license",
    "mozilla public license.*2", "^mpl.?2",
    "public domain", "^cc0", "creative commons cc0", "unlicense",
    "gnu general public license.*version 2.*later", "^gpl-2\\.0-or-later",
    "gnu general public license.*version 3.*later", "^gpl-3\\.0-or-later", "gnu lesser general public license",
    "go license",
).map { Regex(it, RegexOption.IGNORE_CASE) }

val licenseDenylistPatterns = listOf(
    "noncommercial", "non-commercial", "\\bnc\\b", "cc-by-nc", "commons clause",
    "sspl", "server side public license", "business source license", "\\bbusl\\b",
    "not for commercial", "no commercial use",
).map { Regex(it, RegexOption.IGNORE_CASE) }

// Per-coordinate escape hatch for the handful of artifacts whose licence
// genuinely cannot be read out of the POM chain (an empty stub POM, a
// <parent> pinned by an unresolvable property, an EPL-2.0 dependency whose
// secondary-licence election only appears in its LICENSE file). Keyed on
// "group:module" — deliberately not on version, since the value records a
// human verdict about the project, and a relicensing would show up in the
// review this list exists to force. Every entry states where the licence was
// actually read from; an entry without that is not a verdict, it is a mute
// button. Empty is the goal: reach for the parent walk first.
val licenseUnknownExceptions = mapOf<String, String>(
    // "com.example:thing" to "BSD-3-Clause per its LICENSE file at v1.2.3; POM is a stub",
)

// Configuration names (per subproject) whose resolved dependency graph should
// be license-checked — the classpaths that actually ship in a built artifact.
// Kept as a plain map here (rather than a cross-script extension function —
// each *.gradle.kts is its own compilation unit and can't see this file's
// top-level functions) so subprojects don't need any code of their own.
val licenseCheckedConfigurations = mapOf(
    "androidApp" to listOf("fossDebugRuntimeClasspath", "playDebugRuntimeClasspath"),
    "desktopApp" to listOf("desktopRuntimeClasspath", "runtimeClasspath"),
)

subprojects {
    val configNames = licenseCheckedConfigurations[name] ?: return@subprojects

    // NOT resolved eagerly here: AGP creates variant-specific configurations
    // (e.g. fossDebugRuntimeClasspath) lazily, later than a plain
    // project.afterEvaluate {} sees — even in afterEvaluate,
    // configurations.findByName() for them can still return null. Registering
    // unconditionally and looking the configuration up inside doLast (i.e. at
    // task-execution time, after the whole configuration phase is long done)
    // sidesteps that ordering entirely.
    val perConfigTaskNames = configNames.map { configName ->
        val taskName = "checkDependencyLicenses" + configName.replaceFirstChar { it.uppercase() }
        tasks.register(taskName) {
            group = "verification"
            description = "Checks '$configName' dependency licences against the §16 allowlist."
            // Not meaningfully cacheable input-for-input: it's a resolution +
            // network-optional POM read, so just always run it.
            outputs.upToDateWhen { false }
            doLast {
                val configuration = configurations.findByName(configName)
                if (configuration == null) {
                    logger.lifecycle("checkDependencyLicenses ($configName): no such configuration in ${project.path} (variant not built) — skipping.")
                    return@doLast
                }
                checkConfigurationLicenses(configuration)
            }
        }
        taskName
    }
    tasks.register("checkDependencyLicenses") {
        group = "verification"
        description = "Checks this module's shipped dependency licences against the §16 allowlist."
        dependsOn(perConfigTaskNames)
    }
    // "check" is created by the java-base/Android plugins applied in this subproject's
    // own plugins {} block, which (per subprojects{} ordering) runs AFTER this action —
    // so it doesn't exist yet here. afterEvaluate defers until it does.
    afterEvaluate {
        tasks.named("check") { dependsOn("checkDependencyLicenses") }
    }
}

/**
 * Reads the `<licenses>` names declared by a POM, walking `<parent>` when the
 * POM itself declares none — Maven inherits `<licenses>`, so a bare
 * `slf4j-api` POM is not a licence-less artifact, it is one whose licence
 * lives two levels up (slf4j-api -> slf4j-parent -> slf4j-bom -> MIT).
 * Without this walk the check's "unknown" bucket is dominated by artifacts
 * that are in fact perfectly identifiable, which is what forced it to warn
 * rather than fail.
 *
 * Only *direct* children are read at each level: `getElementsByTagName` would
 * also pick up `<licenses>` nested in a `<profile>` or a plugin
 * configuration, which are not the module's own licence.
 */
fun Project.declaredLicenseNames(pomFile: java.io.File?): List<String> {
    var file = pomFile ?: return emptyList()
    val visited = mutableSetOf<String>()
    // Depth cap: a Maven parent chain is a DAG in principle and three levels
    // deep in practice, so anything longer is a cycle or a mistake, and this
    // check must terminate either way.
    repeat(8) {
        val doc = runCatching {
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(file)
        }.getOrNull() ?: return emptyList()
        val root = doc.documentElement ?: return emptyList()

        val names = directChild(root, "licenses")
            ?.let { directChildren(it, "license") }
            ?.mapNotNull { directChild(it, "name")?.textContent?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (names.isNotEmpty()) return names

        val parent = directChild(root, "parent") ?: return emptyList()
        val group = directChild(parent, "groupId")?.textContent?.trim().orEmpty()
        val module = directChild(parent, "artifactId")?.textContent?.trim().orEmpty()
        val version = directChild(parent, "version")?.textContent?.trim().orEmpty()
        // A version left as an unresolved "${...}" property can't be fetched;
        // that dependency lands in the exception list, by hand, on purpose.
        if (group.isEmpty() || module.isEmpty() || version.isEmpty() || version.startsWith('$')) return emptyList()
        if (!visited.add("$group:$module:$version")) return emptyList()

        file = resolvePomFile(group, module, version) ?: return emptyList()
    }
    return emptyList()
}

fun directChild(parent: org.w3c.dom.Element, name: String): org.w3c.dom.Element? =
    directChildren(parent, name).firstOrNull()

fun directChildren(parent: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> {
    val kids = parent.childNodes
    return (0 until kids.length)
        .mapNotNull { kids.item(it) as? org.w3c.dom.Element }
        .filter { it.tagName == name }
}

/** Fetches one POM by coordinates (used for `<parent>` hops, which are not on any classpath). */
fun Project.resolvePomFile(group: String, module: String, version: String): java.io.File? =
    runCatching {
        dependencies.createArtifactResolutionQuery()
            .forModule(group, module, version)
            .withArtifacts(
                org.gradle.maven.MavenModule::class.java,
                org.gradle.maven.MavenPomArtifact::class.java,
            )
            .execute()
            .resolvedComponents
            .firstOrNull()
            ?.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
            ?.filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()
            ?.firstOrNull()
            ?.file
    }.getOrNull()

fun Project.checkConfigurationLicenses(configuration: org.gradle.api.artifacts.Configuration) {
    val moduleIds = configuration.incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        .filterNot { it.group == project.group && it.module == project.name } // skip self
        .toSet()

    if (moduleIds.isEmpty()) return

    val query = dependencies.createArtifactResolutionQuery()
        .forComponents(moduleIds)
        .withArtifacts(
            org.gradle.maven.MavenModule::class.java,
            org.gradle.maven.MavenPomArtifact::class.java,
        )
    val result = query.execute()

    val denied = mutableListOf<String>()
    val unknown = mutableListOf<String>()
    val excepted = mutableListOf<String>()

    for (component in result.resolvedComponents) {
        val id = component.id
        val pomFile = component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()
            .firstOrNull()?.file

        val licenseNames = declaredLicenseNames(pomFile)
        val coordinate = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
            ?.let { "${it.group}:${it.module}" }
        val exceptionReason = coordinate?.let { licenseUnknownExceptions[it] }

        val problem = when {
            licenseNames.any { name -> licenseDenylistPatterns.any { it.containsMatchIn(name) } } -> {
                // Denylisted is never exceptable: P6/D12 is a project
                // constraint, not a metadata-quality question.
                denied += "$id -> $licenseNames"
                null
            }
            licenseNames.isEmpty() ->
                "$id (no <licenses> block in its POM or any of its parents)"
            licenseNames.none { name -> licenseAllowlistPatterns.any { it.containsMatchIn(name) } } ->
                "$id -> $licenseNames (none matched the allowlist)"
            else -> null
        }
        if (problem != null) {
            if (exceptionReason != null) excepted += "$problem — allowed: $exceptionReason" else unknown += problem
        }
    }

    if (excepted.isNotEmpty()) {
        logger.lifecycle(
            "checkDependencyLicenses (${configuration.name}): ${excepted.size} " +
                "dependencies passed on a recorded manual verdict:\n" +
                excepted.joinToString("\n") { "  - $it" }
        )
    }
    if (denied.isNotEmpty()) {
        throw GradleException(
            "checkDependencyLicenses (${configuration.name}): ${denied.size} dependencies matched a " +
                "known commercial-use-incompatible licence term (P6/D12) — do not bundle:\n" +
                denied.joinToString("\n") { "  - $it" }
        )
    }
    if (unknown.isNotEmpty()) {
        // §17.5b says this fails the build, and it means it: a licence this
        // check cannot place is exactly the case where nobody has looked.
        throw GradleException(
            "checkDependencyLicenses (${configuration.name}): ${unknown.size} dependencies have no " +
                "licence this check could place on the §16 allowlist:\n" +
                unknown.joinToString("\n") { "  - $it" } +
                "\n\nEither the licence is genuinely not allowlisted — in which case do not bundle it — " +
                "or the POM metadata is unreadable, in which case verify the licence by hand (its LICENSE " +
                "file, not its README) against §16's commercial-use and GPL-3-compatibility constraints " +
                "and record the verdict in `licenseUnknownExceptions` in the root build.gradle.kts."
        )
    }
    logger.lifecycle(
        "checkDependencyLicenses (${configuration.name}): OK — ${moduleIds.size} dependencies, " +
            "every licence on the §16 allowlist."
    )
}
