package dev.netrunner.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Angled "tech panel" shape, as on the launcher; the corners it cuts depend on the faction. */
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

fun Modifier.scanlines(color: Color = Color.Black.copy(alpha = 0.25f), spacing: Dp = 3.dp): Modifier = drawWithContent {
    drawContent()
    val step = spacing.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/**
 * The faction's background pattern, as on the launcher (grid, pinstripes, hazard bands, dot matrix, rings,
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
        Motif.Spray -> Unit
        Motif.Plain -> Unit
    }
    // Spray paint: drips running down from the top edge and splatter around them, always laid
    // out the same way (fixed seed) so the screen does not change from one launch to the next.
    class Drip(val x: Float, val length: Float, val width: Float)
    val drips = mutableListOf<Drip>()
    val splatter = mutableListOf<Pair<Offset, Float>>()
    if (faction.motif == Motif.Spray) {
        val rnd = Random(7)
        var x = rnd.nextFloat() * step
        while (x < w) {
            drips += Drip(x, h * (0.05f + rnd.nextFloat() * rnd.nextFloat() * 0.5f), 2f + rnd.nextFloat() * 5f)
            x += step * (0.4f + rnd.nextFloat() * 1.6f)
        }
        repeat(90) {
            val y = h * rnd.nextFloat() * rnd.nextFloat() * 0.45f
            splatter += Offset(rnd.nextFloat() * w, y) to (0.8f + rnd.nextFloat() * rnd.nextFloat() * 5f)
        }
    }
    val paint = primary.copy(alpha = 0.13f)
    val band = Brush.verticalGradient(listOf(primary.copy(alpha = 0.10f), Color.Transparent), endY = step * 1.5f)
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
        if (drips.isNotEmpty()) {
            // Paint line along the top, the drips, a drop at the end of each, then the splatter.
            drawRect(band, size = androidx.compose.ui.geometry.Size(w, step * 1.5f))
            drips.forEach { d ->
                drawLine(paint, Offset(d.x, 0f), Offset(d.x, d.length), d.width, StrokeCap.Round)
                drawCircle(paint, d.width * 0.9f, Offset(d.x, d.length + d.width * 0.4f))
            }
            splatter.forEach { (c, r) -> drawCircle(paint, r, c) }
        }
        drawRect(glow)
        drawRect(floor)
    }
}

/** Angled button. [filled] for the main action. */
@Composable
fun HudButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Cp.Yellow,
    filled: Boolean = false,
    size: TextUnit = 15.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .hudPanel(borderColor = color, fill = if (filled) color else Cp.Panel.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Cp.Background else color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 1.sp)
    }
}

@Composable
fun SectionTitle(text: String, color: Color = Cp.Cyan, modifier: Modifier = Modifier) {
    Text("// $text", color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, modifier = modifier)
}

/** True while the app is visible: every ticking loop keys on it, so nothing runs in the background. */
@Composable
fun rememberScreenActive(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/** Wall clock in ms, ticking every [periodMs] while visible, aligned on the period. */
@Composable
fun rememberNow(periodMs: Long = 1000L): State<Long> {
    val active = rememberScreenActive()
    return produceState(System.currentTimeMillis(), active, periodMs) {
        while (active) {
            value = System.currentTimeMillis()
            delay(periodMs - System.currentTimeMillis() % periodMs)
        }
    }
}

fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

/** 1:05:09, 4:07, 0:09 */
fun formatDuration(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** In 7 h 12 min, in 3 min… for the "next ring" line. */
fun untilLabel(ms: Long): String {
    val min = (ms + 59_999) / 60_000
    val d = min / (24 * 60)
    val h = min % (24 * 60) / 60
    val m = min % 60
    return buildString {
        append("dans ")
        if (d > 0) append("$d j ")
        if (h > 0) append("$h h ")
        if (d == 0L) append("$m min")
    }.trim()
}
