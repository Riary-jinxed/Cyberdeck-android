package dev.netrunner.launcher.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.kit.Client
import dev.netrunner.launcher.kit.ClientContract
import dev.netrunner.launcher.kit.ClientDocs
import dev.netrunner.launcher.kit.Clients
import dev.netrunner.launcher.kit.Intervention
import dev.netrunner.launcher.kit.KitBackup
import dev.netrunner.launcher.kit.KitStore
import dev.netrunner.launcher.kit.Logbook
import dev.netrunner.launcher.kit.PcLink
import dev.netrunner.launcher.kit.Quote
import dev.netrunner.launcher.kit.QuoteState
import dev.netrunner.launcher.kit.Stock
import dev.netrunner.launcher.kit.StockItem
import dev.netrunner.launcher.kit.TimeSheet
import dev.netrunner.launcher.system.ConfigBackup
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MonthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)

@Composable
private fun Header(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAction).padding(6.dp))
        }
    }
}

@Composable
private fun InfoBlock(label: String, value: String, onClick: (() -> Unit)? = null) {
    if (value.isBlank()) return
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 4.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = if (onClick != null) Cp.Cyan else Cp.Text, fontFamily = HudFont, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------------- clients

@Composable
internal fun ClientsView(onOpenIntervention: (String) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Client?>(null) }
    var query by remember { mutableStateOf("") }

    editing?.let { c ->
        BackHandler { editing = null }
        ClientEditor(c, onDone = { id -> editing = null; if (id != null) open = id })
        return
    }
    Clients.items.firstOrNull { it.id == open }?.let { c ->
        BackHandler { open = null }
        ClientSheet(c, onBack = { open = null }, onEdit = { editing = c }, onOpenIntervention = onOpenIntervention)
        return
    }

    // Names only seen in the logbook can be turned into a sheet in one tap.
    val withoutSheet = Clients.knownNames().filter { Clients.byName(it) == null }
    val context = LocalContext.current
    val q = query.trim()
    val list = Clients.items.filter { q.isEmpty() || (listOf(it.name, it.contact, it.address) + Clients.refsOf(it)).any { f -> f.contains(q, ignoreCase = true) } }
    // The search also looks through every client's documents (names, and text files' content).
    val rev = ClientDocs.revision.intValue
    val hasDocs = remember(rev, Clients.items.size) { Clients.items.any { ClientDocs.list(context, it.id).isNotEmpty() } }
    val docHits = remember(q, rev) { if (q.length >= 2) ClientDocs.search(context, q) else emptyList() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Header("// CLIENTS", "[ + NOUVEAU ]") {
                editing = Client(KitStore.newId(), "", "", "", "", "", "", "", "", 0)
            }
        }
        if (Clients.items.size > 5 || hasDocs) item { KitField("Rechercher (clients et documents)", query, { query = it }) }
        if (docHits.isNotEmpty()) {
            item { Text("// DOCUMENTS", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            items(docHits, key = { "d" + it.second.path }) { (c, f) -> DocRow(f, c.name) }
        }
        if (list.isEmpty() && withoutSheet.isEmpty()) {
            item { Text("> aucune fiche client : contacts, accès au site et consignes, reliés au carnet", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        items(list, key = { it.id }) { c ->
            val count = Clients.interventionsOf(c.name).size
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                    .clickable { open = c.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(c.name.ifBlank { "(sans nom)" }.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        c.contact, c.phone, "$count intervention" + if (count > 1) "s" else "",
                        if (c.contracts.any { it.state() != ClientContract.State.Expired && it.state() != ClientContract.State.Upcoming }) "sous contrat" else "",
                        c.quotes.count { it.state == QuoteState.Sent }.let { if (it > 0) "$it devis en attente" else "" },
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (withoutSheet.isNotEmpty()) {
            item { Text("// VUS DANS LE CARNET, SANS FICHE", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(withoutSheet, key = { "n$it" }) { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        "+ FICHE",
                        color = Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { editing = Client(KitStore.newId(), name, "", "", "", "", "", "", "", 0) }.padding(6.dp),
                    )
                }
            }
        }
    }
}

/** One filed document: tap opens it in the matching app, long press offers share and delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DocRow(file: java.io.File, client: String?) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val kind = ClientDocs.kind(file)
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 4.dp)
            .combinedClickable(
                onClick = {
                    if (!ClientDocs.open(context, file)) Toast.makeText(context, "Aucune appli pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
                },
                onLongClick = { menu = !menu; confirm = false },
            )
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                kind.tag,
                color = when (kind) {
                    ClientDocs.Kind.Pdf -> Cp.Red
                    ClientDocs.Kind.Image -> Cp.Yellow
                    else -> Cp.Cyan
                },
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.width(38.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(file.name, color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(client, ClientDocs.sizeLabel(file.length()), DateFmt.format(Instant.ofEpochMilli(file.lastModified()))).joinToString(" · "),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        if (menu) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("PARTAGER", Cp.Cyan, Modifier.weight(1f)) { ClientDocs.share(context, file); menu = false }
                KitButton(if (confirm) "CONFIRMER" else "SUPPRIMER", Cp.Red, Modifier.weight(1f)) {
                    if (confirm) ClientDocs.delete(file) else confirm = true
                }
            }
        }
    }
}

@Composable
private fun ClientSheet(c: Client, onBack: () -> Unit, onEdit: () -> Unit, onOpenIntervention: (String) -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    // Contract or quote being edited, with whether it is a new one.
    var contract by remember { mutableStateOf<Pair<ClientContract, Boolean>?>(null) }
    var quote by remember { mutableStateOf<Pair<Quote, Boolean>?>(null) }
    contract?.let { (k, isNew) ->
        BackHandler { contract = null }
        ContractEditor(c.id, k, isNew) { contract = null }
        return
    }
    quote?.let { (q, isNew) ->
        BackHandler { quote = null }
        QuoteEditor(c.id, q, isNew) { quote = null }
        return
    }
    val interventions = Clients.interventionsOf(c.name)
    val minutes = interventions.sumOf { TimeSheet.minutes(it) }
    val rev = ClientDocs.revision.intValue
    val docs = remember(c.id, rev) { ClientDocs.list(context, c.id) }
    var docQuery by remember { mutableStateOf("") }
    val shownDocs = remember(docs, docQuery) { docs.filter { ClientDocs.matches(it, docQuery) } }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val failed = withContext(Dispatchers.IO) { uris.count { !ClientDocs.import(context, c.id, it) } }
            if (failed > 0) Toast.makeText(context, "$failed fichier(s) illisible(s) ou trop lourd(s) (100 Mo max)", Toast.LENGTH_LONG).show()
        }
    }
    fun start(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Aucune appli pour ça", Toast.LENGTH_SHORT).show() }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { BackRow(c.name, "${interventions.size} interventions · ${TimeSheet.hours(minutes)}", onBack) }
        item { KitButton("ÉDITER", Cp.Cyan, Modifier.fillMaxWidth(), onClick = onEdit) }
        item { InfoBlock("Contact", c.contact) }
        item { InfoBlock("Téléphone", c.phone) { start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.phone.trim())))) } }
        item { InfoBlock("E-mail", c.email) { start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email.trim()))) } }
        item { InfoBlock("Adresse", c.address) { start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(c.address.trim())))) } }
        item { InfoBlock("Accès / horaires", c.access) }
        item { InfoBlock("Consignes du site", c.instructions) }
        item { InfoBlock("Notes", c.notes) }
        val sites = Clients.sitesOf(c.name)
        if (sites.isNotEmpty()) item { InfoBlock("Sites", sites.joinToString(", ")) }
        item {
            Header("// CONTRATS · ${c.contracts.size}", "[ + AJOUTER ]") {
                contract = ClientContract(KitStore.newId(), "", "", 0, 0, "", "") to true
            }
        }
        if (c.contracts.isEmpty()) {
            item { Text("> maintenance, support, infogérance : dates, montant, périmètre", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        } else {
            items(c.contracts, key = { "k" + it.id }) { k -> ClientContractRow(k) { contract = k to false } }
        }
        item {
            Header("// DEVIS · ${c.quotes.size}", "[ + AJOUTER ]") {
                quote = Quote(KitStore.newId(), "", "", System.currentTimeMillis(), "", QuoteState.Draft, "") to true
            }
        }
        if (c.quotes.isEmpty()) {
            item { Text("> suivi des devis : brouillon, envoyé, accepté, refusé", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        } else {
            items(c.quotes, key = { "q" + it.id }) { q -> QuoteRow(q) { quote = q to false } }
        }
        item { ClientAgenda(c) }
        item {
            Header("// DOCUMENTATION · ${docs.size}", "[ + AJOUTER ]") {
                pick.launch(arrayOf("*/*"))
            }
        }
        if (docs.isEmpty()) {
            item { Text("> PDF, schémas, procédures, photos de baie : copiés dans le téléphone, lisibles sans réseau", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        } else {
            if (docs.size > 3) item { KitField("Chercher dans la doc", docQuery, { docQuery = it }) }
            items(shownDocs, key = { "d" + it.name }) { f -> DocRow(f, null) }
        }
        if (interventions.isNotEmpty()) {
            item { Text("// HISTORIQUE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            items(interventions, key = { it.id }) { i ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = (if (i.open) Cp.Yellow else Cp.Cyan).copy(alpha = 0.4f), cut = 4.dp)
                        .clickable { onOpenIntervention(i.id) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row {
                        Text(DateFmt.format(Instant.ofEpochMilli(i.started)), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.width(100.dp))
                        Text(i.title.ifBlank { i.site }, color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (i.open) "EN COURS" else TimeSheet.hours(TimeSheet.minutes(i)), color = if (i.open) Cp.Yellow else Cp.Cyan, fontFamily = MonoFont, fontSize = 11.sp)
                    }
                    // The intervention summary, so the client's story reads without opening each one.
                    if (i.notes.isNotBlank()) {
                        Text(i.notes.replace('\n', ' '), color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            KitButton(if (confirm) "CONFIRMER LA SUPPRESSION" else "SUPPRIMER LA FICHE", Cp.Red, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (confirm) {
                    Clients.delete(context, c.id)
                    onBack()
                } else {
                    confirm = true
                }
            }
        }
    }
}

@Composable
private fun ClientEditor(c: Client, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(c.name) }
    var contact by remember { mutableStateOf(c.contact) }
    var phone by remember { mutableStateOf(c.phone) }
    var email by remember { mutableStateOf(c.email) }
    var address by remember { mutableStateOf(c.address) }
    var access by remember { mutableStateOf(c.access) }
    var instructions by remember { mutableStateOf(c.instructions) }
    var notes by remember { mutableStateOf(c.notes) }
    val known = Clients.items.any { it.id == c.id }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(if (known) "Modifier le client" else "Nouveau client", onBack = { onDone(null) }) }
        item { KitField("Nom (comme dans le carnet)", name, { name = it }) }
        item { KitField("Contact sur place", contact, { contact = it }) }
        item { KitField("Téléphone", phone, { phone = it }, keyboard = KeyboardType.Phone) }
        item { KitField("E-mail", email, { email = it }, keyboard = KeyboardType.Email) }
        item { KitField("Adresse", address, { address = it }, singleLine = false) }
        item { KitField("Accès / horaires (badge, boîte à clés, parking…)", access, { access = it }, singleLine = false) }
        item { KitField("Consignes du site (sécurité, local technique…)", instructions, { instructions = it }, singleLine = false) }
        item { KitField("Notes", notes, { notes = it }, singleLine = false) }
        item {
            Text(
                "Ne range pas de mots de passe ici : les fiches ne sont pas chiffrées.",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
        item {
            KitButton("ENREGISTRER", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                if (name.isBlank()) {
                    Toast.makeText(context, "Le nom est obligatoire", Toast.LENGTH_SHORT).show()
                    return@KitButton
                }
                val clash = Clients.byName(name)
                if (clash != null && clash.id != c.id) {
                    Toast.makeText(context, "Une fiche « ${clash.name} » existe déjà", Toast.LENGTH_SHORT).show()
                    return@KitButton
                }
                Clients.upsert(context, c.copy(name = name, contact = contact.trim(), phone = phone.trim(), email = email.trim(), address = address.trim(), access = access.trim(), instructions = instructions.trim(), notes = notes.trim()))
                onDone(c.id)
            }
        }
    }
}

/** Access and site rules of the intervention's client, shown on the sheet and in focus mode. */
@Composable
internal fun ClientReminder(i: Intervention) {
    val c = Clients.byName(i.client) ?: return
    if (c.access.isBlank() && c.instructions.isBlank() && c.phone.isBlank()) return
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text("CLIENT · ${c.name.uppercase()}", color = Cp.Cyan, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (c.contact.isNotBlank() || c.phone.isNotBlank()) {
            Text(
                listOf(c.contact, c.phone).filter { it.isNotBlank() }.joinToString(" · "),
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 13.sp,
                modifier = Modifier.clickable(enabled = c.phone.isNotBlank()) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.phone.trim()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
        }
        if (c.access.isNotBlank()) Text("Accès : ${c.access}", color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp)
        if (c.instructions.isNotBlank()) Text("Consignes : ${c.instructions}", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------------- time

@Composable
internal fun TimeView() {
    val context = LocalContext.current
    val months = TimeSheet.months(Logbook.items)
    var month by remember { mutableStateOf<YearMonth?>(months.firstOrNull()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = TimeSheet.exportCsv(context, uri, Logbook.items.toList(), month)
            Toast.makeText(context, if (ok) "Heures exportées" else "Échec de l'export", Toast.LENGTH_SHORT).show()
        }
    }
    val rows = TimeSheet.rows(Logbook.items, month)
    val total = rows.sumOf { it.minutes }
    val open = Logbook.items.count { it.open }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Header("// TEMPS PASSÉ") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<YearMonth?>(null) + months).forEach { m ->
                    val on = m == month
                    Text(
                        m?.let { MonthFmt.format(it).uppercase() } ?: "TOUT",
                        color = if (on) Cp.Background else Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 4.dp)
                            .clickable { month = m }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), cut = 8.dp).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(TimeSheet.hours(total), color = Cp.Yellow, fontFamily = DisplayFont, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("${rows.sumOf { it.count }} interventions clôturées" + if (open > 0) " · $open en cours (non comptées)" else "", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
        if (rows.isEmpty()) {
            item { Text("> rien à compter : clôture une intervention du carnet pour qu'elle apparaisse ici", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        items(rows, key = { "${it.month}-${it.client}" }) { r ->
            Row(
                Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 4.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.client, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${MonthFmt.format(r.month)} · ${r.count} intervention" + if (r.count > 1) "s" else "", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                }
                Text(TimeSheet.hours(r.minutes), color = Cp.Cyan, fontFamily = MonoFont, fontSize = 14.sp)
            }
        }
        item {
            KitButton("EXPORTER EN CSV ►", Cp.Yellow, Modifier.fillMaxWidth().padding(top = 6.dp), filled = true) {
                export.launch("heures-" + (month?.toString() ?: "tout") + ".csv")
            }
        }
    }
}

// ---------------------------------------------------------------------------------- stock

@Composable
internal fun StockTab() {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<StockItem?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = Stock.exportCsv(context, uri)
            Toast.makeText(context, if (ok) "Stock exporté" else "Échec de l'export", Toast.LENGTH_SHORT).show()
        }
    }
    editing?.let { s ->
        BackHandler { editing = null }
        StockEditor(s, onDone = { editing = null })
        return
    }
    val low = Stock.low()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Header("// CONSOMMABLES", "[ + ARTICLE ]") {
                editing = StockItem(KitStore.newId(), "", Stock.Units[0], 0, 0, "", "", 0)
            }
        }
        if (low.isNotEmpty()) {
            item {
                Text(
                    "⚠ À RÉAPPROVISIONNER : " + low.joinToString(", ") { "${it.name} (${it.qty})" },
                    color = Cp.Red,
                    fontFamily = HudFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (Stock.items.isEmpty()) {
            item { Text("> aucun article. Ajoute tes consommables (RJ45, cordons, SFP…) et décompte-les depuis le carnet.", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        items(Stock.items.toList(), key = { it.id }) { s ->
            val alert = Stock.isLow(s)
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = (if (alert) Cp.Red else Cp.Cyan).copy(alpha = 0.55f), cut = 6.dp)
                    .clickable { editing = s }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(s.location, if (s.threshold > 0) "seuil ${s.threshold}" else "").filter { it.isNotBlank() }.joinToString(" · "), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                }
                Text("−", color = Cp.Cyan, fontFamily = HudFont, fontSize = 22.sp, modifier = Modifier.clickable { Stock.adjust(context, s.id, -1) }.padding(horizontal = 10.dp))
                Text("${s.qty} ${s.unit}", color = if (alert) Cp.Red else Cp.Yellow, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("+", color = Cp.Cyan, fontFamily = HudFont, fontSize = 22.sp, modifier = Modifier.clickable { Stock.adjust(context, s.id, 1) }.padding(horizontal = 10.dp))
            }
        }
        if (Stock.items.isNotEmpty()) {
            item { KitButton("EXPORTER LE STOCK EN CSV", Cp.Cyan, Modifier.fillMaxWidth().padding(top = 6.dp)) { export.launch("stock.csv") } }
        }
    }
}

@Composable
private fun StockEditor(s: StockItem, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(s.name) }
    var unit by remember { mutableStateOf(s.unit) }
    var qty by remember { mutableStateOf(s.qty.toString()) }
    var threshold by remember { mutableStateOf(if (s.threshold > 0) s.threshold.toString() else "") }
    var location by remember { mutableStateOf(s.location) }
    var notes by remember { mutableStateOf(s.notes) }
    val known = Stock.get(s.id) != null
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(if (known) "Modifier l'article" else "Nouvel article", onBack = onDone) }
        item { KitField("Article", name, { name = it }) }
        item { KitChips(Stock.Units, unit) { unit = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Quantité", qty, { qty = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                KitField("Seuil d'alerte", threshold, { threshold = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
        }
        item { KitField("Emplacement (camion, atelier…)", location, { location = it }) }
        item { KitField("Notes / référence fournisseur", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    if (name.isBlank()) {
                        Toast.makeText(context, "Nom obligatoire", Toast.LENGTH_SHORT).show()
                        return@KitButton
                    }
                    Stock.upsert(context, s.copy(name = name, unit = unit, qty = qty.toIntOrNull() ?: 0, threshold = threshold.toIntOrNull() ?: 0, location = location.trim(), notes = notes.trim()))
                    onDone()
                }
                if (known) {
                    KitButton("SUPPRIMER", Cp.Red, Modifier.weight(1f)) {
                        Stock.delete(context, s.id)
                        onDone()
                    }
                }
            }
        }
    }
}

/** "Consommables" block of an intervention: what was used, and a picker to take more. */
@Composable
internal fun ConsumedBlock(i: Intervention) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<StockItem?>(null) }
    var qty by remember { mutableStateOf("1") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("// CONSOMMABLES", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        i.consumed.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${u.qty} ${u.unit}", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                Text(u.name, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("ANNULER", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.clickable { Logbook.unconsume(context, i.id, u) }.padding(6.dp))
            }
        }
        val p = picking
        if (p != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("${p.name} (${p.qty} en stock)", qty, { qty = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                KitButton("OK", Cp.Yellow, filled = true) {
                    val n = qty.toIntOrNull() ?: 0
                    if (n > 0) Logbook.consume(context, i.id, p, n)
                    picking = null
                    qty = "1"
                }
                KitButton("✕", Cp.Dim) { picking = null }
            }
        } else if (Stock.items.isEmpty()) {
            Text("> ajoute des articles dans INVENTAIRE › CONSOMMABLES pour les décompter ici", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stock.items.forEach { s ->
                    Text(
                        "+ ${s.name}",
                        color = if (s.qty > 0) Cp.Cyan else Cp.Dim,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 4.dp)
                            .clickable { picking = s }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- backup

@Composable
internal fun BackupView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var restoreArmed by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val s = withContext(Dispatchers.IO) { KitBackup.export(context.applicationContext, uri) }
            busy = false
            Toast.makeText(
                context,
                if (s != null) "Kit sauvegardé : ${s.files} fichiers, ${s.bytes / 1024} Ko" else "Échec de la sauvegarde",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val s = withContext(Dispatchers.IO) { KitBackup.restore(context.applicationContext, uri) }
            busy = false
            if (s != null) {
                Toast.makeText(context, "Kit restauré (${s.files} fichiers), redémarrage…", Toast.LENGTH_LONG).show()
                ConfigBackup.restart(context)
            } else {
                Toast.makeText(context, "Fichier invalide : le kit actuel n'a pas été modifié", Toast.LENGTH_LONG).show()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Sauvegarde tout le kit dans un fichier .zip : inventaire, checklists, câbles, adressage, baies, carnet avec ses photos, clients, stock et relevés de couverture. " +
                "Garde-le en lieu sûr : il n'est pas chiffré.",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        KitButton(if (busy) "EN COURS…" else "SAUVEGARDER LE KIT ►", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
            if (!busy) export.launch("netrunner-kit-${java.time.LocalDate.now()}.zip")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "La restauration remplace le kit actuel par celui du fichier, puis le launcher redémarre.",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        KitButton(if (restoreArmed) "CHOISIR LE FICHIER ► (REMPLACE LE KIT)" else "RESTAURER UN KIT", Cp.Red, Modifier.fillMaxWidth()) {
            if (busy) return@KitButton
            if (!restoreArmed) {
                restoreArmed = true
            } else {
                restoreArmed = false
                restore.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
    }
}

// ---------------------------------------------------------------------------------- PC link

/**
 * Pairing with Deck Link on the PC (scan its QR code, or type its code) and manual sync. Once
 * paired, the kit also syncs on its own each time it is closed.
 */
@Composable
internal fun PcLinkView() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    remember { PcLink.load(app) }
    var paired by remember { mutableStateOf(PcLink.paired(app)) }
    var auto by remember { mutableStateOf(PcLink.autoEnabled(app)) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var unpairArmed by remember { mutableStateOf(false) }

    fun accept(text: String) {
        if (PcLink.pair(app, text)) {
            paired = true
            typing = false
            typed = ""
            UiSound.play(context, UiSound.Cue.Confirm)
            scope.launch { withContext(Dispatchers.IO) { PcLink.sync(app) } }
        } else {
            Toast.makeText(context, "Ce n'est pas un code Deck Link", Toast.LENGTH_LONG).show()
        }
    }

    fun scan() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { accept(it) } }
            .addOnFailureListener { typing = true }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Deck Link garde une copie du kit sur ton PC (Windows ou Linux) : inventaire, consommables, carnet avec ses photos, clients, câbles, adressage. " +
                "Tout passe par ton Wi-Fi ou le câble USB, chiffré avec le code d'appairage, sans Internet.",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        if (!paired) {
            Text("> ouvre Deck Link sur le PC, puis scanne le QR code affiché", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp)
            KitButton("SCANNER LE QR DU PC ►", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) { scan() }
            if (!typing) {
                KitButton("SAISIR LE CODE À LA MAIN", Cp.Cyan, Modifier.fillMaxWidth()) { typing = true }
            } else {
                KitField("Code affiché par Deck Link", typed, { typed = it.take(40) }, onDone = { accept(typed) })
                KitButton("APPAIRER", Cp.Cyan, Modifier.fillMaxWidth()) { accept(typed) }
            }
            return@Column
        }

        Text("LIÉ À ${PcLink.pcName.orEmpty().uppercase()}", color = Cp.Yellow, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        val label = when {
            !PcLink.busy -> "SYNCHRONISER MAINTENANT ►"
            PcLink.progress != null -> "ENVOI ${PcLink.progress!!.first}/${PcLink.progress!!.second}…"
            else -> "RECHERCHE DU PC…"
        }
        KitButton(label, Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
            if (!PcLink.busy) scope.launch { withContext(Dispatchers.IO) { PcLink.sync(app) } }
        }
        PcLink.status?.let { s ->
            Text(
                (if (s.ok) "✓ " else "✕ ") + DateFmt.format(Instant.ofEpochMilli(s.at)) + " · " + s.text,
                color = if (s.ok) Cp.Cyan else Cp.Red,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
                .clickable {
                    auto = !auto
                    PcLink.setAuto(app, auto)
                }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Synchro à la fermeture du kit", color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (auto) "ON" else "OFF", color = if (auto) Cp.Yellow else Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Par câble USB : branche le téléphone et lance « adb reverse tcp:47321 tcp:47321 » sur le PC, la synchro passe alors par le câble.",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        KitButton(if (unpairArmed) "CONFIRMER : OUBLIER CE PC" else "OUBLIER CE PC", Cp.Red, Modifier.fillMaxWidth()) {
            if (!unpairArmed) {
                unpairArmed = true
            } else {
                PcLink.unpair(app)
                paired = false
                unpairArmed = false
            }
        }
    }
}
