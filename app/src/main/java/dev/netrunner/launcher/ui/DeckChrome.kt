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
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.graphicsLayer
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

private fun bootLines(handle: String, version: String) = listOf(
    "NETRUNNER BIOS v$version // CYBERDECK MK.V",
    "> MONTAGE DU CYBERDECK............ OK",
    "> SLOTS RAM....................... OK",
    "> DAEMONS QUICKHACK............... CHARGÉS",
    "> LIAISON NEURALE................. SYNCHRO",
    "> ICE LOCAL....................... AUCUN",
    "> IDENTITÉ........................ ${handle.uppercase()}",
    "> JACK-IN.",
)

/** Boot animation played once when the launcher starts, in the style picked in the settings. A tap skips it. */
@Composable
fun BootSequence(onDone: () -> Unit) {
    val context = LocalContext.current
    val style = DeckSettings.bootStyle
    if (style == BootStyle.Off) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    // Milliseconds since the animation started, advanced every frame (the boot only lasts ~2 s).
    val elapsed by produceState(0L) {
        val start = withFrameMillis { it }
        while (true) value = withFrameMillis { it } - start
    }
    val total = when (style) {
        BootStyle.Bios -> 1700L
        BootStyle.Breach -> 2100L
        else -> 1900L
    }
    LaunchedEffect(Unit) {
        UiSound.play(context, if (style == BootStyle.Corpo) UiSound.Cue.JackIn else UiSound.Cue.Glitch)
        delay(total)
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
        when (style) {
            BootStyle.Breach -> BreachBoot(elapsed)
            BootStyle.Corpo -> CorpoBoot(elapsed)
            else -> BiosBoot(elapsed, total)
        }
    }
}

@Composable
private fun BiosBoot(elapsed: Long, total: Long) {
    val context = LocalContext.current
    val lines = remember {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        bootLines(DeckSettings.handle, version)
    }
    val shown = ((elapsed / 150L).toInt() + 1).coerceAtMost(lines.size)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.take(shown).forEachIndexed { i, line ->
            Text(
                line,
                color = if (i == 0) Cp.Yellow else if (line.endsWith("OK") || line.endsWith("CHARGÉS")) Cp.Cyan else Cp.Text,
                fontFamily = MonoFont,
                fontSize = 13.sp,
                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth((elapsed / total.toFloat()).coerceIn(0f, 1f))
                .height(4.dp)
                .background(Cp.Yellow)
        )
    }
}

private const val HEX = "0123456789ABCDEF"

/** Code matrix that resolves into "access granted", like the Breach Protocol minigame. */
@Composable
private fun BreachBoot(elapsed: Long) {
    val rows = 9
    val cols = 6
    val solved = (elapsed / 160L).toInt()
    val seed = elapsed / 60L
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("// BREACH PROTOCOL", color = Cp.Yellow, fontFamily = DisplayFont, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (c in 0 until cols) {
                    val locked = r * cols + c < solved * 3
                    val h = ((seed + r * 31 + c * 17) * 2654435761L ushr 7).toInt()
                    val cell = if (locked) listOf("1C", "BD", "55", "E9", "7A")[(r + c) % 5]
                    else "${HEX[(h and 15)]}${HEX[(h shr 4) and 15]}"
                    Text(
                        cell,
                        color = if (locked) Cp.Cyan else Cp.Dim.copy(alpha = 0.6f),
                        fontFamily = MonoFont,
                        fontSize = 16.sp,
                        fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (elapsed > 1400) {
            GlitchTitle("ACCÈS AUTORISÉ", elapsed, Cp.Cyan, 26)
            Text("BIENVENUE, ${DeckSettings.handle.uppercase()}", color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp)
        } else {
            Text("> INJECTION DU DAEMON… ${(elapsed / 14).coerceAtMost(99)}%", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 13.sp)
        }
    }
}

/** Corporate splash: big glitching logo, faction name and a sweeping line. */
@Composable
private fun CorpoBoot(elapsed: Long) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlitchTitle("NETRUNNER", elapsed, Cp.Yellow, 38)
        Box(
            Modifier
                .fillMaxWidth((elapsed / 900f).coerceIn(0f, 1f))
                .height(2.dp)
                .background(Cp.Red)
        )
        if (elapsed > 600) {
            Text(
                "${Cp.faction.label.uppercase()} // SYSTÈMES EN LIGNE",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (elapsed > 1100) {
            Text("BIENVENUE, ${DeckSettings.handle.uppercase()}", color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp)
        }
    }
}

/** Title with an RGB split that jitters for a few frames, then settles. */
@Composable
private fun GlitchTitle(text: String, elapsed: Long, color: androidx.compose.ui.graphics.Color, size: Int) {
    val phase = (elapsed / 70L).toInt()
    val jitter = if (phase % 7 < 2) ((phase * 37) % 9 - 4).toFloat() else 0f
    Box(contentAlignment = Alignment.Center) {
        Text(text, color = Cp.Red.copy(alpha = 0.7f), fontFamily = DisplayFont, fontSize = size.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { translationX = jitter * 2f })
        Text(text, color = Cp.Cyan.copy(alpha = 0.7f), fontFamily = DisplayFont, fontSize = size.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { translationX = -jitter * 2f })
        Text(text, color = color, fontFamily = DisplayFont, fontSize = size.sp, fontWeight = FontWeight.Bold)
    }
}
