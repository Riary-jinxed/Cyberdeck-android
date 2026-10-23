package dev.netrunner.dates.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** A model she can run: LiteRT-LM (.litertlm) or llama.cpp (.gguf). All downloadable without an account. */
data class Model(
    val id: String,
    val label: String,
    val tagline: String,
    val url: String,
    val sizeMb: Int,
    /** Below this much RAM the phone will struggle (or kill the app). */
    val minRamGb: Int,
    /** Qwen 3 reasons out loud by default: turned off, it answers faster. */
    val thinks: Boolean = false,
) {
    val ext: String get() = url.substringAfterLast('.')
    val gguf: Boolean get() = ext == "gguf"
    val fileName: String get() = "$id.$ext"
}

/** Where the model files live, and which one is active. */
object Models {

    private const val HF = "https://huggingface.co"
    private val EXTS = listOf("litertlm", "gguf")

    /**
     * Q4_0 where the model has it: llama.cpp rearranges it for the phone's dot product
     * instructions when loading, which reads the prompt about twice as fast as Q4_K_M.
     * The GGUF links are checked by the build (dates.yml): a file renamed on Hugging Face fails
     * it, instead of failing on the phone.
     */
    val catalog = listOf(
        Model(
            id = "hermes3-3b",
            label = "HERMES 3 · LLAMA 3.2 3B",
            tagline = "GGUF. Fait pour le jeu de rôle : suit bien le personnage et la scène, peu de refus.",
            url = "$HF/bartowski/Hermes-3-Llama-3.2-3B-GGUF/resolve/main/Hermes-3-Llama-3.2-3B-Q4_0.gguf",
            sizeMb = 1830,
            minRamGb = 6,
        ),
        Model(
            id = "gemmasutra-2b",
            label = "GEMMASUTRA MINI · 2B",
            tagline = "GGUF. Petit modèle de jeu de rôle sans filtre, rapide. Français correct.",
            url = "$HF/bartowski/Gemmasutra-Mini-2B-v1-GGUF/resolve/main/Gemmasutra-Mini-2B-v1-Q4_K_M.gguf",
            sizeMb = 1710,
            minRamGb = 4,
        ),
        Model(
            id = "qwen25-3b",
            label = "QWEN 2.5 · 3B",
            tagline = "GGUF. Généraliste, bon en français, plus sage.",
            url = "$HF/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_0.gguf",
            sizeMb = 1745,
            minRamGb = 6,
        ),
        Model(
            id = "stheno-8b",
            label = "STHENO 3.2 · LLAMA 3 8B",
            tagline = "GGUF. Le plus riche en jeu de rôle, mais lourd et lent sur téléphone. Français moyen.",
            url = "$HF/bartowski/L3-8B-Stheno-v3.2-GGUF/resolve/main/L3-8B-Stheno-v3.2-Q4_K_M.gguf",
            sizeMb = 4920,
            minRamGb = 12,
        ),
        Model(
            id = "gemma4-e2b",
            label = "GEMMA 4 · E2B",
            tagline = "LiteRT. Celui du launcher : vif en français, sur GPU, mais prudent en jeu de rôle.",
            url = "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeMb = 2590,
            minRamGb = 6,
        ),
    )

    /** A model file the user picked on the phone, or downloaded from a link. */
    const val CUSTOM_ID = "custom"

    fun dir(context: Context): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    /** The file of [id]; for the custom model, whichever format it came in. */
    fun file(context: Context, id: String): File {
        catalog.firstOrNull { it.id == id }?.let { return File(dir(context), it.fileName) }
        return EXTS.map { File(dir(context), "$id.$it") }.firstOrNull { it.exists() } ?: File(dir(context), "$id.litertlm")
    }

    fun isInstalled(context: Context, id: String) = file(context, id).let { it.exists() && it.length() > 0 }

    fun installed(context: Context): List<String> =
        (catalog.map { it.id } + CUSTOM_ID).filter { isInstalled(context, it) }

    /** The model to load: the one picked last, or any installed one. */
    fun active(context: Context): String? {
        val picked = prefs(context).getString("model", null)
        return picked?.takeIf { isInstalled(context, it) } ?: installed(context).firstOrNull()
    }

    fun select(context: Context, id: String) = prefs(context).edit().putString("model", id).apply()

    fun labelOf(context: Context, id: String): String =
        catalog.firstOrNull { it.id == id }?.label
            ?: prefs(context).getString("custom_name", null)?.uppercase()
            ?: "MODÈLE IMPORTÉ"

    fun thinks(id: String) = catalog.firstOrNull { it.id == id }?.thinks ?: false

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        if (prefs(context).getString("model", null) == id) prefs(context).edit().remove("model").apply()
    }

    // ---- Download, through Android's download manager: it survives the app, resumes and shows a notification.

    data class Download(val modelId: String, val done: Long, val total: Long, val status: Int)

    fun startDownload(context: Context, model: Model, allowMobileData: Boolean) =
        enqueue(context, model.id, model.url, model.label, model.ext, allowMobileData)

    /** Any .gguf or .litertlm link (a Hugging Face "resolve" link, say): it becomes the custom model. */
    fun startDownloadUrl(context: Context, url: String, allowMobileData: Boolean) {
        val clean = url.trim().replace("/blob/", "/resolve/").substringBefore('?')
        val ext = clean.substringAfterLast('.').lowercase()
        require(clean.startsWith("https://") && ext in EXTS) { "il faut un lien https vers un fichier .gguf ou .litertlm" }
        val name = clean.substringAfterLast('/').substringBeforeLast('.')
        prefs(context).edit().putString("dl_name", name).apply()
        enqueue(context, CUSTOM_ID, clean, name, ext, allowMobileData)
    }

    private fun enqueue(context: Context, id: String, url: String, label: String, ext: String, allowMobileData: Boolean) {
        val part = File(dir(context), "$id.$ext.part").apply { delete() }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Rendez-vous · $label")
            .setDescription("Noyau local")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        val dl = manager(context).enqueue(request)
        prefs(context).edit().putLong("dl_id", dl).putString("dl_model", id).putString("dl_ext", ext).apply()
    }

    /** State of the running download, or null. Finishes it (renames the file) once complete. */
    fun pollDownload(context: Context): Download? {
        val p = prefs(context)
        val dl = p.getLong("dl_id", -1L).takeIf { it >= 0 } ?: return null
        val modelId = p.getString("dl_model", null) ?: return null
        val ext = p.getString("dl_ext", null) ?: "litertlm"
        val cursor = manager(context).query(DownloadManager.Query().setFilterById(dl)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                clearDownload(context)
                return null
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // One custom model at a time, whatever its format.
                if (modelId == CUSTOM_ID) {
                    EXTS.forEach { e -> File(dir(context), "$CUSTOM_ID.$e").delete() }
                    p.edit().putString("custom_name", p.getString("dl_name", null)).apply()
                }
                File(dir(context), "$modelId.$ext.part").renameTo(File(dir(context), "$modelId.$ext"))
                clearDownload(context)
                select(context, modelId)
            }
            return Download(modelId, done, total, status)
        }
    }

    fun cancelDownload(context: Context) {
        prefs(context).getLong("dl_id", -1L).takeIf { it >= 0 }?.let { manager(context).remove(it) }
        clearDownload(context)
    }

    private fun clearDownload(context: Context) =
        prefs(context).edit().remove("dl_id").remove("dl_model").remove("dl_ext").apply()

    // ---- Import of a model file already on the phone.

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
        val ext = name.substringAfterLast('.').lowercase()
        require(ext in EXTS) { "il faut un fichier .gguf ou .litertlm" }
        val target = File(dir(context), "$CUSTOM_ID.$ext")
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
        EXTS.forEach { e -> File(dir(context), "$CUSTOM_ID.$e").delete() }
        part.renameTo(target)
        prefs(context).edit().putString("custom_name", name.substringBeforeLast('.')).apply()
        select(context, CUSTOM_ID)
    }

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences("dates", Context.MODE_PRIVATE)
}
