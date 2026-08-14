package dev.fritze.skyward.desktop.autostart

import dev.fritze.skyward.desktop.data.DesktopPaths
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** §10.3's two autostart paths: the XDG entry, and the Flatpak background portal. */
class AutostartManagerTest {

    private fun pathsRootedAt(home: java.nio.file.Path, flatpak: Boolean = false) = DesktopPaths(
        environment = { key -> if (key == "FLATPAK_ID" && flatpak) "dev.fritze.Skyward" else null },
        userHome = home,
    )

    @Test
    fun writesAndRemovesTheXdgAutostartEntry() = runTest {
        val home = createTempDirectory("skyward-home")
        val manager = XdgAutostartManager(pathsRootedAt(home))
        val entry = home.resolve(".config/autostart/skyward.desktop")

        assertFalse(manager.isEnabled())

        assertEquals(AutostartResult.Applied, manager.setEnabled(true))
        assertTrue(entry.exists())
        assertTrue(manager.isEnabled())

        val text = entry.readText()
        assertTrue(text.startsWith("[Desktop Entry]"), text)
        // A session-startup launch must not pop a window in the user's face.
        assertTrue(text.contains("Exec=skyward --background"), text)

        assertEquals(AutostartResult.Applied, manager.setEnabled(false))
        assertFalse(entry.exists())
        assertFalse(manager.isEnabled())
    }

    @Test
    fun gnomesOwnOffSwitchIsHonoured() = runTest {
        // GNOME's Startup Applications toggle leaves the file in place and
        // flips this key, so `exists()` alone would show a switch that
        // disagrees with the desktop's own UI.
        val home = createTempDirectory("skyward-home")
        val manager = XdgAutostartManager(pathsRootedAt(home))
        manager.setEnabled(true)
        assertTrue(manager.isEnabled())

        val entry = home.resolve(".config/autostart/skyward.desktop")
        entry.writeText(entry.readText().replace("X-GNOME-Autostart-enabled=true", "X-GNOME-Autostart-enabled=false"))
        assertFalse(manager.isEnabled())

        // Re-enabling rewrites the entry, so the key comes back as `true`.
        manager.setEnabled(true)
        assertTrue(manager.isEnabled())
    }

    @Test
    fun theFlatpakBackendAsksThePortalAndAdmitsItCannotReadTheState() = runTest {
        val commands = mutableListOf<List<String>>()
        val manager = BackgroundPortalAutostartManager(
            paths = pathsRootedAt(createTempDirectory("skyward-home"), flatpak = true),
            commandRunner = { command ->
                commands += command
                true
            },
        )

        // The portal exposes no getter; guessing would be worse than saying so.
        assertEquals(null, manager.isEnabled())

        val result = manager.setEnabled(true)
        assertIs<AutostartResult.Requested>(result)

        val command = commands.single()
        assertEquals("gdbus", command.first())
        assertTrue(command.contains("org.freedesktop.portal.Background.RequestBackground"), command.toString())
        val options = command.last()
        assertTrue(options.contains("'autostart': <true>"), options)
        assertTrue(options.contains("'commandline': <['dev.fritze.Skyward', '--background']>"), options)
    }

    @Test
    fun aRefusedPortalRequestIsReportedAsAFailure() = runTest {
        val manager = BackgroundPortalAutostartManager(
            paths = pathsRootedAt(createTempDirectory("skyward-home"), flatpak = true),
            commandRunner = { false },
        )
        assertIs<AutostartResult.Failed>(manager.setEnabled(true))
    }

    @Test
    fun theBackendIsChosenByTheSandbox() {
        val home = createTempDirectory("skyward-home")
        assertIs<XdgAutostartManager>(AutostartManager.forEnvironment(pathsRootedAt(home)))
        assertIs<BackgroundPortalAutostartManager>(AutostartManager.forEnvironment(pathsRootedAt(home, flatpak = true)))
    }
}
