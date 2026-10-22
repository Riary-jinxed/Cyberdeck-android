package dev.netrunner.music.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Frequency bands of a block of mono samples (-1..1), for the visualizer. */
object Spectrum {
    const val SIZE = 2048
    private const val LOW = 40.0
    private const val HIGH = 16_000.0
    /** Levels below this many dB under full scale read as silence. */
    private const val FLOOR_DB = 70.0

    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }

    /** [count] bands spread logarithmically from 40 Hz to 16 kHz, each 0..1. */
    fun bands(samples: FloatArray, rate: Int, count: Int): FloatArray {
        require(samples.size == SIZE)
        val re = FloatArray(SIZE) { samples[it] * window[it] }
        val im = FloatArray(SIZE)
        fft(re, im)
        val half = SIZE / 2
        val binHz = rate.toDouble() / SIZE
        val top = minOf(HIGH, rate / 2.0)
        val out = FloatArray(count)
        for (b in 0 until count) {
            val f0 = LOW * (top / LOW).pow(b.toDouble() / count)
            val f1 = LOW * (top / LOW).pow((b + 1).toDouble() / count)
            val i0 = Math.round(f0 / binHz).toInt().coerceIn(1, half - 1)
            val i1 = Math.round(f1 / binHz).toInt().coerceIn(i0 + 1, half)
            var peak = 0f
            for (i in i0 until i1) {
                val m = sqrt(re[i] * re[i] + im[i] * im[i])
                if (m > peak) peak = m
            }
            // A full-scale sine through the Hann window peaks at SIZE / 4.
            val db = 20 * log10((peak / (SIZE / 4f)).toDouble().coerceAtLeast(1e-9))
            out[b] = ((db + FLOOR_DB) / FLOOR_DB).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    /** In-place radix-2 FFT. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** Index of the band holding [hz], with the same layout as [bands]. */
    fun bandOf(hz: Double, rate: Int, count: Int): Int {
        val top = minOf(HIGH, rate / 2.0)
        return (ln(hz / LOW) / ln(top / LOW) * count).toInt().coerceIn(0, count - 1)
    }
}
