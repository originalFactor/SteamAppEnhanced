package cc.ptoe.steam.enhanced.store

import java.io.File
import java.io.IOException

/** The storage operations the account store depends on. */
interface StorageLike {
    fun dirExists(): Boolean
    fun ensureDir()
    fun fileExists(filename: String): Boolean
    fun readFile(filename: String): String
    fun writeFile(filename: String, contents: String)
    fun deleteFile(filename: String)
    fun lastModified(filename: String): Long?
    fun listFiles(extension: String = ".maFile"): List<String>
}

/**
 * Where the `maFiles/` directory lives.
 *
 * Ported from AVA's `services/storage_provider.dart`, minus the desktop variants —
 * this app targets Android only, so the directory is always the app's per-user
 * files directory.
 *
 * Writes and deletes are serialized per filename and files are replaced atomically
 * (write a unique temp sibling, flush, rename). Two concurrent saves — e.g. a
 * session refresh and an avatar refresh both persisting the manifest — must not
 * race on the temp file or lose an update, and a crash must leave the previous file
 * intact rather than a truncated one.
 */
class StorageProvider(private val root: File) : StorageLike {

    /** Absolute path to the `maFiles/` directory. */
    fun maFilesDir(): File = File(root, MA_FILES_DIR)

    fun manifestFile(): File = File(maFilesDir(), MANIFEST_NAME)

    fun fileFor(filename: String): File = File(maFilesDir(), sanitizeFilename(filename))

    override fun dirExists(): Boolean = maFilesDir().isDirectory

    override fun ensureDir() {
        val dir = maFilesDir()
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create ${dir.absolutePath}")
        }
    }

    override fun fileExists(filename: String): Boolean = fileFor(filename).isFile

    override fun readFile(filename: String): String = fileFor(filename).readText(Charsets.UTF_8)

    /**
     * Last modification time of [filename], or null when the file is missing.
     *
     * Rebuild/recovery paths use this to tell a live payload from a stale leftover
     * slot.
     */
    override fun lastModified(filename: String): Long? {
        val f = fileFor(filename)
        return if (f.isFile) f.lastModified() else null
    }

    override fun listFiles(extension: String): List<String> =
        maFilesDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(extension) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    override fun writeFile(filename: String, contents: String) = serialized(filename) {
        ensureDir()
        replaceFileAtomic(fileFor(filename), contents)
    }

    override fun deleteFile(filename: String) = serialized(filename) {
        val f = fileFor(filename)
        if (f.exists() && !f.delete()) throw IOException("cannot delete ${f.absolutePath}")
    }

    // Mutations are chained per filename so a queued write's exists-check and rename
    // cannot interleave with a delete and resurrect or drop a file mid-commit.
    private val chains = HashMap<String, Any>()

    private fun serialized(filename: String, action: () -> Unit) {
        val lock = synchronized(chains) { chains.getOrPut(filename) { Any() } }
        synchronized(lock) { action() }
    }

    companion object {
        private const val MA_FILES_DIR = "maFiles"
        private const val MANIFEST_NAME = "manifest.json"

        // A monotonic suffix so each in-flight write uses its own temp file (a shared
        // "$path.tmp" could be clobbered or renamed out from under a sibling write).
        private val tmpSeq = java.util.concurrent.atomic.AtomicLong()

        /**
         * Validates a filename that came (ultimately) from a manifest — an
         * attacker-tamperable file — before it is joined onto the maFiles directory
         * for a read, write or delete.
         *
         * A crafted `filename` like `../../x` or an absolute path would otherwise let
         * a tampered manifest reach files outside the `maFiles/` sandbox (path
         * traversal). Returns the name unchanged when safe; throws otherwise.
         */
        fun sanitizeFilename(name: String): String {
            val hasBadChar = name.any { it.code <= 0x20 || it.code == 0x7F }
            val bad = name.isEmpty() ||
                name == "." ||
                name == ".." ||
                hasBadChar ||
                name.contains('/') ||
                name.contains('\\') ||
                File(name).isAbsolute
            if (bad) throw IllegalArgumentException("unsafe storage filename: $name")
            return name
        }

        /**
         * Atomically replaces the file at absolute [path]: write a unique sibling temp
         * file (flushed), then rename over the target.
         *
         * A crash or partial write leaves the old file intact instead of a truncated
         * one; rename within a directory is atomic on the platforms we target.
         */
        fun replaceFileAtomic(path: File, contents: String) {
            val tmp = File("${path.absolutePath}.${tmpSeq.getAndIncrement()}.tmp")
            try {
                // Write and fsync through one handle: writing with writeText and then
                // reopening the file to sync would truncate it.
                java.io.FileOutputStream(tmp, false).use { out ->
                    out.write(contents.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (!tmp.renameTo(path)) throw IOException("cannot rename onto ${path.path}")
            } catch (e: Exception) {
                tmp.delete() // never leave a temp that could masquerade as data
                throw e
            }
        }
    }
}

/**
 * In-memory provider for tests.
 *
 * Treats an empty store as "no directory yet", so a load creates a fresh manifest
 * instead of throwing a parse exception.
 */
class MemoryStorageProvider : StorageLike {
    val files = LinkedHashMap<String, String>()
    private val writeSeq = HashMap<String, Long>()
    private var seq = 0L

    // A counter instead of a clock: real clocks tie within a test's timescale, and
    // lastModified is used for deterministic write ordering.
    override fun dirExists(): Boolean = files.isNotEmpty()
    override fun ensureDir() = Unit

    override fun fileExists(filename: String): Boolean =
        files.containsKey(StorageProvider.sanitizeFilename(filename))

    override fun readFile(filename: String): String =
        files[StorageProvider.sanitizeFilename(filename)]
            ?: throw IOException("no such file: $filename")

    override fun writeFile(filename: String, contents: String) {
        val name = StorageProvider.sanitizeFilename(filename)
        files[name] = contents
        writeSeq[name] = ++seq
    }

    override fun deleteFile(filename: String) {
        val name = StorageProvider.sanitizeFilename(filename)
        files.remove(name)
        writeSeq.remove(name)
    }

    override fun lastModified(filename: String): Long? =
        writeSeq[StorageProvider.sanitizeFilename(filename)]

    override fun listFiles(extension: String): List<String> =
        files.keys.filter { it.endsWith(extension) }.toList()
}
