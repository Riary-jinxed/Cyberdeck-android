package dev.netrunner.launcher.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.kit.Intervention
import dev.netrunner.launcher.kit.KitNotes
import dev.netrunner.launcher.kit.KitReport
import dev.netrunner.launcher.kit.KitStore
import dev.netrunner.launcher.kit.Logbook
import dev.netrunner.launcher.kit.NetPlan
import dev.netrunner.launcher.system.Focus
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HourFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun dateText(ms: Long) = DateFmt.format(Instant.ofEpochMilli(ms))

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

private fun subtitle(i: Intervention) =
    listOfNotNull(i.site.takeIf { it.isNotBlank() }, i.client.takeIf { it.isNotBlank() }, dateText(i.started)).joinToString(" · ")

/** Starts focus: the screen, then Do Not Disturb when Android lets us. */
private fun startFocus(context: android.content.Context, id: String) {
    Logbook.setFocus(context, id)
    Focus.engage(context)
    UiSound.play(context, UiSound.Cue.Confirm)
}

private fun stopFocus(context: android.content.Context) {
    Logbook.setFocus(context, null)
    Focus.release(context)
}

// ---------------------------------------------------------------------------------- list

@Composable
internal fun LogbookTab(open: String?, onOpen: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Logbook.get(open)?.let { i ->
        BackHandler { onOpen(null) }
        InterventionView(i, onBack = { onOpen(null) })
        return
    }
    if (creating) {
        BackHandler { creating = false }
        InterventionEditor(null, onDone = { id -> creating = false; onOpen(id) })
        return
    }

    val q = query.trim()
    val list = Logbook.items.filter {
        q.isEmpty() || listOf(it.title, it.site, it.client, it.ticket).any { f -> f.contains(q, ignoreCase = true) }
    }.sortedWith(compareBy<Intervention> { !it.open }.thenByDescending { it.started })

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("// CARNET D'INTERVENTION", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "[ + NOUVELLE ]",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { creating = true }.padding(6.dp),
                )
            }
        }
        if (Logbook.items.size > 4) {
            item { KitField("Rechercher (titre, site, client, ticket)", query, { query = it }) }
        }
        if (list.isEmpty()) {
            item {
                Text(
                    if (Logbook.items.isEmpty()) "> aucune intervention. Crée une fiche en arrivant sur site : journal horodaté, photos, checklists et rapport PDF au même endroit."
                    else "> aucun résultat",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
            }
        }
        items(list, key = { it.id }) { i ->
            val color = if (i.open) Cp.Yellow else Cp.Cyan
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = color.copy(alpha = 0.55f), cut = 8.dp)
                    .clickable { onOpen(i.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(i.title.ifBlank { "(sans titre)" }.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            Logbook.focusId == i.id -> "◉ FOCUS"
                            i.open -> "EN COURS"
                            else -> "✓ CLÔTURÉE"
                        },
                        color = color,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
                Text(subtitle(i), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "journal ${i.journal.size} · photos ${i.photos.size} · checklists ${Logbook.runsOf(i).size}" + if (i.ticket.isNotBlank()) " · #${i.ticket}" else "",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------- editor

/** Creates ([existing] null) or edits an intervention's header. */
@Composable
private fun InterventionEditor(existing: Intervention?, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var site by remember { mutableStateOf(existing?.site.orEmpty()) }
    var client by remember { mutableStateOf(existing?.client.orEmpty()) }
    var ticket by remember { mutableStateOf(existing?.ticket.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    val sites = remember { NetPlan.load(context.applicationContext); Logbook.knownSites() }
    val suggestions = sites.filter { site.isBlank() || (it.contains(site.trim(), ignoreCase = true) && !it.equals(site.trim(), ignoreCase = true)) }.take(6)

    fun save(focus: Boolean) {
        if (title.isBlank() && site.isBlank()) {
            Toast.makeText(context, "Donne au moins un titre ou un site", Toast.LENGTH_SHORT).show()
            return
        }
        val id = if (existing == null) {
            Logbook.create(context, title, site, client, ticket).id
        } else {
            Logbook.update(context, existing.copy(title = title.trim(), site = site.trim(), client = client.trim(), ticket = ticket.trim(), notes = notes.trim()))
            existing.id
        }
        if (focus) startFocus(context, id)
        onDone(id)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(if (existing == null) "Nouvelle intervention" else "Modifier l'intervention", onBack = { onDone(existing?.id) }) }
        item { KitField("Titre (ce qui est fait)", title, { title = it }) }
        item { KitField("Site", site, { site = it }) }
        if (suggestions.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        Text(
                            s,
                            color = Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 4.dp)
                                .clickable {
                                    site = s
                                    if (client.isBlank()) client = Logbook.clientFor(s)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        item { KitField("Client", client, { client = it }) }
        val clientHints = dev.netrunner.launcher.kit.Clients.knownNames()
            .filter { client.isNotBlank() && it.contains(client.trim(), ignoreCase = true) && !it.equals(client.trim(), ignoreCase = true) }.take(6)
        if (clientHints.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    clientHints.forEach { n ->
                        Text(
                            n,
                            color = Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 4.dp)
                                .clickable { client = n }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        item { KitField("Ticket / référence", ticket, { ticket = it }) }
        if (existing != null) item { KitField("Notes", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton(if (existing == null) "CRÉER" else "ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) { save(false) }
                if (existing == null) KitButton("CRÉER + FOCUS ►", Cp.Cyan, Modifier.weight(1f)) { save(true) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- sheet

@Composable
private fun InterventionView(i: Intervention, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var openRun by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val photos = rememberPhotoPickers(i.id)

    if (editing) {
        BackHandler { editing = false }
        InterventionEditor(i, onDone = { editing = false })
        return
    }
    KitStore.runs.firstOrNull { it.id == openRun }?.let { run ->
        BackHandler { openRun = null }
        RunView(run, onBack = { openRun = null })
        return
    }

    val cables = KitStore.cables.count { i.site.isNotBlank() && it.site.equals(i.site, ignoreCase = true) }
    val equipment = KitStore.equipment.count { i.site.isNotBlank() && it.location.contains(i.site, ignoreCase = true) }
    val vlans = NetPlan.plans.count { i.site.isNotBlank() && it.site.equals(i.site, ignoreCase = true) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(i.title.ifBlank { "Intervention" }, subtitle(i), onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (i.open) {
                    KitButton(if (Logbook.focusId == i.id) "◉ FOCUS ACTIF" else "► FOCUS", Cp.Yellow, Modifier.weight(1f), filled = true) {
                        startFocus(context, i.id)
                    }
                }
                KitButton("ÉDITER", Cp.Cyan, Modifier.weight(1f)) { editing = true }
            }
        }
        item {
            Text(
                buildString {
                    if (i.ticket.isNotBlank()) append("ticket ${i.ticket} · ")
                    append(if (i.open) "en cours depuis ${duration(System.currentTimeMillis() - i.started)}" else "clôturée ${dateText(i.ended)} · durée ${duration(i.ended - i.started)}")
                    if (i.site.isNotBlank()) append("\nsite : $cables câbles · $equipment équipements · $vlans VLAN (repris dans le rapport)")
                },
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }

        item { ClientReminder(i) }

        item { SectionTitle("JOURNAL") }
        item { JournalInput(entry, { entry = it }) { Logbook.addEntry(context, i.id, entry); entry = "" } }
        items(i.journal.reversed(), key = { "j${it.at}${it.text.hashCode()}" }) { e ->
            Row(verticalAlignment = Alignment.Top) {
                Text(HourFmt.format(Instant.ofEpochMilli(e.at)), color = Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                Text(e.text, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("✕", color = Cp.Dim, fontSize = 12.sp, modifier = Modifier.clickable { Logbook.removeEntry(context, i.id, e) }.padding(horizontal = 6.dp))
            }
        }

        item { SectionTitle("PHOTOS · ${i.photos.size}/${Logbook.MaxPhotos}") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("📷 PHOTO", Cp.Cyan, Modifier.weight(1f)) { photos.camera() }
                KitButton("+ GALERIE", Cp.Cyan, Modifier.weight(1f)) { photos.gallery() }
            }
        }
        if (i.photos.isNotEmpty()) item { PhotoStrip(i) }

        item { SectionTitle("CHECKLISTS") }
        items(Logbook.runsOf(i), key = { "r" + it.id }) { r ->
            val complete = r.done.size == r.steps.size && r.steps.isNotEmpty()
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = (if (complete) Cp.Cyan else Cp.Yellow).copy(alpha = 0.5f), cut = 6.dp)
                    .clickable { openRun = r.id }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row {
                    Text(r.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${r.done.size}/${r.steps.size}", color = if (complete) Cp.Cyan else Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp)
                }
                ProgressBar(r.done.size, r.steps.size, if (complete) Cp.Cyan else Cp.Yellow)
            }
        }
        item { TemplateChips { t -> Logbook.startChecklist(context, i.id, t)?.let { openRun = it.id } } }

        item { ConsumedBlock(i) }

        if (i.notes.isNotBlank()) {
            item { SectionTitle("NOTES") }
            item { Text(i.notes, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp) }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item {
            KitButton(if (busy) "GÉNÉRATION…" else "RAPPORT PDF ►", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                if (busy) return@KitButton
                busy = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) { KitReport.build(context.applicationContext, i) }
                    busy = false
                    if (file != null) KitReport.share(context, file)
                    else Toast.makeText(context, "Échec de la génération du rapport", Toast.LENGTH_SHORT).show()
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("→ NOTE", Cp.Cyan, Modifier.weight(1f)) {
                    KitNotes.create(context, i.title.ifBlank { "Intervention ${i.site}" }, Logbook.summary(i, ::dateText))
                }
                if (i.open) {
                    KitButton("CLÔTURER", Cp.Cyan, Modifier.weight(1f)) {
                        if (Logbook.focusId == i.id) Focus.release(context)
                        Logbook.close(context, i.id)
                    }
                } else {
                    KitButton("ROUVRIR", Cp.Cyan, Modifier.weight(1f)) { Logbook.reopen(context, i.id) }
                }
            }
        }
        item {
            KitButton(if (confirmDelete) "CONFIRMER : SUPPRIMER FICHE ET PHOTOS" else "SUPPRIMER", Cp.Red, Modifier.fillMaxWidth()) {
                if (!confirmDelete) {
                    confirmDelete = true
                } else {
                    if (Logbook.focusId == i.id) Focus.release(context)
                    Logbook.delete(context, i.id)
                    onBack()
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text("// $text", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun JournalInput(value: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KitField("Ajouter au journal (horodaté)", value, onChange, Modifier.weight(1f), onDone = onAdd)
        KitButton("+", Cp.Yellow, filled = true, onClick = onAdd)
    }
}

/** "+ template" chips that start a checklist on the intervention's site. */
@Composable
private fun TemplateChips(onStart: (dev.netrunner.launcher.kit.ChecklistTemplate) -> Unit) {
    if (KitStore.templates.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KitStore.templates.forEach { t ->
            Text(
                "+ ${t.name}",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 4.dp)
                    .clickable { onStart(t) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------- photos

private class PhotoPickers(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
private fun rememberPhotoPickers(id: String): PhotoPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun full() = (Logbook.get(id)?.photos?.size ?: 0) >= Logbook.MaxPhotos
    fun feedback(ok: Boolean) {
        if (!ok) Toast.makeText(context, "Photo non ajoutée", Toast.LENGTH_SHORT).show()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (!taken) return@rememberLauncherForActivityResult
        scope.launch {
            val file = Logbook.cameraTarget(context, id).first
            val ok = withContext(Dispatchers.IO) { Logbook.commitCamera(context.applicationContext, id, file) }
            feedback(ok)
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { uris.count { Logbook.importPhoto(context.applicationContext, id, it) } }
            if (added < uris.size) Toast.makeText(context, "$added / ${uris.size} photos ajoutées", Toast.LENGTH_SHORT).show()
        }
    }
    return PhotoPickers(
        camera = {
            if (full()) {
                Toast.makeText(context, "Maximum ${Logbook.MaxPhotos} photos", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { camera.launch(Logbook.cameraTarget(context, id).second) }
                    .onFailure { Toast.makeText(context, "Aucune appli photo disponible", Toast.LENGTH_SHORT).show() }
            }
        },
        gallery = {
            if (full()) Toast.makeText(context, "Maximum ${Logbook.MaxPhotos} photos", Toast.LENGTH_SHORT).show()
            else gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
}

@Composable
private fun PhotoStrip(i: Intervention) {
    val context = LocalContext.current
    var armed by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        i.photos.forEach { name ->
            Box(contentAlignment = Alignment.TopEnd) {
                Thumbnail(i.id, name)
                // Two taps to delete: the first arms the ✕, the second removes the photo.
                Text(
                    if (armed == name) "SUPPR." else "✕",
                    color = if (armed == name) Cp.Background else Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (armed == name) Cp.Red else Cp.Background.copy(alpha = 0.7f))
                        .clickable {
                            if (armed == name) {
                                Logbook.removePhoto(context, i.id, name)
                                armed = null
                            } else {
                                armed = name
                            }
                        }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(id: String, name: String) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, id, name) {
        value = withContext(Dispatchers.IO) {
            Logbook.decode(context, android.net.Uri.fromFile(Logbook.photo(context, id, name)), 240)?.asImageBitmap()
        }
    }
    Box(Modifier.size(96.dp).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 4.dp), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp)) }
            ?: Text("…", color = Cp.Dim, fontFamily = MonoFont)
    }
}

// ---------------------------------------------------------------------------------- focus

/** Small banner shown over the kit tabs while an intervention is in focus. */
@Composable
internal fun FocusBanner(onReturn: () -> Unit) {
    val i = Logbook.get(Logbook.focusId) ?: return
    val now by rememberNow()
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow, fill = Cp.Yellow.copy(alpha = 0.12f), cut = 6.dp)
            .clickable(onClick = onReturn)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◉ FOCUS", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            "  ${i.title.ifBlank { i.site }} · ${duration(now - i.started)}",
            color = Cp.Text,
            fontFamily = MonoFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("REVENIR ►", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun rememberNow() = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1000)
    }
}

/**
 * Focus screen for the intervention in progress: timer, the checklist step by step, the
 * journal, a photo button and nothing else. Keeps the screen on; Do Not Disturb is set when
 * Android allows it.
 */
@Composable
internal fun FocusScreen(onOpenKit: () -> Unit, onOpenSheet: (String) -> Unit) {
    val context = LocalContext.current
    val i = Logbook.get(Logbook.focusId) ?: return
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val now by rememberNow()
    var entry by remember { mutableStateOf("") }
    var runIndex by remember { mutableStateOf(-1) }
    var dndTick by remember { mutableLongStateOf(0L) }
    // Back to the screen after granting the permission: turn Do Not Disturb on then.
    LaunchedEffect(dndTick) { if (dndTick > 0) { delay(500); Focus.engage(context) } }
    val silenced = remember(dndTick, now / 5000) {
        context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
            ?.let { it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL } == true
    }
    val photos = rememberPhotoPickers(i.id)
    val runs = Logbook.runsOf(i)
    val run = runs.getOrNull(runIndex)
        ?: runs.firstOrNull { it.done.size < it.steps.size }
        ?: runs.lastOrNull()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("◉ FOCUS · ${i.title.ifBlank { "INTERVENTION" }.uppercase()}", color = Cp.Yellow, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle(i), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("KIT ►", color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onOpenKit).padding(8.dp))
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), cut = 10.dp).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(duration(now - i.started), color = Cp.Yellow, fontFamily = DisplayFont, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (silenced) "NE PAS DÉRANGER : ACTIF" else "NE PAS DÉRANGER : INACTIF",
                    color = if (silenced) Cp.Cyan else Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                )
                if (!silenced) {
                    Text(
                        if (Focus.canSilence(context)) "[ ACTIVER ]" else "[ AUTORISER NETRUNNER ]",
                        color = Cp.Yellow,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                if (Focus.canSilence(context)) {
                                    Focus.engage(context)
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                                dndTick = System.currentTimeMillis()
                            }
                            .padding(6.dp),
                    )
                }
            }
        }

        item { ClientReminder(i) }

        // Checklist, one tap per step.
        if (runs.size > 1) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    runs.forEachIndexed { k, r ->
                        val on = r.id == run?.id
                        Text(
                            "${r.name} ${r.done.size}/${r.steps.size}",
                            color = if (on) Cp.Background else Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 4.dp)
                                .clickable { runIndex = k }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (run != null) {
            item { ProgressBar(run.done.size, run.steps.size, Cp.Yellow) }
            itemsIndexed(run.steps, key = { k, _ -> "s${run.id}-$k" }) { k, step ->
                val done = k in run.done
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = (if (done) Cp.Cyan else Cp.Dim).copy(alpha = 0.6f), fill = if (done) Cp.Cyan.copy(alpha = 0.08f) else Cp.Panel, cut = 6.dp)
                        .clickable {
                            UiSound.play(context, if (done) UiSound.Cue.Tap else UiSound.Cue.Confirm)
                            KitStore.toggle(context, run.id, k)
                            if (!done) Logbook.addEntry(context, i.id, "✓ ${run.name} : $step")
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (done) "✓" else "${k + 1}", color = if (done) Cp.Cyan else Cp.Yellow, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.width(26.dp))
                    Text(
                        step,
                        color = if (done) Cp.Dim else Cp.Text,
                        fontFamily = HudFont,
                        fontSize = 16.sp,
                        textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
            }
        } else {
            item { Text("> lance une checklist pour la suivre ici :", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) }
        }
        item { TemplateChips { t -> Logbook.startChecklist(context, i.id, t); runIndex = Logbook.runsOf(Logbook.get(i.id) ?: i).lastIndex } }

        item { SectionTitle("JOURNAL") }
        item { JournalInput(entry, { entry = it }) { Logbook.addEntry(context, i.id, entry); entry = "" } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("📷 PHOTO · ${i.photos.size}", Cp.Cyan, Modifier.weight(1f)) { photos.camera() }
                KitButton("FICHE ►", Cp.Cyan, Modifier.weight(1f)) { onOpenSheet(i.id) }
            }
        }
        items(i.journal.reversed().take(8), key = { "fj${it.at}${it.text.hashCode()}" }) { e ->
            Row {
                Text(HourFmt.format(Instant.ofEpochMilli(e.at)), color = Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                Text(e.text, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp)
            }
        }

        item { Spacer(Modifier.height(6.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("QUITTER LE FOCUS", Cp.Dim, Modifier.weight(1f)) { stopFocus(context) }
                KitButton("TERMINER ✓", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    Logbook.addEntry(context, i.id, "Fin d'intervention")
                    Focus.release(context)
                    Logbook.close(context, i.id)
                    onOpenSheet(i.id)
                }
            }
        }
    }
}
