package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A consumable kept in the van or the workshop: RJ45 plugs, patch cords, SFPs… */
data class StockItem(
    val id: String,
    val name: String,
    /** "pcs", "m", "boîte"… */
    val unit: String,
    val qty: Int,
    /** Alert when the quantity drops to this level or below (0 = no alert). */
    val threshold: Int,
    val location: String,
    val notes: String,
    val updated: Long,
)

/** Consumables stock in kit_stock.json; interventions take from it through [Logbook.consume]. */
object Stock {
    val items = mutableStateListOf<StockItem>()
    val Units = listOf("pcs", "m", "boîte", "rouleau")
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "kit_stock.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val a = JSONObject(f.readText()).optJSONArray("stock") ?: return
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                items += StockItem(
                    o.getString("id"), o.optString("name"), o.optString("unit", Units[0]), o.optInt("qty"),
                    o.optInt("threshold"), o.optString("location"), o.optString("notes"), o.optLong("updated"),
                )
            }
        }
    }

    private fun save(context: Context) {
        val root = JSONObject().put("stock", JSONArray(items.map { s ->
            JSONObject().put("id", s.id).put("name", s.name).put("unit", s.unit).put("qty", s.qty)
                .put("threshold", s.threshold).put("location", s.location).put("notes", s.notes).put("updated", s.updated)
        }))
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }
    }

    fun get(id: String) = items.firstOrNull { it.id == id }

    fun isLow(s: StockItem) = s.threshold > 0 && s.qty <= s.threshold

    fun low(): List<StockItem> = items.filter(::isLow)

    fun upsert(context: Context, item: StockItem) {
        val s = item.copy(name = item.name.trim(), updated = System.currentTimeMillis())
        val k = items.indexOfFirst { it.id == s.id }
        if (k >= 0) items[k] = s else items += s
        items.sortBy { it.name.lowercase() }
        save(context)
    }

    fun delete(context: Context, id: String) {
        items.removeAll { it.id == id }
        save(context)
    }

    /** Adds [delta] (negative to take out). The quantity never goes below zero. */
    fun adjust(context: Context, id: String, delta: Int) {
        val k = items.indexOfFirst { it.id == id }
        if (k < 0) return
        val s = items[k]
        items[k] = s.copy(qty = (s.qty + delta).coerceAtLeast(0), updated = System.currentTimeMillis())
        save(context)
    }

    fun exportCsv(context: Context, uri: Uri): Boolean = runCatching {
        fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val text = buildString {
            appendLine("Article;Quantité;Unité;Seuil;Emplacement;Notes")
            items.forEach { s ->
                appendLine(listOf(cell(s.name), s.qty.toString(), cell(s.unit), s.threshold.toString(), cell(s.location), cell(s.notes)).joinToString(";"))
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(("﻿" + text).toByteArray()) } ?: error("no stream")
    }.isSuccess
}
