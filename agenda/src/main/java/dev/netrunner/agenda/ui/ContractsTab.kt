package dev.netrunner.agenda.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.agenda.data.Contract
import dev.netrunner.agenda.data.ContractTime
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import java.time.LocalDate
import java.time.ZoneId

/** The coming contracts, day by day, over the next 60 days. */
@Composable
fun ContractsTab(state: AgendaState, onOpen: (Contract) -> Unit, onNew: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = today.plusDays(DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
    val contracts by produceState<List<Contract>?>(null, state.version, state.hidden, state.showAska) {
        value = state.load(from, to)
    }
    val now = System.currentTimeMillis()

    Box(Modifier.fillMaxSize()) {
        val list = contracts
        when {
            list == null -> Text("Chargement…", color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text(
                "Aucun contrat à l'horizon.\nTouche + CONTRAT pour en ajouter un.",
                color = Cp.Dim, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(16.dp),
            )
            else -> {
                val days = ContractTime.group(list, zone, today, today.plusDays(DAYS))
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    days.forEach { (day, items) ->
                        item(key = "h$day") { DayHeader(ContractTime.dayLabel(day, today), day == today) }
                        items(items, key = { "${day}_${it.source}_${it.eventId}_${it.begin}" }) { c -> ContractCard(c, now) { onOpen(c) } }
                    }
                    item { Box(Modifier.padding(bottom = 80.dp)) }
                }
            }
        }
        HudButton("+ CONTRAT", filled = true, size = 16.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), onClick = onNew)
    }
}

private const val DAYS = 60L
