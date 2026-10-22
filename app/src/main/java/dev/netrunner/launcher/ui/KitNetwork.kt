package dev.netrunner.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.kit.CableRecord
import dev.netrunner.launcher.kit.CheatSheet
import dev.netrunner.launcher.kit.ConfigTemplate
import dev.netrunner.launcher.kit.ConfigTemplates
import dev.netrunner.launcher.kit.Ipv6
import dev.netrunner.launcher.kit.KitStore
import dev.netrunner.launcher.system.UiSound
import java.time.Instant

// ---------------------------------------------------------------------------------- cable survey

/** "P-12 → SW1 port 24 · VLAN 20 · 18 m · PoE+ · OK" */
internal fun cableLine(c: CableRecord): String = listOfNotNull(
    c.outlet.ifBlank { "?" } + " → " + listOf(c.switchName, c.port.takeIf { it.isNotBlank() }?.let { "port $it" })
        .filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "?" },
    c.vlan.takeIf { it.isNotBlank() }?.let { "VLAN $it" },
    c.lengthM.takeIf { it.isNotBlank() }?.let { "$it m" },
    c.poe.takeIf { it != KitStore.PoeModes[0] },
    c.test.takeIf { it != KitStore.TestResults[0] },
).joinToString(" · ")

private fun blankCable() = CableRecord(KitStore.newId(), "", "", "", "", "", KitStore.PoeModes[0], KitStore.TestResults[0], "", "", "", 0)

/** "24" → "25", "1/0/12" → "1/0/13": the next port when surveying a row of outlets. */
private fun nextLabel(text: String): String {
    val m = Regex("""^(.*?)(\d+)$""").find(text.trim()) ?: return text
    val (head, digits) = m.destructured
    return head + (digits.toLong() + 1).toString().padStart(digits.length, '0')
}

@Composable
internal fun CablesTab() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<CableRecord?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = KitStore.exportCablesCsv(context, uri)
            Toast.makeText(context, if (ok) "Relevé exporté" else "Échec de l'export", Toast.LENGTH_SHORT).show()
        }
    }

    editing?.let { cable ->
        BackHandler { editing = null }
        CableSheet(cable, onDone = { editing = it })
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KitButton("+ RELEVÉ", Cp.Yellow, Modifier.weight(1f), filled = true) { editing = blankCable() }
        KitButton("CSV ►", Cp.Cyan, Modifier.weight(1f)) { export.launch("releve-cables-netrunner.csv") }
    }
    Spacer(Modifier.height(8.dp))
    KitField("Recherche (prise, switch, VLAN, site…)", query, { query = it })
    Spacer(Modifier.height(8.dp))
    val results = KitStore.searchCables(query)
    Text("${results.size} / ${KitStore.cables.size} CÂBLE(S)", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(results, key = { it.id }) { c ->
            val color = when (c.test) {
                "Défaut" -> Cp.Red
                "OK" -> Cp.Cyan
                else -> Cp.Yellow
            }
            val linked = KitStore.equipment.firstOrNull { it.id == c.equipmentId }
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = color.copy(alpha = 0.55f), cut = 8.dp)
                    .clickable { editing = c }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(cableLine(c), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    linked?.let { "⇄ " + it.name.ifBlank { it.code } },
                    c.site.takeIf { it.isNotBlank() },
                    DateFmt.format(Instant.ofEpochMilli(c.updated)),
                ).joinToString(" · ")
                Text(sub, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Edits one cable; [onDone] gets the next sheet to show (null to go back to the list). */
@Composable
private fun CableSheet(cable: CableRecord, onDone: (CableRecord?) -> Unit) {
    val context = LocalContext.current
    var outlet by remember(cable.id) { mutableStateOf(cable.outlet) }
    var switchName by remember(cable.id) { mutableStateOf(cable.switchName) }
    var port by remember(cable.id) { mutableStateOf(cable.port) }
    var vlan by remember(cable.id) { mutableStateOf(cable.vlan) }
    var length by remember(cable.id) { mutableStateOf(cable.lengthM) }
    var poe by remember(cable.id) { mutableStateOf(cable.poe) }
    var test by remember(cable.id) { mutableStateOf(cable.test) }
    var equipmentId by remember(cable.id) { mutableStateOf(cable.equipmentId) }
    var site by remember(cable.id) { mutableStateOf(cable.site) }
    var notes by remember(cable.id) { mutableStateOf(cable.notes) }
    var find by remember(cable.id) { mutableStateOf("") }
    val known = KitStore.cables.any { it.id == cable.id }

    fun current() = cable.copy(
        outlet = outlet.trim(), switchName = switchName.trim(), port = port.trim(), vlan = vlan.trim(), lengthM = length.trim(),
        poe = poe, test = test, equipmentId = equipmentId, site = site.trim(), notes = notes.trim(),
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (known) "// RELEVÉ · MAJ ${DateFmt.format(Instant.ofEpochMilli(cable.updated))}" else "// NOUVEAU RELEVÉ",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Prise murale", outlet, { outlet = it }, Modifier.weight(1f))
                KitField("Site / local", site, { site = it }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Switch / baie", switchName, { switchName = it }, Modifier.weight(1.3f))
                KitField("Port", port, { port = it }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("VLAN", vlan, { vlan = it.filter { c -> c.isDigit() || c == ',' || c == '-' }.take(20) }, Modifier.weight(1f), KeyboardType.Number)
                KitField("Longueur (m)", length, { length = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(6) }, Modifier.weight(1f), KeyboardType.Decimal)
            }
        }
        item {
            Text("POE", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            KitChips(KitStore.PoeModes, poe) { poe = it }
        }
        item {
            Text("TEST DU CÂBLE", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            KitChips(KitStore.TestResults, test) { test = it }
        }
        item {
            Text("ÉQUIPEMENT AU BOUT (INVENTAIRE)", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val linked = KitStore.equipment.firstOrNull { it.id == equipmentId }
            if (linked != null) {
                Row(
                    Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⇄ " + linked.name.ifBlank { linked.code }.uppercase(),
                        color = Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("✕", color = Cp.Red, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.clickable { equipmentId = "" }.padding(horizontal = 6.dp))
                }
            } else if (KitStore.equipment.isEmpty()) {
                Text("> inventaire vide : ajoute d'abord l'équipement dans INVENTAIRE", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
            } else {
                KitField("Chercher (nom, code, série)", find, { find = it })
            }
        }
        if (equipmentId.isBlank() && find.isNotBlank()) {
            items(KitStore.search(find).take(5), key = { "e" + it.id }) { e ->
                Text(
                    "+ " + listOf(e.name, e.code, e.location).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Dim.copy(alpha = 0.5f), cut = 4.dp)
                        .clickable {
                            equipmentId = e.id
                            find = ""
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        item { KitField("Notes", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    KitStore.upsertCable(context, current())
                    UiSound.play(context, UiSound.Cue.Confirm)
                    onDone(null)
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f)) { onDone(null) }
            }
        }
        item {
            // Surveying a row of outlets: same switch, VLAN and site, next outlet and port.
            KitButton("ENREGISTRER + SUIVANT ►", Cp.Cyan, Modifier.fillMaxWidth()) {
                val saved = current()
                KitStore.upsertCable(context, saved)
                UiSound.play(context, UiSound.Cue.Confirm)
                onDone(
                    blankCable().copy(
                        outlet = nextLabel(saved.outlet), switchName = saved.switchName, port = nextLabel(saved.port),
                        vlan = saved.vlan, poe = saved.poe, site = saved.site,
                    )
                )
            }
        }
        if (known) {
            item {
                KitButton("SUPPRIMER LE RELEVÉ", Cp.Red, Modifier.fillMaxWidth()) {
                    KitStore.deleteCable(context, cable.id)
                    onDone(null)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- config library

@Composable
internal fun ConfigsTab() {
    var vendor by remember { mutableStateOf("Tous") }
    var open by remember { mutableStateOf<ConfigTemplate?>(null) }
    var editing by remember { mutableStateOf<ConfigTemplate?>(null) }
    var generator by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (generator) {
        BackHandler { generator = false }
        SwitchGenerator(onBack = { generator = false })
        return
    }

    editing?.let { t ->
        BackHandler { editing = null }
        ConfigEditor(t, onDone = { saved ->
            editing = null
            if (saved != null) open = saved
        })
        return
    }
    open?.let { t ->
        BackHandler { open = null }
        ConfigView(t, onBack = { open = null }, onEdit = { editing = t })
        return
    }

    val all = KitStore.allConfigs()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            KitButton("⚙ GÉNÉRER UNE CONFIG DE SWITCH COMPLÈTE ►", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) { generator = true }
        }
        item { KitChips(listOf("Tous", "Cisco", "HP / Aruba", "Ubiquiti"), vendor) { vendor = it } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("// MODÈLES DE COMMANDES", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "[ + NOUVEAU ]",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { editing = ConfigTemplate(KitStore.newId(), if (vendor == "Tous") "Cisco" else vendor, "", "") }
                        .padding(6.dp),
                )
            }
        }
        items(all.filter { vendor == "Tous" || it.vendor == vendor }, key = { it.id }) { t ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                    .clickable { open = t }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(t.title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    t.vendor + (t.variables.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString(", ") } ?: ""),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Text(
                "RESTAURER LES MODÈLES D'ORIGINE",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable {
                        KitStore.restoreConfigs(context)
                        Toast.makeText(context, "Modèles d'origine restaurés", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ConfigView(template: ConfigTemplate, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    // Values are shared across templates (VLAN_ID typed once serves everywhere) and saved on leave.
    DisposableEffect(template.id) { onDispose { KitStore.flush(context) } }
    val rendered = template.render(KitStore.configValues)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(template.title.uppercase(), color = Cp.Yellow, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(template.vendor, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                }
                Text("◄ RETOUR", color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            }
        }
        items(template.variables, key = { "v$it" }) { name ->
            val hint = ConfigTemplates.Hints[name]
            KitField(
                if (hint != null) "$name (ex : $hint)" else name,
                KitStore.configValues[name].orEmpty(),
                { KitStore.setConfigValue(name, it) },
            )
        }
        item {
            Text(
                rendered,
                color = Cp.Cyan,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
        item {
            val missing = template.variables.filter { KitStore.configValues[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                Text("> à remplir : ${missing.joinToString(", ")}", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("⧉ COPIER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(template.title, rendered))
                    KitStore.flush(context)
                    UiSound.play(context, UiSound.Cue.Confirm)
                    Toast.makeText(context, "Commandes copiées", Toast.LENGTH_SHORT).show()
                }
                KitButton("ÉDITER", Cp.Cyan, Modifier.weight(1f), onClick = onEdit)
            }
        }
    }
}

@Composable
private fun ConfigEditor(template: ConfigTemplate, onDone: (ConfigTemplate?) -> Unit) {
    val context = LocalContext.current
    var vendor by remember { mutableStateOf(template.vendor.ifBlank { ConfigTemplates.Vendors[0] }) }
    var title by remember { mutableStateOf(template.title) }
    var body by remember { mutableStateOf(template.body) }
    val known = KitStore.allConfigs().any { it.id == template.id }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (known) "// MODIFIER LE MODÈLE" else "// NOUVEAU MODÈLE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        item { KitChips(ConfigTemplates.Vendors, vendor) { vendor = it } }
        item { KitField("Titre", title, { title = it }) }
        item {
            KitField("Commandes · {{NOM}} pour une variable (majuscules)", body, { body = it }, singleLine = false)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    if (title.isBlank() || body.isBlank()) {
                        Toast.makeText(context, "Donne un titre et des commandes", Toast.LENGTH_SHORT).show()
                    } else {
                        val saved = template.copy(vendor = vendor, title = title.trim(), body = body.trimEnd())
                        KitStore.saveConfig(context, saved)
                        onDone(saved)
                    }
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f)) { onDone(null) }
            }
        }
        if (known) {
            item {
                KitButton("SUPPRIMER LE MODÈLE", Cp.Red, Modifier.fillMaxWidth()) {
                    KitStore.deleteConfig(context, template.id)
                    onDone(null)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- memo

@Composable
internal fun MemoTab() {
    var query by remember { mutableStateOf("") }
    val sections = CheatSheet.search(query)
    Column {
        KitField("Recherche (port, couleur, norme, OID…)", query, { query = it })
        Spacer(Modifier.height(8.dp))
        if (sections.isEmpty()) {
            Text("> aucun résultat", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            sections.forEach { s ->
                item(key = "s" + s.title) {
                    Text("// " + s.title, color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                itemsIndexed(s.entries, key = { i, _ -> s.title + i }) { _, e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.25f), cut = 4.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(e.key, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.value, color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (e.detail.isNotBlank()) Text(e.detail, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- IPv6

@Composable
internal fun Ipv6Tab() {
    var input by remember { mutableStateOf("2001:db8:abcd::/48") }
    var split by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    val info = Ipv6.parse(input)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Adresse IPv6 / préfixe", input, { input = it.trim() }) }
        if (info == null) {
            item { Text("> ENTRÉE INVALIDE (ex : 2001:db8::1/64 ou fd00:1::/48)", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        } else {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 8.dp)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Ipv6.report(info).forEach { (k, v) ->
                        Text(k.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
                        Text(v, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitField("Découper en /", split, { split = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), KeyboardType.Number)
                    KitField("MAC → EUI-64", mac, { mac = it.take(17) }, Modifier.weight(1.4f))
                }
            }
            if (mac.isNotBlank()) {
                val id = Ipv6.eui64(mac)
                item {
                    if (id == null) {
                        Text("> MAC invalide (ex : 00:1a:2b:3c:4d:5e)", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp)
                    } else {
                        val full = if (info.prefix <= 64) Ipv6.withInterfaceId(info, id) else null
                        Column {
                            Text("> identifiant : $id", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp)
                            if (full != null) Text("> adresse SLAAC : $full", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp)
                        }
                    }
                }
            }
            split.toIntOrNull()?.let { p ->
                val parts = Ipv6.split(info, p)
                if (parts.isEmpty()) {
                    item { Text("> /$p doit être plus long que /${info.prefix} (et ≤ 128)", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
                } else {
                    item {
                        Text(
                            "// ${Ipv6.count(info, p)} PRÉFIXES /$p",
                            color = Cp.Yellow,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    itemsIndexed(parts) { i, s ->
                        Text(
                            "#${i + 1}  ${Ipv6.compressed(s.network)}/$p",
                            color = Cp.Cyan,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    if (p - info.prefix > 6) {
                        item { Text("… liste limitée aux ${parts.size} premiers", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- calculations

private fun num(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

private fun watts(w: Double) = "%.1f W".format(w)

private fun db(v: Double) = "%.2f dB".format(v)

@Composable
internal fun CalcTab() {
    var mode by remember { mutableStateOf("PoE") }
    // Kept here so switching between the three calculators keeps what was typed.
    val poeCounts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    Column {
        KitChips(listOf("PoE", "Fibre", "MAC", "Wi-Fi QR"), mode) { mode = it }
        Spacer(Modifier.height(8.dp))
        when (mode) {
            "PoE" -> PoeCalc(poeCounts)
            "Fibre" -> FiberCalc()
            "MAC" -> MacCalc()
            else -> WifiQr()
        }
    }
}

@Composable
private fun ResultBox(color: androidx.compose.ui.graphics.Color, lines: List<Pair<String, String>>, verdict: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = 0.7f), fill = color.copy(alpha = 0.06f), cut = 8.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        verdict?.let { Text(it, color = color, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        lines.forEach { (k, v) ->
            Row {
                Text(k.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(v, color = Cp.Text, fontFamily = MonoFont, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PoeCalc(counts: MutableMap<String, Int>) {
    var budget by remember { mutableStateOf("370") }
    var ports by remember { mutableStateOf("24") }
    val result = dev.netrunner.launcher.kit.FieldCalc.poe(counts, num(budget) ?: 0.0)
    val portCount = ports.toIntOrNull()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Budget PoE du switch (W)", budget, { budget = it.take(6) }, Modifier.weight(1.4f), KeyboardType.Decimal)
                KitField("Ports PoE", ports, { ports = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), KeyboardType.Number)
            }
        }
        item {
            val over = result.budget > 0 && result.reservedLeft < 0
            val tight = result.budget > 0 && result.load > 80
            val noPorts = portCount != null && result.ports > portCount
            val color = when {
                over || noPorts -> Cp.Red
                tight -> Cp.Yellow
                else -> Cp.Cyan
            }
            val verdict = when {
                result.ports == 0 -> "> ajoute des appareils ci-dessous"
                noPorts -> "✕ ${result.ports} appareils pour $portCount ports PoE"
                over -> "✕ BUDGET DÉPASSÉ de ${watts(-result.reservedLeft)}"
                tight -> "⚠ BUDGET TENDU (${result.load.toInt()} % réservé)"
                else -> "✓ ÇA PASSE (${result.load.toInt()} % réservé)"
            }
            ResultBox(
                color,
                listOf(
                    "Appareils" to "${result.ports}" + (portCount?.let { " / $it ports" } ?: ""),
                    "Réservé (classes)" to watts(result.reserved),
                    "Reste réservable" to watts(result.reservedLeft),
                    "Conso typique" to watts(result.typical),
                    "Reste en usage" to watts(result.typicalLeft),
                ),
                verdict,
            )
        }
        item {
            Text(
                "Le switch réserve la puissance de la classe annoncée. Visez moins de 80 % réservé pour garder de la marge.",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        items(dev.netrunner.launcher.kit.FieldCalc.PoeDevices, key = { it.name }) { d ->
            val n = counts[d.name] ?: 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = (if (n > 0) Cp.Yellow else Cp.Cyan).copy(alpha = 0.45f), cut = 6.dp)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.standard} · ${watts(d.reservedW)} réservés · ~${watts(d.typicalW)}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp)
                }
                Text(
                    "−",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 22.sp,
                    modifier = Modifier.clickable(enabled = n > 0) { counts[d.name] = n - 1 }.padding(horizontal = 10.dp),
                )
                Text("$n", color = if (n > 0) Cp.Yellow else Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp, modifier = Modifier.width(26.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(
                    "+",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 22.sp,
                    modifier = Modifier.clickable { counts[d.name] = n + 1 }.padding(horizontal = 10.dp),
                )
            }
        }
        item {
            Text(
                "REMETTRE À ZÉRO",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 11.sp,
                modifier = Modifier.clickable { counts.clear() }.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FiberCalc() {
    val fibers = dev.netrunner.launcher.kit.FieldCalc.Fibers
    var fiberName by remember { mutableStateOf(fibers[0].name) }
    var length by remember { mutableStateOf("2") }
    var connectors by remember { mutableStateOf("2") }
    var splices by remember { mutableStateOf("0") }
    var margin by remember { mutableStateOf("3") }
    var tx by remember { mutableStateOf("") }
    var rx by remember { mutableStateOf("") }
    val fiber = fibers.first { it.name == fiberName }
    val r = dev.netrunner.launcher.kit.FieldCalc.fiber(
        fiber, num(length) ?: 0.0, connectors.toIntOrNull() ?: 0, splices.toIntOrNull() ?: 0, num(margin) ?: 0.0, num(tx), num(rx),
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fibers.chunked(2).forEach { row -> KitChips(row.map { it.name }, fiberName) { fiberName = it } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Longueur (km)", length, { length = it.take(7) }, Modifier.weight(1f), KeyboardType.Decimal)
                KitField("Connecteurs", connectors, { connectors = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), KeyboardType.Number)
                KitField("Soudures", splices, { splices = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), KeyboardType.Number)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Émission (dBm)", tx, { tx = it.take(6) }, Modifier.weight(1f), KeyboardType.Text)
                KitField("Sensibilité (dBm)", rx, { rx = it.take(6) }, Modifier.weight(1f), KeyboardType.Text)
                KitField("Marge (dB)", margin, { margin = it.take(4) }, Modifier.weight(1f), KeyboardType.Decimal)
            }
        }
        item {
            val headroom = r.headroom
            val color = when {
                headroom == null -> Cp.Cyan
                headroom < 0 -> Cp.Red
                headroom < 1 -> Cp.Yellow
                else -> Cp.Cyan
            }
            val verdict = when {
                headroom == null -> "> perte totale : ${db(r.loss)}"
                headroom < 0 -> "✕ LIAISON HORS BUDGET (${db(headroom)})"
                headroom < 1 -> "⚠ LIAISON JUSTE (reste ${db(headroom)})"
                else -> "✓ LIAISON OK (reste ${db(headroom)})"
            }
            val lines = buildList {
                add("Fibre (${fiber.dbPerKm} dB/km)" to db(r.cable))
                add("Connecteurs (${dev.netrunner.launcher.kit.FieldCalc.CONNECTOR_DB} dB)" to db(r.connectors))
                add("Soudures (${dev.netrunner.launcher.kit.FieldCalc.SPLICE_DB} dB)" to db(r.splices))
                add("Perte totale" to db(r.loss))
                r.budget?.let { add("Budget optique" to db(it)) }
                if (r.budget != null) add("Marge de sécurité" to db(r.margin))
            }
            ResultBox(color, lines, verdict)
        }
        item {
            Text(
                "Valeurs maximales TIA-568 : le résultat est pessimiste. Puissance d'émission mini et sensibilité dans la fiche du module SFP (ex : 1000BASE-LX −9,5 / −20 dBm).",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MacCalc() {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val mac = dev.netrunner.launcher.kit.FieldCalc.mac(input)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Adresse MAC (tout format)", input, { input = it.take(20) }) }
        if (input.isNotBlank() && mac == null) {
            item { Text("> MAC invalide (12 chiffres hexadécimaux)", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        if (mac != null) {
            item {
                val kind = when {
                    mac.broadcast -> "diffusion (broadcast)"
                    mac.multicast -> "multicast"
                    mac.local -> "locale : aléatoire (téléphone) ou virtuelle"
                    else -> "unicast, attribuée au constructeur"
                }
                ResultBox(
                    Cp.Cyan,
                    listOf(
                        "Constructeur" to (mac.vendor ?: if (mac.local) "—" else "inconnu (OUI ${mac.oui})"),
                        "Type" to kind,
                        "EUI-64" to (Ipv6.eui64(mac.bare) ?: "—"),
                    ),
                )
            }
            items(
                listOf("Linux / Unix" to mac.colon, "Windows" to mac.dash, "Cisco" to mac.cisco, "Brut" to mac.bare),
                key = { it.first },
            ) { (label, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
                        .clickable {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("MAC", value))
                            Toast.makeText(context, "$value copié", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(value, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 14.sp)
                    Text("  ⧉", color = Cp.Dim, fontSize = 12.sp)
                }
            }
            item {
                Text(
                    "Liste de constructeurs courte et hors ligne (réseau, VoIP, caméras, virtualisation).",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Strong guest password: no look-alike characters (0/O, 1/l/I), easy to type from a poster. */
private fun guestPassword(length: Int = 16): String {
    val chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    val random = java.security.SecureRandom()
    return (1..length).map { chars[random.nextInt(chars.length)] }
        .joinToString("").chunked(4).joinToString("-")
}

/** The standard "WIFI:" payload phones read to join a network (special characters escaped). */
private fun wifiPayload(ssid: String, security: String, password: String, hidden: Boolean): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    val type = if (security == "Ouvert") "nopass" else "WPA"
    return buildString {
        append("WIFI:T:").append(type).append(";S:").append(esc(ssid)).append(';')
        if (type != "nopass") append("P:").append(esc(password)).append(';')
        if (hidden) append("H:true;")
        append(';')
    }
}

@Composable
private fun WifiQr() {
    val context = LocalContext.current
    var ssid by remember { mutableStateOf("Invités") }
    var security by remember { mutableStateOf("WPA2/WPA3") }
    var password by remember { mutableStateOf(guestPassword()) }
    var hidden by remember { mutableStateOf(false) }
    val open = security == "Ouvert"
    val payload = wifiPayload(ssid.trim(), security, password, hidden)
    val tooShort = !open && password.length < 8

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Nom du réseau (SSID)", ssid, { ssid = it.take(32) }) }
        item { KitChips(listOf("WPA2/WPA3", "Ouvert"), security) { security = it } }
        if (!open) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    KitField("Mot de passe (8 à 63)", password, { password = it.take(63) }, Modifier.weight(1f))
                    KitButton("↻", Cp.Cyan) { password = guestPassword() }
                }
            }
        }
        item {
            Text(
                (if (hidden) "[✓]" else "[ ]") + " RÉSEAU MASQUÉ",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 12.sp,
                modifier = Modifier.clickable { hidden = !hidden }.padding(vertical = 4.dp),
            )
        }
        if (ssid.isBlank() || tooShort) {
            item {
                Text(
                    if (ssid.isBlank()) "> donne un nom de réseau" else "> le mot de passe doit faire au moins 8 caractères",
                    color = Cp.Red,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
            }
        } else {
            item {
                Column(
                    Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f), cut = 10.dp).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    remember(payload) { encodeQr(payload) }?.let { QrImage(it, Modifier.size(220.dp)) }
                    Text("WI-FI : ${ssid.trim()}", color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (!open) Text(password, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 14.sp)
                    Text("Scanne avec l'appareil photo pour te connecter", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitButton("PARTAGER ►", Cp.Yellow, Modifier.weight(1f), filled = true) {
                        val lines = if (open) listOf("Réseau ouvert") else listOf("Mot de passe : $password")
                        qrLabelBitmap(payload, "Wi-Fi : ${ssid.trim()}", lines)?.let { shareBitmap(context, it, "wifi-${ssid.trim()}") }
                    }
                    KitButton("⧉ MOT DE PASSE", Cp.Cyan, Modifier.weight(1f)) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Wi-Fi", password))
                        Toast.makeText(context, "Mot de passe copié", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item {
                Text(
                    "Rien n'est appliqué à la borne : crée le réseau invité avec ces valeurs dans son interface, puis affiche ou imprime le QR.",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
