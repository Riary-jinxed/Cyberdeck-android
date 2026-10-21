package dev.netrunner.launcher.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlin.random.Random

enum class WallpaperMode(val id: String, val label: String) {
    Rain("rain", "PLUIE DE CODE"),
    City("city", "NIGHT CITY"),
    Off("off", "AUCUN");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Rain
    }
}

/** Selected wallpaper, kept in Compose state so a change from the terminal shows at once. */
object Wallpaper {
    var mode by mutableStateOf(WallpaperMode.Rain)
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

private const val Glyphs = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄ0123456789ABCDEF<>/{}#$%"

private class Drop(var column: Int, var head: Float, var speed: Float, var length: Int, val seed: Int)

/**
 * Animated background behind the HUD. Rain of code or a Night City skyline. Drawn at 10 fps and
 * only while the screen is visible. It follows the battery (red and slow below 20 %, faster while
 * charging) and the hour (denser and bluer at night, sparse and warmer by day).
 */
@Composable
fun LiveWallpaper(batteryLevel: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val mode = Wallpaper.mode
    if (mode == WallpaperMode.Off) return
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
        WallpaperMode.Rain -> CodeRain(t, tint, density = if (night) 1f else 0.55f, speed = if (low) 0.5f else if (charging) 1.6f else 1f, modifier)
        WallpaperMode.City -> Skyline(t, tint, night, modifier)
        WallpaperMode.Off -> Unit
    }
}

@Composable
private fun CodeRain(t: Long, tint: Color, density: Float, speed: Float, modifier: Modifier) {
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
        val wanted = (columns * 0.6f * density).toInt().coerceAtLeast(4)
        while (drops.size < wanted) {
            drops += Drop(Random.nextInt(columns), -Random.nextInt(rows).toFloat(), 0.4f + Random.nextFloat() * 0.8f, 6 + Random.nextInt(14), Random.nextInt())
        }
        while (drops.size > wanted) drops.removeAt(drops.lastIndex)
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
                val alpha = if (k == 0) 0.55f else 0.28f * (1f - k / d.length.toFloat())
                val color = if (k == 0) Color.White else tint
                paint.color = color.copy(alpha = alpha).toArgb()
                val glyph = Glyphs[((d.seed + row * 31 + (t / 400).toInt() * (k % 3)) and 0x7fffffff) % Glyphs.length]
                drawIntoCanvas { it.nativeCanvas.drawText(glyph.toString(), d.column * cell, (row + 1) * cell, paint) }
            }
        }
    }
}

@Composable
private fun Skyline(t: Long, tint: Color, night: Boolean, modifier: Modifier) {
    // Building layout is fixed per session; only window lights and the beacon change.
    val seed = remember { Random.nextInt() }
    Canvas(modifier.fillMaxSize()) {
        val rnd = Random(seed)
        val base = size.height
        var x = 0f
        val unit = 6.dp.toPx()
        while (x < size.width) {
            val w = unit * (4 + rnd.nextInt(8))
            val h = size.height * (0.12f + rnd.nextFloat() * if (rnd.nextInt(5) == 0) 0.45f else 0.25f)
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(x, base - h), Size(w, h))
            drawRect(tint.copy(alpha = 0.18f), Offset(x, base - h), Size(w, 1.5f))
            windows(rnd, x, base - h, w, h, unit, t, tint, night)
            x += w + unit * rnd.nextInt(2)
        }
        // Aircraft beacon crossing the sky.
        val period = 24_000L
        val p = (t % period) / period.toFloat()
        val y = size.height * 0.18f
        val blink = (t / 500) % 2 == 0L
        drawCircle(Cp.Red.copy(alpha = if (blink) 0.8f else 0.2f), 2.5.dp.toPx(), Offset(size.width * p, y))
    }
}

private fun DrawScope.windows(rnd: Random, x: Float, top: Float, w: Float, h: Float, unit: Float, t: Long, tint: Color, night: Boolean) {
    val cols = (w / unit).toInt() - 1
    val rows = (h / unit).toInt() - 1
    val flicker = (t / 3000).toInt()
    for (r in 1 until rows step 2) for (c in 1 until cols step 2) {
        val id = rnd.nextInt()
        val on = Math.floorMod(id + flicker * (id and 7), if (night) 3 else 7) == 0
        if (on) {
            drawRect(tint.copy(alpha = 0.22f), Offset(x + c * unit, top + r * unit), Size(unit * 0.7f, unit * 0.6f))
        }
    }
}
