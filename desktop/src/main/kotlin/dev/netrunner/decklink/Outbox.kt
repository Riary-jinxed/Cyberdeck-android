package dev.netrunner.decklink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.sync.Op
import dev.netrunner.sync.SyncServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID

/**
 * Edits made on the PC, waiting for the phone: each one is
 * `{"op", "kind", "id", "at", "deleted", "item": {fields}}` (read by the launcher's
 * kit/KitRemote.kt). Kept in config/outbox.json so nothing is lost if Deck Link closes before
 * the phone syncs. A second edit of the same item replaces the first.
 */
class Outbox(private val file: File) : SyncServer.Outbox {
    data class Change(val op: String, val kind: String, val id: String, val at: Long, val deleted: Boolean, val item: JsonObject) {
        fun toJson() = JsonObject(
            mapOf(
                "op" to JsonPrimitive(op), "kind" to JsonPrimitive(kind), "id" to JsonPrimitive(id), "at" to JsonPrimitive(at),
                "deleted" to JsonPrimitive(deleted), "item" to item,
            ),
        )
    }

    private val list = ArrayList<Change>()

    /** How many edits wait for the phone, for the screens. */
    var count by mutableStateOf(0)
        private set

    init {
        runCatching {
            (Json.parseToJsonElement(file.readText()) as JsonArray).forEach { e ->
                val o = e.jsonObject
                list += Change(
                    o.str("op"), o.str("kind"), o.str("id"), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0,
                    (o["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false, o["item"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        count = list.size
    }

    @Synchronized
    fun changes(): List<Change> = list.toList()

    /** Records an edit of [kind] item [id]: [fields] are merged over a pending edit of the same item. */
    @Synchronized
    fun put(kind: String, id: String, fields: Map<String, JsonElement>) {
        val old = list.firstOrNull { it.kind == kind && it.id == id && !it.deleted }
        list.removeAll { it.kind == kind && it.id == id }
        val item = JsonObject((old?.item ?: emptyMap()) + fields)
        list += Change(UUID.randomUUID().toString(), kind, id, System.currentTimeMillis(), false, item)
        save()
    }

    @Synchronized
    fun delete(kind: String, id: String) {
        list.removeAll { it.kind == kind && it.id == id }
        list += Change(UUID.randomUUID().toString(), kind, id, System.currentTimeMillis(), true, JsonObject(emptyMap()))
        save()
    }

    @Synchronized
    override fun pending(folder: String): List<Op> =
        if (folder != "kit") emptyList() else list.map { Op(it.op, it.toJson().toString()) }

    @Synchronized
    override fun applied(folder: String, ids: List<String>) {
        if (ids.isEmpty()) return
        list.removeAll { it.op in ids }
        save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JsonArray(list.map { it.toJson() }).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        count = list.size
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

}
