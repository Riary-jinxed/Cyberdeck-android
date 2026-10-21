package dev.netrunner.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Icons drawn in the game's HUD style: straight cuts, chamfered corners, no rounded shapes. */
enum class HudGlyph { Phone, Message, Wifi, Kit }

@Composable
fun HudIcon(glyph: HudGlyph, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        // Every glyph is designed on a 24 × 24 grid.
        val u = this.size.minDimension / 24f
        when (glyph) {
            HudGlyph.Phone -> phone(u, color)
            HudGlyph.Message -> message(u, color)
            HudGlyph.Wifi -> wifi(u, color)
            HudGlyph.Kit -> kit(u, color)
        }
    }
}

private fun DrawScope.poly(u: Float, vararg pts: Pair<Float, Float>, close: Boolean = true): Path = Path().apply {
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
    if (close) close()
}

private fun DrawScope.line(u: Float, color: Color, width: Float, vararg pts: Pair<Float, Float>) {
    drawPath(
        poly(u, *pts, close = false),
        color,
        style = Stroke(width = width * u, cap = StrokeCap.Square, join = StrokeJoin.Miter),
    )
}

/** Angular handset with two signal brackets, like the holo-call prompt. */
private fun DrawScope.phone(u: Float, color: Color) {
    drawPath(
        poly(
            u,
            5f to 2f, 9f to 2f, 11f to 7f, 8f to 9.5f, 14.5f to 16f, 17f to 13f,
            22f to 15f, 22f to 19f, 19f to 22f, 15f to 22f, 2f to 9f, 2f to 5f,
        ),
        color,
    )
    line(u, color, 1.6f, 14f to 2f, 22f to 2f, 22f to 10f)
    line(u, color, 1.6f, 14f to 6f, 18f to 6f, 18f to 10f)
}

/** Data-shard envelope: chamfered corner, flap and a filled "unread" block. */
private fun DrawScope.message(u: Float, color: Color) {
    line(u, color, 2f, 2f to 5f, 17f to 5f, 22f to 10f, 22f to 19f, 2f to 19f, 2f to 5f)
    line(u, color, 2f, 2f to 5f, 12f to 12.5f, 17f to 8.75f)
    drawPath(poly(u, 19f to 2f, 23f to 2f, 23f to 6f), color)
    line(u, color, 1.4f, 5f to 16f, 9f to 16f)
}

/** Field case: chamfered box, handle and a latch block. */
private fun DrawScope.kit(u: Float, color: Color) {
    line(u, color, 2f, 2f to 9f, 5f to 6f, 19f to 6f, 22f to 9f, 22f to 21f, 2f to 21f, 2f to 9f)
    line(u, color, 2f, 8f to 6f, 8f to 3f, 16f to 3f, 16f to 6f)
    line(u, color, 1.6f, 2f to 13f, 22f to 13f)
    drawRect(color, Offset(10f * u, 11f * u), androidx.compose.ui.geometry.Size(4f * u, 4f * u))
}

/** Signal as three chevrons narrowing to a square node. */
private fun DrawScope.wifi(u: Float, color: Color) {
    line(u, color, 2f, 2f to 9f, 12f to 3f, 22f to 9f)
    line(u, color, 2f, 5.5f to 13f, 12f to 9f, 18.5f to 13f)
    line(u, color, 2f, 9f to 17f, 12f to 15f, 15f to 17f)
    drawRect(color, Offset(10.5f * u, 19f * u), androidx.compose.ui.geometry.Size(3f * u, 3f * u))
}
