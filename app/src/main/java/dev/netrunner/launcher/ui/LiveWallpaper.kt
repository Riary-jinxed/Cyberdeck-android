package dev.netrunner.launcher.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.sin
import kotlin.random.Random

enum class WallpaperMode(val id: String, val label: String) {
    Breach("breach", "BREACH"),
    Rain("rain", "PLUIE DE CODE"),
    Off("off", "AUCUN");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Breach
    }
}

/** Selected wallpaper, kept in Compose state so a change from the terminal shows at once. */
object Wallpaper {
    var mode by mutableStateOf(WallpaperMode.Breach)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        mode = WallpaperMode.of(prefs(context).getString("wallpaper", null))
    }

    fun select(context: Context, m: WallpaperMode) {
        prefs(context).edit().putString("wallpaper", m.id).apply()
        mode = m
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}

private val HexCodes = listOf("1C", "55", "BD", "E9", "7A", "FF")
private const val Glyphs = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄ0123456789ABCDEF<>/{}#$%"

/**
 * Animated background behind the HUD, drawn at 10 fps and only while the screen is visible.
 * It follows the battery (red below 20 %, faster while charging) and the hour (bluer at night).
 */
@Composable
fun LiveWallpaper(batteryLevel: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val mode = Wallpaper.mode
    if (mode == WallpaperMode.Off || dev.netrunner.launcher.system.NightWatch.active) return
    if (dev.netrunner.launcher.system.Ripperdoc.active) return
    val t by rememberSlowClock(fps = 10)
    val hour = remember(t / 60_000) { LocalTime.now().hour }
    val night = hour >= 20 || hour < 7
    val low = batteryLevel in 0..19
    val tint = when {
        low -> Cp.Red
        night -> Cp.Cyan
        else -> Cp.Yellow
    }
    when (mode) {
        WallpaperMode.Breach -> BreachMatrix(t, tint, speed = if (low) 0.5f else if (charging) 1.6f else 1f, modifier)
        WallpaperMode.Rain -> CodeRain(t, tint, speed = if (low) 0.5f else if (charging) 1.6f else 1f, modifier)
        WallpaperMode.Off -> Unit
    }
}

/**
 * The game's breach protocol: a faint grid of hex codes, with a highlighted row and column
 * sweeping across it like a cursor picking the sequence.
 */
@Composable
private fun BreachMatrix(t: Long, tint: Color, speed: Float, modifier: Modifier) {
    val seed = remember { Random.nextInt() }
    val paint = remember {
        Paint().apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val cellW = 34.dp.toPx()
        val cellH = 30.dp.toPx()
        paint.textSize = 13.dp.toPx()
        val cols = (size.width / cellW).toInt() + 1
        val rows = (size.height / cellH).toInt() + 1
        val step = (t * speed / 1400).toLong()
        val activeRow = Math.floorMod(step / 6, rows.toLong()).toInt()
        val activeCol = Math.floorMod(step * 7 / 5, cols.toLong()).toInt()
        // Highlight bands, like the game's selection cursor.
        drawRect(tint.copy(alpha = 0.06f), Offset(0f, activeRow * cellH), Size(size.width, cellH))
        drawRect(Cp.Cyan.copy(alpha = 0.05f), Offset(activeCol * cellW, 0f), Size(cellW, size.height))
        for (r in 0 until rows) for (c in 0 until cols) {
            // Each cell slowly cycles through the six codes.
            val phase = (seed + r * 131 + c * 71 + ((t / 2600) * ((r + c) % 3)).toInt())
            val code = HexCodes[Math.floorMod(phase, HexCodes.size)]
            val hot = r == activeRow || c == activeCol
            val alpha = when {
                r == activeRow && c == activeCol -> 0.65f
                hot -> 0.22f
                else -> 0.07f
            }
            paint.color = (if (r == activeRow && c == activeCol) Cp.Text else tint).copy(alpha = alpha).toArgb()
            drawIntoCanvas { it.nativeCanvas.drawText(code, c * cellW + 6.dp.toPx(), (r + 1) * cellH - 9.dp.toPx(), paint) }
        }
    }
}

private class Drop(var column: Int, var head: Float, var speed: Float, var length: Int, val seed: Int)

@Composable
private fun CodeRain(t: Long, tint: Color, speed: Float, modifier: Modifier) {
    val drops = remember { mutableListOf<Drop>() }
    // Plain holder, not state: writing state while drawing would trigger another draw.
    val last = remember { LongArray(1) }
    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val cell = 14.dp.toPx()
        paint.textSize = cell * 0.9f
        val columns = (size.width / cell).toInt()
        val rows = (size.height / cell).toInt()
        val wanted = (columns * 0.5f).toInt().coerceAtLeast(4)
        while (drops.size < wanted) {
            drops += Drop(Random.nextInt(columns), -Random.nextInt(rows).toFloat(), 0.4f + Random.nextFloat() * 0.8f, 6 + Random.nextInt(14), Random.nextInt())
        }
        val dt = if (last[0] == 0L) 0f else (t - last[0]).coerceIn(0, 500) / 100f
        last[0] = t
        drops.forEach { d ->
            d.head += d.speed * speed * dt
            if (d.head - d.length > rows) {
                d.column = Random.nextInt(columns)
                d.head = -Random.nextInt(10).toFloat()
            }
            for (k in 0 until d.length) {
                val row = d.head.toInt() - k
                if (row < 0 || row > rows) continue
                val alpha = if (k == 0) 0.5f else 0.22f * (1f - k / d.length.toFloat())
                paint.color = (if (k == 0) Color.White else tint).copy(alpha = alpha).toArgb()
                val glyph = Glyphs[Math.floorMod(d.seed + row * 31 + (t / 400).toInt() * (k % 3), Glyphs.length)]
                drawIntoCanvas { it.nativeCanvas.drawText(glyph.toString(), d.column * cell, (row + 1) * cell, paint) }
            }
        }
    }
}

/**
 * CRT monitor look over the whole screen: vignette, a slow rolling bright band, a faint flicker
 * and, every few seconds, a short glitch burst (the image jumps sideways and torn red/cyan
 * slices flash across it), like the game's hacking screens.
 */
fun Modifier.crtGlitch(): Modifier = composed {
    // Netwatch night mode: no glitches, no flicker.
    if (dev.netrunner.launcher.system.NightWatch.active) return@composed Modifier
    if (dev.netrunner.launcher.system.Ripperdoc.active) return@composed Modifier
    val active = rememberScreenActive()
    val t by rememberSlowClock(fps = 12)
    var jitter by remember { mutableFloatStateOf(0f) }
    var tears by remember { mutableStateOf<List<Triple<Float, Float, Boolean>>>(emptyList()) }
    // Corporate and military themes keep a clean picture: no random glitch bursts.
    val glitch = Cp.faction.glitch
    LaunchedEffect(active, glitch) {
        while (active && glitch) {
            delay(Random.nextLong(3500, 8000))
            repeat(Random.nextInt(3, 7)) {
                jitter = Random.nextFloat() * 14f - 7f
                tears = List(Random.nextInt(1, 4)) {
                    Triple(Random.nextFloat(), 0.004f + Random.nextFloat() * 0.03f, Random.nextBoolean())
                }
                delay(Random.nextLong(40, 90))
            }
            jitter = 0f
            tears = emptyList()
        }
    }
    // Short burst on demand (panel opening).
    LaunchedEffect(GlitchBus.pulse) {
        if (GlitchBus.pulse == 0) return@LaunchedEffect
        repeat(3) {
            jitter = Random.nextFloat() * 18f - 9f
            tears = List(Random.nextInt(2, 5)) {
                Triple(Random.nextFloat(), 0.004f + Random.nextFloat() * 0.04f, Random.nextBoolean())
            }
            delay(45)
        }
        jitter = 0f
        tears = emptyList()
    }
    this
        .graphicsLayer { translationX = jitter * density }
        .drawWithContent {
            drawContent()
            val h = size.height
            // Rolling band.
            val band = ((t % 7000L) / 7000f) * (h + 200f) - 100f
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.035f), Color.Transparent),
                    startY = band - 90f,
                    endY = band + 90f,
                ),
                topLeft = Offset(0f, band - 90f),
                size = Size(size.width, 180f),
            )
            // Flicker.
            val flicker = 0.02f + 0.02f * sin(t / 90f)
            drawRect(Color.Black.copy(alpha = flicker.coerceAtLeast(0f)))
            // Vignette.
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    center = center,
                    radius = maxOf(size.width, size.height) * 0.75f,
                )
            )
            // Glitch tears.
            tears.forEach { (y, height, red) ->
                val top = y * h
                val bandH = height * h
                drawRect((if (red) Cp.Red else Cp.Cyan).copy(alpha = 0.22f), Offset(0f, top), Size(size.width, bandH))
                drawRect(Color.White.copy(alpha = 0.10f), Offset(size.width * 0.1f, top + bandH / 2), Size(size.width * 0.6f, 1.5f))
            }
        }
}
