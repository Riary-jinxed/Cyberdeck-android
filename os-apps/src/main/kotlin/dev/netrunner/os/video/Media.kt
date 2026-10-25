package dev.netrunner.os.video

import dev.netrunner.os.photos.Images
import java.io.File

/** Netrunner Vidéo's files: what it plays, the folder as a playlist, times as people write them. */
object Media {
    val Video = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "mpg", "mpeg", "ts", "wmv", "flv", "ogv", "3gp")
    val Audio = setOf("mp3", "flac", "ogg", "oga", "opus", "wav", "m4a", "aac", "wma")

    fun isMedia(f: File) = f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in Video + Audio
    fun isAudio(f: File) = f.extension.lowercase() in Audio

    /** The media of [file]'s folder in natural order, the first file when the argument is a folder. */
    fun playlist(arg: File): Pair<List<File>, Int> {
        val dir = if (arg.isDirectory) arg else arg.absoluteFile.parentFile ?: return listOf(arg) to 0
        val list = dir.listFiles().orEmpty().filter { isMedia(it) }.map { it.canonicalFile }.sortedWith(Images.Natural)
        if (arg.isDirectory) return list to 0
        val canonical = arg.canonicalFile
        return if (canonical in list) list to list.indexOf(canonical) else (listOf(canonical) + list) to 0
    }

    /** "1:02:03", "12:34" or "0:05". */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "83", "1:23", "1:02:03", "2m30", "1h05", "1h2m3s" → milliseconds; null when unreadable. */
    fun parseTime(text: String): Long? {
        val t = text.trim().lowercase().replace(" ", "")
        if (t.isEmpty()) return null
        if (Regex("\\d+(:\\d{1,2}){0,2}").matches(t)) {
            return t.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000
        }
        val m = Regex("(?:(\\d+)h)?(?:(\\d+)(?:m|min))?(?:(\\d+)s?)?").matchEntire(t) ?: return null
        val (h, min, s) = m.destructured
        if (h.isEmpty() && min.isEmpty() && s.isEmpty()) return null
        // "1h05": the number after an hour is minutes.
        val sec = if (h.isNotEmpty() && min.isEmpty() && s.isNotEmpty() && !t.endsWith("s")) s.toLong() * 60 else s.toLongOrNull() ?: 0
        return ((h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + sec) * 1000
    }
}
