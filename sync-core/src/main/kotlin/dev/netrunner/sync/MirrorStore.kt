package dev.netrunner.sync

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The PC's copy of a phone folder. Files arrive in a staging area and only replace the copy once
 * their SHA-256 matches; files gone from the phone are moved to `.corbeille/<date>/`, never
 * deleted, so a wrong sync can always be undone by hand.
 */
class MirrorStore(val root: File) {
    private val scanner = FolderScanner()
    private val staging get() = File(root, "$INTERNAL/incoming")

    data class Plan(val wanted: List<FileEntry>, val removed: List<String>)

    fun local(): List<FileEntry> = scanner.scan(root) { !it.startsWith("$INTERNAL/") && !it.startsWith("$TRASH/") }

    fun plan(remote: List<FileEntry>): Plan {
        val mine = local().associateBy { it.path }
        val wanted = remote.filter { SafePath.ok(it.path) && !isReserved(it.path) && mine[it.path]?.sha256 != it.sha256 }
        val theirs = remote.mapTo(HashSet()) { it.path }
        return Plan(wanted, mine.keys.filter { it !in theirs }.sorted())
    }

    /**
     * Copies [size] bytes from [chunks] into [path]. Returns false (copy untouched) when the
     * content does not hash to [sha256], e.g. the file changed on the phone while it was sent.
     */
    fun receive(path: String, size: Long, sha256: String, chunks: () -> ByteArray): Boolean {
        val target = SafePath.resolve(root, path)?.takeIf { !isReserved(path) }
        staging.mkdirs()
        val part = File.createTempFile("incoming", ".part", staging)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            part.outputStream().buffered(1 shl 16).use { out ->
                var left = size
                while (left > 0) {
                    val chunk = chunks()
                    if (chunk.isEmpty() || chunk.size > left) throw java.io.IOException("taille inattendue pour $path")
                    out.write(chunk)
                    digest.update(chunk)
                    left -= chunk.size
                }
            }
            if (target == null || Hashes.hex(digest.digest()) != sha256) return false
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                }
            }
            return true
        } finally {
            part.delete()
        }
    }

    /** Moves [paths] to the trash folder; returns how many moved. */
    fun remove(paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        val bin = File(root, "$TRASH/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")))
        var n = 0
        for (p in paths) {
            val f = SafePath.resolve(root, p) ?: continue
            if (!f.isFile) continue
            val dest = File(bin, p).apply { parentFile?.mkdirs() }
            if (f.renameTo(dest) || runCatching { f.copyTo(dest, overwrite = true); f.delete() }.getOrDefault(false)) n++
            // Drop the folders the move left empty, up to the root.
            var dir = f.parentFile
            while (dir != null && dir != root && dir.listFiles()?.isEmpty() == true) { dir.delete(); dir = dir.parentFile }
        }
        return n
    }

    companion object {
        /** Bookkeeping of the app itself, never synced. */
        const val INTERNAL = ".decklink"
        const val TRASH = ".corbeille"

        private fun isReserved(path: String) = path.startsWith("$INTERNAL/") || path.startsWith("$TRASH/") || path == INTERNAL || path == TRASH

        /** Reads the raw bytes of a file for [FileStart]: chunks of at most [SecureChannel.MAX_FRAME]. */
        fun chunks(input: InputStream, each: (ByteArray, Int) -> Unit) {
            val buffer = ByteArray(256 * 1024)
            input.use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    if (n > 0) each(buffer, n)
                }
            }
        }
    }
}
