package dev.netrunner.agenda.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.agenda.data.Contract
import dev.netrunner.agenda.data.ContractTime
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import java.time.ZoneId

/** The calendar's own colour when it has one, so Google Agenda colours carry over. */
fun contractColor(c: Contract): Color = when {
    c.source == Contract.Source.ASKA -> Color(0xFFFF4FA3) // As//ka's pink
    c.color != 0 -> Color(c.color or 0xFF000000.toInt())
    else -> Cp.Cyan
}

/** One contract, as a mission card: time column, title, fixer (calendar) and place. */
@Composable
fun ContractCard(c: Contract, now: Long, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val status = ContractTime.status(c, now)
    val past = status == "TERMINÉ" || status == "PASSÉ"
    val accent = contractColor(c).copy(alpha = if (past) 0.4f else 1f)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)
            .hudPanel(borderColor = accent.copy(alpha = 0.6f), cut = 8.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.width(78.dp).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
            Text(ContractTime.timeRange(c, zone).substringBefore(" "), color = if (past) Cp.Dim else Cp.Yellow, fontFamily = MonoFont, fontSize = 16.sp)
            if (!c.allDay && c.source == Contract.Source.CALENDAR) {
                Text(ContractTime.timeRange(c, zone).substringAfter("– ", ""), color = Cp.Dim, fontFamily = MonoFont, fontSize = 13.sp, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.source == Contract.Source.ASKA) "RAPPEL // AS//KA" else "CONTRAT // ${c.calendarName.uppercase()}",
                    color = accent, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (status.isNotEmpty()) {
                    Text(
                        status, color = when (status) { "EN COURS" -> Cp.Background; else -> if (past) Cp.Dim else Cp.Cyan },
                        fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp,
                        modifier = Modifier.background(if (status == "EN COURS") Cp.Yellow else Color.Transparent).padding(horizontal = 4.dp),
                    )
                }
            }
            Text(
                c.title, color = if (past) Cp.Dim else Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            c.location?.let { Text("⌖ $it", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun DayHeader(label: String, highlight: Boolean) {
    Text(
        "// $label", color = if (highlight) Cp.Yellow else Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** Opens the place in Maps (or any app handling geo: links). */
fun navigate(context: Context, place: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))) }
}
