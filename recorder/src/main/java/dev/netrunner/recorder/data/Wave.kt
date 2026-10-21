package dev.netrunner.recorder.data

import kotlin.math.ln
import kotlin.math.roundToInt

/** Waveform helpers: levels are bytes 0..100, one every [STEP_MS] while recording. Plain Kotlin, tested on the JVM. */
object Wave {
    const val STEP_MS = 50L

    /** MediaRecorder.getMaxAmplitude (0..32767) to a 0..100 level, on a log scale so speech shows well. */
    fun level(amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val db = 20 * ln(amplitude / 32767.0) / ln(10.0) // -90..0
        return ((db + 60) / 60 * 100).roundToInt().coerceIn(0, 100)
    }

    /** [levels] squeezed (peak per bucket) or stretched to exactly [n] bars. */
    fun bars(levels: ByteArray, n: Int): IntArray {
        if (n <= 0) return IntArray(0)
        if (levels.isEmpty()) return IntArray(n)
        return IntArray(n) { i ->
            val from = (i.toLong() * levels.size / n).toInt()
            val to = maxOf(from + 1, ((i + 1).toLong() * levels.size / n).toInt()).coerceAtMost(levels.size)
            var peak = 0
            for (k in from until to) peak = maxOf(peak, levels[k].toInt() and 0xFF)
            peak
        }
    }

    /** 0:07, 12:34, 1:02:03 */
    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** A file name from a moment: "Mémo 2026-10-21 19h32". */
    fun defaultName(ms: Long): String =
        java.text.SimpleDateFormat("'Mémo' yyyy-MM-dd HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date(ms))
}
