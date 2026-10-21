package dev.netrunner.launcher.aska

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * As//ka's voice: Android's offline speech synthesis, run through a small effects chain
 * (ring modulator, bit crusher, chorus, echo) so it sounds like a game AI rather than a GPS.
 * The speech engine is only bound while her panel is open.
 */
class AskaVoice(private val context: Context, private val scope: CoroutineScope) {

    /** Effect settings for one voice pack. */
    class Fx(
        val ringHz: Float = 0f,
        val ringMix: Float = 0f,
        val bits: Int = 16,
        val downsample: Int = 1,
        val drive: Float = 0f,
        val chorusMix: Float = 0f,
        val echoMs: Int = 0,
        val echoGain: Float = 0f,
        val highpass: Float = 0f,
    )

    enum class Pack(val label: String, val detail: String, val female: Boolean, val pitch: Float, val rate: Float, val fx: Fx) {
        Spectre("SPECTRE", "féminine, synthétique et nette", true, 1.06f, 1.0f,
            Fx(ringHz = 55f, ringMix = 0.22f, bits = 11, chorusMix = 0.25f, echoMs = 75, echoGain = 0.22f, highpass = 0.15f)),
        Oracle("ORACLE", "féminine, douce, réverbérée", true, 1.0f, 0.95f,
            Fx(chorusMix = 0.4f, echoMs = 120, echoGain = 0.3f)),
        Chrome("CHROME", "masculine, métallique", false, 0.86f, 1.0f,
            Fx(ringHz = 32f, ringMix = 0.3f, bits = 9, downsample = 2, drive = 2.2f, echoMs = 60, echoGain = 0.18f, highpass = 0.2f)),
        Construct("CONSTRUCT", "masculine, grave, radio corpo", false, 0.72f, 0.92f,
            Fx(drive = 3f, chorusMix = 0.3f, echoMs = 95, echoGain = 0.25f, highpass = 0.35f)),
        Raw("BRUTE", "voix système sans filtre", true, 1.0f, 1.0f, Fx());

        companion object {
            fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Spectre
        }
    }

    private val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("aska_voice_on", true))
        private set

    var pack by mutableStateOf(Pack.of(prefs.getString("aska_voice_pack", null)))
        private set

    /** True while she is speaking, for the sigil and the stop button. */
    var speaking by mutableStateOf(false)
        private set

    /** Shown when the phone has no usable speech engine. */
    var problem by mutableStateOf<String?>(null)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var job: Job? = null
    private var track: AudioTrack? = null
    // Filled on the main thread, completed on the speech engine's thread.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    fun toggle() {
        enabled = !enabled
        prefs.edit().putBoolean("aska_voice_on", enabled).apply()
        if (!enabled) stop()
    }

    fun select(pack: Pack) {
        this.pack = pack
        prefs.edit().putString("aska_voice_pack", pack.name).apply()
    }

    /** Reads [text] aloud, interrupting whatever she was saying. */
    fun speak(text: String, force: Boolean = false) {
        if (!enabled && !force) return
        val clean = forSpeech(text)
        if (clean.isBlank()) return
        stop()
        job = scope.launch {
            val engine = engine() ?: return@launch
            speaking = true
            try {
                val p = pack
                var playing: Job? = null
                // Sentence by sentence: the first one plays while the next is synthesized.
                for (sentence in sentences(clean)) {
                    val pcm = synthesize(engine, sentence, p) ?: break
                    val processed = withContext(Dispatchers.Default) { process(pcm, p.fx) }
                    playing?.join()
                    if (!isActive) break
                    playing = launch { play(processed) }
                }
                playing?.join()
            } finally {
                speaking = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        speaking = false
    }

    /** Frees the speech engine (panel closed). */
    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    // ---- Speech engine.

    private suspend fun engine(): TextToSpeech? {
        tts?.let { if (ready) return it }
        val engine = suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context.applicationContext) { status ->
                if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) created else null)
            }
        } ?: run {
            problem = "Aucun moteur de synthèse vocale. Installe « Services vocaux Google » depuis le Play Store."
            return null
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.invoke(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.invoke(false) }
            override fun onError(utteranceId: String, errorCode: Int) { pending.remove(utteranceId)?.invoke(false) }
        })
        val french = Locale.FRANCE
        if (engine.isLanguageAvailable(french) < TextToSpeech.LANG_AVAILABLE) {
            problem = "Voix française absente : Paramètres › Synthèse vocale › installe le pack français."
        } else {
            problem = null
            engine.setLanguage(french)
        }
        tts = engine
        ready = true
        return engine
    }

    /** Picks a French voice of the pack's gender. Engines don't expose gender, so names decide. */
    private fun pickVoice(engine: TextToSpeech, female: Boolean): Voice? {
        val french = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        if (french.isEmpty()) return null
        fun isMale(v: Voice) = v.name.contains("male", true) && !v.name.contains("female", true) ||
            MALE_GOOGLE.any { v.name.contains(it, true) }
        val wanted = french.filter { isMale(it) != female }
        return (wanted.ifEmpty { french }).sortedByDescending { it.quality }.first()
    }

    private suspend fun synthesize(engine: TextToSpeech, text: String, pack: Pack): Pcm? {
        engine.setPitch(pack.pitch)
        engine.setSpeechRate(pack.rate)
        pickVoice(engine, pack.female)?.let { runCatching { engine.setVoice(it) } }
        val file = File(context.cacheDir, "aska_voice_${System.nanoTime()}.wav")
        val id = file.name
        val ok = suspendCancellableCoroutine { cont ->
            pending[id] = { success -> if (cont.isActive) cont.resume(success) }
            cont.invokeOnCancellation { pending.remove(id) }
            if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                cont.resume(false)
            }
        }
        return try {
            if (ok) withContext(Dispatchers.IO) { readWav(file) } else null
        } finally {
            file.delete()
        }
    }

    private suspend fun play(pcm: Pcm) {
        val bytes = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.samples.forEach { bytes.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes.capacity().coerceAtLeast(4))
            .build()
        track = audio
        try {
            audio.write(bytes.array(), 0, bytes.capacity())
            audio.play()
            delay(pcm.samples.size * 1000L / pcm.rate + 60)
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
            if (track === audio) track = null
        }
    }

    // ---- Audio.

    private class Pcm(val samples: FloatArray, val rate: Int)

    /** Reads a PCM 16-bit WAV (mono or stereo, mixed down). */
    private fun readWav(file: File): Pcm? {
        val data = file.readBytes()
        if (data.size < 44) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 1
        var rate = 22050
        var bits = 16
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "fmt ") {
                channels = buf.getShort(pos + 10).toInt()
                rate = buf.getInt(pos + 12)
                bits = buf.getShort(pos + 22).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val end = minOf(data.size, pos + 8 + if (size > 0) size else data.size)
                val frames = (end - pos - 8) / (2 * channels)
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += buf.getShort(pos + 8 + (i * channels + c) * 2) / 32768f
                    out[i] = sum / channels
                }
                return Pcm(out, rate)
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun process(pcm: Pcm, fx: Fx): Pcm {
        val x = pcm.samples
        val rate = pcm.rate
        val n = x.size
        // Tail for the echo.
        val tail = if (fx.echoMs > 0) rate * fx.echoMs * 3 / 1000 else 0
        val y = FloatArray(n + tail)
        System.arraycopy(x, 0, y, 0, n)

        if (fx.highpass > 0f) {
            // One-pole high-pass: thins the voice like a comm channel.
            val a = 1f - fx.highpass * 0.2f
            var prevIn = 0f
            var prevOut = 0f
            for (i in 0 until n) {
                val out = a * (prevOut + y[i] - prevIn)
                prevIn = y[i]
                prevOut = out
                y[i] = out
            }
        }
        if (fx.ringMix > 0f) {
            for (i in 0 until n) {
                val carrier = sin(2.0 * PI * fx.ringHz * i / rate).toFloat()
                y[i] *= (1f - fx.ringMix) + fx.ringMix * carrier
            }
        }
        if (fx.drive > 0f) {
            val norm = tanh(fx.drive)
            for (i in 0 until n) y[i] = tanh(fx.drive * y[i]) / norm
        }
        if (fx.bits < 16 || fx.downsample > 1) {
            val levels = (1 shl (fx.bits - 1)).toFloat()
            var hold = 0f
            for (i in 0 until n) {
                if (i % fx.downsample == 0) hold = Math.round(y[i] * levels) / levels
                y[i] = hold
            }
        }
        if (fx.chorusMix > 0f) {
            // Short modulated delay (5–12 ms): the doubled, "synthetic" shimmer.
            val dry = y.copyOf()
            for (i in 0 until n) {
                val delayMs = 8.5 + 3.5 * sin(2.0 * PI * 0.6 * i / rate)
                val j = i - (delayMs * rate / 1000).toInt()
                if (j >= 0) y[i] = dry[i] * (1f - fx.chorusMix * 0.5f) + dry[j] * fx.chorusMix
            }
        }
        if (fx.echoMs > 0 && fx.echoGain > 0f) {
            val d = rate * fx.echoMs / 1000
            for (i in d until y.size) y[i] += y[i - d] * fx.echoGain
        }
        // Normalize to a comfortable peak.
        var peak = 0f
        for (v in y) peak = maxOf(peak, abs(v))
        if (peak > 0f) {
            val gain = 0.9f / peak
            for (i in y.indices) y[i] *= gain
        }
        return Pcm(y, rate)
    }

    // ---- Text.

    private fun forSpeech(text: String): String =
        text.replace(Regex("(?i)as//ka"), "Aska")
            .replace(Regex("\\[\\[.*?]]"), "")
            .replace(Regex("[▸▌◢■●•*_#`>]"), " ")
            .replace(Regex("[\\p{So}\\p{Cs}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sentences(text: String): List<String> {
        val parts = Regex("(?<=[.!?…])\\s+").split(text).filter { it.isNotBlank() }
        // Merge very short pieces so the voice does not stutter.
        val out = mutableListOf<String>()
        for (p in parts) {
            if (out.isNotEmpty() && (out.last().length < 40 || p.length < 12)) out[out.lastIndex] = out.last() + " " + p
            else out.add(p)
        }
        return out
    }

    companion object {
        // Google's offline French voices: frb and frd are the male ones.
        private val MALE_GOOGLE = listOf("fr-fr-x-frb", "fr-fr-x-frd", "fr-fr-x-vlm")
    }
}
