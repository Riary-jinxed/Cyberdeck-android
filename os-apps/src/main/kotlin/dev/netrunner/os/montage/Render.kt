package dev.netrunner.os.montage

import java.io.File

/**
 * The montage made into a video by ffmpeg: every clip brought to the same size, rate and sound
 * format, put end to end, the music mixed under them. H.264 and AAC in MP4, which every phone,
 * TV and site reads.
 */
object Render {
    enum class Quality(val label: String, val height: Int, val crf: Int) {
        High("1080p", 1080, 20), Medium("720p", 720, 23), Small("480p, léger", 480, 26),
    }

    /** The ffmpeg command for [p] into [out]; title texts are written in [work], drawn with [font]. */
    fun command(p: Project, out: File, quality: Quality, font: File, work: File): List<String> {
        require(p.clips.isNotEmpty()) { "Le montage est vide." }
        val h = minOf(quality.height, p.height).let { it - it % 2 }
        val w = (p.width.toLong() * h / p.height).toInt().let { it - it % 2 }
        val args = mutableListOf("ffmpeg", "-hide_banner", "-nostdin", "-y")
        val filters = mutableListOf<String>()
        var input = 0
        fun sec(ms: Long) = "%.3f".format(java.util.Locale.ROOT, ms / 1000.0)
        val pairs = StringBuilder()
        p.clips.forEachIndexed { k, c ->
            val d = sec(c.length)
            val v = input++
            when (c.kind) {
                Clip.Kind.Video -> args += listOf("-ss", sec(c.start), "-t", d, "-i", c.file!!)
                Clip.Kind.Image -> args += listOf("-loop", "1", "-framerate", p.fps.toString(), "-t", d, "-i", c.file!!)
                Clip.Kind.Title -> args += listOf("-f", "lavfi", "-t", d, "-i", "color=c=0x07070D:s=${w}x$h:r=${p.fps}")
            }
            val fit = "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=${p.fps},format=yuv420p"
            filters += when (c.kind) {
                Clip.Kind.Title -> {
                    // The text goes through a file: nothing in it needs escaping for ffmpeg.
                    val txt = File(work, "titre-$k.txt").apply { writeText(c.text.ifBlank { " " }) }
                    "[$v:v]drawtext=fontfile='${font.path.replace("'", "")}':textfile='${txt.path.replace("'", "")}':fontcolor=0xFCEE0A:fontsize=${h / 11}:line_spacing=${h / 40}" +
                        ":x=(w-text_w)/2:y=(h-text_h)/2,setsar=1,format=yuv420p,trim=duration=$d,setpts=PTS-STARTPTS[v$k]"
                }
                else -> "[$v:v]$fit,trim=duration=$d,setpts=PTS-STARTPTS[v$k]"
            }
            val hasSound = c.kind == Clip.Kind.Video && !c.muted && c.volume > 0 && (Probe.of(File(c.file!!))?.audio ?: false)
            val a = if (hasSound) "$v:a" else {
                args += listOf("-f", "lavfi", "-t", d, "-i", "anullsrc=r=48000:cl=stereo")
                "${input++}:a"
            }
            val vol = if (hasSound && c.volume != 100) ",volume=${c.volume / 100.0}" else ""
            filters += "[$a]aformat=sample_rates=48000:channel_layouts=stereo$vol,apad,atrim=duration=$d,asetpts=PTS-STARTPTS[a$k]"
            pairs.append("[v$k][a$k]")
        }
        filters += "${pairs}concat=n=${p.clips.size}:v=1:a=1[v][cat]"
        val music = p.music?.takeIf { File(it.file).isFile && it.volume > 0 }
        if (music != null) {
            args += listOf("-stream_loop", "-1", "-i", music.file)
            filters += "[${input++}:a]aformat=sample_rates=48000:channel_layouts=stereo,volume=${music.volume / 100.0}[mus]"
            // The music fades out over the last two seconds.
            val fade = maxOf(0L, p.duration - 2000)
            filters += "[mus]atrim=duration=${sec(p.duration)},afade=t=out:st=${sec(fade)}:d=2[mus2]"
            filters += "[cat][mus2]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]"
        } else {
            filters += "[cat]anull[a]"
        }
        args += listOf("-filter_complex", filters.joinToString(";"))
        args += listOf("-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "veryfast", "-crf", quality.crf.toString(), "-pix_fmt", "yuv420p")
        args += listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-progress", "pipe:1", "-nostats", out.path)
        return args
    }

    /** Runs [command]; [progress] gets 0 to 1. Null when done, else what went wrong. */
    fun run(command: List<String>, total: Long, progress: (Float) -> Unit, cancelled: () -> Boolean = { false }): String? {
        val proc = runCatching { ProcessBuilder(command).redirectErrorStream(false).start() }.getOrElse { return "ffmpeg introuvable : ${it.message}" }
        val errors = StringBuilder()
        val err = Thread { proc.errorStream.bufferedReader().forEachLine { synchronized(errors) { errors.appendLine(it); if (errors.length > 20_000) errors.delete(0, 10_000) } } }.apply { isDaemon = true; start() }
        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (cancelled()) { proc.destroy(); break }
                val us = line.substringAfter("out_time_us=", "").ifEmpty { line.substringAfter("out_time_ms=", "") }.toLongOrNull()
                if (us != null && total > 0) progress((us / 1000f / total).coerceIn(0f, 1f))
            }
        }
        val code = proc.waitFor()
        err.join(2000)
        if (cancelled()) return "Export annulé."
        if (code == 0) { progress(1f); return null }
        val last = synchronized(errors) { errors.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n") }
        return "ffmpeg a échoué ($code) : $last"
    }
}
