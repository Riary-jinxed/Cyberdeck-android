package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Her voice and her ears, offline: Piper with the French voice Siwis for speaking, whisper.cpp
 * for listening while the runner holds Ctrl+Alt+Espace. Both are downloaded once into her folder.
 */
object Voice {
    val home: File get() = File(Store.dir, "voice").also { it.mkdirs() }

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(10)).build()

    // ---- Speaking: Piper.

    private const val PIPER = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/"
    private const val SIWIS = "https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/siwis/medium/fr_FR-siwis-medium.onnx"
    /** A little higher than Siwis's own voice: synthesized slower, played faster by as much. */
    private const val PITCH = 1.09f

    private val voiceModel get() = File(home, "fr_FR-siwis-medium.onnx")
    private fun piper(): File? = find(File(home, "piper"), if (isWindows) "piper.exe" else "piper")

    val canSpeak get() = piper() != null && voiceModel.isFile && File(voiceModel.path + ".json").isFile

    suspend fun installSpeech(progress: (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        if (piper() == null) {
            val archive = File(home, if (isWindows) "piper.zip" else "piper.tar.gz")
            download(PIPER + if (isWindows) "piper_windows_amd64.zip" else "piper_linux_x86_64.tar.gz", archive) { progress("Téléchargement de Piper…", it) }
            progress("Installation de Piper…", null)
            unpack(archive, File(home, "piper"))
            piper()?.setExecutable(true)
        }
        if (!voiceModel.isFile) download(SIWIS, voiceModel) { progress("Téléchargement de la voix Siwis…", it) }
        val json = File(voiceModel.path + ".json")
        if (!json.isFile) download("$SIWIS.json", json) { progress("Téléchargement de la voix Siwis…", it) }
    }

    /** What she says aloud: her words without her gestures between asterisks. */
    fun spoken(text: String): String = text.replace(Regex("\\*[^*]*\\*"), " ").replace(Regex("[#`_~>|]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var talking: Process? = null

    /** Speaks [text], cutting what she was saying. Returns when she is done, or cut. */
    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        stop()
        val words = spoken(text).takeIf { it.length > 1 } ?: return@withContext
        val exe = piper() ?: return@withContext
        val rate = runCatching {
            Comfy.json.parseToJsonElement(File(voiceModel.path + ".json").readText()).jsonObject["audio"]?.jsonObject?.get("sample_rate")
                ?.let { (it as JsonPrimitive).content.toInt() }
        }.getOrNull() ?: 22_050
        val p = ProcessBuilder(exe.path, "--model", voiceModel.path, "--output_raw", "--length_scale", PITCH.toString())
            .directory(exe.parentFile).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        talking = p
        p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(words); it.newLine() }
        val format = AudioFormat(rate * PITCH, 16, 1, true, false)
        val out = AudioSystem.getSourceDataLine(format).also { it.open(format); it.start() }
        line = out
        try {
            val buffer = ByteArray(8192)
            val input = p.inputStream
            while (true) {
                val n = input.read(buffer)
                if (n < 0 || line !== out) break
                out.write(buffer, 0, n - n % 2)
            }
            if (line === out) out.drain()
        } finally {
            out.close()
            p.destroy()
            if (line === out) line = null
        }
    }

    fun stop() {
        line?.let { line = null; it.stop(); it.flush() }
        talking?.destroy()
    }

    // ---- Listening: whisper.cpp.

    private const val WHISPER_RELEASES = "https://api.github.com/repos/ggml-org/whisper.cpp/releases/latest"
    private const val WHISPER_MODEL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

    private val earModel get() = File(home, "ggml-small.bin")
    private fun whisper(): File? = find(File(home, "whisper"), if (isWindows) "whisper-cli.exe" else "whisper-cli")
        ?: find(File(home, "whisper"), if (isWindows) "main.exe" else "main")

    /** On Linux, a whisper-cli on the PATH works too (built by hand or from the distribution). */
    private fun whisperCommand(): String? = whisper()?.path ?: if (!isWindows && runCatching { ProcessBuilder("whisper-cli", "--help").start().waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) "whisper-cli" else null

    val canListen get() = whisperCommand() != null && earModel.isFile

    suspend fun installListening(progress: (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        if (whisperCommand() == null) {
            if (!isWindows) throw IllegalStateException("Sous Linux, installe whisper.cpp (whisper-cli dans le PATH), puis réessaie.")
            val release = http.send(HttpRequest.newBuilder(URI(WHISPER_RELEASES)).header("User-Agent", "Aska").build(), HttpResponse.BodyHandlers.ofString()).body()
            val assets = Comfy.json.parseToJsonElement(release).jsonObject["assets"] as? JsonArray ?: throw IllegalStateException("whisper.cpp introuvable sur GitHub.")
            val url = assets.map { it.jsonObject }.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == "whisper-bin-x64.zip" }
                ?.let { (it["browser_download_url"] as JsonPrimitive).content } ?: throw IllegalStateException("whisper-bin-x64.zip introuvable dans la dernière version.")
            val archive = File(home, "whisper.zip")
            download(url, archive) { progress("Téléchargement de whisper.cpp…", it) }
            progress("Installation de whisper.cpp…", null)
            unpack(archive, File(home, "whisper"))
        }
        if (!earModel.isFile) download(WHISPER_MODEL, earModel) { progress("Téléchargement de ses oreilles (466 Mo)…", it) }
    }

    private val micFormat = AudioFormat(16_000f, 16, 1, true, false)
    @Volatile private var mic: TargetDataLine? = null
    private var recorded = ByteArrayOutputStream()
    private var recorder: Thread? = null

    /** Starts recording the microphone; false when there is none. */
    fun startListening(): Boolean {
        if (mic != null) return true
        val m = runCatching { (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, micFormat)) as TargetDataLine).also { it.open(micFormat); it.start() } }.getOrNull() ?: return false
        mic = m
        recorded = ByteArrayOutputStream()
        val sink = recorded
        recorder = Thread {
            val buffer = ByteArray(4096)
            while (mic === m) {
                val n = m.read(buffer, 0, buffer.size)
                if (n > 0) sink.write(buffer, 0, n)
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    /** Stops recording and writes what the runner said; null when it was too short or unheard. */
    suspend fun stopListening(): String? = withContext(Dispatchers.IO) {
        val m = mic ?: return@withContext null
        mic = null
        m.stop()
        m.close()
        recorder?.join(500)
        val pcm = recorded.toByteArray()
        // Less than half a second: a key pressed by mistake.
        if (pcm.size < 16_000) return@withContext null
        val wav = File(home, "ecoute.wav")
        AudioSystem.write(AudioInputStream(pcm.inputStream(), micFormat, pcm.size / 2L), javax.sound.sampled.AudioFileFormat.Type.WAVE, wav)
        val command = whisperCommand() ?: return@withContext null
        val p = ProcessBuilder(command, "-m", earModel.path, "-f", wav.path, "-l", "fr", "-nt", "-np", "-t", Runtime.getRuntime().availableProcessors().coerceIn(2, 8).toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        p.waitFor(60, TimeUnit.SECONDS)
        cleanTranscript(text)
    }

    /** whisper.cpp marks silence and noises as [BLANK_AUDIO], (musique)…: they are not words. */
    fun cleanTranscript(raw: String): String? = raw.lines().joinToString(" ") { it.trim() }
        .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim().takeIf { it.length > 1 }

    // ---- Downloads.

    private fun download(url: String, to: File, progress: (Float?) -> Unit) {
        val part = File(to.path + ".part")
        val response = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "Aska").build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IllegalStateException("Téléchargement refusé (${response.statusCode()}) : $url")
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        response.body().use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                var shown = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - shown > 1_000_000) { shown = done; progress(if (total > 0) done.toFloat() / total else null) }
                }
            }
        }
        to.delete()
        if (!part.renameTo(to)) throw IllegalStateException("Impossible d'écrire ${to.name}.")
    }

    private fun unpack(archive: File, into: File) {
        into.mkdirs()
        if (archive.name.endsWith(".zip")) {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(into, entry.name).canonicalFile
                    // A name with « .. » must not land outside her folder.
                    if (!target.path.startsWith(into.canonicalPath)) continue
                    if (entry.isDirectory) target.mkdirs() else { target.parentFile.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                }
            }
        } else {
            val p = ProcessBuilder("tar", "xzf", archive.path, "-C", into.path).redirectErrorStream(true).start()
            if (p.waitFor() != 0) throw IllegalStateException("Archive illisible : ${archive.name}")
        }
        archive.delete()
    }

    private fun find(dir: File, name: String): File? = dir.takeIf { it.isDirectory }?.walkTopDown()?.maxDepth(4)?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
}
