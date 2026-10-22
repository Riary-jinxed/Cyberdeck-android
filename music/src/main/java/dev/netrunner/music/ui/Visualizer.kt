package dev.netrunner.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.netrunner.deck.Cp
import dev.netrunner.music.data.MusicPrefs
import dev.netrunner.music.system.Analyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Neon spectrum of what is playing. Bars rise at once and fall slowly, with a peak marker
 * that hangs for a moment; everything sinks to rest when the music stops.
 */
@Composable
fun Visualizer(playing: Boolean, style: MusicPrefs.Style, glow: Boolean, modifier: Modifier) {
    val levels = remember { FloatArray(Analyzer.BANDS) }
    val peaks = remember { FloatArray(Analyzer.BANDS) }
    val wave = remember { FloatArray(Analyzer.WAVE) }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playing) {
        var last = 0L
        var settled = false
        // Paused: run until everything has sunk to rest, then stop drawing.
        while (!settled) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val frame = Analyzer.frames.value
                // A frame older than a quarter second means the audio stopped.
                val live = playing && System.nanoTime() - frame.time < 250_000_000L
                for (i in levels.indices) {
                    val target = if (live) frame.bands[i] else 0f
                    levels[i] = if (target > levels[i]) target else max(target, levels[i] - dt * 1.6f)
                    peaks[i] = if (levels[i] > peaks[i]) levels[i] else max(0f, peaks[i] - dt * 0.35f)
                }
                for (i in wave.indices) {
                    val target = if (live) frame.wave[i] else 0f
                    wave[i] += (target - wave[i]) * 0.5f
                }
                tick = now
                settled = !playing && peaks.all { it == 0f } && levels.all { it == 0f }
            }
        }
    }

    val low = Cp.Cyan
    val mid = Cp.Red
    val high = Cp.Yellow
    Canvas(modifier) {
        tick // redraw on every frame
        when (style) {
            MusicPrefs.Style.BARS -> bars(levels, peaks, low, mid, high, glow)
            MusicPrefs.Style.WAVE -> wave(wave, levels, low, mid, glow)
            MusicPrefs.Style.RING -> ring(levels, low, mid, high, glow)
        }
    }
}

private fun DrawScope.bars(levels: FloatArray, peaks: FloatArray, low: Color, mid: Color, high: Color, glow: Boolean) {
    val n = levels.size
    val step = size.width / n
    val w = step * 0.62f
    val floor = size.height
    val brush = Brush.verticalGradient(listOf(high, mid, low), startY = 0f, endY = floor)
    for (i in 0 until n) {
        val x = i * step + step / 2
        val h = (levels[i] * floor).coerceAtLeast(2f)
        if (glow) drawLine(brush, Offset(x, floor), Offset(x, floor - h), w * 2.2f, StrokeCap.Butt, alpha = 0.18f)
        drawLine(brush, Offset(x, floor), Offset(x, floor - h), w, StrokeCap.Butt)
        val py = floor - peaks[i] * floor
        drawLine(high, Offset(x - w / 2, py), Offset(x + w / 2, py), 3f)
    }
}

private fun DrawScope.wave(wave: FloatArray, levels: FloatArray, low: Color, mid: Color, glow: Boolean) {
    val mid0 = size.height / 2
    val energy = levels.average().toFloat()
    val gain = 1.2f + energy * 2f
    val path = Path()
    wave.forEachIndexed { i, v ->
        val x = i * size.width / (wave.size - 1)
        val y = mid0 - (v * gain).coerceIn(-1f, 1f) * mid0 * 0.95f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val brush = Brush.horizontalGradient(listOf(low, mid, low))
    if (glow) drawPath(path, brush, alpha = 0.25f, style = Stroke(width = 12f, cap = StrokeCap.Round))
    drawPath(path, brush, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
    drawLine(low.copy(alpha = 0.25f), Offset(0f, mid0), Offset(size.width, mid0), 1f)
}

private fun DrawScope.ring(levels: FloatArray, low: Color, mid: Color, high: Color, glow: Boolean) {
    val c = center
    val radius = size.minDimension * 0.26f
    val reach = size.minDimension * 0.22f
    val n = levels.size
    // Mirrored, so the bass sits at the top and both halves match.
    for (k in 0 until n * 2) {
        val i = if (k < n) k else 2 * n - 1 - k
        val a = (k.toFloat() / (n * 2)) * 2 * PI.toFloat() - PI.toFloat() / 2
        val dir = Offset(cos(a), sin(a))
        val start = c + dir * radius
        val end = c + dir * (radius + 4f + levels[i] * reach)
        val color = if (levels[i] > 0.75f) high else if (k % 2 == 0) low else mid
        if (glow) drawLine(color, start, end, 9f, StrokeCap.Round, alpha = 0.2f)
        drawLine(color, start, end, 4f, StrokeCap.Round)
    }
    val bass = levels.take(6).average().toFloat()
    drawCircle(mid.copy(alpha = 0.25f + bass * 0.5f), radius * (0.92f + bass * 0.06f), c, style = Stroke(2.5f))
}
