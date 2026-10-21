package dev.netrunner.launcher.aska

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** A LiteRT-LM model As//ka can run. All of them are Apache 2.0, downloadable without an account. */
data class AskaModel(
    val id: String,
    val label: String,
    val tagline: String,
    val url: String,
    val sizeMb: Int,
    /** Below this much RAM the phone will struggle (or kill the launcher). */
    val minRamGb: Int,
    /** Qwen 3 reasons out loud by default: turned off, it answers faster. */
    val thinks: Boolean = false,
) {
    val fileName: String get() = "$id.litertlm"
}

/** Where the model files live, and which one is active. */
object AskaModels {

    private const val HF = "https://huggingface.co/litert-community"

    val catalog = listOf(
        AskaModel(
            id = "gemma4-e2b",
            label = "GEMMA 4 · E2B",
            tagline = "Le plus vif en français. Pour les téléphones à 8 Go de RAM ou plus.",
            url = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeMb = 2590,
            minRamGb = 6,
        ),
        AskaModel(
            id = "qwen25-1.5b",
            label = "QWEN 2.5 · 1.5B",
            tagline = "Bon compromis entre vitesse et qualité.",
            url = "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeMb = 1600,
            minRamGb = 4,
        ),
        AskaModel(
            id = "qwen3-0.6b",
            label = "QWEN 3 · 0.6B",
            tagline = "Très léger et rapide, réponses plus simples.",
            url = "$HF/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sizeMb = 614,
            minRamGb = 3,
            thinks = true,
        ),
    )

    /** A model file the user picked on the phone. */
    const val CUSTOM_ID = "custom"

    fun dir(context: Context): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    fun file(context: Context, id: String): File = File(dir(context), "$id.litertlm")

    fun isInstalled(context: Context, id: String) = file(context, id).let { it.exists() && it.length() > 0 }

    fun installed(context: Context): List<String> =
        (catalog.map { it.id } + CUSTOM_ID).filter { isInstalled(context, it) }

    /** The model to load: the one picked last, or any installed one. */
    fun active(context: Context): String? {
        val picked = prefs(context).getString("aska_model", null)
        return picked?.takeIf { isInstalled(context, it) } ?: installed(context).firstOrNull()
    }

    fun select(context: Context, id: String) = prefs(context).edit().putString("aska_model", id).apply()

    fun labelOf(context: Context, id: String): String =
        catalog.firstOrNull { it.id == id }?.label
            ?: prefs(context).getString("aska_custom_name", null)?.uppercase()
            ?: "MODÈLE IMPORTÉ"

    fun thinks(id: String) = catalog.firstOrNull { it.id == id }?.thinks ?: false

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        if (prefs(context).getString("aska_model", null) == id) prefs(context).edit().remove("aska_model").apply()
    }

    // ---- Download, through Android's download manager: it survives the launcher, resumes and shows a notification.

    data class Download(val modelId: String, val done: Long, val total: Long, val status: Int)

    fun startDownload(context: Context, model: AskaModel, allowMobileData: Boolean) {
        val part = File(dir(context), model.fileName + ".part").apply { delete() }
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("As//ka · ${model.label}")
            .setDescription("Noyau local (${model.sizeMb} Mo)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        val id = manager(context).enqueue(request)
        prefs(context).edit().putLong("aska_dl_id", id).putString("aska_dl_model", model.id).apply()
    }

    /** State of the running download, or null. Finishes it (renames the file) once complete. */
    fun pollDownload(context: Context): Download? {
        val p = prefs(context)
        val id = p.getLong("aska_dl_id", -1L).takeIf { it >= 0 } ?: return null
        val modelId = p.getString("aska_dl_model", null) ?: return null
        val cursor = manager(context).query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                clearDownload(context)
                return null
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val model = catalog.first { m -> m.id == modelId }
                File(dir(context), model.fileName + ".part").renameTo(file(context, modelId))
                clearDownload(context)
                select(context, modelId)
            }
            return Download(modelId, done, total, status)
        }
    }

    fun cancelDownload(context: Context) {
        prefs(context).getLong("aska_dl_id", -1L).takeIf { it >= 0 }?.let { manager(context).remove(it) }
        clearDownload(context)
    }

    private fun clearDownload(context: Context) =
        prefs(context).edit().remove("aska_dl_id").remove("aska_dl_model").apply()

    // ---- Import of a .litertlm file already on the phone.

    /** Copies the picked file next to the others, reporting progress from 0 to 1. Runs on the caller's thread. */
    fun import(context: Context, uri: Uri, onProgress: (Float) -> Unit) {
        val resolver = context.contentResolver
        var name = "modèle importé"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { i -> i >= 0 }?.let { i -> name = it.getString(i) }
                it.getColumnIndex(OpenableColumns.SIZE).takeIf { i -> i >= 0 }?.let { i -> size = it.getLong(i) }
            }
        }
        require(name.endsWith(".litertlm", ignoreCase = true)) { "il faut un fichier .litertlm" }
        val target = file(context, CUSTOM_ID)
        val part = File(target.path + ".part")
        resolver.openInputStream(uri)!!.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (size > 0) onProgress(copied.toFloat() / size)
                }
            }
        }
        target.delete()
        part.renameTo(target)
        prefs(context).edit().putString("aska_custom_name", name.substringBeforeLast('.')).apply()
        select(context, CUSTOM_ID)
    }

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
