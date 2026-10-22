package dev.netrunner.osint.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The case being worked on: a name and dated entries, either saved results or free notes. */
data class Case(val name: String, val entries: List<Entry>) {
    data class Entry(val id: Long, val time: Long, val text: String, val report: Report? = null) {
        val body get() = report?.asText() ?: text
    }

    /** The whole case as plain text, oldest entry first. */
    fun asText(): String = buildString {
        append("ENQUÊTE : ${name.ifBlank { "sans nom" }}\n")
        append("Exporté le ${stamp(System.currentTimeMillis())}\n")
        entries.sortedBy { it.time }.forEach { e ->
            append("\n— ${stamp(e.time)}\n")
            append(e.body).append('\n')
        }
    }.trimEnd()

    companion object {
        fun stamp(t: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(t))
    }
}

object Casebook {
    private fun prefs(context: Context) = context.getSharedPreferences("osint", Context.MODE_PRIVATE)

    fun load(context: Context): Case {
        val p = prefs(context)
        val entries = runCatching { JSONArray(p.getString("entries", "[]")) }.getOrDefault(JSONArray()).objects().map {
            Case.Entry(it.optLong("id"), it.optLong("time"), it.optString("text"), it.optJSONObject("report")?.let { r -> Report.fromJson(r) })
        }
        return Case(p.getString("name", "").orEmpty(), entries)
    }

    fun save(context: Context, case: Case) {
        val entries = JSONArray(case.entries.map { e ->
            JSONObject().put("id", e.id).put("time", e.time).put("text", e.text).apply { e.report?.let { put("report", it.toJson()) } }
        })
        prefs(context).edit().putString("name", case.name).putString("entries", entries.toString()).apply()
    }

    // ---- Export to the deck's Notes app ----

    private val NOTES = Uri.parse("content://dev.netrunner.notes.provider/notes")

    /** The new note's uri, or null when Notes is missing or refused. */
    fun toNotes(context: Context, case: Case): Uri? = runCatching {
        val values = ContentValues().apply {
            put("title", "Enquête · ${case.name.ifBlank { Case.stamp(System.currentTimeMillis()) }}")
            put("content", case.asText())
            put("source", "osint")
        }
        context.contentResolver.insert(NOTES, values)
    }.getOrNull()

    fun openNote(context: Context, note: Uri) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, note).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Whether the deck's Notes app is there to receive an export. */
    fun hasNotes(context: Context) = runCatching { context.packageManager.getPackageInfo("dev.netrunner.notes", 0) }.isSuccess
}
