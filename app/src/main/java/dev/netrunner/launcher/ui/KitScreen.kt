package dev.netrunner.launcher.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.netrunner.launcher.kit.ChecklistRun
import dev.netrunner.launcher.kit.ChecklistTemplate
import dev.netrunner.launcher.kit.Equipment
import dev.netrunner.launcher.kit.KitStore
import dev.netrunner.launcher.kit.Subnet
import dev.netrunner.launcher.system.UiSound
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class KitTab(val label: String) { Inventory("INVENTAIRE"), Checklists("CHECKLISTS"), Subnet("SOUS-RÉSEAUX") }

/** Field kit for the technician: equipment inventory, intervention checklists and subnet calculator. */
@Composable
fun KitScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    remember { KitStore.load(context.applicationContext) }
    var tab by remember { mutableStateOf(KitTab.Inventory) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        PanelHeader(title = "// KIT DE TERRAIN", accent = Cp.Yellow, onClose = onClose)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KitTab.entries.forEach { t ->
                val on = t == tab
                Text(
                    t.label,
                    color = if (on) Cp.Background else Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.7f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 6.dp)
                        .clickable {
                            UiSound.play(context, UiSound.Cue.Tap)
                            tab = t
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            KitTab.Inventory -> InventoryTab()
            KitTab.Checklists -> ChecklistsTab()
            KitTab.Subnet -> SubnetTab()
        }
    }
}

// ---------------------------------------------------------------------------------- shared bits

@Composable
private fun KitField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onDone: () -> Unit = {},
) {
    Column(modifier) {
        Text(label.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 5,
            textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp),
            cursorBrush = SolidColor(Cp.Yellow),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun KitButton(label: String, color: Color = Cp.Yellow, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (filled) Cp.Background else color,
        fontFamily = HudFont,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .hudPanel(borderColor = color, fill = if (filled) color else color.copy(alpha = 0.08f), cut = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private val DateFmt = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.systemDefault())

// ---------------------------------------------------------------------------------- inventory

@Composable
private fun InventoryTab() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Equipment?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = KitStore.exportCsv(context, uri)
            Toast.makeText(context, if (ok) "Inventaire exporté" else "Échec de l'export", Toast.LENGTH_SHORT).show()
        }
    }

    // Scan → existing sheet if the code is known, otherwise a new sheet with the code filled in.
    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue.orEmpty().trim()
                if (code.isEmpty()) return@addOnSuccessListener
                UiSound.play(context, UiSound.Cue.Confirm)
                editing = KitStore.findByCode(code) ?: Equipment(KitStore.newId(), code, "", "", "", KitStore.States[0], "", 0)
            }
            .addOnFailureListener {
                Toast.makeText(context, "Scanner indisponible : saisis le code à la main", Toast.LENGTH_LONG).show()
                editing = Equipment(KitStore.newId(), "", "", "", "", KitStore.States[0], "", 0)
            }
    }

    editing?.let { item ->
        BackHandler { editing = null }
        EquipmentSheet(item, onDone = { editing = null })
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KitButton("▣ SCANNER", Cp.Yellow, Modifier.weight(1f), filled = true) { scan() }
        KitButton("+ FICHE", Cp.Cyan, Modifier.weight(1f)) {
            editing = Equipment(KitStore.newId(), "", "", "", "", KitStore.States[0], "", 0)
        }
        KitButton("CSV ►", Cp.Cyan, Modifier.weight(1f)) { export.launch("inventaire-netrunner.csv") }
    }
    Spacer(Modifier.height(8.dp))
    KitField("Recherche (code, nom, série, lieu, état…)", query, { query = it })
    Spacer(Modifier.height(8.dp))
    val results = KitStore.search(query)
    Text("${results.size} / ${KitStore.equipment.size} ÉQUIPEMENT(S)", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(results, key = { it.id }) { e ->
            val color = when (e.state) {
                "HS" -> Cp.Red
                "À réparer" -> Cp.Yellow
                else -> Cp.Cyan
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = color.copy(alpha = 0.55f), cut = 8.dp)
                    .clickable { editing = e }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.name.ifBlank { e.code.ifBlank { "SANS NOM" } }.uppercase(),
                        color = Cp.Text,
                        fontFamily = HudFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(e.state.uppercase(), color = color, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    listOf(e.serial.takeIf { it.isNotBlank() }?.let { "S/N $it" }, e.location.takeIf { it.isNotBlank() }, e.code.takeIf { it.isNotBlank() })
                        .filterNotNull().joinToString(" · "),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EquipmentSheet(item: Equipment, onDone: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(item.code) }
    var name by remember { mutableStateOf(item.name) }
    var serial by remember { mutableStateOf(item.serial) }
    var location by remember { mutableStateOf(item.location) }
    var state by remember { mutableStateOf(item.state) }
    var notes by remember { mutableStateOf(item.notes) }
    val known = KitStore.equipment.any { it.id == item.id }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (known) "// FICHE · MAJ ${DateFmt.format(Instant.ofEpochMilli(item.updated))}" else "// NOUVELLE FICHE",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        item { KitField("Code-barres / QR", code, { code = it }) }
        item { KitField("Nom / modèle", name, { name = it }) }
        item { KitField("Numéro de série", serial, { serial = it }) }
        item { KitField("Emplacement", location, { location = it }) }
        item {
            Text("ÉTAT", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KitStore.States.forEach { s ->
                    val on = s == state
                    Text(
                        s.uppercase(),
                        color = if (on) Cp.Background else Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 4.dp)
                            .clickable { state = s }
                            .padding(vertical = 7.dp),
                    )
                }
            }
        }
        item { KitField("Notes", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    KitStore.upsert(context, item.copy(code = code.trim(), name = name.trim(), serial = serial.trim(), location = location.trim(), state = state, notes = notes.trim()))
                    UiSound.play(context, UiSound.Cue.Confirm)
                    onDone()
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f)) { onDone() }
            }
        }
        if (known) {
            item {
                KitButton("SUPPRIMER LA FICHE", Cp.Red, Modifier.fillMaxWidth()) {
                    KitStore.delete(context, item.id)
                    onDone()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- checklists

@Composable
private fun ChecklistsTab() {
    val context = LocalContext.current
    var openRun by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ChecklistTemplate?>(null) }
    var starting by remember { mutableStateOf<ChecklistTemplate?>(null) }

    val run = KitStore.runs.firstOrNull { it.id == openRun }
    if (run != null) {
        BackHandler { openRun = null }
        RunView(run, onBack = { openRun = null })
        return
    }
    editing?.let { t ->
        BackHandler { editing = null }
        TemplateEditor(t, onDone = { editing = null })
        return
    }
    starting?.let { t ->
        BackHandler { starting = null }
        var site by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("// LANCER : ${t.name.uppercase()}", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            KitField("Site / client / ticket (facultatif)", site, { site = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("DÉMARRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    openRun = KitStore.start(context, t, site).id
                    starting = null
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f)) { starting = null }
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("// MODÈLES", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "[ + NOUVEAU ]",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { editing = ChecklistTemplate(KitStore.newId(), "", emptyList()) }.padding(6.dp),
                )
            }
        }
        items(KitStore.templates.toList(), key = { "t" + it.id }) { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.steps.size} étapes", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                }
                Text("ÉDITER", color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editing = t }.padding(8.dp))
                Text("► LANCER", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { starting = t }.padding(8.dp))
            }
        }
        if (KitStore.runs.isNotEmpty()) {
            item {
                Text("// INTERVENTIONS", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            }
        }
        items(KitStore.runs.toList(), key = { "r" + it.id }) { r ->
            val complete = r.done.size == r.steps.size && r.steps.isNotEmpty()
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = (if (complete) Cp.Cyan else Cp.Yellow).copy(alpha = 0.55f), cut = 8.dp)
                    .clickable { openRun = r.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (complete) "✓ TERMINÉE" else "${r.done.size}/${r.steps.size}", color = if (complete) Cp.Cyan else Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp)
                }
                Text(
                    listOfNotNull(r.site.takeIf { it.isNotBlank() }, DateFmt.format(Instant.ofEpochMilli(r.started))).joinToString(" · "),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
                ProgressBar(r.done.size, r.steps.size, if (complete) Cp.Cyan else Cp.Yellow)
            }
        }
    }
}

@Composable
private fun ProgressBar(done: Int, total: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(total.coerceAtLeast(1)) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(if (i < done) color else Cp.Dim.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun RunView(run: ChecklistRun, onBack: () -> Unit) {
    val context = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(run.name.uppercase(), color = Cp.Yellow, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(run.site.takeIf { it.isNotBlank() }, DateFmt.format(Instant.ofEpochMilli(run.started))).joinToString(" · "),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
            Text("◄ RETOUR", color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
        }
        ProgressBar(run.done.size, run.steps.size, Cp.Yellow)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(run.steps) { i, step ->
                val done = i in run.done
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = (if (done) Cp.Cyan else Cp.Dim).copy(alpha = 0.6f), fill = if (done) Cp.Cyan.copy(alpha = 0.08f) else Cp.Panel, cut = 6.dp)
                        .clickable {
                            UiSound.play(context, if (done) UiSound.Cue.Tap else UiSound.Cue.Confirm)
                            KitStore.toggle(context, run.id, i)
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .hudPanel(borderColor = if (done) Cp.Cyan else Cp.Dim, fill = if (done) Cp.Cyan else Cp.Panel, cut = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Text("✓", color = Cp.Background, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${i + 1}. $step",
                        color = if (done) Cp.Dim else Cp.Text,
                        fontFamily = HudFont,
                        fontSize = 15.sp,
                        textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
            }
            item {
                KitButton("SUPPRIMER CETTE INTERVENTION", Cp.Red, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    KitStore.deleteRun(context, run.id)
                    onBack()
                }
            }
        }
    }
}

@Composable
private fun TemplateEditor(template: ChecklistTemplate, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(template.name) }
    // One step per line: simple to type and to reorder on a phone keyboard.
    var steps by remember { mutableStateOf(template.steps.joinToString("\n")) }
    val known = KitStore.templates.any { it.id == template.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (known) "// MODIFIER LE MODÈLE" else "// NOUVEAU MODÈLE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        KitField("Nom", name, { name = it })
        KitField("Étapes (une par ligne)", steps, { steps = it }, singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                val list = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (name.isBlank() || list.isEmpty()) {
                    Toast.makeText(context, "Donne un nom et au moins une étape", Toast.LENGTH_SHORT).show()
                } else {
                    KitStore.saveTemplate(context, template.copy(name = name.trim(), steps = list))
                    onDone()
                }
            }
            KitButton("ANNULER", Cp.Dim, Modifier.weight(1f)) { onDone() }
        }
        if (known) {
            KitButton("SUPPRIMER LE MODÈLE", Cp.Red, Modifier.fillMaxWidth()) {
                KitStore.deleteTemplate(context, template.id)
                onDone()
            }
        }
    }
}

// ---------------------------------------------------------------------------------- subnet

@Composable
private fun SubnetTab() {
    var input by remember { mutableStateOf("192.168.1.0/24") }
    var split by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf("") }
    val info = Subnet.parse(input)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Adresse/préfixe ou adresse + masque", input, { input = it }) }
        if (info == null) {
            item { Text("> ENTRÉE INVALIDE (ex : 10.0.0.5/22 ou 10.0.0.5 255.255.252.0)", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        } else {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 8.dp)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Subnet.report(info).forEach { (k, v) ->
                        Row {
                            Text(k.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(v, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(Subnet.binary(info.mask), color = Cp.Yellow.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitField("Découper en /", split, { split = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), KeyboardType.Number)
                    KitField("Hôtes nécessaires", hosts, { hosts = it.filter(Char::isDigit).take(9) }, Modifier.weight(1f), KeyboardType.Number)
                }
            }
            hosts.toLongOrNull()?.takeIf { it > 0 }?.let { n ->
                val p = Subnet.prefixForHosts(n)
                item {
                    Text(
                        "> $n hôtes → /$p (${Subnet.text(Subnet.of(0, p).mask)}, ${Subnet.of(0, p).hosts} utilisables)",
                        color = Cp.Yellow,
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                    )
                }
            }
            split.toIntOrNull()?.let { p ->
                val parts = Subnet.split(info, p)
                if (parts.isEmpty()) {
                    item { Text("> /$p doit être plus long que /${info.prefix} (et ≤ 32)", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
                } else {
                    val total = 1L shl (p - info.prefix)
                    item {
                        Text(
                            "// $total SOUS-RÉSEAUX /$p · ${parts.first().hosts} HÔTES CHACUN",
                            color = Cp.Yellow,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    itemsIndexed(parts) { i, s ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("#${i + 1}  ${Subnet.text(s.network)}/$p", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 13.sp)
                            Text(
                                "${Subnet.text(s.firstHost)} → ${Subnet.text(s.lastHost)} · bc ${Subnet.text(s.broadcast)}",
                                color = Cp.Dim,
                                fontFamily = MonoFont,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    if (total > parts.size) {
                        item { Text("… ${total - parts.size} de plus", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
