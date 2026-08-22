package dev.fritze.skyward.desktop.data

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Owner-only file creation for everything the desktop app writes to disk.
 *
 * P1 makes this a privacy question rather than a filesystem detail: Skyward's
 * whole premise is that a user's saved locations never leave their machine,
 * and `~/.local/share/skyward/skyward.db` holds precise coordinates for every
 * place they care about. Created under the usual `umask 022` that file is
 * world-readable, so on a shared machine "never transmitted" (§10.2's own
 * disclosure wording) still leaves the data one `cat` away from every other
 * account on the box. §12 exports carry the same coordinates in plain JSON.
 *
 * Every operation here is best-effort by design: the permission bits are a
 * hardening measure, and failing to apply them must never stop the app from
 * opening its database or the user from taking a backup. On a filesystem with
 * no POSIX view at all (a FAT stick, a Windows share) there is nothing to set
 * and the write proceeds unchanged.
 */
internal object PrivateFiles {

    private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------")
    private val OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------")

    /**
     * Creates [directory] (and any missing parents) and restricts it to its
     * owner, returning it.
     *
     * Only the leaf gets the tight bits: the parents are shared XDG
     * directories (`~/.local/share`) that other applications keep their own
     * data in, and quietly making one of those owner-only would reach well
     * beyond this app's business.
     */
    fun createDirectory(directory: Path): Path {
        directory.parent?.let { runCatching { Files.createDirectories(it) } }
        try {
            // The permissions are passed as a creation attribute rather than
            // chmod-ed afterwards so the directory is never briefly readable.
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY))
            return directory
        } catch (e: FileAlreadyExistsException) {
            // This fires for two very different situations. Usually the
            // directory is simply already there — an install that predates
            // this hardening — and wants tightening rather than being left on
            // its old bits forever. But it also fires when something that is
            // not a directory occupies the path, and there the only honest
            // answer is the exception: chmod-ing a stray file to `rwx------`
            // would hand it an execute bit, and swallowing this would trade a
            // message naming the offending path for whichever vaguer one
            // SQLite eventually produces. `createDirectories` threw here
            // before this class existed; it still does.
            if (!Files.isDirectory(directory)) throw e
            restrict(directory, OWNER_ONLY_DIRECTORY)
            return directory
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem: nothing to set, but the directory still
            // has to exist.
            Files.createDirectories(directory)
            return directory
        }
    }

    /**
     * Ensures [file] exists with owner-only permissions, so that whatever
     * writes to it next — SQLite, a `writeText` — cannot leave a
     * world-readable file behind.
     */
    fun createFile(file: Path): Path {
        try {
            createOwnerOnly(file)
        } catch (e: FileAlreadyExistsException) {
            // As in [createDirectory]: the ordinary case is a file that is
            // already there, which is what this branch is for. A directory on
            // the path is not — `rw-------` would strip its execute bit and
            // make it untraversable, which is a good deal worse than the
            // world-readable bits this is here to remove — so that one is
            // reported rather than mangled.
            if (!Files.isRegularFile(file)) throw e
            restrict(file, OWNER_ONLY_FILE)
        } catch (e: IOException) {
            // Unwritable directory, say. The caller's own write will fail with
            // a message that actually explains the problem; swallowing it here
            // would replace that with a confusing one.
        }
        return file
    }

    /**
     * Creates [file] owner-only, or plainly on a filesystem with no POSIX
     * view.
     *
     * The fallback is nested inside the creation attempt rather than sitting
     * beside the [FileAlreadyExistsException] handler so that both routes
     * report an occupied path the same way. Catching it out there instead
     * would leave the non-POSIX route silently accepting a directory where
     * its POSIX twin raises.
     */
    private fun createOwnerOnly(file: Path) {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
        } catch (e: UnsupportedOperationException) {
            Files.createFile(file)
        }
    }

    /** [createFile] followed by the write, for §12's exports. */
    fun writeText(file: Path, text: String) {
        createFile(file)
        Files.newBufferedWriter(
            file,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { it.write(text) }
    }

    /** Tightens an existing path's permissions, ignoring filesystems that have none. */
    fun restrict(path: Path, permissions: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }

}
