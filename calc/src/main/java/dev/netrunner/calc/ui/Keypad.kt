package dev.netrunner.calc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.hudPanel

/** One keypad key. [enabled] false dims it (digits above the current base). */
class Key(
    val label: String,
    val color: Color,
    val filled: Boolean = false,
    val weight: Float = 1f,
    val enabled: Boolean = true,
    val size: TextUnit = 20.sp,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Rows of keys sharing the available height evenly. */
@Composable
fun Keypad(rows: List<List<Key>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { KeyCell(it) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.KeyCell(key: Key) {
    val haptic = LocalHapticFeedback.current
    val color = if (key.enabled) key.color else Cp.Dim.copy(alpha = 0.4f)
    Box(
        Modifier
            .weight(key.weight)
            .fillMaxHeight()
            .hudPanel(borderColor = color.copy(alpha = if (key.filled) 1f else 0.55f), fill = if (key.filled) color else Cp.Panel.copy(alpha = 0.85f), cut = 8.dp)
            .combinedClickable(
                enabled = key.enabled,
                onLongClick = key.onLongClick?.let { long -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); long() } },
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); key.onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key.label, color = if (key.filled) Cp.Background else color, fontFamily = HudFont,
            fontWeight = FontWeight.Bold, fontSize = key.size, maxLines = 1,
        )
    }
}
