package dev.netrunner.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.system.SystemInfo
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** RAM slots of the deck, in the blue of the in-game cyberdeck bar. */
private val DeckBlue = Color(0xFF2BD9FE)

/** Yellow corner brackets around the whole screen, like the edges of the game's HUD. */
fun Modifier.hudFrame(color: Color = Cp.Yellow.copy(alpha = 0.7f)): Modifier = drawWithContent {
    drawContent()
    val arm = 22.dp.toPx()
    val inset = 6.dp.toPx()
    val stroke = 2.dp.toPx()
    val w = size.width
    val h = size.height
    val corners = listOf(
        Offset(inset, inset) to Offset(1f, 1f),
        Offset(w - inset, inset) to Offset(-1f, 1f),
        Offset(inset, h - inset) to Offset(1f, -1f),
        Offset(w - inset, h - inset) to Offset(-1f, -1f),
    )
    corners.forEach { (p, d) ->
        drawLine(color, p, Offset(p.x + arm * d.x, p.y), stroke)
        drawLine(color, p, Offset(p.x, p.y + arm * d.y), stroke)
    }
    // Side ticks, half-way down.
    val tick = 8.dp.toPx()
    drawLine(Cp.Cyan.copy(alpha = 0.5f), Offset(inset, h / 2 - tick), Offset(inset, h / 2 + tick), stroke)
    drawLine(Cp.Cyan.copy(alpha = 0.5f), Offset(w - inset, h / 2 - tick), Offset(w - inset, h / 2 + tick), stroke)
}

/** "RAM ▮▮▮▮▮▯▯▯" deck memory bar with the battery temperature, refreshed every 10 s while visible. */
@Composable
fun DeckRamBar() {
    val context = LocalContext.current
    val active = rememberScreenActive()
    val usage by produceState(0f to null as Float?, active) {
        while (active) {
            value = withContext(Dispatchers.Default) {
                val mem = SystemInfo.memory(context)
                val used = if (mem.totalBytes > 0) 1f - mem.availBytes.toFloat() / mem.totalBytes else 0f
                used to SystemInfo.battery(context).temperatureC
            }
            delay(10_000)
        }
    }
    val slots = 14
    val filled = (usage.first * slots).toInt().coerceIn(0, slots)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("RAM", color = DeckBlue, fontFamily = HudFont, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(slots) { i ->
                Box(
                    Modifier
                        .size(width = 9.dp, height = 7.dp)
                        .background(if (i < filled) DeckBlue else DeckBlue.copy(alpha = 0.15f))
                )
            }
        }
        Spacer(Modifier.weight(1f))
        usage.second?.let { temp ->
            Text(
                "TEMP %.0f°C".format(temp),
                color = if (temp >= 42f) Cp.Red else Cp.Dim,
                fontFamily = HudFont,
                fontSize = 10.sp,
            )
        }
    }
}

private val BootLines = listOf(
    "NETRUNNER BIOS v0.8 // CYBERDECK MK.V",
    "> MONTAGE DU CYBERDECK............ OK",
    "> SLOTS RAM....................... OK",
    "> DAEMONS QUICKHACK............... CHARGÉS",
    "> LIAISON NEURALE................. SYNCHRO",
    "> ICE LOCAL....................... AUCUN",
    "> JACK-IN.",
)

/** Short boot log played once when the launcher starts. A tap skips it. */
@Composable
fun BootSequence(onDone: () -> Unit) {
    var shown by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        UiSound.play(context, UiSound.Cue.Glitch)
        while (shown < BootLines.size) {
            delay(140)
            shown++
        }
        delay(350)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Cp.Background)
            .scanlines()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone)
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BootLines.take(shown).forEachIndexed { i, line ->
                Text(
                    line,
                    color = if (i == 0) Cp.Yellow else if (line.endsWith("OK") || line.endsWith("CHARGÉS")) Cp.Cyan else Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 13.sp,
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(shown / BootLines.size.toFloat())
                    .height(4.dp)
                    .background(Cp.Yellow)
            )
        }
    }
}
