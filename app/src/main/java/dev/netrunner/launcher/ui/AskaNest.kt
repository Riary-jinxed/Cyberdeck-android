package dev.netrunner.launcher.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.R
import dev.netrunner.launcher.aska.AskaPet
import dev.netrunner.launcher.aska.AskaPet.Mood
import kotlin.math.sin
import kotlinx.coroutines.delay

@DrawableRes
internal fun Mood.drawable(): Int = when (this) {
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
    // Her daily gift, once the nest is open.
    LaunchedEffect(Unit) { pet.dailyGift() }
    val mood = pet.mood()
    val stats = pet.stats
    var mode by remember { mutableStateOf(NestMode.Nest) }

    if (mode == NestMode.Game) {
        MacaronGame(modifier.padding(top = 10.dp, bottom = 10.dp)) { caught ->
            pet.played(caught)
            mode = NestMode.Nest
        }
        return
    }

    Column(modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NID // AS//KA", color = AskaColors.Pink, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(stats.title, color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("¢ ${stats.credits}", color = AskaColors.HotPink, fontFamily = MonoFont, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            Text("NIV ${stats.level}", color = Cp.Yellow, fontFamily = DisplayFont, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(4.dp))
        Bar(stats.levelProgress, Cp.Yellow, Modifier.fillMaxWidth().height(3.dp))

        val looks = pet.looks
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestDecor(looks.getValue(AskaPet.Slot.Decor)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AskaBubble(pet.line, Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(width = 230.dp, height = 250.dp), contentAlignment = Alignment.BottomCenter) {
                    Aura(looks.getValue(AskaPet.Slot.Aura), Modifier.fillMaxSize())
                    AskaPad(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        color = Color(looks.getValue(AskaPet.Slot.Pad).argb),
                    )
                    NestChibi(mood, active, Modifier.fillMaxSize().padding(bottom = 10.dp)) { pet.pet() }
                    reaction?.particle()?.let { Particles(it, key = pet.line) }
                }
            }
        }

        if (mode != NestMode.Nest) {
            when (mode) {
                NestMode.Shop -> Shop(pet)
                NestMode.Wardrobe -> Wardrobe(pet)
                else -> Journal(pet)
            }
            Spacer(Modifier.height(8.dp))
            NestButton("◂ RETOUR AU NID", Cp.Cyan, Modifier.fillMaxWidth()) { mode = NestMode.Nest }
        } else {
            StatRow("GOURMANDISE", stats.food / 100f, AskaColors.HotPink)
            StatRow("HUMEUR", stats.joy / 100f, Cp.Yellow)
            StatRow("ÉNERGIE", stats.energy / 100f, Cp.Cyan)
            StatRow("LIEN", stats.bond / 100f, AskaColors.Pink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NestButton("◢ MACARON", AskaColors.Pink, Modifier.weight(1f), pet::feed)
                NestButton("◆ JEU", Cp.Yellow, Modifier.weight(1f)) { if (pet.canPlay()) mode = NestMode.Game }
                NestButton("♥ CÂLIN", AskaColors.HotPink, Modifier.weight(1f), pet::pet)
                NestButton(if (stats.asleep) "☀ RÉVEIL" else "☾ DODO", Cp.Cyan, Modifier.weight(1f), pet::toggleSleep)
            }
            Spacer(Modifier.height(8.dp))
            val bag = stats.stock.values.sum()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NestButton("◈ BOUTIQUE" + if (bag > 0) " ($bag)" else "", AskaColors.HotPink, Modifier.weight(1f)) { mode = NestMode.Shop }
                NestButton("✦ GARDE-ROBE", Cp.Yellow, Modifier.weight(1f)) { mode = NestMode.Wardrobe }
                NestButton("▤ JOURNAL", Cp.Cyan, Modifier.weight(1f)) { mode = NestMode.Journal }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

private enum class NestMode { Nest, Game, Shop, Wardrobe, Journal }

/** Her looks, unlocked as she grows, and whether she may call when neglected. */
@Composable
private fun Wardrobe(pet: AskaPet) {
    val level = pet.stats.level
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AskaPet.Slot.entries.forEach { slot ->
            Text(slot.label, color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                AskaPet.Look.entries.filter { it.slot == slot }.forEach { look ->
                    val worn = pet.looks[slot] == look
                    val open = look.level <= level
                    NestButton(
                        if (open) (if (worn) "■ " else "") + look.label else "⌧ NIV ${look.level}",
                        when {
                            worn -> AskaColors.HotPink
                            open -> Color(look.argb).takeIf { look.argb != 0L } ?: Cp.Text
                            else -> Cp.Dim
                        },
                        Modifier.widthIn(min = 84.dp),
                    ) { pet.wear(look) }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (!pet.calls && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    pet.toggleCalls(context)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (pet.calls) "[■]" else "[ ]", color = AskaColors.Pink, fontFamily = MonoFont, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Elle te réclame quand tu la négliges (une notification par jour au plus, entre 10 h et 21 h)",
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 12.sp,
            )
        }
    }
}

/** Her memories together, newest first. */
@Composable
private fun Journal(pet: AskaPet) {
    val format = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.FRENCH) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pet.journal.isEmpty()) {
            Text("Rien encore… prends soin d'elle, et les souvenirs viendront.", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
        }
        pet.journal.forEach { memory ->
            Row {
                Text(
                    java.time.LocalDate.ofEpochDay(memory.day).format(format).uppercase(),
                    color = AskaColors.HotPink,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    modifier = Modifier.width(86.dp),
                )
                Text(memory.text, color = Cp.Text, fontFamily = HudFont, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
    }
}

/** The glow behind her, in the color of the aura she wears. */
@Composable
private fun Aura(look: AskaPet.Look, modifier: Modifier) {
    if (look.argb == 0L) return
    val glitch = look == AskaPet.Look.AuraGlitch
    androidx.compose.foundation.Canvas(modifier) {
        val center = Offset(size.width / 2, size.height * 0.55f)
        val colors = if (glitch) listOf(Color(look.argb), Cp.Cyan) else listOf(Color(look.argb))
        colors.forEachIndexed { i, c ->
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.32f), Color.Transparent), center = center + Offset(i * 10f, -i * 6f), radius = size.minDimension * 0.55f),
                radius = size.minDimension * 0.55f,
                center = center + Offset(i * 10f, -i * 6f),
            )
        }
    }
}

/** The backdrop of her nest, drawn once into a cache. */
private fun Modifier.nestDecor(look: AskaPet.Look): Modifier {
    if (look.argb == 0L) return this
    if (look == AskaPet.Look.DecorScales) return this.dragonScales()
    val color = Color(look.argb)
    return drawWithCache {
        val step = 22.dp.toPx()
        val random = java.util.Random(7)
        onDrawBehind {
            when (look) {
                AskaPet.Look.DecorGrid -> {
                    var x = 0f
                    while (x < size.width) { drawLine(color.copy(alpha = 0.10f), Offset(x, 0f), Offset(x, size.height)); x += step }
                    var y = 0f
                    while (y < size.height) { drawLine(color.copy(alpha = 0.10f), Offset(0f, y), Offset(size.width, y)); y += step }
                }
                AskaPet.Look.DecorStars -> repeat(60) {
                    drawCircle(color.copy(alpha = 0.15f + random.nextFloat() * 0.35f), 1f + random.nextFloat() * 2.2f, Offset(random.nextFloat() * size.width, random.nextFloat() * size.height))
                }
                else -> repeat(18) {
                    // Rain of code: faint vertical dashes, like a waterfall frozen in place.
                    val x = random.nextFloat() * size.width
                    var y = random.nextFloat() * size.height * 0.5f
                    repeat(6 + random.nextInt(8)) {
                        drawRect(color.copy(alpha = 0.08f + random.nextFloat() * 0.18f), Offset(x, y), androidx.compose.ui.geometry.Size(3f, 9f))
                        y += 14f
                    }
                }
            }
        }
    }
}

/** Her treats: buy with the credits won at her game, then give them to her. */
@Composable
private fun Shop(pet: AskaPet) {
    val stats = pet.stats
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Gagne des ¢ au jeu (un par macaron) et chaque jour en ouvrant son nid. Record : ${stats.bestScore}.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
        AskaPet.Treat.entries.forEach { treat ->
            val owned = stats.stock[treat] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(treat.glyph, color = AskaColors.Pink, fontFamily = DisplayFont, fontSize = 16.sp, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(treat.label, color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(effects(treat), color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp)
                }
                NestButton("${treat.price} ¢", if (stats.credits >= treat.price) Cp.Yellow else Cp.Dim, Modifier.width(64.dp)) { pet.buy(treat) }
                Spacer(Modifier.width(6.dp))
                NestButton(if (owned > 0) "DONNER ×$owned" else "—", if (owned > 0) AskaColors.Pink else Cp.Dim, Modifier.width(96.dp)) { pet.give(treat) }
            }
        }
    }
}

private fun effects(treat: AskaPet.Treat) = listOfNotNull(
    treat.food.takeIf { it > 0 }?.let { "GOURM +${it.toInt()}" },
    treat.joy.takeIf { it > 0 }?.let { "HUMEUR +${it.toInt()}" },
    treat.energy.takeIf { it > 0 }?.let { "ÉNERGIE +${it.toInt()}" },
    treat.bond.takeIf { it > 0 }?.let { "LIEN +${it.toInt()}" },
).joinToString(" · ")

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
