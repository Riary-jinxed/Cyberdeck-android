package dev.netrunner.aska

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Image

/** The deck's NETRUNNER colors (deck/Theme.kt), with As//ka's neon pink. */
object Cp {
    val Pink = Color(0xFFFF2E97)
    val Yellow = Color(0xFFFCEE0A)
    val Cyan = Color(0xFF00F0FF)
    val Red = Color(0xFFFF003C)
    val Background = Color(0xFF07070D)
    val Panel = Color(0xFF11111B)
    val Dim = Color(0xFF5C5C74)
    val Text = Color(0xFFE8E8F0)
}

private fun font(name: String, weight: FontWeight) =
    Font("aska-$name", Cp::class.java.getResourceAsStream("/font/$name.ttf")!!.use { it.readBytes() }, weight)

/** Rajdhani for the interface, Orbitron for titles, Share Tech Mono for counters (SIL OFL, see licenses/). */
val HudFont = FontFamily(font("rajdhani_semibold", FontWeight.SemiBold), font("rajdhani_bold", FontWeight.Bold))
val TitleFont = FontFamily(font("orbitron", FontWeight.Bold))
val MonoFont = FontFamily(font("share_tech_mono", FontWeight.Normal))

fun hudShape(cut: Dp = 10.dp) = CutCornerShape(topEnd = cut, bottomStart = cut)

fun Modifier.hudPanel(borderColor: Color = Cp.Pink, fill: Color = Cp.Panel.copy(alpha = 0.92f), cut: Dp = 10.dp): Modifier =
    this.background(fill, hudShape(cut)).border(1.dp, borderColor, hudShape(cut))

/** Text in the deck's style: Rajdhani by default. */
@Composable
fun T(
    text: String,
    color: Color = Cp.Text,
    size: TextUnit = 15.sp,
    font: FontFamily = HudFont,
    weight: FontWeight = FontWeight.SemiBold,
    modifier: Modifier = Modifier,
) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size, fontFamily = font, fontWeight = weight))
}

/** One of her sprites (src/main/resources/sprites). */
fun sprite(name: String): ImageBitmap =
    Image.makeFromEncoded(Cp::class.java.getResourceAsStream("/sprites/$name.png")!!.use { it.readBytes() }).toComposeImageBitmap()
