package dev.fritze.skyward.desktop.data

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §11's DB location and §10.3's Flatpak detection. */
class DesktopPathsTest {

    private val home = Path.of("/home/tester")

    private fun paths(vararg environment: Pair<String, String?>) =
        DesktopPaths(environment = { key -> environment.toMap()[key] }, userHome = home)

    @Test
    fun defaultsToTheXdgFallbackLocations() {
        val paths = paths()
        assertEquals(Path.of("/home/tester/.local/share/skyward"), paths.dataDir())
        assertEquals(Path.of("/home/tester/.config"), paths.configDir())
        assertEquals(Path.of("/home/tester/.config/autostart"), paths.autostartDir())
    }

    @Test
    fun honoursXdgOverrides() {
        val paths = paths("XDG_DATA_HOME" to "/data", "XDG_CONFIG_HOME" to "/config")
        assertEquals(Path.of("/data/skyward"), paths.dataDir())
        assertEquals(Path.of("/config/autostart"), paths.autostartDir())
    }

    @Test
    fun ignoresBlankAndRelativeXdgValues() {
        // The XDG spec says a relative path must be ignored; honouring one
        // would resolve the database against whatever directory the app
        // happened to be launched from.
        assertEquals(Path.of("/home/tester/.local/share/skyward"), paths("XDG_DATA_HOME" to "relative/path").dataDir())
        assertEquals(Path.of("/home/tester/.local/share/skyward"), paths("XDG_DATA_HOME" to "   ").dataDir())
    }

    @Test
    fun detectsFlatpakFromTheAppIdEnvironmentVariable() {
        assertFalse(paths().isFlatpak)
        assertFalse(paths("FLATPAK_ID" to "").isFlatpak)
        assertTrue(paths("FLATPAK_ID" to "dev.fritze.Skyward").isFlatpak)
    }

    @Test
    fun theAppIdFallsBackToTheKnownIdOutsideFlatpak() {
        assertEquals("dev.fritze.Skyward", paths().appId)
        assertEquals("dev.fritze.Skyward", paths("FLATPAK_ID" to "dev.fritze.Skyward").appId)
    }
}
