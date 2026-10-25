package dev.netrunner.os.montage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A montage: clips one after the other (a piece of a video, a photo shown for a while, a title
 * card), and music under them. Times are in milliseconds. Immutable: every change makes a new one.
 */
data class Project(
    val clips: List<Clip> = emptyList(),
    val music: Music? = null,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
) {
    val duration: Long get() = clips.sumOf { it.length }

    /** Where clip [i] starts in the montage. */
    fun startOf(i: Int): Long = clips.take(i).sumOf { it.length }

    /** The clip under [ms] and how far into it. */
    fun at(ms: Long): Pair<Int, Long>? {
        var t = 0L
        clips.forEachIndexed { i, c ->
            if (ms < t + c.length) return i to (ms - t).coerceAtLeast(0)
            t += c.length
        }
        return if (clips.isEmpty()) null else clips.lastIndex to clips.last().length
    }

    fun with(i: Int, c: Clip) = copy(clips = clips.toMutableList().also { it[i] = c })

    fun insert(at: Int, c: Clip) = copy(clips = clips.toMutableList().also { it.add(at.coerceIn(0, clips.size), c) })

    fun remove(i: Int) = copy(clips = clips.filterIndexed { k, _ -> k != i })

    fun move(from: Int, to: Int): Project {
        if (from !in clips.indices) return this
        val list = clips.toMutableList()
        val c = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), c)
        return copy(clips = list)
    }

    /** Cuts the clip under [ms] in two there; unchanged when [ms] is on a cut already. */
    fun split(ms: Long): Project {
        val (i, off) = at(ms) ?: return this
        val c = clips[i]
        if (off < MinLength || c.length - off < MinLength) return this
        val a = c.copy(end = c.start + off)
        val b = c.copy(start = c.start + off)
        return copy(clips = clips.toMutableList().also { it[i] = a; it.add(i + 1, b) })
    }

    companion object {
        const val MinLength = 200L
        const val Extension = "montage"

        fun read(f: File): Project {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            fun JsonObject.str(k: String) = this[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
            fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
            val base = f.absoluteFile.parentFile
            fun path(p: String?) = p?.let { if (File(it).isAbsolute) it else File(base, it).path }
            val clips = o["clips"]?.jsonArray.orEmpty().map { e ->
                val c = e.jsonObject
                Clip(
                    Clip.Kind.valueOf(c.str("kind") ?: "Video"), path(c.str("file")), c.long("start") ?: 0, c.long("end") ?: 3000,
                    c.str("text").orEmpty(), c["muted"]?.jsonPrimitive?.booleanOrNull ?: false, c.int("volume") ?: 100, c.long("sourceLength") ?: 0,
                )
            }
            val music = o["music"]?.takeIf { it != JsonNull }?.jsonObject?.let { m -> Music(path(m.str("file"))!!, m.int("volume") ?: 60) }
            return Project(clips, music, o.int("width") ?: 1920, o.int("height") ?: 1080, o.int("fps") ?: 30)
        }

        fun write(p: Project, f: File) {
            val json = JsonObject(
                mapOf(
                    "version" to JsonPrimitive(1),
                    "width" to JsonPrimitive(p.width), "height" to JsonPrimitive(p.height), "fps" to JsonPrimitive(p.fps),
                    "clips" to JsonArray(p.clips.map { c ->
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive(c.kind.name), "file" to (c.file?.let { JsonPrimitive(it) } ?: JsonNull),
                                "start" to JsonPrimitive(c.start), "end" to JsonPrimitive(c.end), "text" to JsonPrimitive(c.text),
                                "muted" to JsonPrimitive(c.muted), "volume" to JsonPrimitive(c.volume), "sourceLength" to JsonPrimitive(c.sourceLength),
                            ),
                        )
                    }),
                    "music" to (p.music?.let { JsonObject(mapOf("file" to JsonPrimitive(it.file), "volume" to JsonPrimitive(it.volume))) } ?: JsonNull),
                ),
            )
            val tmp = File(f.absoluteFile.parentFile, ".${f.name}.part")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) { f.writeText(json.toString()); tmp.delete() }
        }
    }
}

/**
 * One piece of the montage: [start] to [end] of a video (or of a photo's or title's own time,
 * from 0). [sourceLength] is the whole video's length, the limit for [end].
 */
data class Clip(
    val kind: Kind,
    val file: String? = null,
    val start: Long = 0,
    val end: Long = 4000,
    val text: String = "",
    val muted: Boolean = false,
    val volume: Int = 100,
    val sourceLength: Long = 0,
) {
    enum class Kind(val label: String) { Video("Vidéo"), Image("Photo"), Title("Titre") }

    val length: Long get() = end - start

    val name: String get() = when (kind) {
        Kind.Title -> "« ${text.lineSequence().firstOrNull().orEmpty().take(30)} »"
        else -> File(file.orEmpty()).name
    }

    /** New bounds, kept inside the source and at least [Project.MinLength] long. */
    fun trim(newStart: Long, newEnd: Long): Clip {
        val max = if (kind == Kind.Video && sourceLength > 0) sourceLength else Long.MAX_VALUE / 4
        val s = newStart.coerceIn(0, max - Project.MinLength)
        val e = newEnd.coerceIn(s + Project.MinLength, max)
        return if (kind == Kind.Video) copy(start = s, end = e) else copy(start = 0, end = e - s)
    }
}

data class Music(val file: String, val volume: Int = 60)

/** What ffprobe says of a file. */
data class Probe(val duration: Long, val video: Boolean, val audio: Boolean, val width: Int, val height: Int) {
    companion object {
        val Images = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        val Videos = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "mpg", "mpeg", "ts", "wmv", "3gp")
        val Sounds = setOf("mp3", "ogg", "opus", "flac", "wav", "m4a", "aac")

        fun of(f: File): Probe? = runCatching {
            val p = ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", f.path)
                .redirectErrorStream(false).start()
            val out = p.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) return@runCatching null
            val o = Json.parseToJsonElement(out).jsonObject
            val streams = o["streams"]?.jsonArray.orEmpty().map { it.jsonObject }
            val v = streams.firstOrNull { it["codec_type"]?.jsonPrimitive?.content == "video" && it["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull != 1 }
            val seconds = o["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            Probe(
                (seconds * 1000).toLong(), v != null, streams.any { it["codec_type"]?.jsonPrimitive?.content == "audio" },
                v?.get("width")?.jsonPrimitive?.intOrNull ?: 0, v?.get("height")?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.getOrNull()
    }
}

/** 75 500 ms as "1:15,5". */
fun clock(ms: Long, tenths: Boolean = true): String {
    val t = ms.coerceAtLeast(0)
    val s = t / 1000
    val base = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    return if (tenths) base + "," + (t % 1000 / 100) else base
}

/** "1:15,5", "75", "75,5" or "1:02:03" in milliseconds. */
fun parseClock(s: String): Long? {
    val parts = s.trim().replace(',', '.').split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { it.toDoubleOrNull() ?: return null }
    return (nums.fold(0.0) { acc, n -> acc * 60 + n } * 1000).toLong().takeIf { it >= 0 }
}
