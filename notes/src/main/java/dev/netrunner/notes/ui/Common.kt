package dev.netrunner.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.hudPanel

/** Note colours: 0 follows the faction's cyan, then fixed neon tones. */
val noteColors: List<() -> Color> = listOf(
    { Cp.Cyan }, { Cp.Yellow }, { Cp.Red }, { Color(0xFF39FF14) }, { Color(0xFFFF4FA3) }, { Color(0xFFB967FF) },
)

fun noteColor(index: Int): Color = noteColors.getOrElse(index) { noteColors[0] }()

@Composable
fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1,
        modifier = Modifier.padding(start = 6.dp).hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 5.dp).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun Message(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
}

/** Opens the field-kit sheet of [code] in the launcher (needs a launcher with the kit link, v1.3.8+). */
fun openKit(context: android.content.Context, code: String) {
    val intent = android.content.Intent("dev.netrunner.action.KIT_OPEN")
        .setClassName("dev.netrunner.launcher", "dev.netrunner.launcher.kit.KitLinkActivity")
        .putExtra("code", code)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Not installed, too old, or the permission is missing.
        android.widget.Toast.makeText(context, "Mets à jour le launcher Netrunner pour ouvrir le kit.", android.widget.Toast.LENGTH_LONG).show()
    }
}
