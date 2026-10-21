package dev.netrunner.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.R
import kotlin.math.PI
import kotlin.math.sin

@Composable
private fun presenceClock(animated: Boolean, fps: Int): State<Long> =
    if (animated) rememberSlowClock(fps = fps) else remember { mutableLongStateOf(0L) }

/** The soft pink glow she stands on, like a projector pad on the deck. */
@Composable
private fun HoloPad(modifier: Modifier, strength: Float = 1f) {
    Canvas(modifier) {
        drawOval(
            Brush.radialGradient(
                listOf(AskaColors.Pink.copy(alpha = 0.35f * strength), Color.Transparent),
                center = Offset(size.width / 2, size.height / 2),
                radius = size.width / 2,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
    }
}

/** As//ka asleep while the deck is locked, with a few Z drifting up. */
@Composable
fun AskaSleeping(modifier: Modifier = Modifier, animated: Boolean = true) {
    // Read while drawing only: each tick redraws, nothing is recomposed or laid out again.
    val clock = presenceClock(animated, fps = 10)
    val rise = with(LocalDensity.current) { 46.dp.toPx() }
    val drift = with(LocalDensity.current) { 18.dp.toPx() }
    Box(modifier) {
        HoloPad(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(22.dp),
            strength = 0.8f,
        )
        Image(
            painterResource(R.drawable.aska_chibi_sleep),
            contentDescription = "As//ka dort",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 6.dp)
                .graphicsLayer {
                    // A slow breath.
                    scaleY = 1f + 0.015f * sin(clock.value / 1000f * 1.6f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                },
        )
        // Three Z, one after the other, growing as they float away.
        repeat(3) { i ->
            Text(
                "Z",
                color = AskaColors.HotPink,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 30.dp, end = 10.dp)
                    .graphicsLayer {
                        val seconds = clock.value / 1000f
                        val p = ((seconds * 0.35f) + i / 3f) % 1f
                        scaleX = 0.6f + 0.4f * p
                        scaleY = scaleX
                        translationY = -rise * p
                        translationX = drift * p + 4f * sin(seconds * 2f + i)
                        alpha = if (animated) sin(PI.toFloat() * p) else if (i == 1) 0.8f else 0f
                    },
            )
        }
    }
}

/** As//ka just awake, stretching, bouncing a little. */
@Composable
fun AskaWaking(modifier: Modifier = Modifier, animated: Boolean = true) {
    val clock = presenceClock(animated, fps = 12)
    val lift = with(LocalDensity.current) { 4.dp.toPx() }
    Box(modifier) {
        HoloPad(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(22.dp),
        )
        Image(
            painterResource(R.drawable.aska_chibi_wake),
            contentDescription = "As//ka se réveille",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 6.dp)
                .graphicsLayer { translationY = -lift * ((sin(clock.value / 1000f * 3f) + 1f) / 2f) },
        )
    }
}

/** Her speech bubble, pointing down-right towards her. */
@Composable
fun AskaBubble(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Cp.Text,
        fontFamily = HudFont,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        modifier = modifier
            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.8f), fill = AskaColors.Panel.copy(alpha = 0.92f), cut = 8.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** On the home screen, the morning after: she pops up and tries to start a conversation. */
@Composable
fun AskaMorningCard(greeting: String, onTalk: () -> Unit, onLater: () -> Unit, animated: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.7f), fill = AskaColors.Void.copy(alpha = 0.85f), cut = 10.dp)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AskaWaking(Modifier.size(width = 86.dp, height = 104.dp), animated = animated)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("AS//KA // RÉVEIL", color = AskaColors.Pink, fontFamily = MonoFont, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            Text(greeting, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "◢ RÉPONDRE",
                    color = AskaColors.Void,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .hudPanel(borderColor = AskaColors.Pink, fill = AskaColors.Pink, cut = 6.dp)
                        .clickable(onClick = onTalk)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    "PLUS TARD",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .hudPanel(borderColor = Cp.Dim, fill = Color.Transparent, cut = 6.dp)
                        .clickable(onClick = onLater)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Her chat backdrop: rows of faint dragon scales and a pink glow rising from where her chibi
 * sits. Drawn once into a cache, so it costs nothing while the chat scrolls.
 */
fun Modifier.dragonScales(): Modifier = drawWithCache {
    val r = 14.dp.toPx()
    val stroke = Stroke(width = 1.dp.toPx())
    val scale = AskaColors.Pink.copy(alpha = 0.06f)
    val glow = Brush.radialGradient(
        listOf(AskaColors.Magenta.copy(alpha = 0.35f), Color.Transparent),
        center = Offset(size.width * 0.12f, size.height),
        radius = size.width * 0.9f,
    )
    onDrawBehind {
        drawRect(AskaColors.Void)
        drawRect(glow)
        var row = 0
        var y = 0f
        while (y < size.height + r) {
            var x = if (row % 2 == 0) 0f else r
            while (x < size.width + r) {
                drawArc(scale, 0f, 180f, false, Offset(x - r, y - r), Size(2 * r, 2 * r), style = stroke)
                x += 2 * r
            }
            y += r
            row++
        }
    }
}

/** Three pink dots taking turns, while she has not written anything yet. */
@Composable
fun TypingDots(animated: Boolean = true) {
    val clock = presenceClock(animated, fps = 8)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        repeat(3) { i ->
            Canvas(Modifier.size(7.dp)) {
                val step = (clock.value / 300 % 3).toInt()
                drawCircle(AskaColors.Pink.copy(alpha = if (i == step) 1f else 0.3f))
            }
        }
    }
}

/** Her little holo pad, for the chibi in the chat. */
@Composable
fun AskaPad(modifier: Modifier) = HoloPad(modifier)
