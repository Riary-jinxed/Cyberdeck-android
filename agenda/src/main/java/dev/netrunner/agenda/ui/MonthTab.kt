package dev.netrunner.agenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.agenda.data.Contract
import dev.netrunner.agenda.data.ContractTime
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DisplayFont
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudShape
import java.time.LocalDate
import java.time.ZoneId

/** Month grid with a dot per contract, and the chosen day's contracts below. */
@Composable
fun MonthTab(state: AgendaState, onOpen: (Contract) -> Unit, onNew: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    var selected by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var month by rememberSaveable { mutableStateOf(today.year * 12 + today.monthValue - 1) }
    val year = month / 12
    val monthValue = month % 12 + 1
    val grid = ContractTime.monthGrid(year, monthValue)
    val from = grid.first().atStartOfDay(zone).toInstant().toEpochMilli()
    val to = grid.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val contracts by produceState(emptyList<Contract>(), month, state.version, state.hidden, state.showAska) {
        value = state.load(from, to)
    }
    val byDay = ContractTime.group(contracts, zone, grid.first(), grid.last())
    val day = LocalDate.ofEpochDay(selected)
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HudButton("◂", color = Cp.Cyan) { month-- }
            Text(
                ContractTime.monthTitle(year, monthValue), color = Cp.Yellow, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                fontSize = 17.sp, modifier = Modifier.weight(1f).clickable {
                    month = today.year * 12 + today.monthValue - 1; selected = today.toEpochDay()
                }, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            HudButton("▸", color = Cp.Cyan) { month++ }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("LUN", "MAR", "MER", "JEU", "VEN", "SAM", "DIM").forEach {
                Text(it, color = Cp.Dim, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { d ->
                    val inMonth = d.monthValue == monthValue
                    val isSel = d == day
                    val dots = byDay[d].orEmpty()
                    Column(
                        Modifier.weight(1f).aspectRatio(1f).padding(vertical = 2.dp)
                            .background(if (isSel) Cp.Yellow.copy(alpha = 0.18f) else Color.Transparent, hudShape(5.dp))
                            .then(if (d == today) Modifier.border(1.dp, Cp.Yellow, hudShape(5.dp)) else Modifier)
                            .clickable { selected = d.toEpochDay() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${d.dayOfMonth}", color = when { isSel -> Cp.Yellow; inMonth -> Cp.Text; else -> Cp.Dim.copy(alpha = 0.5f) },
                            fontFamily = MonoFont, fontSize = 15.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                            dots.take(3).forEach { Box(Modifier.size(5.dp).background(contractColor(it), CircleShape)) }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayHeader(ContractTime.dayLabel(day, today), day == today)
            Spacer(Modifier.weight(1f))
            Text(
                "+ AJOUTER", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp).clickable { onNew(day) },
            )
        }
        val items = byDay[day].orEmpty()
        if (items.isEmpty()) {
            Text("Rien de prévu.", color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(items, key = { "${it.source}_${it.eventId}_${it.begin}" }) { c -> ContractCard(c, now) { onOpen(c) } }
        }
    }
}
