package dev.netrunner.launcher.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.netrunner.launcher.aska.AskaBrain
import kotlinx.coroutines.delay
import java.text.Normalizer
import kotlin.math.sin

/** The little As//ka in the corner of the chat, and the mood she shows. */
enum class AskaMood(val drawable: String) {
    Waiting("aska_chibi_waiting"),
    Thinking("aska_chibi_thinking"),
    Talking("aska_chibi_talking"),
    Happy("aska_chibi_happy"),
    Shy("aska_chibi_shy"),
    Mischievous("aska_chibi_mischievous"),
    Gourmand("aska_chibi_gourmand");

    companion object {
        /** Guesses how she feels after a reply, from what she just said and what she was told. */
        fun after(reply: String, asked: String): AskaMood? {
            val said = plain(reply)
            val heard = plain(asked)
            return when {
                GOURMAND.containsMatchIn(said) || GOURMAND.containsMatchIn(heard) -> Gourmand
                SHY.containsMatchIn(heard) || said.contains("rougi") || said.startsWith("euh") -> Shy
                MISCHIEF.containsMatchIn(said) -> Mischievous
                HAPPY.containsMatchIn(said) || said.contains('!') -> Happy
                else -> null
            }
        }

        private val GOURMAND = Regex("gateau|patiss|macaron|bonbon|sucre|chocolat|dessert|cookie|beignet|donut|glace|creme|miam|faim|manger|gourmand")
        private val SHY = Regex("(t'es|tu es|t'est) (trop |tellement |si )?(belle|mignonne|jolie|adorable|geniale|cute)|je t'aime|je t'adore|merci beaucoup|bravo")
        private val MISCHIEF = Regex("hehe|hihi|niark|\\bfufu|devine|secret|;\\)|\\bpeut-etre bien")
        private val HAPPY = Regex("super|genial|trop bien|youpi|chouette|yay|\\bcool\\b|^\\s*oh|bien sur")

        private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}

/**
 * Her chibi, following what she is doing: thinking while the core works, talking while her
 * voice plays or the reply streams, a short mood after a reply, then waiting. Draws nothing when
 * the pictures are missing from the build. A gentle float, at a low frame rate.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun AskaChibi(brain: AskaBrain, modifier: Modifier = Modifier, animated: Boolean = true) {
    val lines = brain.lines
    val last = lines.lastOrNull()
    val streaming = last != null && !last.fromUser && !last.done
    var aftermath by remember { mutableStateOf<AskaMood?>(null) }

    // A finished reply gives her a feeling for a few seconds, once her voice is done.
    val finishedId = last?.takeIf { !it.fromUser && it.done }?.id
    LaunchedEffect(finishedId, brain.voice.speaking) {
        if (finishedId == null || brain.voice.speaking) return@LaunchedEffect
        val index = lines.indexOfLast { it.id == finishedId }
        val asked = lines.subList(0, index.coerceAtLeast(0)).lastOrNull { it.fromUser }?.text.orEmpty()
        aftermath = AskaMood.after(lines.getOrNull(index)?.text.orEmpty(), asked) ?: return@LaunchedEffect
        delay(7_000)
        aftermath = null
    }

    val mood = when {
        brain.voice.speaking || (streaming && last?.text?.isNotEmpty() == true) -> AskaMood.Talking
        brain.state == AskaBrain.State.Thinking || brain.state == AskaBrain.State.Waking -> AskaMood.Thinking
        else -> aftermath ?: AskaMood.Waiting
    }

    val context = LocalContext.current
    val id = remember(mood) {
        val res = context.resources
        res.getIdentifier(mood.drawable, "drawable", context.packageName).takeIf { it != 0 }
            ?: res.getIdentifier(AskaMood.Waiting.drawable, "drawable", context.packageName)
    }
    if (id == 0) return

    val clock = if (animated) rememberSlowClock(fps = 12) else remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val t by clock
    val seconds = t / 1000f
    val lift = with(LocalDensity.current) { 3.dp.toPx() }
    // Livelier while she talks, a slow breath otherwise.
    val speed = if (mood == AskaMood.Talking) 7f else 1.8f
    Image(
        painterResource(id),
        contentDescription = "As//ka",
        modifier = modifier.graphicsLayer {
            translationY = -lift * ((sin(seconds * speed) + 1f) / 2f)
            if (mood == AskaMood.Talking) {
                val s = 1f + 0.02f * sin(seconds * 13f)
                scaleX = s
                scaleY = s
            }
        },
    )
}
