package dev.netrunner.agenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.agenda.data.AskaReminders
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater
import dev.netrunner.deck.hudShape
import dev.netrunner.deck.hudSwitch

/** Releases tagged "agenda-v…", apart from the other deck apps. */
val AgendaUpdater = Updater("agenda-v")

@Composable
fun SettingsTab(state: AgendaState) {
    val context = LocalContext.current
    val askaReady = remember { AskaReminders.available(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("AGENDAS AFFICHÉS", Cp.Yellow)
        Text(
            "Les contrats viennent du calendrier d'Android (Google Agenda, Exchange…), donc tout reste synchronisé.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        if (state.calendars.isEmpty()) Text("Aucun agenda trouvé.", color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp)
        state.calendars.forEach { cal ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(cal.color or 0xFF000000.toInt()), hudShape(3.dp)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(cal.name, color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                    if (cal.account.isNotBlank() && cal.account != cal.name) Text(cal.account, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
                }
                Switch(checked = cal.id !in state.hidden, onCheckedChange = { state.toggleCalendar(cal.id) }, colors = hudSwitch())
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("RAPPELS D'AS//KA", Cp.Yellow)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Afficher ses rappels", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                Text(
                    if (askaReady) "Lus depuis le launcher Netrunner."
                    else "Pas encore disponibles : il faut un launcher qui les partage, installé avant cette appli (sinon réinstalle l'agenda).",
                    color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
                )
            }
            Switch(checked = state.showAska, onCheckedChange = { state.toggleAska() }, colors = hudSwitch())
        }

        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(AgendaUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
