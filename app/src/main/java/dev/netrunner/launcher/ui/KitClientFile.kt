package dev.netrunner.launcher.ui

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.data.Agenda
import dev.netrunner.launcher.data.Contract
import dev.netrunner.launcher.kit.Client
import dev.netrunner.launcher.kit.ClientContract
import dev.netrunner.launcher.kit.Clients
import dev.netrunner.launcher.kit.Quote
import dev.netrunner.launcher.kit.QuoteState
import dev.netrunner.launcher.system.DeckLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The business side of a client sheet: contracts, quotes and the visits planned in the agenda.
 * Everything stays in kit_clients.json, next to the contacts, and goes into the kit backup.
 */

private val DayFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())
private val EventFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.FRENCH).withZone(ZoneId.systemDefault())

private fun day(ms: Long) = if (ms == 0L) "" else DayFmt.format(Instant.ofEpochMilli(ms))

private fun stateLabel(c: ClientContract): Pair<String, Color> = when (c.state()) {
    ClientContract.State.Upcoming -> "À VENIR" to Cp.Dim
    ClientContract.State.Active -> "ACTIF" to Cp.Cyan
    ClientContract.State.Ending -> "EXPIRE DANS ${c.daysLeft()} J" to Cp.Yellow
    ClientContract.State.Expired -> "EXPIRÉ" to Cp.Red
}

private fun quoteColor(s: QuoteState) = when (s) {
    QuoteState.Draft -> Cp.Dim
    QuoteState.Sent -> Cp.Yellow
    QuoteState.Accepted -> Cp.Cyan
    QuoteState.Refused -> Cp.Red
}

/** One line of the sheet: two lines of text on the left, a coloured tag on the right. */
@Composable
private fun FileRow(title: String, detail: String, tag: String, tagColor: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = tagColor.copy(alpha = 0.45f), cut = 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(tag, color = tagColor, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun ContractRow(c: ClientContract, onClick: () -> Unit) {
    val (tag, color) = stateLabel(c)
    val period = when {
        c.start > 0 && c.end > 0 -> "du ${day(c.start)} au ${day(c.end)}"
        c.start > 0 -> "depuis le ${day(c.start)}"
        c.end > 0 -> "jusqu'au ${day(c.end)}"
        else -> ""
    }
    FileRow(
        listOf(c.ref, c.kind).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Contrat" },
        listOf(period, c.amount).filter { it.isNotBlank() }.joinToString(" · "),
        tag, color, onClick,
    )
}

@Composable
internal fun QuoteRow(q: Quote, onClick: () -> Unit) {
    FileRow(
        listOf(q.ref, q.label).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Devis" },
        listOf(day(q.date), q.amount).filter { it.isNotBlank() }.joinToString(" · "),
        q.state.label.uppercase(), quoteColor(q.state), onClick,
    )
}

/** Does this agenda event concern the client (name in the title or place, or its address)? */
private fun concerns(e: Contract, c: Client): Boolean {
    val place = e.location.orEmpty()
    return (c.name.length >= 3 && (e.title.contains(c.name, ignoreCase = true) || place.contains(c.name, ignoreCase = true))) ||
        (c.address.length >= 8 && place.contains(c.address.lineSequence().first().trim(), ignoreCase = true))
}

/** Visits at this client in the agenda: the last 30 days and the next 90. */
@Composable
internal fun ClientAgenda(c: Client) {
    val context = LocalContext.current
    var events by remember(c.id) { mutableStateOf<List<Contract>?>(null) }
    LaunchedEffect(c.id, c.name, c.address) {
        val now = System.currentTimeMillis()
        events = withContext(Dispatchers.IO) {
            Agenda.between(context, now - 30 * 86_400_000L, now + 90 * 86_400_000L, limit = 12) { concerns(it, c) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("// AGENDA CHEZ LE CLIENT", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "[ + PLANIFIER ]",
                color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { DeckLinks.newEvent(context, "Intervention · ${c.name}", c.address) }.padding(6.dp),
            )
        }
        val list = events
        when {
            !Agenda.granted(context) -> Text("> autorise l'accès à l'agenda (accueil, widget contrats) pour voir les RDV de ce client", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
            list == null -> Unit
            list.isEmpty() -> Text("> aucun RDV trouvé : mets le nom du client dans le titre ou le lieu de l'événement", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
            else -> {
                val now = System.currentTimeMillis()
                list.forEach { e ->
                    val past = e.end < now
                    FileRow(
                        e.title,
                        listOfNotNull(EventFmt.format(Instant.ofEpochMilli(e.begin)), e.location).joinToString(" · "),
                        when {
                            past -> "PASSÉ"
                            e.begin <= now -> "EN COURS"
                            else -> "PRÉVU"
                        },
                        if (past) Cp.Dim else Cp.Cyan,
                    ) { DeckLinks.openEvent(context, e.id) }
                }
            }
        }
    }
}

/** A date picked with the system dialog; long press clears it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DateField(label: String, value: Long, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(label.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (value == 0L) "— choisir —" else day(value),
            color = if (value == 0L) Cp.Dim else Cp.Text,
            fontFamily = HudFont,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp)
                .combinedClickable(
                    onLongClick = { onChange(0L) },
                    onClick = {
                        val d = if (value == 0L) LocalDate.now() else Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate()
                        DatePickerDialog(context, { _, y, m, dd ->
                            onChange(LocalDate.of(y, m + 1, dd).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    },
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun DeleteButton(label: String, onConfirmed: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    KitButton(if (confirm) "CONFIRMER LA SUPPRESSION" else label, Cp.Red, Modifier.fillMaxWidth()) {
        if (confirm) onConfirmed() else confirm = true
    }
}

@Composable
internal fun ContractEditor(clientId: String, c: ClientContract, isNew: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var ref by remember { mutableStateOf(c.ref) }
    var kind by remember { mutableStateOf(c.kind) }
    var start by remember { mutableStateOf(c.start) }
    var end by remember { mutableStateOf(c.end) }
    var amount by remember { mutableStateOf(c.amount) }
    var notes by remember { mutableStateOf(c.notes) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(if (isNew) "Nouveau contrat" else "Contrat", onBack = onDone) }
        item { KitField("Référence", ref, { ref = it }) }
        item { KitField("Type (maintenance, support, infogérance…)", kind, { kind = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { DateField("Début", start) { start = it } }
                Column(Modifier.weight(1f)) { DateField("Fin", end) { end = it } }
            }
        }
        item { KitField("Montant / volume (ex. 1 200 € HT/an, 20 h)", amount, { amount = it }) }
        item { KitField("Périmètre, SLA, astreinte…", notes, { notes = it }, singleLine = false) }
        item {
            KitButton("ENREGISTRER", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                if (start > 0 && end > 0 && end < start) {
                    Toast.makeText(context, "La fin est avant le début", Toast.LENGTH_SHORT).show()
                    return@KitButton
                }
                Clients.putContract(context, clientId, c.copy(ref = ref.trim(), kind = kind.trim(), start = start, end = end, amount = amount.trim(), notes = notes.trim()))
                onDone()
            }
        }
        if (!isNew) item { DeleteButton("SUPPRIMER LE CONTRAT") { Clients.removeContract(context, clientId, c.id); onDone() } }
    }
}

@Composable
internal fun QuoteEditor(clientId: String, q: Quote, isNew: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var ref by remember { mutableStateOf(q.ref) }
    var label by remember { mutableStateOf(q.label) }
    var date by remember { mutableStateOf(q.date) }
    var amount by remember { mutableStateOf(q.amount) }
    var state by remember { mutableStateOf(q.state) }
    var notes by remember { mutableStateOf(q.notes) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(if (isNew) "Nouveau devis" else "Devis", onBack = onDone) }
        item { KitField("Référence", ref, { ref = it }) }
        item { KitField("Objet", label, { label = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { DateField("Date", date) { date = it } }
                Column(Modifier.weight(1f)) { KitField("Montant", amount, { amount = it }, keyboard = KeyboardType.Text) }
            }
        }
        item {
            Column {
                Text("ÉTAT", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                KitChips(QuoteState.entries.map { it.label }, state.label) { l -> state = QuoteState.entries.first { it.label == l } }
            }
        }
        item { KitField("Détail, relance, conditions…", notes, { notes = it }, singleLine = false) }
        item {
            KitButton("ENREGISTRER", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                Clients.putQuote(context, clientId, q.copy(ref = ref.trim(), label = label.trim(), date = date, amount = amount.trim(), state = state, notes = notes.trim()))
                onDone()
            }
        }
        if (!isNew) item { DeleteButton("SUPPRIMER LE DEVIS") { Clients.removeQuote(context, clientId, q.id); onDone() } }
    }
}
