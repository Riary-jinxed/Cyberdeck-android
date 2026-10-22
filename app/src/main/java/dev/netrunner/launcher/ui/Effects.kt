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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
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

/** Angled "tech panel" shape used across the HUD; the corners it cuts depend on the faction. */
fun hudShape(cut: Dp = 10.dp) = when (Cp.faction.cut) {
    PanelCut.Diagonal -> CutCornerShape(topEnd = cut, bottomStart = cut)
    PanelCut.Mirror -> CutCornerShape(topStart = cut, bottomEnd = cut)
    PanelCut.Octagon -> CutCornerShape(cut * 0.6f)
    PanelCut.Tab -> CutCornerShape(topStart = cut, topEnd = cut)
    PanelCut.Notch -> CutCornerShape(topEnd = cut * 1.6f)
}

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

/**
 * The faction's background pattern (grid, pinstripes, hazard bands, dot matrix, rings,
 * stencil marks) plus a primary-colour glow at the top, behind everything. The pattern is
 * built once per size and faction, so redraws only replay it.
 */
fun Modifier.cyberGrid(cell: Dp = 36.dp): Modifier = drawWithCache {
    val faction = Cp.faction
    val step = cell.toPx()
    val w = size.width
    val h = size.height
    val accent = Cp.Cyan
    val primary = Cp.Yellow
    val path = Path()
    val points = mutableListOf<Offset>()
    var lineAlpha = 0.06f
    var stroke = 1f
    when (faction.motif) {
        Motif.Grid -> {
            var x = 0f
            while (x < w) { path.moveTo(x, 0f); path.lineTo(x, h); x += step }
            var y = 0f
            while (y < h) { path.moveTo(0f, y); path.lineTo(w, y); y += step }
        }
        Motif.Pinstripe -> {
            // Corporate pinstripes with a heavier rule every fourth line.
            var x = 0f
            var k = 0
            while (x < w) {
                path.moveTo(x, 0f); path.lineTo(x, h)
                if (k % 4 == 0) { path.moveTo(x + 2f, 0f); path.lineTo(x + 2f, h) }
                x += step / 2; k++
            }
            lineAlpha = 0.05f
        }
        Motif.Hazard -> {
            // Grid, plus hazard hatching on the top and bottom bands.
            var x = 0f
            while (x < w) { path.moveTo(x, 0f); path.lineTo(x, h); x += step * 2 }
            val band = step * 2
            var d = -band
            while (d < w + band) {
                path.moveTo(d, 0f); path.lineTo(d + band, band)
                path.moveTo(d, h - band); path.lineTo(d + band, h)
                d += step / 2
            }
            stroke = 2f
        }
        Motif.Dots -> {
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) { points += Offset(x, y); x += step / 2 }
                y += step / 2
            }
            lineAlpha = 0.16f
            stroke = 3f
        }
        Motif.Rings -> {
            // Concentric rings and spokes around a point off the top right: a net "vévé".
            val c = Offset(w * 0.8f, h * 0.18f)
            var r = step
            while (r < maxOf(w, h) * 1.2f) {
                path.addOval(androidx.compose.ui.geometry.Rect(c, r))
                r += step * 1.4f
            }
            for (i in 0 until 24) {
                val a = Math.toRadians(i * 15.0)
                path.moveTo(c.x, c.y)
                path.lineTo(c.x + (cos(a) * w * 2).toFloat(), c.y + (sin(a) * w * 2).toFloat())
            }
            lineAlpha = 0.05f
        }
        Motif.Stencil -> {
            // Crosshair marks on a wide grid and long diagonal slashes, spray-paint style.
            val big = step * 2
            var y = big / 2
            while (y < h) {
                var x = big / 2
                while (x < w) {
                    path.moveTo(x - 6f, y); path.lineTo(x + 6f, y)
                    path.moveTo(x, y - 6f); path.lineTo(x, y + 6f)
                    x += big
                }
                y += big
            }
            var d = -h
            while (d < w) { path.moveTo(d, h); path.lineTo(d + h * 0.5f, 0f); d += big * 3 }
            lineAlpha = 0.09f
            stroke = 2f
        }
        Motif.Plain -> Unit
    }
    val line = (if (faction.motif == Motif.Hazard) primary else accent).copy(alpha = lineAlpha)
    val glow = Brush.radialGradient(
        colors = listOf(primary.copy(alpha = 0.10f), Color.Transparent),
        center = Offset(w * 0.2f, 0f),
        radius = w.coerceAtLeast(1f),
    )
    val floor = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Cp.Red.copy(alpha = 0.08f)),
        startY = h * 0.6f,
    )
    val background = Cp.Background
    onDrawBehind {
        drawRect(background)
        if (points.isNotEmpty()) drawPoints(points, PointMode.Points, line, strokeWidth = stroke, cap = StrokeCap.Round)
        else drawPath(path, line, style = Stroke(width = stroke))
        drawRect(glow)
        drawRect(floor)
    }
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
    val lean = dev.netrunner.launcher.system.Ripperdoc.active
    LaunchedEffect(active, lean) {
        while (active && !lean) {
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
    // Ripperdoc power saving freezes every clock-driven animation on its current frame.
    val active = rememberScreenActive() && !dev.netrunner.launcher.system.Ripperdoc.active
    return produceState(0L, active) {
        val start = android.os.SystemClock.uptimeMillis() - value
        while (active) {
            value = android.os.SystemClock.uptimeMillis() - start
            delay(1000L / fps)
        }
    }
}
