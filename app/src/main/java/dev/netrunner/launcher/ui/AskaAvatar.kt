package dev.netrunner.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.netrunner.launcher.R
import java.io.File
import kotlin.math.sin

/** As//ka's portrait: the user's own import, else the bundled one, else the sigil stands in. */
object AskaPortrait {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "aska_portrait.png")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        image = runCatching { BitmapFactory.decodeFile(file(context).path)?.asImageBitmap() }.getOrNull()
            ?: bundled(context)
    }

    /** The portrait shipped with the launcher, used until the user imports their own. */
    private fun bundled(context: Context): ImageBitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.aska_portrait)?.asImageBitmap()
    }.getOrNull()

    /** Decodes, crops to a centred square and scales to 512 px, so drawing it stays cheap. */
    fun import(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("image illisible")
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        file(context).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image = scaled.asImageBitmap()
    }

    fun clear(context: Context) {
        file(context).delete()
        image = bundled(context)
    }

    private const val SIZE = 512
}

/**
 * As//ka as shown in the HUD: her portrait as a pink hologram (tint, scanlines, pulsing frame,
 * an occasional flicker) when one is imported, her sigil otherwise.
 */
@Composable
fun AskaAvatar(modifier: Modifier = Modifier, animated: Boolean = true, intensity: Float = 1f) {
    val context = LocalContext.current
    remember { AskaPortrait.load(context.applicationContext) }
    val portrait = AskaPortrait.image
    if (portrait == null) {
        AskaSigil(modifier, animated = animated, intensity = intensity)
        return
    }
    val clock = if (animated) rememberSlowClock(fps = 12) else remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val t by clock
    val pulse = if (animated) 0.6f + 0.4f * ((sin(t / 1000f * 2.2f) + 1f) / 2f) else 0.8f
    val flicker = animated && (t / 80) % 47 in 0L..1L
    val shape = hudShape(12.dp)
    Box(modifier.clip(shape)) {
        Image(
            portrait,
            contentDescription = "As//ka",
            contentScale = ContentScale.Crop,
            alpha = if (flicker) 0.55f else 0.92f,
            // A little pink washed over the picture, like a projection.
            colorFilter = ColorFilter.tint(AskaColors.Pink.copy(alpha = 0.18f), BlendMode.SrcAtop),
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            // Scanlines.
            val step = 3f * density
            var y = (t / 40 % 3).toFloat() * density
            while (y < size.height) {
                drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
            // Holo glow from the bottom, as if projected from the deck.
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, AskaColors.Pink.copy(alpha = 0.22f * pulse * intensity.coerceAtMost(2f))),
                    startY = size.height * 0.55f,
                )
            )
            drawRect(AskaColors.Pink.copy(alpha = 0.45f + 0.4f * pulse), style = Stroke(width = 1.5f * density))
        }
    }
}
