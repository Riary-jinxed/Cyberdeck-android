package dev.netrunner.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import dev.netrunner.launcher.kit.ChecklistRun
import dev.netrunner.launcher.kit.DevTools
import dev.netrunner.launcher.kit.IpAssignment
import dev.netrunner.launcher.kit.KitReport
import dev.netrunner.launcher.kit.KitStore
import dev.netrunner.launcher.kit.NetPlan
import dev.netrunner.launcher.kit.Rack
import dev.netrunner.launcher.kit.RackItem
import dev.netrunner.launcher.kit.Subnet
import dev.netrunner.launcher.kit.SwitchConfig
import dev.netrunner.launcher.kit.VlanPlan
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ---------------------------------------------------------------------------------- shared

@Composable
private fun BackRow(title: String, subtitle: String = "", onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), color = Cp.Yellow, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
        }
        Text("◄ RETOUR", color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
    }
}

private fun copy(context: android.content.Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copié", Toast.LENGTH_SHORT).show()
}

/** Picks an inventory item by searching; shows the linked one with a ✕ to unlink. */
@Composable
private fun EquipmentLink(equipmentId: String, onChange: (String) -> Unit) {
    var find by remember { mutableStateOf("") }
    val linked = KitStore.equipment.firstOrNull { it.id == equipmentId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ÉQUIPEMENT (INVENTAIRE)", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (linked != null) {
            Row(
                Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⇄ " + linked.name.ifBlank { linked.code }.uppercase(), color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✕", color = Cp.Red, fontSize = 16.sp, modifier = Modifier.clickable { onChange("") }.padding(horizontal = 6.dp))
            }
        } else if (KitStore.equipment.isEmpty()) {
            Text("> inventaire vide", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
        } else {
            KitField("Chercher (nom, code, série)", find, { find = it })
            if (find.isNotBlank()) {
                KitStore.search(find).take(5).forEach { e ->
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
                                onChange(e.id)
                                find = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun equipmentName(id: String): String? = KitStore.equipment.firstOrNull { it.id == id }?.let { it.name.ifBlank { it.code } }

// ---------------------------------------------------------------------------------- addressing plan (IPAM)

@Composable
internal fun AddressingTab() {
    val context = LocalContext.current
    remember { NetPlan.load(context.applicationContext) }
    var site by remember { mutableStateOf(NetPlan.sites().firstOrNull().orEmpty()) }
    var openPlan by remember { mutableStateOf<String?>(null) }
    var editingPlan by remember { mutableStateOf<VlanPlan?>(null) }
    var editingIp by remember { mutableStateOf<IpAssignment?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = NetPlan.exportCsv(context, uri)
            Toast.makeText(context, if (ok) "Plan exporté" else "Échec de l'export", Toast.LENGTH_SHORT).show()
        }
    }

    editingIp?.let { a ->
        BackHandler { editingIp = null }
        val plan = NetPlan.plans.firstOrNull { it.id == a.planId }
        if (plan != null) {
            IpEditor(plan, a, onDone = { editingIp = null })
            return
        }
    }
    editingPlan?.let { p ->
        BackHandler { editingPlan = null }
        PlanEditor(p, onDone = { saved ->
            editingPlan = null
            if (saved != null) site = saved.site
        })
        return
    }
    NetPlan.plans.firstOrNull { it.id == openPlan }?.let { p ->
        BackHandler { openPlan = null }
        PlanView(p, onBack = { openPlan = null }, onEdit = { editingPlan = p }, onIp = { editingIp = it })
        return
    }

    val sites = NetPlan.sites()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("+ VLAN", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    editingPlan = VlanPlan(KitStore.newId(), site, "", "", "", "", "")
                }
                KitButton("CSV ►", Cp.Cyan, Modifier.weight(1f)) { export.launch("plan-adressage-netrunner.csv") }
            }
        }
        if (sites.isEmpty()) {
            item {
                Text(
                    "> aucun plan : ajoute un VLAN avec son site et son sous-réseau, puis les adresses attribuées.",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sites.forEach { s ->
                        val on = s == site
                        Text(
                            s.uppercase(),
                            color = if (on) Cp.Background else Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 4.dp)
                                .clickable { site = s }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            NetPlan.overlaps(site).forEach { (a, b) ->
                item(key = "o" + a.id + b.id) {
                    Text("⚠ VLAN ${a.vlan} (${a.subnet}) chevauche VLAN ${b.vlan} (${b.subnet})", color = Cp.Red, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
            items(NetPlan.plans.filter { it.site == site }.sortedBy { it.vlan.toIntOrNull() ?: 0 }, key = { it.id }) { p ->
                val ips = NetPlan.assignmentsOf(p.id)
                val info = Subnet.parse(p.subnet)
                val issues = ips.count { NetPlan.problems(p, it).isNotEmpty() }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = (if (issues > 0) Cp.Red else Cp.Cyan).copy(alpha = 0.55f), cut = 8.dp)
                        .clickable { openPlan = p.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("VLAN ${p.vlan}", color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                        Text(p.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text(
                        "${p.subnet.ifBlank { "?" }} · ${ips.size}" + (info?.let { " / ${it.hosts}" } ?: "") + " IP" +
                            (if (issues > 0) " · ⚠ $issues à corriger" else ""),
                        color = if (issues > 0) Cp.Red else Cp.Dim,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanEditor(plan: VlanPlan, onDone: (VlanPlan?) -> Unit) {
    val context = LocalContext.current
    var site by remember { mutableStateOf(plan.site) }
    var vlan by remember { mutableStateOf(plan.vlan) }
    var name by remember { mutableStateOf(plan.name) }
    var subnet by remember { mutableStateOf(plan.subnet) }
    var gateway by remember { mutableStateOf(plan.gateway) }
    var notes by remember { mutableStateOf(plan.notes) }
    val known = NetPlan.plans.any { it.id == plan.id }
    val info = Subnet.parse(subnet)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (known) "// MODIFIER LE VLAN" else "// NOUVEAU VLAN", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        item { KitField("Site", site, { site = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("VLAN", vlan, { vlan = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), KeyboardType.Number)
                KitField("Nom", name, { name = it.take(32) }, Modifier.weight(2f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Sous-réseau (ex : 10.0.20.0/24)", subnet, { subnet = it.trim() }, Modifier.weight(1.4f))
                KitField("Passerelle", gateway, { gateway = it.trim() }, Modifier.weight(1f))
            }
        }
        if (subnet.isNotBlank()) {
            item {
                Text(
                    if (info == null) "> sous-réseau invalide"
                    else "> ${Subnet.text(info.network)}/${info.prefix} · ${info.hosts} hôtes · ${Subnet.text(info.firstHost)} → ${Subnet.text(info.lastHost)}",
                    color = if (info == null) Cp.Red else Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
            if (info != null && gateway.isBlank()) {
                item {
                    Text(
                        "[ passerelle = ${Subnet.text(info.firstHost)} ]",
                        color = Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { gateway = Subnet.text(info.firstHost) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
        item { KitField("Notes", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    if (site.isBlank() || vlan.isBlank() || info == null) {
                        Toast.makeText(context, "Site, numéro de VLAN et sous-réseau valides requis", Toast.LENGTH_LONG).show()
                    } else {
                        val saved = plan.copy(
                            site = site.trim(), vlan = vlan, name = name.trim(),
                            subnet = "${Subnet.text(info.network)}/${info.prefix}", gateway = gateway.trim(), notes = notes.trim(),
                        )
                        NetPlan.savePlan(context, saved)
                        onDone(saved)
                    }
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f)) { onDone(null) }
            }
        }
        if (known) {
            item {
                KitButton("SUPPRIMER LE VLAN ET SES ADRESSES", Cp.Red, Modifier.fillMaxWidth()) {
                    NetPlan.deletePlan(context, plan.id)
                    onDone(null)
                }
            }
        }
    }
}

@Composable
private fun PlanView(plan: VlanPlan, onBack: () -> Unit, onEdit: () -> Unit, onIp: (IpAssignment) -> Unit) {
    val ips = NetPlan.assignmentsOf(plan.id)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { BackRow("VLAN ${plan.vlan} · ${plan.name}", "${plan.site} · ${plan.subnet} · passerelle ${plan.gateway.ifBlank { "-" }}", onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("+ ADRESSE", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    onIp(IpAssignment(KitStore.newId(), plan.id, NetPlan.nextFree(plan).orEmpty(), "", "", ""))
                }
                KitButton("ÉDITER LE VLAN", Cp.Cyan, Modifier.weight(1f), onClick = onEdit)
            }
        }
        NetPlan.nextFree(plan)?.let { item { Text("> prochaine adresse libre : $it", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) } }
        items(ips, key = { it.id }) { a ->
            val problems = NetPlan.problems(plan, a)
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = (if (problems.isEmpty()) Cp.Cyan else Cp.Red).copy(alpha = 0.5f), cut = 6.dp)
                    .clickable { onIp(a) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.ip, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.width(120.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(a.host.takeIf { it.isNotBlank() }, equipmentName(a.equipmentId)?.let { "⇄ $it" }).joinToString(" · ").ifBlank { "—" },
                        color = Cp.Text,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (problems.isNotEmpty()) Text("⚠ " + problems.joinToString(", "), color = Cp.Red, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun IpEditor(plan: VlanPlan, a: IpAssignment, onDone: () -> Unit) {
    val context = LocalContext.current
    var ip by remember { mutableStateOf(a.ip) }
    var host by remember { mutableStateOf(a.host) }
    var equipmentId by remember { mutableStateOf(a.equipmentId) }
    var notes by remember { mutableStateOf(a.notes) }
    val known = NetPlan.assignments.any { it.id == a.id }
    val draft = a.copy(ip = ip.trim(), host = host.trim(), equipmentId = equipmentId, notes = notes.trim())
    val problems = if (ip.isNotBlank()) NetPlan.problems(plan, draft) else emptyList()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("// VLAN ${plan.vlan} · ${plan.subnet}", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Adresse IP", ip, { ip = it.trim() }, Modifier.weight(1f), KeyboardType.Decimal)
                KitField("Nom d'hôte", host, { host = it }, Modifier.weight(1f))
            }
        }
        if (problems.isNotEmpty()) item { Text("⚠ " + problems.joinToString(", "), color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        item { EquipmentLink(equipmentId) { equipmentId = it } }
        item { KitField("Notes", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    if (Subnet.ip(ip) == null) {
                        Toast.makeText(context, "Adresse IPv4 invalide", Toast.LENGTH_SHORT).show()
                    } else {
                        NetPlan.saveAssignment(context, draft)
                        UiSound.play(context, UiSound.Cue.Confirm)
                        onDone()
                    }
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f), onClick = onDone)
            }
        }
        if (known) {
            item {
                KitButton("LIBÉRER L'ADRESSE", Cp.Red, Modifier.fillMaxWidth()) {
                    NetPlan.deleteAssignment(context, a.id)
                    onDone()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- racks

@Composable
internal fun RacksTab() {
    val context = LocalContext.current
    remember { NetPlan.load(context.applicationContext) }
    var openRack by remember { mutableStateOf<String?>(null) }
    var editingRack by remember { mutableStateOf<Rack?>(null) }
    var editingItem by remember { mutableStateOf<RackItem?>(null) }

    editingItem?.let { item ->
        BackHandler { editingItem = null }
        RackItemEditor(item, onDone = { editingItem = null })
        return
    }
    editingRack?.let { r ->
        BackHandler { editingRack = null }
        RackEditor(r, onDone = { editingRack = null })
        return
    }
    NetPlan.racks.firstOrNull { it.id == openRack }?.let { r ->
        BackHandler { openRack = null }
        RackView(r, onBack = { openRack = null }, onEdit = { editingRack = r }, onItem = { editingItem = it })
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            KitButton("+ BAIE", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                editingRack = Rack(KitStore.newId(), NetPlan.sites().firstOrNull().orEmpty(), "", 42)
            }
        }
        if (NetPlan.racks.isEmpty()) {
            item { Text("> aucune baie : crée-la puis place les équipements unité par unité.", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        items(NetPlan.racks.sortedWith(compareBy({ it.site }, { it.name })), key = { it.id }) { r ->
            val used = NetPlan.itemsOf(r.id).sumOf { it.heightU }
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.55f), cut = 8.dp)
                    .clickable { openRack = r.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(r.name.ifBlank { "BAIE" }.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${r.site.ifBlank { "?" }} · ${r.units}U · $used U occupés", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RackEditor(rack: Rack, onDone: () -> Unit) {
    val context = LocalContext.current
    var site by remember { mutableStateOf(rack.site) }
    var name by remember { mutableStateOf(rack.name) }
    var units by remember { mutableStateOf(rack.units.toString()) }
    val known = NetPlan.racks.any { it.id == rack.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (known) "// MODIFIER LA BAIE" else "// NOUVELLE BAIE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        KitField("Site", site, { site = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitField("Nom (ex : Baie A, local IT)", name, { name = it }, Modifier.weight(2f))
            KitField("Hauteur (U)", units, { units = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                val u = units.toIntOrNull()?.coerceIn(1, 60) ?: 42
                NetPlan.saveRack(context, rack.copy(site = site.trim(), name = name.trim(), units = u))
                onDone()
            }
            KitButton("ANNULER", Cp.Dim, Modifier.weight(1f), onClick = onDone)
        }
        if (known) {
            KitButton("SUPPRIMER LA BAIE", Cp.Red, Modifier.fillMaxWidth()) {
                NetPlan.deleteRack(context, rack.id)
                onDone()
            }
        }
    }
}

@Composable
private fun RackView(rack: Rack, onBack: () -> Unit, onEdit: () -> Unit, onItem: (RackItem) -> Unit) {
    val items = NetPlan.itemsOf(rack.id)
    // Top unit first, as the rack is read standing in front of it.
    val starts = items.associateBy { it.topU }
    val covered = items.flatMap { i -> NetPlan.unitsOf(i).map { it to i } }.toMap()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { BackRow(rack.name.ifBlank { "BAIE" }, "${rack.site} · ${rack.units}U", onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                KitButton("+ ÉQUIPEMENT", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    val free = (rack.units downTo 1).firstOrNull { it !in covered } ?: rack.units
                    onItem(RackItem(KitStore.newId(), rack.id, free, 1, "", ""))
                }
                KitButton("ÉDITER", Cp.Cyan, Modifier.weight(1f), onClick = onEdit)
            }
        }
        var u = rack.units
        val rows = mutableListOf<Pair<Int, RackItem?>>()
        while (u >= 1) {
            val start = starts[u]
            if (start != null) {
                rows += u to start
                u -= start.heightU
            } else {
                if (covered[u] == null) rows += u to null
                u--
            }
        }
        items(rows, key = { "u" + it.first }) { (unit, item) ->
            if (item == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(Cp.Panel)
                        .clickable { onItem(RackItem(KitStore.newId(), rack.id, unit, 1, "", "")) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%2d".format(unit), color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp, modifier = Modifier.width(28.dp).padding(start = 4.dp))
                    Text("· libre", color = Cp.Dim.copy(alpha = 0.6f), fontFamily = MonoFont, fontSize = 10.sp)
                }
            } else {
                val clash = NetPlan.clash(item) != null
                val color = if (clash) Cp.Red else Cp.Yellow
                val switchCables = KitStore.cables.count { c ->
                    c.switchName.isNotBlank() && (c.switchName.equals(item.label, true) || c.switchName.equals(equipmentName(item.equipmentId), true))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height((22 * item.heightU + 2 * (item.heightU - 1)).dp)
                        .hudPanel(borderColor = color.copy(alpha = 0.7f), fill = color.copy(alpha = 0.08f), cut = 4.dp)
                        .clickable { onItem(item) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%2d".format(unit), color = color, fontFamily = MonoFont, fontSize = 10.sp, modifier = Modifier.width(28.dp).padding(start = 4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label.ifBlank { equipmentName(item.equipmentId) ?: "?" }.uppercase() + if (item.heightU > 1) " · ${item.heightU}U" else "",
                            color = Cp.Text,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.heightU > 1 || switchCables > 0 || clash) {
                            Text(
                                listOfNotNull(
                                    equipmentName(item.equipmentId)?.takeIf { item.label.isNotBlank() }?.let { "⇄ $it" },
                                    switchCables.takeIf { it > 0 }?.let { "$it câble(s) relevé(s)" },
                                    "⚠ chevauchement".takeIf { clash },
                                ).joinToString(" · "),
                                color = if (clash) Cp.Red else Cp.Dim,
                                fontFamily = MonoFont,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RackItemEditor(item: RackItem, onDone: () -> Unit) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(item.label) }
    var top by remember { mutableStateOf(item.topU.toString()) }
    var height by remember { mutableStateOf(item.heightU.toString()) }
    var equipmentId by remember { mutableStateOf(item.equipmentId) }
    val known = NetPlan.rackItems.any { it.id == item.id }
    val rack = NetPlan.racks.firstOrNull { it.id == item.rackId }
    val draft = item.copy(
        label = label.trim(),
        topU = top.toIntOrNull()?.coerceIn(1, rack?.units ?: 60) ?: item.topU,
        heightU = height.toIntOrNull()?.coerceIn(1, 20) ?: 1,
        equipmentId = equipmentId,
    )
    val clash = NetPlan.clash(draft)
    val cables = KitStore.cables.filter { c ->
        c.switchName.isNotBlank() && (c.switchName.equals(draft.label, true) || c.switchName.equals(equipmentName(equipmentId), true))
    }.sortedBy { it.port.filter(Char::isDigit).toIntOrNull() ?: 0 }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (known) "// ÉQUIPEMENT DE BAIE" else "// PLACER UN ÉQUIPEMENT", color = Cp.Yellow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        item { KitField("Libellé (ex : SW-ETAGE1, patch A, onduleur)", label, { label = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Unité du haut", top, { top = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), KeyboardType.Number)
                KitField("Hauteur (U)", height, { height = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), KeyboardType.Number)
            }
        }
        if (clash != null) {
            item { Text("⚠ chevauche « ${clash.label.ifBlank { equipmentName(clash.equipmentId) ?: "?" }} » (U${clash.topU})", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        item { EquipmentLink(equipmentId) { equipmentId = it } }
        if (cables.isNotEmpty()) {
            item { Text("BRASSAGE (RELEVÉ DES CÂBLES)", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            items(cables, key = { "c" + it.id }) { c ->
                Text(
                    "port ${c.port.ifBlank { "?" }} → ${c.outlet.ifBlank { "?" }}" + (c.vlan.takeIf { it.isNotBlank() }?.let { " · VLAN $it" } ?: "") +
                        (equipmentName(c.equipmentId)?.let { " · $it" } ?: ""),
                    color = Cp.Cyan,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("ENREGISTRER", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    NetPlan.saveRackItem(context, draft)
                    onDone()
                }
                KitButton("ANNULER", Cp.Dim, Modifier.weight(1f), onClick = onDone)
            }
        }
        if (known) {
            item {
                KitButton("RETIRER DE LA BAIE", Cp.Red, Modifier.fillMaxWidth()) {
                    NetPlan.deleteRackItem(context, item.id)
                    onDone()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- switch config generator

@Composable
internal fun SwitchGenerator(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { NetPlan.load(context.applicationContext) }
    val sites = NetPlan.sites()
    val switches = KitStore.cables.map { it.switchName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    var os by remember { mutableStateOf(SwitchConfig.Os.Ios) }
    var site by remember { mutableStateOf(sites.firstOrNull().orEmpty()) }
    var switchName by remember { mutableStateOf(switches.firstOrNull().orEmpty()) }
    var hostname by remember { mutableStateOf(switchName) }
    var prefix by remember { mutableStateOf(os.portHint) }
    var uplinks by remember { mutableStateOf("") }
    var mgmtPlan by remember { mutableStateOf("") }
    var mgmtIp by remember { mutableStateOf("") }

    val input = SwitchConfig.Input(os, hostname, site, switchName, prefix, uplinks, mgmtPlan, mgmtIp)
    val text = SwitchConfig.build(input, LocalDate.now().toString())
    val warnings = SwitchConfig.warnings(input)
    val sitePlans = NetPlan.plans.filter { it.site == site }.sortedBy { it.vlan.toIntOrNull() ?: 0 }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow("Générer une config de switch", "à partir du plan d'adressage et du relevé des câbles", onBack) }
        item {
            KitChips(SwitchConfig.Os.entries.map { it.label }, os.label) { label ->
                val next = SwitchConfig.Os.entries.first { it.label == label }
                if (prefix == os.portHint) prefix = next.portHint
                os = next
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Site (plan d'adressage)", site, { site = it }, Modifier.weight(1f))
                KitField("Switch (champ des câbles)", switchName, {
                    if (hostname == switchName) hostname = it
                    switchName = it
                }, Modifier.weight(1f))
            }
        }
        if (sites.size > 1 || switches.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (sites.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sites.forEach { s -> Text("[$s]", color = if (s == site) Cp.Yellow else Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.clickable { site = s }.padding(4.dp)) }
                    }
                    if (switches.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        switches.forEach { s ->
                            Text("[$s]", color = if (s == switchName) Cp.Yellow else Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.clickable {
                                if (hostname == switchName) hostname = s
                                switchName = s
                            }.padding(4.dp))
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitField("Nom d'hôte", hostname, { hostname = it.filter { c -> !c.isWhitespace() } }, Modifier.weight(1f))
                KitField("Préfixe des ports", prefix, { prefix = it }, Modifier.weight(1f))
            }
        }
        item { KitField("Ports d'uplink (trunk, ex : 48 ou 49,50)", uplinks, { uplinks = it }) }
        if (sitePlans.isNotEmpty()) {
            item {
                Text("VLAN DE GESTION", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf<VlanPlan?>(null) + sitePlans).forEach { p ->
                        val on = (p?.id ?: "") == mgmtPlan
                        Text(
                            p?.let { "${it.vlan} ${it.name}" } ?: "AUCUN",
                            color = if (on) Cp.Background else Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 4.dp)
                                .clickable {
                                    mgmtPlan = p?.id.orEmpty()
                                    // Suggest the address already given to this switch in that VLAN, if any.
                                    mgmtIp = p?.let { plan ->
                                        NetPlan.assignmentsOf(plan.id).firstOrNull { a ->
                                            a.host.equals(hostname, true) || equipmentName(a.equipmentId).equals(switchName, true)
                                        }?.ip
                                    } ?: mgmtIp
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            if (mgmtPlan.isNotBlank()) item { KitField("IP de gestion du switch", mgmtIp, { mgmtIp = it.trim() }, keyboard = KeyboardType.Decimal) }
        }
        warnings.forEach { w -> item { Text("⚠ $w", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 11.sp) } }
        item {
            Text(
                text,
                color = Cp.Cyan,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
        item {
            KitButton("⧉ COPIER LA CONFIG", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) { copy(context, "config $hostname", text) }
        }
        item {
            Text(
                "À relire avant de coller dans la console du switch : l'appli ne l'envoie nulle part.",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------- admin tools

@Composable
internal fun ToolsTab() {
    var mode by remember { mutableStateOf("Hash") }
    Column {
        KitChips(listOf("Hash", "Encodage", "JWT", "Bases", "Regex"), mode) { mode = it }
        Spacer(Modifier.height(8.dp))
        when (mode) {
            "Hash" -> HashTool()
            "Encodage" -> EncodeTool()
            "JWT" -> JwtTool()
            "Bases" -> BasesTool()
            else -> RegexTool()
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
            .clickable { copy(context, label, value) }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label.uppercase() + "  ⧉", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
        Text(value, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp)
    }
}

@Composable
private fun HashTool() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DevTools.FileHashes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expected by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            result = null
            scope.launch {
                result = withContext(Dispatchers.IO) { DevTools.hashFile(context, uri) }
                busy = false
                if (result == null) Toast.makeText(context, "Lecture du fichier impossible", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            KitButton(if (busy) "CALCUL EN COURS…" else "▣ CHOISIR UN FICHIER (FIRMWARE, ISO…)", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                if (!busy) pick.launch(arrayOf("*/*"))
            }
        }
        result?.let { r ->
            item { Text("${r.name} · ${"%,d".format(r.size).replace(',', ' ')} octets", color = Cp.Text, fontFamily = MonoFont, fontSize = 12.sp) }
            item { ValueRow("SHA-256", r.sha256) }
            item { ValueRow("SHA-1", r.sha1) }
            item { ValueRow("MD5", r.md5) }
            item { KitField("Comparer avec l'empreinte publiée", expected, { expected = it.trim() }) }
            if (expected.isNotBlank()) {
                val e = expected.lowercase().filter { it.isLetterOrDigit() }
                val ok = e == r.sha256 || e == r.sha1 || e == r.md5
                item {
                    Text(
                        if (ok) "✓ EMPREINTE IDENTIQUE" else "✕ EMPREINTE DIFFÉRENTE : ne pas installer",
                        color = if (ok) Cp.Cyan else Cp.Red,
                        fontFamily = HudFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
        item { KitField("Ou un texte", text, { text = it }, singleLine = false) }
        if (text.isNotEmpty()) {
            item { ValueRow("SHA-256", DevTools.hashText(text, "SHA-256")) }
            item { ValueRow("MD5", DevTools.hashText(text, "MD5")) }
        }
    }
}

@Composable
private fun EncodeTool() {
    var input by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Texte", input, { input = it }, singleLine = false) }
        if (input.isNotEmpty()) {
            item { ValueRow("Base64", DevTools.base64Encode(input)) }
            DevTools.base64Decode(input)?.takeIf { decoded -> decoded.all { !it.isISOControl() || it.isWhitespace() } }?.let { item { ValueRow("Base64 décodé", it) } }
            item { ValueRow("URL encodé", DevTools.urlEncode(input)) }
            DevTools.urlDecode(input)?.takeIf { it != input }?.let { item { ValueRow("URL décodé", it) } }
        }
    }
}

@Composable
private fun JwtTool() {
    var token by remember { mutableStateOf("") }
    val jwt = DevTools.jwt(token)
    val fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Jeton JWT (eyJ…)", token, { token = it.trim() }, singleLine = false) }
        if (token.isNotBlank() && jwt == null) item { Text("> jeton illisible", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        jwt?.let { j ->
            j.expiry?.let { exp ->
                val expired = exp * 1000 < System.currentTimeMillis()
                item {
                    Text(
                        (if (expired) "✕ EXPIRÉ depuis le " else "✓ valide jusqu'au ") + fmt.format(java.time.Instant.ofEpochSecond(exp)),
                        color = if (expired) Cp.Red else Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            j.issuedAt?.let { iat -> item { Text("émis le " + fmt.format(java.time.Instant.ofEpochSecond(iat)), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) } }
            item { ValueRow("En-tête", j.header) }
            item { ValueRow("Contenu", j.payload) }
            item { Text("La signature n'est pas vérifiée : il faudrait la clé de l'émetteur.", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun BasesTool() {
    var input by remember { mutableStateOf("") }
    var radix by remember { mutableStateOf("Déc") }
    val r = DevTools.bases(input, when (radix) { "Hex" -> 16; "Bin" -> 2; "Oct" -> 8; else -> 10 })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitChips(listOf("Déc", "Hex", "Bin", "Oct"), radix) { radix = it } }
        item { KitField("Valeur (ou 0x…, 0b…, 0o…)", input, { input = it.take(64) }) }
        if (input.isNotBlank() && r == null) item { Text("> valeur invalide dans cette base", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) }
        r?.let {
            item { ValueRow("Décimal", it.dec) }
            item { ValueRow("Hexadécimal", it.hex) }
            item { ValueRow("Binaire", it.bin) }
            item { ValueRow("Octal", it.oct) }
        }
    }
}

@Composable
private fun RegexTool() {
    var pattern by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(false) }
    val r = DevTools.regex(pattern, text, ignoreCase)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitField("Expression régulière", pattern, { pattern = it }) }
        item {
            Text(
                (if (ignoreCase) "[✓]" else "[ ]") + " IGNORER LA CASSE",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 12.sp,
                modifier = Modifier.clickable { ignoreCase = !ignoreCase }.padding(vertical = 4.dp),
            )
        }
        item { KitField("Texte à tester", text, { text = it }, singleLine = false) }
        r.error?.let { item { Text("> $it", color = Cp.Red, fontFamily = MonoFont, fontSize = 12.sp) } }
        if (pattern.isNotEmpty() && r.error == null) {
            item { Text("${r.matches.size} correspondance(s)", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            items(r.matches.indices.toList()) { i ->
                Column(Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("#${i + 1}  ${r.matches[i]}", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                    r.groups[i].forEachIndexed { g, v -> Text("  groupe ${g + 1} : $v", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- intervention report

/** Notes and photos to add, then the PDF is built and shared. */
@Composable
internal fun ReportSheet(run: ChecklistRun, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris ->
        if (uris.isNotEmpty()) photos = (photos + uris).distinct().take(12)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow("Rapport d'intervention", "${run.name} · ${run.site.ifBlank { "sans site" }}", onBack) }
        item {
            Text(
                "Contient la checklist" + if (run.site.isNotBlank()) ", les câbles, équipements et adresses du site « ${run.site} »." else ". Indique un site à l'intervention pour y joindre câbles, équipements et adresses.",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        item { KitField("Notes du technicien", notes, { notes = it }, singleLine = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KitButton("+ PHOTOS", Cp.Cyan, Modifier.weight(1f)) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Text("${photos.size} / 12", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                if (photos.isNotEmpty()) Text("✕", color = Cp.Red, fontSize = 16.sp, modifier = Modifier.clickable { photos = emptyList() }.padding(6.dp))
            }
        }
        item {
            KitButton(if (busy) "GÉNÉRATION…" else "GÉNÉRER LE PDF ►", Cp.Yellow, Modifier.fillMaxWidth(), filled = true) {
                if (busy) return@KitButton
                busy = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) { KitReport.build(context.applicationContext, run, notes, photos) }
                    busy = false
                    if (file != null) KitReport.share(context, file)
                    else Toast.makeText(context, "Échec de la génération du rapport", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
