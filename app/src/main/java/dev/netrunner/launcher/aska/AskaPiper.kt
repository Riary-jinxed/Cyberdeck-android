package dev.netrunner.launcher.aska

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Her neural voice: a Piper model (French, "upmc", speaker Jessica, CC BY-SA 4.0) run offline by sherpa-onnx.
 * Downloaded once from the sherpa-onnx releases, then unpacked next to the language models.
 */
object AskaPiper {

    private const val NAME = "vits-piper-fr_FR-upmc-medium"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$NAME.tar.bz2"
    const val SIZE_MB = 80

    fun dir(context: Context): File =
        (context.getExternalFilesDir("voices") ?: File(context.filesDir, "voices")).apply { mkdirs() }

    private fun home(context: Context) = File(dir(context), NAME)
    private fun archive(context: Context) = File(dir(context), "$NAME.tar.bz2")
    private fun marker(context: Context) = File(home(context), ".installed")

    /** Jessica, the first of the model's two speakers. */
    const val SPEAKER = 0

    fun model(context: Context) = File(home(context), "fr_FR-upmc-medium.onnx")
    fun tokens(context: Context) = File(home(context), "tokens.txt")
    fun espeakData(context: Context) = File(home(context), "espeak-ng-data")

    fun isInstalled(context: Context) = marker(context).exists() && model(context).exists()

    fun delete(context: Context) {
        home(context).deleteRecursively()
        archive(context).delete()
    }

    // ---- Download, like the models: through Android's download manager.

    fun startDownload(context: Context, allowMobileData: Boolean) {
        archive(context).delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("As//ka · voix neuronale")
            .setDescription("Voix française hors ligne ($SIZE_MB Mo)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(archive(context)))
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        val id = manager(context).enqueue(request)
        prefs(context).edit().putLong(KEY_DL, id).apply()
    }

    /** Bytes so far, total, and the download manager's status; null when nothing runs. */
    fun pollDownload(context: Context): Triple<Long, Long, Int>? {
        val id = prefs(context).getLong(KEY_DL, -1L).takeIf { it >= 0 } ?: return null
        val cursor = manager(context).query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                clearDownload(context)
                return null
            }
            return Triple(
                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            )
        }
    }

    fun cancelDownload(context: Context) {
        prefs(context).getLong(KEY_DL, -1L).takeIf { it >= 0 }?.let { manager(context).remove(it) }
        clearDownload(context)
        archive(context).delete()
    }

    fun clearDownload(context: Context) = prefs(context).edit().remove(KEY_DL).apply()

    /** True when a downloaded archive waits to be unpacked. */
    fun hasArchive(context: Context) = archive(context).let { it.exists() && it.length() > 0 }

    /** Progress of the unpacking, from 0 to 1, while it runs. */
    var progress by mutableStateOf<Float?>(null)
        private set

    /** Why the last unpacking failed. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Bumped each time an unpacking ends, for the screens to pick up the new voice. */
    var finished by mutableIntStateOf(0)
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aska-voice-unpack") }

    /**
     * Unpacks the downloaded archive on its own thread (about a minute: bzip2 is slow), once at
     * a time, whether or not the screen that asked stays open.
     */
    fun installInBackground(context: Context) {
        if (progress != null) return
        val app = context.applicationContext
        progress = 0f
        error = null
        worker.execute {
            runCatching { install(app) { progress = it } }.onFailure {
                delete(app)
                error = "Installation impossible : ${it.message ?: it.javaClass.simpleName}. Télécharge-la à nouveau."
            }
            progress = null
            finished++
        }
    }

    private fun install(context: Context, onProgress: (Float) -> Unit) {
        val source = archive(context)
        val root = dir(context).canonicalFile
        home(context).deleteRecursively()
        val total = source.length().coerceAtLeast(1)
        var read = 0L
        // Counted below the buffer: bzip2 reads byte by byte, the file is read in large blocks.
        val counting = object : FilterInputStream(source.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) read += it }
        }
        TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered(1 shl 16))).use { tar ->
            var last = -1
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                // Never write outside the voices folder, whatever the archive says.
                require(out.path.startsWith(root.path + File.separator)) { "archive invalide" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { copy(tar, it) }
                }
                val percent = (read * 100 / total).toInt()
                if (percent != last) {
                    last = percent
                    onProgress(percent / 100f)
                }
            }
        }
        require(model(context).exists() && tokens(context).exists()) { "voix incomplète" }
        marker(context).writeText("ok")
        source.delete()
    }

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
        }
    }

    private const val KEY_DL = "aska_piper_dl"

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
