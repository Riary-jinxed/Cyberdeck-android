package dev.netrunner.launcher.wallpaper

import dev.netrunner.launcher.system.Ripperdoc
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import dev.netrunner.launcher.ui.Faction
import java.time.LocalTime
import kotlin.random.Random

/**
 * The breach grid as a real Android live wallpaper, so it also shows behind the lock screen
 * and the notification shade. 8 frames per second, and nothing at all while it is hidden.
 */
class BreachWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = BreachEngine()

    private inner class BreachEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val start = SystemClock.uptimeMillis()
        private val seed = Random.nextInt()
        private var visible = false
        private var width = 0
        private var height = 0
        private var batteryLevel = 100
        private var lastBatteryRead = 0L
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private val fill = Paint()
        private val frame = Runnable { draw() }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) {
                Ripperdoc.refresh(applicationContext)
                draw()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width
            this.height = height
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas: Canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                render(canvas, SystemClock.uptimeMillis() - start)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            handler.removeCallbacks(frame)
            // Ripperdoc power saving: one still frame, no animation.
            if (visible && !Ripperdoc.active) handler.postDelayed(frame, 125)
        }

        private fun render(canvas: Canvas, t: Long) {
            val palette = Faction.of(
                applicationContext.getSharedPreferences("netrunner", Context.MODE_PRIVATE).getString("faction", null)
            ).palette
            if (t - lastBatteryRead > 60_000 || lastBatteryRead == 0L) {
                lastBatteryRead = t.coerceAtLeast(1)
                batteryLevel = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
            }
            val hour = LocalTime.now().hour
            val night = hour >= 20 || hour < 7
            val tint = when {
                batteryLevel < 20 -> palette.red
                night -> palette.cyan
                else -> palette.yellow
            }.toArgb()
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            canvas.drawColor(palette.background.toArgb())

            val density = resources.displayMetrics.density
            val cellW = 34 * density
            val cellH = 30 * density
            text.textSize = 13 * density
            val cols = (w / cellW).toInt() + 1
            val rows = (h / cellH).toInt() + 1
            val step = t / 1400
            val activeRow = Math.floorMod(step / 6, rows.toLong()).toInt()
            val activeCol = Math.floorMod(step * 7 / 5, cols.toLong()).toInt()

            fill.shader = null
            fill.color = withAlpha(tint, 0.07f)
            canvas.drawRect(0f, activeRow * cellH, w, (activeRow + 1) * cellH, fill)
            fill.color = withAlpha(palette.cyan.toArgb(), 0.05f)
            canvas.drawRect(activeCol * cellW, 0f, (activeCol + 1) * cellW, h, fill)

            for (r in 0 until rows) for (c in 0 until cols) {
                val phase = seed + r * 131 + c * 71 + ((t / 2600) * ((r + c) % 3)).toInt()
                val code = Codes[Math.floorMod(phase, Codes.size)]
                val alpha = when {
                    r == activeRow && c == activeCol -> 0.7f
                    r == activeRow || c == activeCol -> 0.24f
                    else -> 0.08f
                }
                text.color = withAlpha(if (r == activeRow && c == activeCol) palette.text.toArgb() else tint, alpha)
                canvas.drawText(code, c * cellW + 6 * density, (r + 1) * cellH - 9 * density, text)
            }

            // Scanlines.
            fill.color = Color.argb(60, 0, 0, 0)
            var y = 0f
            val gap = 3 * density
            while (y < h) {
                canvas.drawRect(0f, y, w, y + density, fill)
                y += gap
            }
            // Rolling band.
            val band = ((t % 7000L) / 7000f) * (h + 400f) - 200f
            fill.shader = LinearGradient(0f, band - 180f, 0f, band + 180f,
                intArrayOf(Color.TRANSPARENT, Color.argb(10, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, band - 180f, w, band + 180f, fill)
            // Vignette.
            fill.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.75f,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }

        private fun withAlpha(color: Int, alpha: Float) =
            Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }

    companion object {
        private val Codes = listOf("1C", "55", "BD", "E9", "7A", "FF")

        /** Opens Android's live wallpaper preview with Netrunner's wallpaper selected. */
        fun pickerIntent(context: Context): Intent =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, BreachWallpaperService::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
