package dev.netrunner.recorder.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.io.IOException

/** Recordings are .m4a files in the app's own storage, each with a ".wave" file of its levels
 * and, once transcribed, a ".txt". */
object Recordings {
    data class Item(val file: File, val name: String, val date: Long, val duration: Long, val size: Long)

    fun dir(context: Context): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) ?: File(context.filesDir, "recordings")).apply { mkdirs() }

    fun waveFile(audio: File) = File(audio.parentFile, audio.nameWithoutExtension + ".wave")

    /** Newest first. */
    fun all(context: Context): List<Item> =
        dir(context).listFiles { f -> f.isFile && f.extension == "m4a" }.orEmpty()
            .map { Item(it, it.nameWithoutExtension, it.lastModified(), duration(it), it.length()) }
            .sortedByDescending { it.date }

    fun levels(audio: File): ByteArray = runCatching { waveFile(audio).readBytes() }.getOrDefault(ByteArray(0))

    private fun duration(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.path)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    }.getOrDefault(0L)

    /** A free file for a new recording called [name]. */
    fun newFile(context: Context, name: String): File {
        val dir = dir(context)
        val clean = clean(name).ifEmpty { "Mémo" }
        var f = File(dir, "$clean.m4a")
        var n = 2
        while (f.exists()) f = File(dir, "$clean ($n).m4a").also { n++ }
        return f
    }

    fun rename(item: Item, name: String): File {
        val clean = clean(name)
        if (clean.isEmpty()) throw IOException("Nom invalide")
        val target = File(item.file.parentFile, "$clean.m4a")
        if (target.exists()) throw IOException("« $clean » existe déjà")
        if (!item.file.renameTo(target)) throw IOException("Renommage refusé")
        waveFile(item.file).renameTo(waveFile(target))
        Transcripts.file(item.file).renameTo(Transcripts.file(target))
        target.setLastModified(item.date)
        return target
    }

    fun delete(item: Item) {
        item.file.delete()
        waveFile(item.file).delete()
        Transcripts.file(item.file).delete()
    }

    private fun clean(name: String) = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
}
