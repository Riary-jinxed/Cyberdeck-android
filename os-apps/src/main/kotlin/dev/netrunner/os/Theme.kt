package dev.netrunner.os

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The NETRUNNER faction of the phone (deck/Theme.kt) and of Deck Link. */
object Cp {
    val Yellow = Color(0xFFFCEE0A)
    val Cyan = Color(0xFF00F0FF)
    val Red = Color(0xFFFF003C)
    val Background = Color(0xFF07070D)
    val Panel = Color(0xFF11111B)
    val Dim = Color(0xFF5C5C74)
    val Text = Color(0xFFE8E8F0)
}

private fun font(name: String, weight: FontWeight) =
    Font("netrunner-$name", Cp::class.java.getResourceAsStream("/font/$name.ttf")!!.use { it.readBytes() }, weight)

/** Rajdhani for the interface, Orbitron for titles, Share Tech Mono for codes (SIL OFL, see licenses/). */
val HudFont = FontFamily(font("rajdhani_semibold", FontWeight.SemiBold), font("rajdhani_bold", FontWeight.Bold))
val TitleFont = FontFamily(font("orbitron", FontWeight.Bold))
val MonoFont = FontFamily(font("share_tech_mono", FontWeight.Normal))

fun hudShape(cut: Dp = 10.dp) = CutCornerShape(topEnd = cut, bottomStart = cut)

fun Modifier.hudPanel(borderColor: Color = Cp.Yellow, fill: Color = Cp.Panel.copy(alpha = 0.85f), cut: Dp = 10.dp): Modifier =
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
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(
    text,
    modifier,
    style = TextStyle(color = color, fontSize = size, fontFamily = font, fontWeight = weight),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun Button(label: String, color: Color = Cp.Yellow, filled: Boolean = false, onClick: () -> Unit) {
    T(
        label, if (filled) Cp.Background else color, 14.sp, weight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier
            .hudPanel(color, if (filled) color else color.copy(alpha = 0.08f), 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** A small cut-corner badge in [color] with two bars, for the window icon. */
fun badge(color: Color): BitmapPainter {
    val image = ImageBitmap(32, 32)
    Canvas(image).apply {
        drawRect(0f, 0f, 32f, 32f, Paint().apply { this.color = Cp.Background })
        val p = Path().apply { moveTo(4f, 4f); lineTo(22f, 4f); lineTo(28f, 10f); lineTo(28f, 28f); lineTo(10f, 28f); lineTo(4f, 22f); close() }
        drawPath(p, Paint().apply { this.color = color })
        drawRect(10f, 13f, 22f, 15f, Paint().apply { this.color = Cp.Background })
        drawRect(10f, 18f, 18f, 20f, Paint().apply { this.color = Cp.Background })
    }
    return BitmapPainter(image)
}
