package dev.netrunner.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.hudPanel

/** Vertical bars centred on a line; bars before [progress] (0..1) are drawn in [played]. */
@Composable
fun WaveBars(bars: IntArray, modifier: Modifier, color: Color = Cp.Cyan, played: Color = Cp.Yellow, progress: Float = 0f) {
    Canvas(modifier) {
        if (bars.isEmpty()) return@Canvas
        val step = size.width / bars.size
        val stroke = (step * 0.6f).coerceAtLeast(1f)
        val mid = size.height / 2
        bars.forEachIndexed { i, v ->
            val h = (v / 100f).coerceIn(0.04f, 1f) * size.height / 2
            val x = i * step + step / 2
            drawLine(if (i < bars.size * progress) played else color, Offset(x, mid - h), Offset(x, mid + h), stroke, StrokeCap.Round)
        }
    }
}

@Composable
fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1,
        modifier = Modifier.padding(start = 6.dp).hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 5.dp).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun Message(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
}
