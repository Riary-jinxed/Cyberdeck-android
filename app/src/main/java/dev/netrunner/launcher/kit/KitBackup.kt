package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole field kit in one zip file the user picks: inventory, checklists, cables, addressing,
 * racks, logbook with its photos, clients, stock and coverage surveys. Restoring replaces the
 * current kit; the launcher then restarts to read it back.
 */
object KitBackup {
    private const val MANIFEST = "netrunner-kit.json"
    private val Files = listOf("kit.json", "kit_net.json", "kit_log.json", "kit_clients.json", "kit_stock.json", "kit_coverage.json")
    private val Dirs = listOf("logbook", "coverage", "clients")
    /** A restore never writes more than this, whatever the archive claims. */
    private const val MAX_BYTES = 1L shl 30

    data class Summary(val files: Int, val bytes: Long)

    fun export(context: Context, uri: Uri): Summary? = runCatching {
        val root = context.filesDir
        var count = 0
        var bytes = 0L
        context.contentResolver.openOutputStream(uri, "wt")?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(JSONObject().put("app", "netrunner").put("kind", "kit").put("format", 1).put("created", System.currentTimeMillis()).toString().toByteArray())
                zip.closeEntry()
                val all = Files.map { File(root, it) }.filter { it.isFile } +
                    Dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".tmp.jpg") }.toList() }
                all.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                    bytes += f.length()
                }
            }
        } ?: error("no stream")
        Summary(count, bytes)
    }.getOrNull()

    /** True when [name] is a file this backup may write: a known JSON or something under a known folder. */
    private fun allowed(name: String): Boolean {
        if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." || it == "." || it.isEmpty() }) return false
        return name in Files || Dirs.any { name.startsWith("$it/") }
    }

    /**
     * Unpacks into a staging folder, checks it is a Netrunner kit, then swaps it in. The current
     * kit is left untouched if anything fails before the swap.
     */
    fun restore(context: Context, uri: Uri): Summary? {
        val root = context.filesDir
        val stage = File(context.cacheDir, "kit_restore").apply { deleteRecursively(); mkdirs() }
        return runCatching {
            var manifest = false
            var count = 0
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (entry.isDirectory) continue
                        if (name == MANIFEST) {
                            val m = JSONObject(zip.readBytes().decodeToString())
                            require(m.optString("app") == "netrunner" && m.optString("kind") == "kit")
                            manifest = true
                            continue
                        }
                        if (!allowed(name)) continue
                        val out = File(stage, name)
                        require(out.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= MAX_BYTES)
                                o.write(buffer, 0, n)
                            }
                        }
                        count++
                    }
                }
            } ?: error("no stream")
            require(manifest && count > 0)
            // Swap: the old kit goes, the staged one takes its place.
            Files.forEach { File(root, it).delete() }
            Dirs.forEach { File(root, it).deleteRecursively() }
            stage.listFiles()?.forEach { f -> f.copyRecursively(File(root, f.name), overwrite = true) }
            Summary(count, total)
        }.also { stage.deleteRecursively() }.getOrNull()
    }
}
