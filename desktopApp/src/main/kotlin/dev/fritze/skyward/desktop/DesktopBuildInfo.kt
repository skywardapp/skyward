package dev.fritze.skyward.desktop

import java.util.Properties

/**
 * §13.1/§15.4: desktop About needs the same version-derived source-link
 * metadata as Android, but the JVM app has no BuildConfig equivalent.
 */
object DesktopBuildInfo {
    private val properties = Properties().apply {
        DesktopBuildInfo::class.java.getResourceAsStream("/skyward-build.properties")?.use(::load)
    }

    val appVersion: String = properties.getProperty("appVersion", "0.0.0-dev")
    val releaseTag: String? = properties.getProperty("releaseTag")?.ifBlank { null }
}
