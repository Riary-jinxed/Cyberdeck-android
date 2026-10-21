package dev.netrunner.agenda.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.netrunner.agenda.data.AgendaPrefs
import dev.netrunner.agenda.data.CalendarRepo
import dev.netrunner.agenda.data.Contract
import dev.netrunner.agenda.data.ContractTime
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.cyberGrid
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val REMINDERS = listOf<Pair<Int?, String>>(null to "AUCUN", 5 to "5 MIN", 15 to "15 MIN", 30 to "30 MIN", 60 to "1 H", 1440 to "1 JOUR")

/**
 * Full-screen contract editor. [contract] with eventId < 0 is a new one (fields prefilled).
 * Recurring events and As//ka's reminders are shown read-only.
 */
@Composable
fun EditorDialog(state: AgendaState, contract: Contract, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val isNew = contract.eventId < 0 && contract.source == Contract.Source.CALENDAR
    val readOnly = contract.recurring || contract.source == Contract.Source.ASKA

    val begin = Instant.ofEpochMilli(contract.begin).atZone(if (contract.allDay) ZoneOffset.UTC else zone)
    // An all-day event ends at the next midnight: the last day is the one before.
    val endInstant = Instant.ofEpochMilli(if (contract.allDay) contract.end - 1 else contract.end).atZone(if (contract.allDay) ZoneOffset.UTC else zone)
    var title by remember { mutableStateOf(contract.title.takeUnless { isNew && it == "Contrat sans nom" } ?: "") }
    var allDay by remember { mutableStateOf(contract.allDay) }
    var date by remember { mutableStateOf(begin.toLocalDate()) }
    var endDate by remember { mutableStateOf(endInstant.toLocalDate().coerceAtLeast(begin.toLocalDate())) }
    var start by remember { mutableStateOf(begin.toLocalTime().withSecond(0).withNano(0)) }
    var end by remember { mutableStateOf(endInstant.toLocalTime().withSecond(0).withNano(0)) }
    var location by remember { mutableStateOf(contract.location ?: "") }
    var description by remember { mutableStateOf(contract.description ?: "") }
    val writable = state.calendars.filter { it.writable }
    var calendarId by remember {
        mutableStateOf(
            contract.calendarId.takeIf { it >= 0 }
                ?: AgendaPrefs.defaultCalendar(context).takeIf { id -> writable.any { it.id == id } }
                ?: writable.firstOrNull()?.id ?: -1L
        )
    }
    var reminder by remember { mutableStateOf(if (isNew) AgendaPrefs.reminder(context) else null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(contract.eventId) {
        if (!isNew && contract.source == Contract.Source.CALENDAR) {
            reminder = withContext(Dispatchers.IO) { runCatching { CalendarRepo.reminder(context, contract.eventId) }.getOrNull() }
        }
    }

    fun save() {
        if (title.isBlank()) { Toast.makeText(context, "Donne un nom au contrat", Toast.LENGTH_SHORT).show(); return }
        if (calendarId < 0) { Toast.makeText(context, "Aucun agenda modifiable sur le téléphone", Toast.LENGTH_SHORT).show(); return }
        val b: Long
        var e: Long
        if (allDay) {
            b = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            e = endDate.coerceAtLeast(date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            b = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
            e = endDate.atTime(end).atZone(zone).toInstant().toEpochMilli()
            if (e <= b) e = b + 3_600_000
        }
        val draft = CalendarRepo.Draft(
            eventId = if (isNew) null else contract.eventId, calendarId = calendarId, title = title, begin = b, end = e,
            allDay = allDay, location = location, description = description, reminder = reminder,
        )
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { CalendarRepo.save(context, draft) }.isSuccess }
            Toast.makeText(context, if (ok) "Contrat enregistré" else "Enregistrement refusé", Toast.LENGTH_SHORT).show()
            if (ok) {
                AgendaPrefs.setDefaultCalendar(context, calendarId)
                if (isNew) AgendaPrefs.setReminder(context, reminder)
                state.refresh()
                onClose()
            }
        }
    }

    fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().cyberGrid().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    when {
                        contract.source == Contract.Source.ASKA -> "RAPPEL AS//KA"
                        isNew -> "NOUVEAU CONTRAT"
                        readOnly -> "CONTRAT RÉCURRENT"
                        else -> "MODIFIER LE CONTRAT"
                    },
                    Cp.Yellow, Modifier.weight(1f),
                )
                HudButton("✕", color = Cp.Dim, onClick = onClose)
            }
            Spacer(Modifier.height(12.dp))

            if (readOnly) {
                ReadOnly(contract, zone)
                if (contract.recurring) {
                    Text(
                        "Ce contrat se répète : modifie la série dans Google Agenda pour ne rien casser.",
                        color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                contract.location?.let { place ->
                    HudButton("⌖ NAVIGUER", color = Cp.Cyan, modifier = Modifier.fillMaxWidth()) { navigate(context, place) }
                }
            } else {
                TextField(
                    title, { title = it }, singleLine = true, colors = hudFieldColors(),
                    label = { Text("Contrat", fontFamily = HudFont) },
                    textStyle = TextStyle(fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 19.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Toute la journée", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it }, colors = hudSwitch())
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field("DÉBUT", ContractTime.shortDate(date), Modifier.weight(1f)) {
                        pickDate(date) { d ->
                            val shift = d.toEpochDay() - date.toEpochDay()
                            date = d
                            endDate = endDate.plusDays(shift)
                        }
                    }
                    if (!allDay) Field("", start.toString(), Modifier.weight(1f)) {
                        pickTime(start) { t ->
                            // Keeps the duration when the start moves.
                            val length = java.time.Duration.between(date.atTime(start), endDate.atTime(end))
                            start = t
                            val newEnd = date.atTime(t).plus(length)
                            endDate = newEnd.toLocalDate(); end = newEnd.toLocalTime()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field("FIN", ContractTime.shortDate(endDate), Modifier.weight(1f)) { pickDate(endDate) { endDate = it.coerceAtLeast(date) } }
                    if (!allDay) Field("", end.toString(), Modifier.weight(1f)) { pickTime(end) { end = it } }
                }
                Spacer(Modifier.height(12.dp))
                TextField(
                    location, { location = it }, singleLine = true, colors = hudFieldColors(),
                    label = { Text("Lieu", fontFamily = HudFont) }, textStyle = TextStyle(fontFamily = HudFont, fontSize = 17.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    description, { description = it }, colors = hudFieldColors(), minLines = 3,
                    label = { Text("Briefing", fontFamily = HudFont) }, textStyle = TextStyle(fontFamily = HudFont, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                SectionTitle("RAPPEL")
                ChipRow(REMINDERS.map { it.second }, REMINDERS.indexOfFirst { it.first == reminder }) { reminder = REMINDERS[it].first }
                Spacer(Modifier.height(14.dp))
                SectionTitle("AGENDA")
                if (writable.isEmpty()) {
                    Text("Aucun agenda modifiable : ajoute un compte Google dans les réglages d'Android.", color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp)
                } else {
                    ChipRow(writable.map { it.name }, writable.indexOfFirst { it.id == calendarId }) { calendarId = writable[it].id }
                }
                Spacer(Modifier.height(20.dp))
                HudButton(if (isNew) "SIGNER LE CONTRAT" else "ENREGISTRER", filled = true, size = 17.sp, modifier = Modifier.fillMaxWidth()) { save() }
                if (location.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    HudButton("⌖ NAVIGUER", color = Cp.Cyan, modifier = Modifier.fillMaxWidth()) { navigate(context, location) }
                }
                if (!isNew) {
                    Spacer(Modifier.height(8.dp))
                    HudButton(if (confirmDelete) "CONFIRMER LA SUPPRESSION" else "SUPPRIMER", color = Cp.Red, filled = confirmDelete, modifier = Modifier.fillMaxWidth()) {
                        if (!confirmDelete) { confirmDelete = true; return@HudButton }
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { CalendarRepo.delete(context, contract.eventId) } }
                            Toast.makeText(context, "Contrat supprimé", Toast.LENGTH_SHORT).show()
                            state.refresh()
                            onClose()
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
