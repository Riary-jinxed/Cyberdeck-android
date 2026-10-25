package dev.netrunner.aska

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

private val Skin = Color(0xFFF4DCD2)
private val Sleeve = Color(0xFF15151F)

/** Which key lights up for tap [taps]: left half for the left hand, right half for the right one. */
fun bongoKey(taps: Long, rows: Int = 3, cols: Int = 10): Pair<Int, Int> {
    val left = taps % 2L == 0L
    val n = ((taps * 7919L) ushr 1).mod((rows * cols / 2).toLong()).toInt()
    val row = n / (cols / 2)
    val col = n % (cols / 2) + if (left) 0 else cols / 2
    return row to col
}

/**
 * Her keyboard, in front of her, Bongo Cat style: on every key the runner presses, one of her
 * hands slaps a key, left then right, and the key flashes pink. It slides in while the runner types.
 */
@Composable
fun BongoKeyboard(taps: Long, visible: Boolean, width: Dp, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(260))
    val flash = remember { Animatable(0f) }
    LaunchedEffect(taps) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(180))
    }
    if (shown == 0f) return
    Canvas(
        modifier.width(width).height(width * 0.42f).graphicsLayer {
            alpha = shown
            translationY = (1f - shown) * size.height * 0.5f
        },
    ) {
        val w = size.width
        val h = size.height
        val top = h * 0.46f
        val rows = 3
        val cols = 10
        // The keyboard, slightly in perspective.
        val body = Path().apply {
            moveTo(w * 0.1f, top); lineTo(w * 0.9f, top); lineTo(w * 0.98f, h * 0.96f); lineTo(w * 0.02f, h * 0.96f); close()
        }
        drawPath(body, Cp.Panel)
        drawPath(body, Cp.Pink, style = Stroke(width = 2f))
        val (litRow, litCol) = bongoKey(taps, rows, cols)
        for (r in 0 until rows) {
            val f0 = 0.1f + r * 0.29f
            val y = top + (h * 0.96f - top) * f0
            val keyH = (h * 0.96f - top) * 0.22f
            val inset = w * (0.13f - 0.035f * r)
            val keyW = (w - 2 * inset) / cols
            for (c in 0 until cols) {
                val lit = r == litRow && c == litCol && taps > 0
                val color = if (lit) lerp(Cp.Dim.copy(alpha = 0.55f), Cp.Pink, flash.value.coerceAtLeast(0.35f)) else Cp.Dim.copy(alpha = 0.55f)
                drawRoundRect(color, Offset(inset + c * keyW + keyW * 0.1f, y), Size(keyW * 0.8f, keyH), CornerRadius(3f))
            }
        }
        // Her hands: the one that just played rests on the keys, the other one waits up high.
        val left = taps % 2L == 0L
        hand(Offset(w * 0.3f, if (taps > 0 && left) top + h * 0.02f else top - h * 0.3f), w, h)
        hand(Offset(w * 0.7f, if (taps > 0 && !left) top + h * 0.02f else top - h * 0.3f), w, h)
    }
}

private fun DrawScope.hand(at: Offset, w: Float, h: Float) {
    val handW = w * 0.15f
    val handH = h * 0.3f
    // A bit of black sleeve-glove up the wrist, then the hand, then pink nails at the tips.
    drawRoundRect(Sleeve, Offset(at.x - handW * 0.38f, at.y - handH * 0.9f), Size(handW * 0.76f, handH * 0.8f), CornerRadius(handW * 0.3f))
    drawRoundRect(Skin, Offset(at.x - handW / 2, at.y - handH * 0.35f), Size(handW, handH * 0.7f), CornerRadius(handW * 0.45f))
    for (i in 0 until 3) {
        val x = at.x - handW * 0.28f + i * handW * 0.28f
        drawOval(Cp.Pink, Offset(x - handW * 0.08f, at.y + handH * 0.2f), Size(handW * 0.16f, handH * 0.13f))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)
