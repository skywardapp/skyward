package dev.fritze.skyward.desktop.autostart

import dev.fritze.skyward.desktop.data.DesktopPaths
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** What actually happened when autostart was toggled — the two backends differ in how sure they can be. */
sealed interface AutostartResult {
    /** The change is in effect now (an XDG `.desktop` file was written or removed). */
    data object Applied : AutostartResult

    /** A portal request was handed off; the desktop decides, possibly after asking the user. */
    data class Requested(val note: String) : AutostartResult

    data class Failed(val message: String) : AutostartResult
}

/**
 * §10.3: "Autostart: write XDG `~/.config/autostart/skyward.desktop` when
 * enabled; inside Flatpak use the `org.freedesktop.portal.Background` portal
 * instead (detect via `FLATPAK_ID` env)."
 */
interface AutostartManager {
    /**
     * The best answer this backend can give about the *current* state.
     * Null means "cannot be determined" — the portal has no readable state,
     * so callers fall back to the persisted `autostart` setting (§11).
     */
    fun isEnabled(): Boolean?

    fun setEnabled(enabled: Boolean): AutostartResult

    companion object {
        fun forEnvironment(paths: DesktopPaths = DesktopPaths()): AutostartManager =
            if (paths.isFlatpak) BackgroundPortalAutostartManager(paths) else XdgAutostartManager(paths)
    }
}

/** The plain-desktop path: a `.desktop` file in the XDG autostart directory. */
class XdgAutostartManager(private val paths: DesktopPaths) : AutostartManager {

    private fun entryFile() = paths.autostartDir().resolve("$AUTOSTART_BASENAME.desktop")

    override fun isEnabled(): Boolean = runCatching { entryFile().exists() }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean): AutostartResult = try {
        val file = entryFile()
        if (enabled) {
            file.parent.createDirectories()
            file.writeText(DESKTOP_ENTRY)
        } else {
            file.deleteIfExists()
        }
        AutostartResult.Applied
    } catch (e: Exception) {
        AutostartResult.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    private companion object {
        const val AUTOSTART_BASENAME = "skyward"

        // Mirrors flatpak/dev.fritze.Skyward.desktop, plus the two autostart-only
        // keys. `--background` starts hidden to tray, which is the only sensible
        // thing for a session-startup launch (§10.3).
        val DESKTOP_ENTRY = """
            [Desktop Entry]
            Type=Application
            Name=Skyward
            Comment=Location-based reminders for natural & sky events
            Exec=skyward --background
            Icon=dev.fritze.Skyward
            Categories=Science;Education;
            Terminal=false
            X-GNOME-Autostart-enabled=true
            StartupNotify=false

        """.trimIndent()
    }
}

/**
 * The Flatpak path. Writing into `~/.config/autostart` from inside the
 * sandbox would need `--filesystem=xdg-config`, which §15.5's minimal
 * sandbox deliberately does not grant; the portal is the sanctioned route
 * and is already in the manifest's `finish-args`.
 *
 * Driven through `gdbus` (glib, always present in `org.freedesktop.Platform`)
 * rather than a DBus client library — the same "shell out to something the
 * runtime guarantees" reasoning as §10.3's `notify-send` fallback, and it
 * keeps a second native-integration dependency out of the build (§19 R8).
 */
class BackgroundPortalAutostartManager(
    private val paths: DesktopPaths,
    private val commandRunner: (List<String>) -> Boolean = ::runCommand,
) : AutostartManager {

    /**
     * The portal exposes no getter for the current autostart grant, and
     * guessing would be worse than admitting it: the UI falls back to the
     * persisted setting and labels it as a request rather than a fact.
     */
    override fun isEnabled(): Boolean? = null

    override fun setEnabled(enabled: Boolean): AutostartResult {
        val options = buildString {
            append("{'reason': <'Skyward delivers reminders while its window is closed.'>, ")
            append("'autostart': <$enabled>, ")
            append("'commandline': <['${paths.appId}', '--background']>, ")
            append("'dbus-activatable': <false>}")
        }
        val ok = commandRunner(
            listOf(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop",
                "--method", "org.freedesktop.portal.Background.RequestBackground",
                "", // parent_window: no window handle to hand over from Compose
                options,
            ),
        )
        return if (ok) {
            AutostartResult.Requested(
                if (enabled) {
                    "Asked the desktop to start Skyward at login. Your desktop may ask you to confirm."
                } else {
                    "Asked the desktop to stop starting Skyward at login."
                },
            )
        } else {
            AutostartResult.Failed("The background portal did not accept the request.")
        }
    }

    private companion object {
        const val PORTAL_TIMEOUT_SECONDS = 10L

        fun runCommand(command: List<String>): Boolean = try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (process.waitFor(PORTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.exitValue() == 0
            } else {
                process.destroy()
                false
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            System.err.println("background portal request failed (${e.message ?: e::class.simpleName})")
            false
        }
    }
}
