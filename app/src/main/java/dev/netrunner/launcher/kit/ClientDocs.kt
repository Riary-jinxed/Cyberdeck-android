package dev.netrunner.launcher.kit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Offline documentation filed under each client: PDFs, wiring diagrams, procedures, photos of a
 * rack. Files are copied into files/clients/<client id>/, so they open without network, and
 * they are found by name or, for text files, by content.
 */
object ClientDocs {
    enum class Kind(val tag: String) { Pdf("PDF"), Image("IMG"), Text("TXT"), Other("DOC") }

    /** Bumped on each change, so lists read the folder again. */
    val revision = mutableIntStateOf(0)

    private const val MAX_BYTES = 100L * 1024 * 1024
    private val TextExt = setOf("txt", "md", "csv", "cfg", "conf", "log", "json", "xml", "ini", "yaml", "yml")

    private fun root(context: Context) = File(context.filesDir, "clients")
    fun dir(context: Context, clientId: String) = File(root(context), clientId)

    fun list(context: Context, clientId: String): List<File> =
        dir(context, clientId).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun kind(file: File): Kind = when (file.extension.lowercase(Locale.ROOT)) {
        "pdf" -> Kind.Pdf
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "svg" -> Kind.Image
        in TextExt -> Kind.Text
        else -> Kind.Other
    }

    /** Copies a picked file into the client's folder; returns false when it can't be read or is too big. */
    fun import(context: Context, clientId: String, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        var name = "document"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) return false
        val dir = dir(context, clientId).apply { mkdirs() }
        val clean = name.replace(Regex("""[/\\:*?"<>|\u0000]"""), "_").trim().trimStart('.').ifEmpty { "document" }
        var target = File(dir, clean)
        var n = 2
        while (target.exists()) {
            target = File(dir, "${clean.substringBeforeLast('.')} ($n)" + if (clean.contains('.')) ".${clean.substringAfterLast('.')}" else "")
            n++
        }
        val tmp = File(dir, target.name + ".part")
        resolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_BYTES) error("too big")
                    out.write(buf, 0, r)
                }
            }
        } ?: error("no stream")
        tmp.renameTo(target)
        revision.intValue++
        true
    }.getOrElse { false }

    fun delete(file: File) {
        file.delete()
        revision.intValue++
    }

    /** Removes everything filed under a client (when the sheet is deleted). */
    fun deleteAll(context: Context, clientId: String) {
        dir(context, clientId).deleteRecursively()
        revision.intValue++
    }

    /** Name match, or content match for small text files. */
    fun matches(file: File, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (file.name.contains(q, ignoreCase = true)) return true
        return kind(file) == Kind.Text && file.length() < 1_000_000 &&
            runCatching { file.readText().contains(q, ignoreCase = true) }.getOrDefault(false)
    }

    /** Documents of every client matching [query], for the search on the client list. */
    fun search(context: Context, query: String): List<Pair<Client, File>> =
        Clients.items.flatMap { c -> list(context, c.id).filter { matches(it, query) }.map { c to it } }

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "*/*"

    fun open(context: Context, file: File): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(context, file), mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun share(context: Context, file: File) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(mimeOf(file)).putExtra(Intent.EXTRA_STREAM, uriOf(context, file))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    file.name,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f Mo".format(bytes / 1_048_576f)
        bytes >= 1024 -> "${bytes / 1024} Ko"
        else -> "$bytes o"
    }
}
