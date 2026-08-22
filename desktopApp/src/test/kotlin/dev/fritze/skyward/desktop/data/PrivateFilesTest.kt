package dev.fritze.skyward.desktop.data

import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        //
        // The parent's mode is set here rather than left to whatever umask the
        // machine running the tests happens to have, so the assertion below
        // means "unchanged" instead of "not the one value we'd object to".
        val parent = temporaryDirectory.resolve("share")
        Files.createDirectory(parent)
        if (posixSupported) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"))

        PrivateFiles.createDirectory(parent.resolve("skyward"))

        assertTrue(Files.isDirectory(parent))
        if (posixSupported) assertEquals("rwxr-xr-x", permissionsOf(parent))
    }

    @Test
    fun reportsAPathOccupiedByAFileInsteadOfChmoddingIt() {
        // The data dir's path taken by something that is not a directory.
        // `createDirectories` threw here before this class existed, and the
        // exception names the offending path — much better than tightening a
        // stray file to `rwx------`, handing it an execute bit, and leaving
        // SQLite to report the real problem in vaguer words later.
        val occupied = temporaryDirectory.resolve("not-a-directory")
        Files.writeString(occupied, "someone else's file")
        if (posixSupported) Files.setPosixFilePermissions(occupied, PosixFilePermissions.fromString("rw-r--r--"))

        assertFailsWith<FileAlreadyExistsException> { PrivateFiles.createDirectory(occupied) }

        assertEquals("someone else's file", Files.readString(occupied))
        if (posixSupported) assertEquals("rw-r--r--", permissionsOf(occupied))
    }

    @Test
    fun reportsAPathOccupiedByADirectoryInsteadOfMakingItUntraversable() {
        // The mirror case, and the worse one: `rw-------` on a directory
        // strips the execute bit and locks everyone out of it, which is a good
        // deal worse than the world-readable bits this is here to remove.
        val occupied = temporaryDirectory.resolve("not-a-file")
        Files.createDirectory(occupied)
        if (posixSupported) Files.setPosixFilePermissions(occupied, PosixFilePermissions.fromString("rwxr-xr-x"))

        assertFailsWith<FileAlreadyExistsException> { PrivateFiles.createFile(occupied) }

        assertTrue(Files.isDirectory(occupied))
        if (posixSupported) assertEquals("rwxr-xr-x", permissionsOf(occupied))
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

}
