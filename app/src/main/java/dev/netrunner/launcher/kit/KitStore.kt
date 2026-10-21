package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
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

/**
 * Field kit data (equipment inventory and checklists), kept in a JSON file in the app's private
 * storage. Nothing leaves the phone except the CSV the user exports.
 */
object KitStore {
    val States = listOf("En service", "En stock", "À réparer", "HS", "Prêté")

    val equipment = mutableStateListOf<Equipment>()
    val templates = mutableStateListOf<ChecklistTemplate>()
    val runs = mutableStateListOf<ChecklistRun>()
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
        }
    }

    private fun save(context: Context) {
        val root = JSONObject()
            .put("equipment", JSONArray(equipment.map { e ->
                JSONObject().put("id", e.id).put("code", e.code).put("name", e.name).put("serial", e.serial)
                    .put("location", e.location).put("state", e.state).put("notes", e.notes).put("updated", e.updated)
            }))
            .put("templates", JSONArray(templates.map { t ->
                JSONObject().put("id", t.id).put("name", t.name).put("steps", JSONArray(t.steps))
            }))
            .put("runs", JSONArray(runs.map { r ->
                JSONObject().put("id", r.id).put("template", r.templateId).put("name", r.name).put("steps", JSONArray(r.steps))
                    .put("done", JSONArray(r.done.sorted())).put("started", r.started).put("site", r.site)
            }))
        val f = file(context)
        val tmp = File(f.parentFile, "kit.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }

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
        save(context)
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
