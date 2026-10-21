package dev.netrunner.notes.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** All notes in one JSON file in the app's private storage, written atomically. */
object NoteStore {
    const val AUTHORITY = "dev.netrunner.notes.provider"
    val NOTES_URI: Uri = Uri.parse("content://$AUTHORITY/notes")

    private val lock = Any()
    private var cache: List<Note>? = null

    private fun file(context: Context) = File(context.filesDir, "notes.json")

    fun all(context: Context): List<Note> = synchronized(lock) {
        cache ?: read(context).also { cache = it }
    }

    fun get(context: Context, id: Long): Note? = all(context).firstOrNull { it.id == id }

    /** Adds or replaces [note] (by id) and tells observers, the open app included. */
    fun save(context: Context, note: Note) = update(context) { list -> list.filter { it.id != note.id } + note }

    fun delete(context: Context, id: Long) = update(context) { list -> list.filter { it.id != id } }

    /** A new id: time based, bumped if two notes are made in the same millisecond. */
    fun newId(context: Context): Long {
        val taken = all(context).map { it.id }.toSet()
        var id = System.currentTimeMillis()
        while (id in taken) id++
        return id
    }

    private fun update(context: Context, change: (List<Note>) -> List<Note>) {
        synchronized(lock) {
            val next = change(all(context))
            write(context, next)
            cache = next
        }
        context.contentResolver.notifyChange(NOTES_URI, null)
    }

    private fun read(context: Context): List<Note> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val array = JSONArray(f.readText())
        (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(context: Context, notes: List<Note>) {
        val array = JSONArray()
        notes.forEach { array.put(toJson(it)) }
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(f)) { f.writeText(array.toString()); tmp.delete() }
    }

    private fun toJson(n: Note) = JSONObject()
        .put("id", n.id).put("title", n.title).put("body", n.body).put("checklist", n.checklist)
        .put("pinned", n.pinned).put("color", n.color).put("created", n.created).put("updated", n.updated).put("source", n.source)
        .put("items", JSONArray().apply { n.items.forEach { put(JSONObject().put("t", it.text).put("d", it.done)) } })

    private fun fromJson(o: JSONObject): Note {
        val items = o.optJSONArray("items")
        return Note(
            id = o.getLong("id"),
            title = o.optString("title"),
            body = o.optString("body"),
            items = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it).let { i -> Note.Item(i.optString("t"), i.optBoolean("d")) } },
            checklist = o.optBoolean("checklist"),
            pinned = o.optBoolean("pinned"),
            color = o.optInt("color"),
            created = o.optLong("created"),
            updated = o.optLong("updated"),
            source = o.optString("source"),
        )
    }
}
