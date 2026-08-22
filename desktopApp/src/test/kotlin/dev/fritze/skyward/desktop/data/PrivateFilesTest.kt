package dev.fritze.skyward.desktop.data

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner-only permissions on everything the desktop app writes (issue #78).
 *
 * The assertions are skipped on a filesystem with no POSIX view, since there
 * is nothing to assert there — the app is Linux-only (§15.5) but the tests
 * shouldn't fail spuriously if someone runs them elsewhere.
 */
class PrivateFilesTest {

    private val posixSupported = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    private val temporaryDirectory: Path = createTempDirectory("skyward-private-files-test")

    @AfterTest
    fun cleanUp() {
        Files.walk(temporaryDirectory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun permissionsOf(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun createsTheDataDirectoryOwnerOnly() {
        val directory = PrivateFiles.createDirectory(temporaryDirectory.resolve("share/skyward"))

        assertTrue(Files.isDirectory(directory))
        if (posixSupported) assertEquals("rwx------", permissionsOf(directory))
    }

    @Test
    fun leavesTheSharedXdgParentAlone() {
        // `~/.local/share` belongs to every application on the machine, not to
        // Skyward — making it owner-only would reach well past this app.
        val parent = temporaryDirectory.resolve("share")
        PrivateFiles.createDirectory(parent.resolve("skyward"))

        assertTrue(Files.isDirectory(parent))
        if (posixSupported) assertTrue(permissionsOf(parent) != "rwx------", "the shared parent was tightened too")
    }

    @Test
    fun tightensADataDirectoryLeftOverFromBeforeThisHardening() {
        val directory = temporaryDirectory.resolve("legacy-skyward")
        Files.createDirectory(directory)
        if (posixSupported) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))

        PrivateFiles.createDirectory(directory)

        if (posixSupported) assertEquals("rwx------", permissionsOf(directory))
    }

    @Test
    fun createsAFileOwnerOnlyBeforeAnythingWritesToIt() {
        val file = PrivateFiles.createFile(temporaryDirectory.resolve("skyward.db"))

        assertTrue(Files.exists(file))
        assertEquals(0L, Files.size(file), "the placeholder must be empty — SQLite treats it as a fresh database")
        if (posixSupported) assertEquals("rw-------", permissionsOf(file))
    }

    @Test
    fun tightensAnExistingWorldReadableFile() {
        val file = temporaryDirectory.resolve("old-export.json")
        Files.writeString(file, "{}")
        if (posixSupported) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        PrivateFiles.createFile(file)

        assertEquals("{}", Files.readString(file), "an existing file's contents must survive")
        if (posixSupported) assertEquals("rw-------", permissionsOf(file))
    }

    @Test
    fun writesAnExportOwnerOnly() {
        val file = temporaryDirectory.resolve("export.json")

        PrivateFiles.writeText(file, """{"locations":[]}""")

        assertEquals("""{"locations":[]}""", Files.readString(file))
        if (posixSupported) assertEquals("rw-------", permissionsOf(file))
    }

    @Test
    fun overwritingAnExportLeavesNoTailOfTheOldOne() {
        val file = temporaryDirectory.resolve("export.json")
        PrivateFiles.writeText(file, "a-much-longer-previous-export")

        PrivateFiles.writeText(file, "short")

        assertEquals("short", Files.readString(file))
        if (posixSupported) assertEquals("rw-------", permissionsOf(file))
    }

    @Test
    fun restrictingAMissingFileIsANoOp() {
        // SQLite's -wal/-shm sidecars only exist once it has opened in WAL mode.
        PrivateFiles.restrictFile(temporaryDirectory.resolve("never-created-wal"))
    }
}
