package dev.fritze.skyward.desktop.data

import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * XDG base directories (§11: "DB at `$XDG_DATA_HOME/skyward/skyward.db`,
 * fallback `~/.local/share/skyward/`"; §10.3: autostart file under
 * `~/.config/autostart/`).
 *
 * [environment] is injectable so the tests can exercise the XDG-set and
 * XDG-unset branches without mutating the real process environment, which
 * the JDK deliberately makes impossible to do portably.
 */
class DesktopPaths(
    private val environment: (String) -> String? = System::getenv,
    private val userHome: Path = Path.of(System.getProperty("user.home").orEmpty()),
) {

    /** True inside a Flatpak sandbox — §10.3 switches autostart to the Background portal on this. */
    val isFlatpak: Boolean get() = !environment("FLATPAK_ID").isNullOrBlank()

    /** The app id, which is also the Flatpak app id and the `.desktop` file's basename (§15.5). */
    val appId: String get() = environment("FLATPAK_ID")?.takeIf { it.isNotBlank() } ?: DEFAULT_APP_ID

    fun dataDir(): Path = xdgDir("XDG_DATA_HOME", ".local/share").resolve("skyward")

    fun configDir(): Path = xdgDir("XDG_CONFIG_HOME", ".config")

    fun autostartDir(): Path = configDir().resolve("autostart")

    /** Creates the data directory if needed and returns the DB file path inside it. */
    fun databaseFile(): Path = dataDir().createDirectories().resolve("skyward.db")

    private fun xdgDir(variable: String, homeRelativeFallback: String): Path {
        val fromEnvironment = environment(variable)?.takeIf { it.isNotBlank() }
        // The XDG spec requires an absolute path and says relative ones must be
        // ignored — a relative value here would otherwise silently resolve
        // against whatever directory the app happened to be launched from.
        val absolute = fromEnvironment?.let(Path::of)?.takeIf { it.isAbsolute }
        return absolute ?: userHome.resolve(homeRelativeFallback)
    }

    private companion object {
        const val DEFAULT_APP_ID = "dev.fritze.Skyward"
    }
}
