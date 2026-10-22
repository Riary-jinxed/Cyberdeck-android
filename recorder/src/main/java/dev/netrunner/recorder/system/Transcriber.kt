package dev.netrunner.recorder.system

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.netrunner.recorder.data.NotesLink
import dev.netrunner.recorder.data.RecorderPrefs
import dev.netrunner.recorder.data.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Speech to text on the phone with Vosk: the French model is downloaded once (about 40 Mo),
 * then nothing leaves the device. Jobs outlive the screen that started them.
 */
object Transcriber {
    sealed interface ModelState {
        data object Missing : ModelState
        data class Downloading(val progress: Float) : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
    const val MODEL_SIZE_MB = 41

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _model = MutableStateFlow<ModelState?>(null)
    val model: StateFlow<ModelState?> = _model.asStateFlow()

    /** Recordings being transcribed, by path, with their progress (0..1). */
    private val _jobs = MutableStateFlow<Map<String, Float>>(emptyMap())
    val jobs: StateFlow<Map<String, Float>> = _jobs.asStateFlow()

    /** Bumped each time a transcript is written, so screens re-read it. */
    private val _done = MutableStateFlow(0)
    val done: StateFlow<Int> = _done.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    private var download: Job? = null
    private var loaded: Model? = null

    private fun dir(context: Context) = File(context.filesDir, "vosk-fr")

    fun refresh(context: Context) {
        if (_model.value is ModelState.Downloading) return
        _model.value = if (File(dir(context), "conf").isDirectory) ModelState.Ready else ModelState.Missing
    }

    fun downloadModel(context: Context) {
        if (download?.isActive == true) return
        val app = context.applicationContext
        _model.value = ModelState.Downloading(0f)
        download = scope.launch(Dispatchers.IO) {
            _model.value = try {
                fetch(app)
                ModelState.Ready
            } catch (e: Exception) {
                ModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun deleteModel(context: Context) {
        download?.cancel()
        synchronized(this) { loaded?.close(); loaded = null }
        dir(context).deleteRecursively()
        _model.value = ModelState.Missing
    }

    private suspend fun fetch(context: Context) {
        val target = dir(context)
        val tmp = File(context.filesDir, "vosk-fr.part").apply { deleteRecursively(); mkdirs() }
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("Serveur du modèle : erreur ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (MODEL_SIZE_MB * 1_048_576L)
            val counting = CountingStream(conn.inputStream) { read -> _model.value = ModelState.Downloading((read.toFloat() / total).coerceAtMost(1f)) }
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!coroutineContext.isActive) throw IOException("Annulé")
                    // The archive holds one top folder ("vosk-model-small-fr-0.22/…"): drop it.
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isEmpty() || rel.contains("..")) continue
                    val out = File(tmp, rel)
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!File(tmp, "conf").isDirectory) {
            tmp.deleteRecursively()
            throw IOException("Archive du modèle inattendue")
        }
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("Installation du modèle impossible")
    }

    private class CountingStream(private val base: java.io.InputStream, private val onRead: (Long) -> Unit) : java.io.InputStream() {
        private var count = 0L
        private var reported = 0L
        override fun read(): Int = base.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = base.read(b, off, len).also { if (it > 0) add(it.toLong()) }
        override fun close() = base.close()
        private fun add(n: Long) {
            count += n
            if (count - reported > 256 * 1024) { reported = count; onRead(count) }
        }
    }

    @Synchronized
    private fun loadModel(context: Context): Model {
        loaded?.let { return it }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(dir(context).path).also { loaded = it }
    }

    /** Transcribes [audio] in the background; with [toNotes] the result also lands in Notes. */
    fun transcribe(context: Context, audio: File, toNotes: Boolean = RecorderPrefs.autoNotes(context)) {
        val key = audio.path
        if (key in _jobs.value) return
        val app = context.applicationContext
        _jobs.update { it + (key to 0f) }
        scope.launch {
            try {
                val text = run(app, audio) { p -> _jobs.update { it + (key to p) } }
                Transcripts.write(audio, text)
                if (toNotes && text.isNotBlank() && NotesLink.add(app, audio.nameWithoutExtension, text) == null) {
                    _errors.value = "Transcription faite, mais l'appli Notes est introuvable"
                }
                _done.update { it + 1 }
            } catch (e: Exception) {
                _errors.value = "Transcription impossible : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _jobs.update { it - key }
            }
        }
    }

    fun clearError() { _errors.value = null }

    /** Decodes the AAC track to 16-bit mono PCM and feeds it to the recognizer. */
    private suspend fun run(context: Context, audio: File, progress: (Float) -> Unit): String {
        val extractor = MediaExtractor()
        extractor.setDataSource(audio.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("Pas de piste audio")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val chunks = mutableListOf<String>()
        var recognizer: Recognizer? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!coroutineContext.isActive) throw IOException("Annulé")
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.let {
                            rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val pcm = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = pcm.remaining() / channels
                            val mono = ShortArray(frames)
                            for (f in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += pcm.get(f * channels + c)
                                mono[f] = (sum / channels).toShort()
                            }
                            // Vosk resamples to the model's 16 kHz itself.
                            val rec = recognizer ?: Recognizer(loadModel(context), rate.toFloat()).also { recognizer = it }
                            if (rec.acceptWaveForm(mono, mono.size)) chunks += Transcripts.text(rec.result)
                            if (duration > 0) progress((info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            recognizer?.let { chunks += Transcripts.text(it.finalResult) }
        } finally {
            recognizer?.close()
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return Transcripts.sentences(chunks)
    }
}
