package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Equipment(
    val id: String,
    val code: String,
    val name: String,
    val serial: String,
    val location: String,
    val state: String,
    val notes: String,
    val updated: Long,
    /** End of the warranty (midnight, local time), 0 when not set. Deck Link raises an alert before it. */
    val warranty: Long = 0,
)

data class ChecklistTemplate(val id: String, val name: String, val steps: List<String>)

/** A checklist being worked through on an intervention. */
data class ChecklistRun(
    val id: String,
    val templateId: String,
    val name: String,
    val steps: List<String>,
    val done: Set<Int>,
    val started: Long,
    val site: String,
)

/** A cable run as surveyed on site: wall outlet, switch port, VLAN, length, PoE and test result. */
data class CableRecord(
    val id: String,
    val outlet: String,
    val switchName: String,
    val port: String,
    val vlan: String,
    val lengthM: String,
    val poe: String,
    val test: String,
    /** Inventory item at the end of the cable (an AP, a phone…), or blank. */
    val equipmentId: String,
    val site: String,
    val notes: String,
    val updated: Long,
)

/**
 * Field kit data (equipment inventory, checklists, cable survey and config templates), kept in a JSON file in the app's private
 * storage. Nothing leaves the phone except the CSV the user exports.
 */
object KitStore {
    val States = listOf("En service", "En stock", "À réparer", "HS", "Prêté")

    val equipment = mutableStateListOf<Equipment>()
    val templates = mutableStateListOf<ChecklistTemplate>()
    val runs = mutableStateListOf<ChecklistRun>()
    val cables = mutableStateListOf<CableRecord>()
    val PoeModes = listOf("Non", "PoE", "PoE+", "PoE++", "Passif")
    val TestResults = listOf("Non testé", "OK", "Défaut")

    /** The user's own config templates, and edited copies of the built-in ones (same id). */
    val configs = mutableStateListOf<ConfigTemplate>()
    /** Built-in templates the user deleted. */
    private val hiddenConfigs = mutableSetOf<String>()
    /** Last value typed for each config variable, so VLAN_ID or HOSTNAME carry over. */
    val configValues = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "kit.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) {
            templates += defaultTemplates()
            return
        }
        runCatching {
            val root = JSONObject(f.readText())
            root.optJSONArray("equipment")?.objects()?.forEach { o ->
                equipment += Equipment(
                    o.getString("id"), o.optString("code"), o.optString("name"), o.optString("serial"),
                    o.optString("location"), o.optString("state", States[0]), o.optString("notes"), o.optLong("updated"),
                    o.optLong("warranty"),
                )
            }
            root.optJSONArray("templates")?.objects()?.forEach { o ->
                templates += ChecklistTemplate(o.getString("id"), o.optString("name"), o.optJSONArray("steps")?.strings().orEmpty())
            }
            root.optJSONArray("runs")?.objects()?.forEach { o ->
                runs += ChecklistRun(
                    o.getString("id"), o.optString("template"), o.optString("name"),
                    o.optJSONArray("steps")?.strings().orEmpty(),
                    o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty(),
                    o.optLong("started"), o.optString("site"),
                )
            }
            root.optJSONArray("cables")?.objects()?.forEach { o ->
                cables += CableRecord(
                    o.getString("id"), o.optString("outlet"), o.optString("switch"), o.optString("port"), o.optString("vlan"),
                    o.optString("length"), o.optString("poe", PoeModes[0]), o.optString("test", TestResults[0]),
                    o.optString("equipment"), o.optString("site"), o.optString("notes"), o.optLong("updated"),
                )
            }
            root.optJSONArray("configs")?.objects()?.forEach { o ->
                configs += ConfigTemplate(o.getString("id"), o.optString("vendor"), o.optString("title"), o.optString("body"))
            }
            root.optJSONArray("configs_hidden")?.strings()?.let { hiddenConfigs += it }
            root.optJSONObject("config_values")?.let { v -> v.keys().forEach { k -> configValues[k] = v.optString(k) } }
        }
    }

    private fun save(context: Context) {
        val root = JSONObject()
            .put("equipment", JSONArray(equipment.map { e ->
                JSONObject().put("id", e.id).put("code", e.code).put("name", e.name).put("serial", e.serial)
                    .put("location", e.location).put("state", e.state).put("notes", e.notes).put("updated", e.updated)
                    .put("warranty", e.warranty)
            }))
            .put("templates", JSONArray(templates.map { t ->
                JSONObject().put("id", t.id).put("name", t.name).put("steps", JSONArray(t.steps))
            }))
            .put("runs", JSONArray(runs.map { r ->
                JSONObject().put("id", r.id).put("template", r.templateId).put("name", r.name).put("steps", JSONArray(r.steps))
                    .put("done", JSONArray(r.done.sorted())).put("started", r.started).put("site", r.site)
            }))
            .put("cables", JSONArray(cables.map { c ->
                JSONObject().put("id", c.id).put("outlet", c.outlet).put("switch", c.switchName).put("port", c.port)
                    .put("vlan", c.vlan).put("length", c.lengthM).put("poe", c.poe).put("test", c.test)
                    .put("equipment", c.equipmentId).put("site", c.site).put("notes", c.notes).put("updated", c.updated)
            }))
            .put("configs", JSONArray(configs.map { t ->
                JSONObject().put("id", t.id).put("vendor", t.vendor).put("title", t.title).put("body", t.body)
            }))
            .put("configs_hidden", JSONArray(hiddenConfigs.toList()))
            .put("config_values", JSONObject(configValues.toMap()))
        val f = file(context)
        val tmp = File(f.parentFile, "kit.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }

    /** A code handed over by the Scanner or a note link, waiting for the KIT panel to open its sheet. */
    data class Request(val code: String, val format: String?)

    var request by androidx.compose.runtime.mutableStateOf<Request?>(null)

    /** The sheet for [request]: the known equipment with that code, or a new one pre-filled. */
    fun sheetFor(request: Request): Equipment =
        findByCode(request.code) ?: Equipment(
            newId(), request.code, "", "", "", States[0],
            request.format?.let { "Format : $it" }.orEmpty(), 0,
        )

    fun newId(): String = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

    // ------------------------------------------------------------------ inventory

    fun findByCode(code: String): Equipment? = equipment.firstOrNull { it.code == code }

    fun upsert(context: Context, item: Equipment) {
        val stamped = item.copy(updated = System.currentTimeMillis())
        val i = equipment.indexOfFirst { it.id == item.id }
        if (i >= 0) equipment[i] = stamped else equipment.add(0, stamped)
        save(context)
    }

    fun delete(context: Context, id: String) {
        equipment.removeAll { it.id == id }
        // Cables keep their survey, they just lose the link.
        cables.replaceAll { if (it.equipmentId == id) it.copy(equipmentId = "") else it }
        save(context)
        NetPlan.load(context)
        NetPlan.forgetEquipment(context, id)
    }

    fun search(query: String): List<Equipment> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return equipment.toList()
        return equipment.filter { e ->
            listOf(e.code, e.name, e.serial, e.location, e.state, e.notes).any { it.lowercase().contains(q) }
        }
    }

    /** Writes the inventory as CSV (semicolons, UTF-8 with BOM so Excel opens accents correctly). */
    fun exportCsv(context: Context, uri: Uri): Boolean = runCatching {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append('﻿')
            append("Code;Nom;Numéro de série;Emplacement;État;Notes;Mis à jour\r\n")
            equipment.forEach { e ->
                append(listOf(e.code, e.name, e.serial, e.location, e.state, e.notes, fmt.format(Instant.ofEpochMilli(e.updated)))
                    .joinToString(";") { cell(it) })
                append("\r\n")
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) } ?: error("no stream")
    }.isSuccess

    // ------------------------------------------------------------------ cable survey

    fun upsertCable(context: Context, cable: CableRecord) {
        val stamped = cable.copy(updated = System.currentTimeMillis())
        val i = cables.indexOfFirst { it.id == cable.id }
        if (i >= 0) cables[i] = stamped else cables.add(0, stamped)
        save(context)
    }

    fun deleteCable(context: Context, id: String) {
        cables.removeAll { it.id == id }
        save(context)
    }

    fun cablesFor(equipmentId: String): List<CableRecord> = cables.filter { it.equipmentId == equipmentId }

    fun searchCables(query: String): List<CableRecord> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return cables.toList()
        return cables.filter { c ->
            val linked = equipment.firstOrNull { it.id == c.equipmentId }?.let { "${it.name} ${it.code}" }.orEmpty()
            listOf(c.outlet, c.switchName, c.port, c.vlan, c.poe, c.test, c.site, c.notes, linked).any { it.lowercase().contains(q) }
        }
    }

    /** Writes the cable survey as CSV, same format as the inventory export. */
    fun exportCablesCsv(context: Context, uri: Uri): Boolean = runCatching {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append('\uFEFF')
            append("Prise;Switch;Port;VLAN;Longueur (m);PoE;Test;Équipement;Site;Notes;Mis à jour\r\n")
            cables.forEach { c ->
                val linked = equipment.firstOrNull { it.id == c.equipmentId }?.let { it.name.ifBlank { it.code } }.orEmpty()
                append(listOf(c.outlet, c.switchName, c.port, c.vlan, c.lengthM, c.poe, c.test, linked, c.site, c.notes, fmt.format(Instant.ofEpochMilli(c.updated)))
                    .joinToString(";") { cell(it) })
                append("\r\n")
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) } ?: error("no stream")
    }.isSuccess

    // ------------------------------------------------------------------ config library

    /** Built-in templates (unless deleted or edited) followed by the user's, grouped by vendor. */
    fun allConfigs(): List<ConfigTemplate> {
        val own = configs.associateBy { it.id }
        val builtIn = ConfigTemplates.defaults.filter { it.id !in hiddenConfigs }.map { own[it.id] ?: it }
        val extra = configs.filter { c -> ConfigTemplates.defaults.none { it.id == c.id } }
        return (builtIn + extra).sortedBy { ConfigTemplates.Vendors.indexOf(it.vendor).let { i -> if (i < 0) 99 else i } }
    }

    fun saveConfig(context: Context, template: ConfigTemplate) {
        val i = configs.indexOfFirst { it.id == template.id }
        if (i >= 0) configs[i] = template else configs += template
        save(context)
    }

    fun deleteConfig(context: Context, id: String) {
        configs.removeAll { it.id == id }
        if (ConfigTemplates.defaults.any { it.id == id }) hiddenConfigs += id
        save(context)
    }

    /** Brings back the deleted or edited built-in templates. */
    fun restoreConfigs(context: Context) {
        hiddenConfigs.clear()
        configs.removeAll { c -> ConfigTemplates.defaults.any { it.id == c.id } }
        save(context)
    }

    /** In memory only while typing; [flush] writes it when the template is left or copied. */
    fun setConfigValue(name: String, value: String) {
        configValues[name] = value
    }

    fun flush(context: Context) = save(context)

    // ------------------------------------------------------------------ checklists

    fun saveTemplate(context: Context, template: ChecklistTemplate) {
        val i = templates.indexOfFirst { it.id == template.id }
        if (i >= 0) templates[i] = template else templates += template
        save(context)
    }

    fun deleteTemplate(context: Context, id: String) {
        templates.removeAll { it.id == id }
        save(context)
    }

    fun start(context: Context, template: ChecklistTemplate, site: String): ChecklistRun {
        val run = ChecklistRun(newId(), template.id, template.name, template.steps, emptySet(), System.currentTimeMillis(), site.trim())
        runs.add(0, run)
        save(context)
        return run
    }

    fun toggle(context: Context, runId: String, step: Int) {
        val i = runs.indexOfFirst { it.id == runId }
        if (i < 0) return
        val r = runs[i]
        runs[i] = r.copy(done = if (step in r.done) r.done - step else r.done + step)
        save(context)
    }

    fun deleteRun(context: Context, id: String) {
        runs.removeAll { it.id == id }
        save(context)
    }

    private fun defaultTemplates() = listOf(
        ChecklistTemplate(
            "tpl-borne", "Mise en service borne Wi-Fi",
            listOf(
                "Vérifier l'alimentation (PoE / injecteur)",
                "Fixer la borne et l'étiqueter",
                "Raccorder au switch, noter le port",
                "Mettre à jour le firmware",
                "Configurer SSID, VLAN et sécurité",
                "Régler canal et puissance",
                "Tester la couverture et le débit",
                "Ajouter la borne à l'inventaire",
            ),
        ),
        ChecklistTemplate(
            "tpl-poste", "Installation d'un poste",
            listOf(
                "Déballer et vérifier le matériel",
                "Brancher écran, clavier, souris, réseau",
                "Joindre le poste au domaine",
                "Installer les applications métier",
                "Configurer imprimante et partages",
                "Tester la session utilisateur",
                "Ajouter le poste à l'inventaire",
            ),
        ),
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
