package dev.netrunner.launcher.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.R
import dev.netrunner.launcher.aska.AskaPet
import dev.netrunner.launcher.aska.AskaPet.Mood
import kotlinx.coroutines.delay
import kotlin.math.sin

@DrawableRes
private fun Mood.drawable(): Int = when (this) {
    Mood.Sleeping -> R.drawable.aska_chibi_sleep
    Mood.Hungry, Mood.Eating -> R.drawable.aska_chibi_gourmand
    Mood.Tired -> R.drawable.aska_chibi_waiting
    Mood.Bored -> R.drawable.aska_chibi_thinking
    Mood.Happy -> R.drawable.aska_chibi_happy
    Mood.Shy -> R.drawable.aska_chibi_shy
    Mood.Mischievous -> R.drawable.aska_chibi_mischievous
    Mood.Content -> R.drawable.aska_chibi_talking
}

/** What floats up from her after a care. */
private fun Mood.particle(): String? = when (this) {
    Mood.Shy -> "♥"
    Mood.Eating -> "●"
    Mood.Mischievous -> "◆"
    Mood.Sleeping -> "Z"
    Mood.Happy -> "✦"
    else -> null
}

/**
 * Her nest, a little Tamagotchi: touch her chibi to cuddle her, feed her macarons, play, put
 * her to bed. Her needs drift with time even when the nest is closed (computed on opening,
 * nothing runs in the background).
 */
@Composable
fun AskaNest(pet: AskaPet, modifier: Modifier = Modifier) {
    val active = rememberScreenActive()
    // Time passes while the nest is open too, slowly: a check per minute is plenty.
    LaunchedEffect(active) {
        while (active) {
            pet.refresh()
            delay(60_000)
        }
    }
    val reaction = pet.reaction
    LaunchedEffect(reaction, pet.line) {
        if (reaction != null) {
            delay(4_000)
            pet.calm()
        }
    }
    val mood = pet.mood()
    val stats = pet.stats

    Column(modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NID // AS//KA", color = AskaColors.Pink, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("NIV ${stats.level}", color = Cp.Yellow, fontFamily = DisplayFont, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(4.dp))
        Bar(stats.levelProgress, Cp.Yellow, Modifier.fillMaxWidth().height(3.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AskaBubble(pet.line, Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(width = 230.dp, height = 250.dp), contentAlignment = Alignment.BottomCenter) {
                    AskaPad(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                    )
                    NestChibi(mood, active, Modifier.fillMaxSize().padding(bottom = 10.dp)) { pet.pet() }
                    reaction?.particle()?.let { Particles(it, key = pet.line) }
                }
            }
        }

        StatRow("GOURMANDISE", stats.food / 100f, AskaColors.HotPink)
        StatRow("HUMEUR", stats.joy / 100f, Cp.Yellow)
        StatRow("ÉNERGIE", stats.energy / 100f, Cp.Cyan)
        StatRow("LIEN", stats.bond / 100f, AskaColors.Pink)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NestButton("◢ MACARON", AskaColors.Pink, Modifier.weight(1f), pet::feed)
            NestButton("◆ JOUER", Cp.Yellow, Modifier.weight(1f), pet::play)
            NestButton("♥ CÂLIN", AskaColors.HotPink, Modifier.weight(1f), pet::pet)
            NestButton(if (stats.asleep) "☀ RÉVEIL" else "☾ DODO", Cp.Cyan, Modifier.weight(1f), pet::toggleSleep)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun NestChibi(mood: Mood, animated: Boolean, modifier: Modifier, onTouch: () -> Unit) {
    val clock = if (animated) rememberSlowClock(fps = 12) else remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val lift = with(LocalDensity.current) { 6.dp.toPx() }
    Image(
        painterResource(mood.drawable()),
        contentDescription = "As//ka",
        modifier = modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTouch)
            .graphicsLayer {
                val seconds = clock.value / 1000f
                when (mood) {
                    // Asleep: a slow breath.
                    Mood.Sleeping -> {
                        scaleY = 1f + 0.015f * sin(seconds * 1.6f)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    // Playing or eating: little hops.
                    Mood.Mischievous, Mood.Eating, Mood.Happy ->
                        translationY = -lift * 1.6f * kotlin.math.abs(sin(seconds * 6f))
                    Mood.Tired -> translationY = -lift * 0.3f * ((sin(seconds * 1.2f) + 1f) / 2f)
                    else -> translationY = -lift * ((sin(seconds * 2f) + 1f) / 2f)
                }
            },
    )
}

/** Five glyphs floating up from her, once per reaction. */
@Composable
private fun Particles(glyph: String, key: Any) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(1_400, easing = LinearEasing)) }
    val rise = with(LocalDensity.current) { 120.dp.toPx() }
    val spread = with(LocalDensity.current) { 70.dp.toPx() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(5) { i ->
            Text(
                glyph,
                color = if (glyph == "Z") AskaColors.HotPink else AskaColors.Pink,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                modifier = Modifier.graphicsLayer {
                    val p = (progress.value * 1.3f - i * 0.07f).coerceIn(0f, 1f)
                    translationY = -rise * p
                    translationX = spread * ((i - 2) / 2f) * p + 6f * sin(p * 9f + i)
                    alpha = if (p <= 0f) 0f else 1f - p
                    scaleX = 0.7f + 0.5f * p
                    scaleY = scaleX
                },
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: Float, color: Color) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.width(96.dp))
        Bar(value, color, Modifier.weight(1f).height(8.dp))
        Text(
            "${(value * 100).toInt()}",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
private fun Bar(value: Float, color: Color, modifier: Modifier) {
    Box(modifier.background(color.copy(alpha = 0.15f))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color)))
        )
    }
}

@Composable
private fun NestButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .hudPanel(borderColor = color.copy(alpha = 0.8f), fill = AskaColors.Panel.copy(alpha = 0.9f), cut = 6.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
