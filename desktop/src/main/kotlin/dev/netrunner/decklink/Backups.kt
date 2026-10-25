package dev.netrunner.decklink

import dev.netrunner.sync.FileEntry
import dev.netrunner.sync.Hashes
import dev.netrunner.sync.SyncServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Dated copies of the kit, in `sauvegardes/` next to the sync folder. Each one is a zip in the
 * launcher's own backup format (kit/KitBackup.kt), so the phone restores it as it would one of
 * its own: from the PC link screen, or from a copied file. One is taken after each sync that
 * changed something; the newest [KEEP] are kept.
 */
class Backups(private val kit: File, private val dir: File) : SyncServer.Backups {
    data class Backup(val file: File, val created: Long, val files: Int, val bytes: Long)

    /** The kit's files, as KitBackup.isKitFile reads them on the phone. */
    private fun kitFiles(): List<File> {
        if (!kit.isDirectory) return emptyList()
        val top = KitFiles.map { File(kit, it) }.filter { it.isFile }
        val nested = KitDirs.flatMap { d -> File(kit, d).walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".tmp.jpg") && !it.name.endsWith(".part") }.toList() }
        return top + nested
    }

    /** What the kit holds now: names, sizes and dates, enough to tell whether a new copy is needed. */
    private fun fingerprint(files: List<File>): String {
        val d = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.path }.forEach { d.update("${it.relativeTo(kit).invariantSeparatorsPath}|${it.length()}|${it.lastModified()}\n".toByteArray()) }
        return Hashes.hex(d.digest())
    }

    fun backups(): List<Backup> = dir.listFiles { f -> f.isFile && f.name.startsWith("kit-") && f.name.endsWith(".zip") }.orEmpty()
        .mapNotNull { f -> runCatching { read(f) }.getOrNull() }
        .sortedByDescending { it.created }

    private fun read(f: File): Backup = ZipFile(f).use { z ->
        val m = z.getEntry(MANIFEST)?.let { e -> z.getInputStream(e).use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject } }
            ?: error("pas une sauvegarde")
        Backup(f, (m["created"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: f.lastModified(), z.size() - 1, f.length())
    }

    /**
     * Writes a new copy unless the newest one already holds the same kit ([force] writes it
     * anyway). Returns the new backup, or null when nothing changed or the kit is empty.
     */
    @Synchronized
    fun take(force: Boolean = false): Backup? {
        val files = kitFiles()
        if (files.isEmpty()) return null
        val print = fingerprint(files)
        if (!force && lastPrint() == print) return null
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val name = "kit-" + Name.format(LocalDateTime.now()) + ".zip"
        val tmp = File(dir, "$name.part")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            val manifest = JsonObject(
                mapOf(
                    "app" to JsonPrimitive("netrunner"), "kind" to JsonPrimitive("kit"), "format" to JsonPrimitive(1),
                    "created" to JsonPrimitive(now), "from" to JsonPrimitive("decklink"), "fingerprint" to JsonPrimitive(print),
                ),
            )
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            files.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(kit).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val out = File(dir, name)
        out.delete()
        if (!tmp.renameTo(out)) { tmp.delete(); error("impossible d'écrire $out") }
        File(dir, ".last").writeText(print)
        backups().drop(KEEP).forEach { it.file.delete() }
        return read(out)
    }

    private fun lastPrint(): String? = runCatching { File(dir, ".last").readText().trim() }.getOrNull()

    // ------------------------------------------------------------------ for the phone

    override fun list(): List<FileEntry> = backups().map { b ->
        FileEntry(b.file.name, b.bytes, hashes.getOrPut(b.file.name to b.file.lastModified()) { b.file.inputStream().use { Hashes.sha256(it) } })
    }

    override fun open(path: String): InputStream {
        val f = File(dir, path)
        require(f.parentFile == dir && f.name.startsWith("kit-") && f.name.endsWith(".zip") && f.isFile) { "sauvegarde inconnue" }
        return f.inputStream()
    }

    private val hashes = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, String>()

    companion object {
        const val KEEP = 30
        private const val MANIFEST = "netrunner-kit.json"
        /** Same lists as the launcher's KitBackup. */
        val KitFiles = listOf("kit.json", "kit_net.json", "kit_log.json", "kit_clients.json", "kit_stock.json", "kit_coverage.json")
        val KitDirs = listOf("logbook", "coverage", "clients")
        private val Name: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
