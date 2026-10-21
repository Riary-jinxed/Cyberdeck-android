package dev.netrunner.gallery.data

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The holo look: picture tinted toward the faction's secondary colour, a slight RGB split,
 * scanlines and a vignette. [matrix] is shared with the live preview, [render] bakes it in.
 */
object Holo {

    /** 4×5 colour matrix: 70 % luminance tinted by (r, g, b in 0..1), 30 % of the original colour. */
    fun matrix(r: Float, g: Float, b: Float, strength: Float = 0.7f): FloatArray {
        val keep = 1f - strength
        fun row(tint: Float, self: Int): FloatArray {
            val boost = tint * strength * 1.25f
            val base = floatArrayOf(0.299f * boost, 0.587f * boost, 0.114f * boost, 0f, 12f * tint)
            base[self] += keep
            return base
        }
        return row(r, 0) + row(g, 1) + row(b, 2) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    }

    fun render(source: Bitmap, r: Float, g: Float, b: Float): Bitmap {
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix(r, g, b))) }
        canvas.drawBitmap(source, 0f, 0f, paint)

        // Chromatic split: a faint red ghost shifted to the right.
        val shift = (w / 300f).coerceAtLeast(2f)
        val red = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f)))
            blendMode = BlendMode.SCREEN
        }
        canvas.drawBitmap(source, shift, 0f, red)

        // Scanlines, about one every 3 px on a 1080 px wide picture.
        val step = (w / 360f).coerceAtLeast(2f)
        val line = Paint().apply { color = 0x40000000; strokeWidth = step / 2.5f }
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w.toFloat(), y, line)
            y += step
        }

        // Vignette.
        val vignette = Paint().apply {
            shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.75f, intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
        return out
    }
}
