package dev.netrunner.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

enum class MascotMood { Idle, Thinking, Talking, Error }

/**
 * GLITCH, the deck's AI: a small cyber-cat head with a visor, an antenna and an
 * equaliser mouth. It floats, blinks, scans while thinking and talks while streaming.
 */
@Composable
fun Mascot(mood: MascotMood, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val loop = rememberInfiniteTransition(label = "glitch")
    val bob by loop.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "bob",
    )
    val phase by loop.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase",
    )
    val antenna by loop.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "antenna",
    )

    var blink by remember { mutableStateOf(false) }
    var glitchShift by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2200, 5200))
            blink = true
            delay(120)
            blink = false
            if (Random.nextInt(3) == 0) {
                repeat(3) {
                    glitchShift = Random.nextFloat() * 6f - 3f
                    delay(50)
                }
                glitchShift = 0f
            }
        }
    }

    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 100f
        val dy = bob * 3 * u
        if (glitchShift != 0f) {
            drawHead(u, dy, glitchShift * u, Cp.Red.copy(alpha = 0.5f), outlineOnly = true)
            drawHead(u, dy, -glitchShift * u, Cp.Cyan.copy(alpha = 0.5f), outlineOnly = true)
        }
        drawHead(u, dy, 0f, Cp.Yellow, outlineOnly = false)

        // Antenna with a blinking tip.
        val tipColor = if (mood == MascotMood.Error) Cp.Red else Cp.Red.copy(alpha = antenna)
        drawLine(Cp.Yellow, Offset(62 * u, 22 * u + dy), Offset(70 * u, 8 * u + dy), strokeWidth = 2.5f * u)
        drawCircle(tipColor, radius = 4 * u, center = Offset(70 * u, 8 * u + dy))

        // Visor.
        val visorTop = 42 * u + dy
        drawRoundRect(
            Color(0xFF041418),
            topLeft = Offset(24 * u, visorTop),
            size = Size(52 * u, 18 * u),
            cornerRadius = CornerRadius(3 * u),
        )
        drawRoundRect(
            Cp.Cyan.copy(alpha = 0.6f),
            topLeft = Offset(24 * u, visorTop),
            size = Size(52 * u, 18 * u),
            cornerRadius = CornerRadius(3 * u),
            style = Stroke(1.5f * u),
        )
        drawEyes(mood, u, visorTop, phase, blink)
        drawMouth(mood, u, dy, phase)
    }
}

private fun DrawScope.drawHead(u: Float, dy: Float, dx: Float, color: Color, outlineOnly: Boolean) {
    // Angular head with two cat ears, cut corners like the HUD panels.
    val head = Path().apply {
        moveTo(18 * u + dx, 30 * u + dy)
        lineTo(24 * u + dx, 12 * u + dy) // left ear
        lineTo(38 * u + dx, 26 * u + dy)
        lineTo(62 * u + dx, 26 * u + dy)
        lineTo(76 * u + dx, 12 * u + dy) // right ear
        lineTo(82 * u + dx, 30 * u + dy)
        lineTo(86 * u + dx, 70 * u + dy)
        lineTo(76 * u + dx, 86 * u + dy)
        lineTo(24 * u + dx, 86 * u + dy)
        lineTo(14 * u + dx, 70 * u + dy)
        close()
    }
    if (!outlineOnly) drawPath(head, Cp.Panel)
    drawPath(head, color, style = Stroke(2.5f * u))
    if (!outlineOnly) {
        // Cheek marks.
        drawLine(Cp.Red, Offset(18 * u, 66 * u + dy), Offset(26 * u, 66 * u + dy), strokeWidth = 2f * u)
        drawLine(Cp.Red, Offset(74 * u, 66 * u + dy), Offset(82 * u, 66 * u + dy), strokeWidth = 2f * u)
    }
}

private fun DrawScope.drawEyes(mood: MascotMood, u: Float, top: Float, phase: Float, blink: Boolean) {
    val eyeY = top + 9 * u
    when (mood) {
        MascotMood.Error -> {
            for (cx in listOf(38f, 62f)) {
                drawLine(Cp.Red, Offset((cx - 4) * u, eyeY - 4 * u), Offset((cx + 4) * u, eyeY + 4 * u), strokeWidth = 2.5f * u)
                drawLine(Cp.Red, Offset((cx - 4) * u, eyeY + 4 * u), Offset((cx + 4) * u, eyeY - 4 * u), strokeWidth = 2.5f * u)
            }
        }
        MascotMood.Thinking -> {
            // A scanning dot sweeping across the visor.
            val x = 50 + 20 * sin(phase)
            drawCircle(Cp.Cyan.copy(alpha = 0.3f), radius = 6 * u, center = Offset(x * u, eyeY))
            drawCircle(Cp.Cyan, radius = 3 * u, center = Offset(x * u, eyeY))
        }
        else -> {
            val h = if (blink) 1.5f * u else 8 * u
            for (cx in listOf(38f, 62f)) {
                drawRect(Cp.Cyan, topLeft = Offset((cx - 5) * u, eyeY - h / 2), size = Size(10 * u, h))
            }
        }
    }
}

private fun DrawScope.drawMouth(mood: MascotMood, u: Float, dy: Float, phase: Float) {
    val baseY = 76 * u + dy
    for (i in 0 until 5) {
        val x = (38 + i * 6) * u
        val h = when (mood) {
            MascotMood.Talking -> (2 + 5 * (0.5f + 0.5f * sin(phase * 2 + i))) * u
            MascotMood.Error -> 1.5f * u
            else -> 2f * u
        }
        drawLine(
            if (mood == MascotMood.Error) Cp.Red else Cp.Yellow,
            Offset(x, baseY - h),
            Offset(x, baseY + h),
            strokeWidth = 2.5f * u,
        )
    }
}
