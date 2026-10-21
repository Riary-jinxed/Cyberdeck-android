package dev.netrunner.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Angled "tech panel" shape, as on the launcher. */
fun hudShape(cut: Dp = 10.dp) = CutCornerShape(topEnd = cut, bottomStart = cut)

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

fun Modifier.cyberGrid(cell: Dp = 36.dp): Modifier = drawBehind {
    drawRect(Cp.Background)
    val step = cell.toPx()
    val line = Cp.Cyan.copy(alpha = 0.06f)
    var x = 0f
    while (x < size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height)); x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y)); y += step
    }
    drawRect(
        Brush.radialGradient(listOf(Cp.Yellow.copy(alpha = 0.10f), Color.Transparent), Offset(size.width * 0.2f, 0f), size.width)
    )
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

/** Bottom tab bar shared by the deck apps. */
@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cp.Panel)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label, color = if (on) Cp.Yellow else Cp.Dim, fontFamily = HudFont,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                )
                Box(
                    Modifier.padding(top = 4.dp).height(2.dp).fillMaxWidth(0.6f)
                        .background(if (on) Cp.Yellow else Cp.Panel)
                )
            }
        }
    }
}

@Composable
fun hudSwitch() = SwitchDefaults.colors(
    checkedThumbColor = Cp.Background, checkedTrackColor = Cp.Yellow,
    uncheckedThumbColor = Cp.Dim, uncheckedTrackColor = Cp.Panel, uncheckedBorderColor = Cp.Dim,
)

@Composable
fun hudFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Cp.Panel, unfocusedContainerColor = Cp.Panel,
    focusedTextColor = Cp.Text, unfocusedTextColor = Cp.Text,
    focusedIndicatorColor = Cp.Yellow, unfocusedIndicatorColor = Cp.Dim,
    cursorColor = Cp.Yellow, focusedLabelColor = Cp.Yellow, unfocusedLabelColor = Cp.Dim,
)

/** True while the app is visible: every ticking loop keys on it, so nothing runs in the background. */
@Composable
fun rememberScreenActive(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
