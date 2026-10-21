package dev.netrunner.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.R
import kotlin.math.abs
import kotlin.random.Random

/** A falling thing: a macaron to catch, or a virus to dodge. Positions from 0 to 1. */
private class Drop(var x: Float, var y: Float, val speed: Float, val virus: Boolean)

/**
 * Her mini-game, "attrape les macarons": twenty seconds of macarons (and a few viruses)
 * falling on the deck; slide her left and right to catch them. Runs at the screen's rate,
 * only while a round lasts; the drops are drawn in a canvas, nothing else recomposes.
 */
@Composable
fun MacaronGame(modifier: Modifier = Modifier, onEnd: (Int) -> Unit) {
    val drops = remember { ArrayList<Drop>() }
    var player by remember { mutableFloatStateOf(0.5f) }
    var score by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(ROUND_SECONDS) }
    // Bumped each frame: read by the canvas only, so a frame redraws without recomposing.
    val frame = remember { mutableLongStateOf(0L) }
    var hit by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = start
        var spawnIn = 0.4f
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val elapsed = (now - start) / 1e9f
            val left = ROUND_SECONDS - elapsed
            if (left <= 0f) break
            secondsLeft = kotlin.math.ceil(left).toInt()
            // Faster and denser as the round goes on.
            val pace = 1f + elapsed / ROUND_SECONDS
            spawnIn -= dt
            if (spawnIn <= 0f) {
                drops += Drop(Random.nextFloat() * 0.84f + 0.08f, -0.05f, (0.32f + Random.nextFloat() * 0.18f) * pace, Random.nextFloat() < 0.18f)
                spawnIn = 0.62f / pace
            }
            val iterator = drops.iterator()
            while (iterator.hasNext()) {
                val d = iterator.next()
                d.y += d.speed * dt
                if (d.y in CATCH_LINE..(CATCH_LINE + 0.08f) && abs(d.x - player) < 0.11f) {
                    if (d.virus) {
                        score = (score - 2).coerceAtLeast(0)
                        hit = now
                    } else {
                        score++
                    }
                    iterator.remove()
                } else if (d.y > 1.05f) {
                    iterator.remove()
                }
            }
            frame.longValue = now
        }
        onEnd(score)
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ATTRAPE LES MACARONS", color = AskaColors.Pink, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$score", color = Cp.Yellow, fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.size(12.dp))
            Text("${secondsLeft}s", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 14.sp)
        }
        Text("Glisse-la pour attraper les macarons. Évite les virus ◆.", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.4f), fill = AskaColors.Void.copy(alpha = 0.7f), cut = 10.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> player = (change.position.x / size.width).coerceIn(0.06f, 0.94f) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { player = (it.x / size.width).coerceIn(0.06f, 0.94f) }
                },
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val chibi = 64.dp
            val chibiPx = with(LocalDensity.current) { chibi.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                frame.longValue
                val r = size.width * 0.035f
                drops.forEach { d ->
                    val c = Offset(d.x * size.width, d.y * size.height)
                    if (d.virus) {
                        val path = Path().apply {
                            moveTo(c.x, c.y - r * 1.3f); lineTo(c.x + r * 1.1f, c.y); lineTo(c.x, c.y + r * 1.3f); lineTo(c.x - r * 1.1f, c.y); close()
                        }
                        drawPath(path, Cp.Red)
                    } else {
                        // A macaron: two shells and the filling.
                        drawOval(AskaColors.Pink, Offset(c.x - r * 1.3f, c.y - r * 0.9f), Size(r * 2.6f, r * 0.9f))
                        drawOval(AskaColors.HotPink, Offset(c.x - r * 1.3f, c.y + 0.1f * r), Size(r * 2.6f, r * 0.9f))
                        drawRect(Cp.Text.copy(alpha = 0.9f), Offset(c.x - r * 1.1f, c.y - r * 0.12f), Size(r * 2.2f, r * 0.28f))
                    }
                }
            }
            Image(
                painterResource(R.drawable.aska_chibi_gourmand),
                contentDescription = "As//ka",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(chibi)
                    .graphicsLayer {
                        translationX = player * widthPx - chibiPx / 2
                        // A shake when she catches a virus.
                        val since = (frame.longValue - hit) / 1e9f
                        if (hit != 0L && since < 0.3f) rotationZ = 12f * kotlin.math.sin(since * 60f)
                    },
            )
        }
    }
}

private const val ROUND_SECONDS = 20
// Where her mouth is, as a fraction of the play area's height.
private const val CATCH_LINE = 0.84f

