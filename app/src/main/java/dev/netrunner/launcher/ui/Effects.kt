package dev.netrunner.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Angled "tech panel" shape used across the HUD. */
fun hudShape(cut: Dp = 10.dp) = CutCornerShape(topEnd = cut, bottomStart = cut)

fun Modifier.hudPanel(
    borderColor: Color = Cp.Yellow,
    fill: Color = Cp.Panel.copy(alpha = 0.85f),
    cut: Dp = 10.dp,
): Modifier = this
    .background(fill, hudShape(cut))
    .border(1.dp, borderColor, hudShape(cut))

/** Thin horizontal lines drawn over the content, like an old CRT. */
fun Modifier.scanlines(
    color: Color = Color.Black.copy(alpha = 0.28f),
    spacing: Dp = 3.dp,
): Modifier = drawWithContent {
    drawContent()
    val step = spacing.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/** Faint cyan grid plus a yellow glow at the top, behind everything. */
fun Modifier.cyberGrid(cell: Dp = 36.dp): Modifier = drawBehind {
    drawRect(Cp.Background)
    val step = cell.toPx()
    val line = Cp.Cyan.copy(alpha = 0.06f)
    var x = 0f
    while (x < size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y))
        y += step
    }
    drawRect(
        Brush.radialGradient(
            colors = listOf(Cp.Yellow.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.2f, 0f),
            radius = size.width,
        )
    )
    drawRect(
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Cp.Red.copy(alpha = 0.08f)),
            startY = size.height * 0.6f,
        )
    )
}

/**
 * Text that now and then splits into red and cyan copies for a fraction of a second,
 * like the in-game glitch on Johnny's engram.
 */
@Composable
fun GlitchText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableFloatStateOf(0f) }
    var flicker by remember { mutableStateOf(false) }
    val active = rememberScreenActive()
    LaunchedEffect(active) {
        while (active) {
            delay(Random.nextLong(1800, 5000))
            repeat(Random.nextInt(3, 7)) {
                shift = Random.nextFloat() * 10f - 5f
                flicker = Random.nextInt(4) == 0
                delay(45)
            }
            shift = 0f
            flicker = false
        }
    }
    Box(modifier) {
        if (shift != 0f) {
            Text(
                text, style = style, color = Cp.Red.copy(alpha = 0.85f),
                modifier = Modifier.offset { IntOffset(shift.dp.roundToPx(), 0) },
            )
            Text(
                text, style = style, color = Cp.Cyan.copy(alpha = 0.85f),
                modifier = Modifier.offset { IntOffset(-shift.dp.roundToPx(), (shift / 3).dp.roundToPx()) },
            )
        }
        Text(text, style = style, color = if (flicker) color.copy(alpha = 0.4f) else color)
    }
}

/**
 * True while the screen is on and Netrunner is visible. Every animation loop keys on it,
 * so nothing ticks in the background, behind another app or with the screen off.
 */
@Composable
fun rememberScreenActive(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Milliseconds since start, updated at a low frame rate. Cheaper than an infinite transition,
 * which redraws at the panel's full 120 Hz.
 */
@Composable
fun rememberSlowClock(fps: Int = 15): State<Long> {
    val active = rememberScreenActive()
    return produceState(0L, active) {
        val start = android.os.SystemClock.uptimeMillis() - value
        while (active) {
            value = android.os.SystemClock.uptimeMillis() - start
            delay(1000L / fps)
        }
    }
}
