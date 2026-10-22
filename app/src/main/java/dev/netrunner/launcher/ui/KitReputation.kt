package dev.netrunner.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.kit.ClientDocs
import dev.netrunner.launcher.kit.Logbook
import dev.netrunner.launcher.kit.Reputation

/** Rank, progress to the next one, how points are earned, and the achievements. */
@Composable
internal fun ReputationView() {
    val context = LocalContext.current
    val faction = Cp.faction
    val status = remember(Logbook.items.toList(), faction, ClientDocs.revision.intValue) { Reputation.status(context, faction) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { RankCard(status) }
        item {
            Text(
                "> par intervention clôturée : 100 pts · +15 par photo (5 max) · +60 checklists terminées · +30 journal de 3 entrées · +20 conso notée · +10 client renseigné",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        val sorted = status.achievements.sortedWith(compareBy({ !it.unlocked }, { -it.progress.toFloat() / it.target }))
        item {
            Text(
                "// SUCCÈS · ${status.achievements.count { it.unlocked }}/${status.achievements.size}",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(sorted, key = { it.title }) { a ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = (if (a.unlocked) Cp.Yellow else Cp.Dim).copy(alpha = 0.5f), cut = 4.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (a.unlocked) "★" else "☆", color = if (a.unlocked) Cp.Yellow else Cp.Dim, fontFamily = HudFont, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title.uppercase(), color = if (a.unlocked) Cp.Text else Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(a.detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
                }
                Text(
                    if (a.unlocked) "OK" else "${a.progress.coerceAtMost(a.target)}/${a.target}",
                    color = if (a.unlocked) Cp.Yellow else Cp.Cyan,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun RankCard(s: Reputation.Status) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f), cut = 10.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("// ${Reputation.TITLE} · ${Cp.faction.label}", color = Cp.Cyan, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("RANG ${s.rank.level} · ${s.rank.title.uppercase()}", color = Cp.Yellow, fontFamily = Cp.faction.clockFont, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text("${s.points} pts · ${s.closed} intervention(s) clôturée(s)", color = Cp.Text, fontFamily = MonoFont, fontSize = 12.sp)
        RankBar(s)
        Text(
            s.next?.let { "${it.from - s.points} pts avant ${it.title}" } ?: "Rang maximal atteint",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
    }
}

/** Progress from the current rank to the next. */
@Composable
internal fun RankBar(s: Reputation.Status, height: Int = 6) {
    val next = s.next
    val f = if (next == null) 1f else ((s.points - s.rank.from).toFloat() / (next.from - s.rank.from)).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(Cp.Panel)
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(height.dp)
                .background(Cp.Yellow)
        )
    }
}
