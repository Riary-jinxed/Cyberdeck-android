package dev.netrunner.launcher.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A timestamped line of the intervention journal ("switch remplacé", "client prévenu"…). */
data class LogEntry(val at: Long, val text: String)

/** One visit on a site: who, when, what was done, with photos and the checklists run there. */
data class Intervention(
    val id: String,
    val title: String,
    val site: String,
    val client: String,
    val ticket: String,
    val started: Long,
    /** 0 while the intervention is open. */
    val ended: Long,
    val notes: String,
    val journal: List<LogEntry>,
    /** File names in the intervention's private photo folder. */
    val photos: List<String>,
    /** Checklist runs (KitStore.runs) done during this intervention. */
    val runIds: List<String>,
) {
    val open get() = ended == 0L
}

/**
 * Intervention logbook, kept in kit_log.json with the photos copied into the app's private
 * storage, so they stay with the sheet even if the gallery is cleaned. Nothing leaves the phone
 * except the PDF report or a note the user creates.
 */
object Logbook {
    const val MaxPhotos = 30

    val items = mutableStateListOf<Intervention>()
    /** The intervention shown in focus mode, if any. */
    var focusId by mutableStateOf<String?>(null)
        private set
    /** Bumped each time focus is (re)started, so the kit shows the focus screen again. */
    var focusRequest by mutableStateOf(0)
        private set
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "kit_log.json")

    fun dir(context: Context, id: String) = File(File(context.filesDir, "logbook"), id)

    fun photo(context: Context, id: String, name: String) = File(dir(context, id), name)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            root.optJSONArray("interventions")?.let { a ->
                for (k in 0 until a.length()) {
                    val o = a.getJSONObject(k)
                    items += Intervention(
                        o.getString("id"), o.optString("title"), o.optString("site"), o.optString("client"),
                        o.optString("ticket"), o.optLong("started"), o.optLong("ended"), o.optString("notes"),
                        o.optJSONArray("journal")?.let { j ->
                            (0 until j.length()).map { j.getJSONObject(it) }.map { LogEntry(it.optLong("at"), it.optString("text")) }
                        }.orEmpty(),
                        o.optJSONArray("photos").strings(),
                        o.optJSONArray("runs").strings(),
                    )
                }
            }
            focusId = root.optString("focus").takeIf { id -> id.isNotEmpty() && items.any { it.id == id && it.open } }
        }
    }

    private fun save(context: Context) {
        val root = JSONObject()
            .put("interventions", JSONArray(items.map { i ->
                JSONObject().put("id", i.id).put("title", i.title).put("site", i.site).put("client", i.client)
                    .put("ticket", i.ticket).put("started", i.started).put("ended", i.ended).put("notes", i.notes)
                    .put("journal", JSONArray(i.journal.map { JSONObject().put("at", it.at).put("text", it.text) }))
                    .put("photos", JSONArray(i.photos))
                    .put("runs", JSONArray(i.runIds))
            }))
            .put("focus", focusId.orEmpty())
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }
    }

    fun get(id: String?) = items.firstOrNull { it.id == id }

    /** Sites already known anywhere in the kit, to pick from instead of retyping. */
    fun knownSites(): List<String> =
        (items.map { it.site } + KitStore.cables.map { it.site } + KitStore.runs.map { it.site } + NetPlan.sites())
            .map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.sorted()

    fun clientFor(site: String): String =
        items.firstOrNull { it.site.equals(site.trim(), ignoreCase = true) && it.client.isNotBlank() }?.client.orEmpty()

    fun create(context: Context, title: String, site: String, client: String, ticket: String): Intervention {
        val i = Intervention(
            KitStore.newId(), title.trim(), site.trim(), client.trim(), ticket.trim(),
            System.currentTimeMillis(), 0L, "", emptyList(), emptyList(), emptyList(),
        )
        items.add(0, i)
        save(context)
        return i
    }

    fun update(context: Context, intervention: Intervention) {
        val k = items.indexOfFirst { it.id == intervention.id }
        if (k < 0) return
        items[k] = intervention
        save(context)
    }

    private fun edit(context: Context, id: String, change: (Intervention) -> Intervention) {
        get(id)?.let { update(context, change(it)) }
    }

    fun addEntry(context: Context, id: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        edit(context, id) { it.copy(journal = it.journal + LogEntry(System.currentTimeMillis(), t)) }
    }

    fun removeEntry(context: Context, id: String, entry: LogEntry) =
        edit(context, id) { it.copy(journal = it.journal - entry) }

    fun close(context: Context, id: String) {
        if (focusId == id) focusId = null
        edit(context, id) { it.copy(ended = System.currentTimeMillis()) }
    }

    fun reopen(context: Context, id: String) = edit(context, id) { it.copy(ended = 0L) }

    fun delete(context: Context, id: String) {
        if (focusId == id) focusId = null
        items.removeAll { it.id == id }
        dir(context, id).deleteRecursively()
        save(context)
    }

    fun setFocus(context: Context, id: String?) {
        focusId = id
        focusRequest++
        save(context)
    }

    /** Starts a checklist on the intervention's site and links it. */
    fun startChecklist(context: Context, id: String, template: ChecklistTemplate): ChecklistRun? {
        val i = get(id) ?: return null
        val run = KitStore.start(context, template, i.site)
        update(context, i.copy(runIds = i.runIds + run.id))
        return run
    }

    fun runsOf(i: Intervention): List<ChecklistRun> = i.runIds.mapNotNull { r -> KitStore.runs.firstOrNull { it.id == r } }

    /** Copies a picked image, downscaled to 1600 px and re-encoded, into the intervention folder. */
    fun importPhoto(context: Context, id: String, uri: Uri): Boolean {
        val i = get(id) ?: return false
        if (i.photos.size >= MaxPhotos) return false
        val bitmap = decode(context, uri, 1600) ?: return false
        val name = "p" + KitStore.newId() + ".jpg"
        val out = photo(context, id, name)
        out.parentFile?.mkdirs()
        val ok = runCatching { out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }.getOrDefault(false)
        bitmap.recycle()
        if (!ok) return false
        update(context, i.copy(photos = i.photos + name))
        return true
    }

    /** A file for the camera app to write into; [commitCamera] shrinks it and adds it once taken. */
    fun cameraTarget(context: Context, id: String): Pair<File, Uri> {
        val f = File(dir(context, id), "camera.tmp.jpg")
        f.parentFile?.mkdirs()
        return f to FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun commitCamera(context: Context, id: String, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val ok = importPhoto(context, id, Uri.fromFile(file))
        file.delete()
        return ok
    }

    fun removePhoto(context: Context, id: String, name: String) {
        photo(context, id, name).delete()
        edit(context, id) { it.copy(photos = it.photos - name) }
    }

    /** Decodes [uri] with its long side brought near [maxSide], honouring the EXIF rotation. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        fun open() = if (uri.scheme == "file") File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = open()?.use {
            when (android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val long = maxOf(raw.width, raw.height)
        val scale = if (long > maxSide) maxSide.toFloat() / long else 1f
        if (rotation == 0f && scale == 1f) return raw
        val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }.getOrNull()

    /** Text for a note or the clipboard: header, journal and notes. */
    fun summary(i: Intervention, format: (Long) -> String): String = buildString {
        appendLine(i.title.ifBlank { "Intervention" })
        if (i.site.isNotBlank()) appendLine("Site : ${i.site}")
        if (i.client.isNotBlank()) appendLine("Client : ${i.client}")
        if (i.ticket.isNotBlank()) appendLine("Ticket : ${i.ticket}")
        appendLine("Début : ${format(i.started)}" + if (!i.open) " · fin : ${format(i.ended)}" else " · en cours")
        if (i.journal.isNotEmpty()) {
            appendLine()
            appendLine("Journal :")
            i.journal.forEach { appendLine("${format(it.at)}  ${it.text}") }
        }
        runsOf(i).forEach { r -> appendLine("Checklist ${r.name} : ${r.done.size}/${r.steps.size}") }
        if (i.notes.isNotBlank()) {
            appendLine()
            appendLine(i.notes)
        }
    }.trimEnd()

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}
