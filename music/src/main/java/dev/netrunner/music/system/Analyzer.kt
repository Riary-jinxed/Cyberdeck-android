package dev.netrunner.music.system

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import dev.netrunner.music.data.Spectrum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the decoded audio on its way to the speaker (no microphone permission needed, unlike
 * android.media.audiofx.Visualizer) and publishes its spectrum and waveform for the screen.
 */
@OptIn(UnstableApi::class)
object Analyzer : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 48
    const val WAVE = 256

    class Frame(val bands: FloatArray, val wave: FloatArray, val time: Long)

    private val _frames = MutableStateFlow(Frame(FloatArray(BANDS), FloatArray(WAVE), 0L))
    val frames: StateFlow<Frame> = _frames.asStateFlow()

    private val ring = FloatArray(Spectrum.SIZE)
    private var head = 0
    private var filled = 0
    private var sinceLast = 0
    private var rate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        head = 0
        filled = 0
        sinceLast = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytes * channels
        while (b.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until channels) sum += if (bytes == 2) b.short / 32768f else b.float
            push(sum / channels)
        }
    }

    private fun push(sample: Float) {
        ring[head] = sample
        head = (head + 1) % ring.size
        if (filled < ring.size) filled++
        // About 43 frames a second at 44.1 kHz: one every 1024 samples.
        if (++sinceLast >= 1024 && filled == ring.size) {
            sinceLast = 0
            val ordered = FloatArray(ring.size) { ring[(head + it) % ring.size] }
            val wave = ordered.copyOfRange(ordered.size - WAVE, ordered.size)
            _frames.value = Frame(Spectrum.bands(ordered, rate, BANDS), wave, System.nanoTime())
        }
    }
}
