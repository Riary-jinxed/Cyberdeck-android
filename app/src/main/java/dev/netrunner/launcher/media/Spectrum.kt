package dev.netrunner.launcher.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Spectrum bars for the music widget. With the microphone permission it reads the real output mix
 * through Android's Visualizer (Spotify and any other player); without it, it draws an animated
 * imitation driven by the playback state.
 */
class Spectrum(private val context: Context, private val bands: Int = 18) {
    private var visualizer: Visualizer? = null
    private val levels = FloatArray(bands)

    /** True when real audio data is available. */
    val live: Boolean get() = visualizer != null

    fun start(): Boolean {
        if (visualizer != null) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        visualizer = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)
                enabled = true
            }
        }.getOrNull()
        return visualizer != null
    }

    fun stop() {
        visualizer?.let { runCatching { it.enabled = false }; it.release() }
        visualizer = null
    }

    /** Current band levels from 0 to 1; [t] is a clock in ms used by the imitation. */
    fun read(t: Long, playing: Boolean): FloatArray {
        val v = visualizer
        if (v != null) {
            val fft = ByteArray(v.captureSize)
            if (runCatching { v.getFft(fft) }.getOrNull() == Visualizer.SUCCESS) {
                val bins = fft.size / 2
                for (b in 0 until bands) {
                    // Logarithmic bands: more resolution in the bass, like a real analyser.
                    val from = (bins * (Math.pow(2.0, b * 9.0 / bands) - 1) / 511).toInt().coerceIn(1, bins - 1)
                    val to = (bins * (Math.pow(2.0, (b + 1) * 9.0 / bands) - 1) / 511).toInt().coerceIn(from + 1, bins)
                    var peak = 0.0
                    for (i in from until to) {
                        val re = fft[2 * i].toDouble()
                        val im = fft[2 * i + 1].toDouble()
                        peak = maxOf(peak, hypot(re, im))
                    }
                    val db = (ln(1 + peak) / ln(128.0)).toFloat().coerceIn(0f, 1f)
                    levels[b] = maxOf(db, levels[b] * 0.8f)
                }
                return levels
            }
        }
        for (b in 0 until bands) {
            val target = if (!playing) 0.05f else {
                val s = t / 1000.0
                (0.35 + 0.3 * sin(s * (3.1 + b * 0.7) + b) + 0.25 * sin(s * (7.3 - b * 0.2) + b * b)).toFloat().coerceIn(0.05f, 1f) *
                    (1f - b / (bands * 1.6f))
            }
            levels[b] = levels[b] + (target - levels[b]) * 0.35f
        }
        return levels
    }
}
