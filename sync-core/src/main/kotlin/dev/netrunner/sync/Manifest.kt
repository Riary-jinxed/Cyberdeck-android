package dev.netrunner.sync

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One file of a synced folder, as listed by the phone. [path] is relative, with '/' separators. */
data class FileEntry(val path: String, val size: Long, val sha256: String)

object SafePath {
    /** True when [path] is a plain relative path that cannot leave the folder it is resolved in. */
    fun ok(path: String): Boolean =
        path.isNotEmpty() && path.length <= 512 && !path.startsWith("/") && !path.contains('\\') && !path.contains(':') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." } && path.none { it.code < 32 }

    /** [path] under [root], or null if it would land outside it. */
    fun resolve(root: File, path: String): File? {
        if (!ok(path)) return null
        val f = File(root, path)
        return f.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }
}

object Hashes {
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(chars[(b.toInt() shr 4) and 15]).append(chars[b.toInt() and 15])
        return out.toString()
    }
}

/**
 * Lists the files of a folder with their SHA-256. Hashes are remembered by size and date, so a
 * sync only re-reads the files that changed since the last one.
 */
class FolderScanner {
    private data class Seen(val size: Long, val modified: Long, val sha256: String)

    private val cache = HashMap<String, Seen>()

    @Synchronized
    fun scan(root: File, include: (String) -> Boolean = { true }): List<FileEntry> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<FileEntry>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val path = f.relativeTo(root).invariantSeparatorsPath
            if (!SafePath.ok(path) || !include(path)) return@forEach
            val size = f.length()
            val modified = f.lastModified()
            val known = cache[path]
            val sha = if (known != null && known.size == size && known.modified == modified) known.sha256
            else runCatching { Hashes.sha256(f.inputStream()) }.getOrNull() ?: return@forEach
            cache[path] = Seen(size, modified, sha)
            out += FileEntry(path, size, sha)
        }
        return out.sortedBy { it.path }
    }
}
