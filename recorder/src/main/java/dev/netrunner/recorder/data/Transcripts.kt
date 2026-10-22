package dev.netrunner.recorder.data

import org.json.JSONObject
import java.io.File

/** A recording's transcript is a .txt file next to its audio. */
object Transcripts {
    fun file(audio: File) = File(audio.parentFile, audio.nameWithoutExtension + ".txt")

    fun read(audio: File): String? = file(audio).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun write(audio: File, text: String) = file(audio).writeText(text)

    /** The "text" of a Vosk result, empty when nothing was heard. */
    fun text(json: String): String = runCatching { JSONObject(json).optString("text") }.getOrDefault("").trim()

    /** Vosk gives one lower-case chunk per pause: each becomes a sentence. */
    fun sentences(chunks: List<String>): String = chunks.map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(" ") { c -> c.replaceFirstChar { it.titlecase() } + if (c.last() in ".?!") "" else "." }
}
