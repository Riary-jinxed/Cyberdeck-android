package dev.netrunner.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.sin

/** As//ka's own colours: they stay black and pink whatever the faction. */
object AskaColors {
    val Pink = Color(0xFFFF2E97)
    val HotPink = Color(0xFFFF6EC7)
    val Magenta = Color(0xFF9C0F5F)
    val Void = Color(0xFF0A0509)
    val Panel = Color(0xFF150A12)
}

// Dragon head in profile, facing right, on a 100 × 100 grid. Angular, like a wireframe.
private val Head = listOf(
    18f to 54f, 28f to 40f, 46f to 33f, 64f to 34f, 80f to 41f, 95f to 50f, 90f to 54f, 74f to 56f,
    90f to 62f, 84f to 68f, 64f to 67f, 48f to 74f, 34f to 74f, 24f to 66f,
)
private val Neck = listOf(24f to 66f, 38f to 74f, 34f to 86f, 30f to 97f, 12f to 97f, 17f to 80f)
private val HornBack = listOf(28f to 40f, 2f to 22f, 22f to 30f, 40f to 35f)
private val HornTop = listOf(42f to 34f, 26f to 4f, 46f to 22f, 56f to 33f)
private val Frills = listOf(
    listOf(18f to 54f, 4f to 50f, 18f to 62f),
    listOf(20f to 64f, 4f to 70f, 17f to 74f),
    listOf(16f to 80f, 2f to 88f, 14f to 90f),
)
private val Eye = listOf(58f to 42f, 72f to 44f, 61f to 48f)
// Circuit traces etched in the scales.
private val Traces = listOf(
    listOf(56f to 52f, 44f to 52f, 36f to 60f, 30f to 60f),
    listOf(66f to 60f, 52f to 61f, 46f to 67f),
    listOf(40f to 44f, 34f to 48f, 26f to 48f),
)

/**
 * As//ka's sigil. [animated] lets the eye glow and the outline flicker, at a low frame rate
 * and only while the screen is on; otherwise it is drawn once.
 */
@Composable
fun AskaSigil(modifier: Modifier = Modifier, animated: Boolean = true, intensity: Float = 1f) {
    val clock = if (animated) rememberSlowClock(fps = 12) else remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val t by clock
    Canvas(modifier) {
        val time = t / 1000f
        val pulse = if (animated) 0.6f + 0.4f * ((sin(time * 2.2f) + 1f) / 2f) else 0.8f
        // Short glitch: the outline jumps sideways for a few frames every few seconds.
        val glitch = animated && (t / 80) % 53 in 0L..1L
        val unit = size.minDimension / 100f
        val offsetX = (size.width - 100f * unit) / 2f
        val offsetY = (size.height - 100f * unit) / 2f
        translate(offsetX, offsetY) {
            if (glitch) {
                translate(-2f * unit, 0f) { drawOutline(unit, AskaColors.Pink.copy(alpha = 0.45f), outlineOnly = true) }
                translate(2f * unit, 0f) { drawOutline(unit, Color(0xFF00F0FF).copy(alpha = 0.35f), outlineOnly = true) }
            }
            drawOutline(unit, AskaColors.Pink, outlineOnly = false, glow = 0.18f * intensity * pulse)
            // Eye.
            val eye = poly(Eye, unit)
            drawPath(eye, AskaColors.HotPink.copy(alpha = 0.25f * pulse), style = Stroke(width = 5f * unit, join = StrokeJoin.Round))
            drawPath(eye, Color.White.copy(alpha = 0.9f))
            drawPath(eye, AskaColors.HotPink.copy(alpha = pulse))
            // Traces with their nodes.
            Traces.forEach { trace ->
                drawPath(open(trace, unit), AskaColors.Pink.copy(alpha = 0.55f), style = Stroke(width = 0.9f * unit, cap = StrokeCap.Round))
                val (x, y) = trace.last()
                drawCircle(AskaColors.HotPink.copy(alpha = 0.4f + 0.6f * pulse), radius = 1.4f * unit, center = Offset(x * unit, y * unit))
            }
            // Nostril.
            drawLine(AskaColors.Pink, Offset(87f * unit, 47f * unit), Offset(91f * unit, 49f * unit), strokeWidth = 1.2f * unit)
        }
    }
}

private fun DrawScope.drawOutline(unit: Float, color: Color, outlineOnly: Boolean, glow: Float = 0f) {
    val shapes = listOf(Neck, HornBack, HornTop, Head) + Frills
    val stroke = Stroke(width = 1.8f * unit, join = StrokeJoin.Miter)
    shapes.forEach { points ->
        val path = poly(points, unit)
        if (!outlineOnly) {
            val horn = points === HornBack || points === HornTop || points in Frills
            drawPath(
                path,
                if (horn) Brush.verticalGradient(listOf(AskaColors.Magenta, AskaColors.Void), startY = 0f, endY = 70f * unit)
                else Brush.verticalGradient(listOf(Color(0xFF1A0A15), AskaColors.Void), startY = 30f * unit, endY = 96f * unit),
            )
            if (glow > 0f) drawPath(path, color.copy(alpha = glow), style = Stroke(width = 6f * unit, join = StrokeJoin.Round))
        }
        drawPath(path, color, style = stroke)
    }
}

private fun poly(points: List<Pair<Float, Float>>, unit: Float) = open(points, unit).apply { close() }

private fun open(points: List<Pair<Float, Float>>, unit: Float) = Path().apply {
    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit) }
}
