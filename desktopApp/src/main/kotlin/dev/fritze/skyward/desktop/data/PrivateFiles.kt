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
            // Already there: an install that predates this hardening, so
            // tighten it rather than leaving the old bits in place forever --
            // but only if it really is a directory. This exception also fires
            // for a path occupied by something else entirely, and chmod-ing
            // that to `rwx------` would hand a stray file an execute bit on
            // the way to failing anyway.
            if (Files.isDirectory(directory)) restrict(directory, OWNER_ONLY_DIRECTORY)
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
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
        } catch (e: FileAlreadyExistsException) {
            // Regular files only. A directory sitting on this path would be
            // made untraversable by `rw-------`, which is a good deal worse
            // than the world-readable bits this is here to remove.
            restrictFile(file)
        } catch (e: UnsupportedOperationException) {
            runCatching { Files.createFile(file) }
        } catch (e: IOException) {
            // Unwritable directory, say. The caller's own write will fail with
            // a message that actually explains the problem; swallowing it here
            // would replace that with a confusing one.
        }
        return file
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

    /**
     * [restrict] with the owner-only file bits — for paths a library created
     * for us, such as SQLite's WAL sidecars.
     *
     * A path that is missing, or that is not a regular file, is left alone:
     * the bits only make sense for something whose contents this app is
     * responsible for.
     */
    fun restrictFile(path: Path) {
        if (Files.isRegularFile(path)) restrict(path, OWNER_ONLY_FILE)
    }
}
