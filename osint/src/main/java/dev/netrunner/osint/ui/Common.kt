package dev.netrunner.osint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.osint.data.Report

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1,
        modifier = modifier.hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 5.dp).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun Message(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
}

/**
 * One lookup's result: rows as a label/value table (green when reassuring, red when worth a look),
 * then the links, and the actions to keep it in the notebook or copy it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportCard(report: Report, saved: Boolean, onSave: (() -> Unit)?, extra: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val accent = if (report.error != null) Cp.Red else Cp.Cyan
    Column(Modifier.fillMaxWidth().hudPanel(borderColor = accent.copy(alpha = 0.45f), cut = 8.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("// ${report.module}", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
            Text(
                "  ${report.target}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        report.error?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
        SelectionContainer {
            Column(Modifier.padding(top = 4.dp)) {
                report.rows.forEach { r ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(r.label.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.width(110.dp))
                        val color = when (r.flag) {
                            Report.Flag.Good -> Color(0xFF39FF14)
                            Report.Flag.Warn -> Cp.Red
                            Report.Flag.None -> Cp.Text
                        }
                        Text(r.value, color = color, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        report.remark?.let { Text("> $it", color = Cp.Cyan.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (report.links.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.links.forEach { l -> Chip("↗ ${l.label}", Cp.Cyan) { openUrl(context, l.url) } }
            }
        }
        extra?.invoke()
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            Chip("COPIER", Cp.Dim) { copy(context, report.asText()) }
            if (onSave != null) Chip(if (saved) "✓ AU CARNET" else "+ CARNET", if (saved) Cp.Dim else Cp.Yellow) { if (!saved) onSave() }
        }
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Aucun navigateur pour ouvrir ce lien.", Toast.LENGTH_SHORT).show()
    }
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OSINT", text))
    Toast.makeText(context, "Copié.", Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
