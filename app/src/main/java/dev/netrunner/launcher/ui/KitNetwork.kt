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
    val context = LocalContext.current

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
