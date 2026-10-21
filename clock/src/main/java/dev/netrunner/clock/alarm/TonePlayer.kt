package dev.netrunner.clock.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File

/**
 * Plays a tone on the alarm stream, looping the chosen excerpt, with the volume rising over
 * [crescendoMs]. Falls back to the phone's default alarm, then ringtone, if the file is gone.
 */
class TonePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L

    fun play(tone: String?, startMs: Long, endMs: Long, crescendoMs: Long, alarmStream: Boolean = true) {
        stop()
        val candidates = listOfNotNull(tone?.let(Uri::parse), Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI)
        for ((i, uri) in candidates.withIndex()) {
            val excerpt = i == 0 && tone != null
            val p = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(if (alarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(context, uri)
                    isLooping = !excerpt || (startMs == 0L && endMs == 0L)
                    prepare()
                }
            }.getOrNull() ?: continue
            player = p
            if (excerpt && startMs > 0) p.seekTo(startMs.toInt())
            if (excerpt && !p.isLooping) {
                p.setOnCompletionListener { it.seekTo(startMs.toInt()); it.start() }
                if (endMs > startMs) loopExcerpt(startMs, endMs)
            }
            startedAt = System.currentTimeMillis()
            if (crescendoMs > 0) rise(crescendoMs) else p.setVolume(1f, 1f)
            p.start()
            return
        }
    }

    private fun loopExcerpt(startMs: Long, endMs: Long) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val p = player ?: return
                if (runCatching { p.currentPosition }.getOrDefault(0) >= endMs) p.seekTo(startMs.toInt())
                handler.postDelayed(this, 200)
            }
        }, 200)
    }

    private fun rise(crescendoMs: Long) {
        player?.setVolume(MIN_VOLUME, MIN_VOLUME)
        handler.postDelayed(object : Runnable {
            override fun run() {
                val p = player ?: return
                val f = ((System.currentTimeMillis() - startedAt).toFloat() / crescendoMs).coerceIn(0f, 1f)
                // Squared, so it feels linear to the ear.
                val v = MIN_VOLUME + (1f - MIN_VOLUME) * f * f
                p.setVolume(v, v)
                if (f < 1f) handler.postDelayed(this, 500)
            }
        }, 500)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }
        player = null
    }

    companion object {
        private const val MIN_VOLUME = 0.05f

        private fun dir(context: Context) = File(context.filesDir, "tones").apply { mkdirs() }

        /**
         * Copies a picked audio file into the app, so it still rings if the original is moved or
         * the access grant lapses. Returns its URI, or null if it cannot be read.
         */
        fun import(context: Context, source: Uri): Uri? = runCatching {
            val name = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "musique"
            val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
            val file = File(dir(context), safe).let { if (it.exists()) File(dir(context), "${System.currentTimeMillis()}_$safe") else it }
            context.contentResolver.openInputStream(source)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(file)
        }.getOrNull()

        /** Imported music, newest first. */
        fun imported(context: Context): List<File> =
            dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        fun durationMs(context: Context, uri: Uri): Long = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                } finally {
                    release()
                }
            }
        }.getOrDefault(0L)

        /** A readable name for a tone URI. */
        fun title(context: Context, tone: String?): String {
            if (tone == null) return "Sonnerie d'alarme du téléphone"
            val uri = Uri.parse(tone)
            if (uri.scheme == "file") return File(uri.path ?: "").name.substringBeforeLast('.')
            return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: "Sonnerie"
        }
    }
}
