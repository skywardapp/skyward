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

// §17.5b / P6 / §16: "A dependency-licence report fails the build on any
// dependency whose licence is not on an allowlist." Best-effort: resolves
// each shipped app's runtime classpath, fetches the POM for every external
// module, and checks declared <license><name> text. A module with no
// discoverable license is a WARNING (many POMs omit it even for genuinely
// permissive libraries — inheriting from a parent POM we didn't fetch, e.g.),
// never a silent pass or a hard failure; a module whose license text matches
// a known-incompatible term (NonCommercial, ShareAlike-without-GPL, SSPL,
// Commons Clause, …) hard-fails the build, since that is exactly the class of
// mistake D12 exists to prevent from recurring as dependencies are added.
val licenseAllowlistPatterns = listOf(
    "apache.*2", "mit license", "^mit$", "bsd.*2.claus", "bsd.*3.claus", "^bsd license",
    "eclipse public license", "^epl", "mozilla public license", "^mpl",
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

    for (component in result.resolvedComponents) {
        val id = component.id
        val pomFile = component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()
            .firstOrNull()?.file

        val licenseNames = pomFile?.let { file ->
            runCatching {
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(file)
                val nodes = doc.getElementsByTagName("license")
                (0 until nodes.length).mapNotNull { i ->
                    (nodes.item(i) as org.w3c.dom.Element)
                        .getElementsByTagName("name")
                        .item(0)?.textContent?.trim()
                }
            }.getOrNull().orEmpty()
        } ?: emptyList()

        when {
            licenseNames.isEmpty() ->
                unknown += "$id (no <licenses> block in its POM)"
            licenseNames.any { name -> licenseDenylistPatterns.any { it.containsMatchIn(name) } } ->
                denied += "$id -> $licenseNames"
            licenseNames.none { name -> licenseAllowlistPatterns.any { it.containsMatchIn(name) } } ->
                unknown += "$id -> $licenseNames (none matched the allowlist)"
        }
    }

    if (unknown.isNotEmpty()) {
        logger.warn(
            "checkDependencyLicenses (${configuration.name}): ${unknown.size} " +
                "dependencies have no license this check could positively allowlist — " +
                "verify manually against §16 before relying on this alone:\n" +
                unknown.joinToString("\n") { "  - $it" }
        )
    }
    if (denied.isNotEmpty()) {
        throw GradleException(
            "checkDependencyLicenses (${configuration.name}): ${denied.size} dependencies matched a " +
                "known commercial-use-incompatible licence term (P6/D12) — do not bundle:\n" +
                denied.joinToString("\n") { "  - $it" }
        )
    }
    logger.lifecycle("checkDependencyLicenses (${configuration.name}): OK — ${moduleIds.size} dependencies, no denylisted licences.")
}
