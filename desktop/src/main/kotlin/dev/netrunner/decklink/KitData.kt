package dev.netrunner.decklink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * The kit as the phone wrote it (same JSON files as the launcher's kit/ package), read from the
 * PC's copy, with the PC's own edits still waiting for the phone laid over it.
 */
data class KitData(
    val equipment: List<Equipment> = emptyList(),
    val stock: List<StockItem> = emptyList(),
    val interventions: List<Intervention> = emptyList(),
    val clients: List<Client> = emptyList(),
    val cables: List<Cable> = emptyList(),
) {
    data class Equipment(val id: String, val code: String, val name: String, val serial: String, val location: String, val state: String, val notes: String, val updated: Long)
    data class StockItem(val id: String, val name: String, val unit: String, val qty: Long, val threshold: Long, val location: String, val notes: String, val updated: Long) {
        val low get() = threshold > 0 && qty <= threshold
    }
    data class LogEntry(val at: Long, val text: String)
    data class Usage(val name: String, val unit: String, val qty: Long)
    data class Intervention(
        val id: String, val title: String, val site: String, val client: String, val ticket: String,
        val started: Long, val ended: Long, val notes: String, val journal: List<LogEntry>, val photos: List<String>, val consumed: List<Usage>,
    ) {
        val open get() = ended == 0L
    }
    data class Contract(val ref: String, val kind: String, val start: Long, val end: Long, val amount: String)
    data class Quote(val ref: String, val label: String, val date: Long, val amount: String, val state: String)
    data class Client(
        val id: String, val name: String, val contact: String, val phone: String, val email: String, val address: String,
        val access: String, val instructions: String, val notes: String, val contracts: List<Contract>, val quotes: List<Quote>,
    )
    data class Cable(val id: String, val equipmentId: String, val outlet: String, val switchName: String, val port: String, val vlan: String, val length: String, val poe: String, val test: String, val site: String, val notes: String)

    val empty get() = equipment.isEmpty() && stock.isEmpty() && interventions.isEmpty() && clients.isEmpty() && cables.isEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun load(dir: File, pending: List<Outbox.Change> = emptyList()): KitData {
            fun read(name: String): JsonObject? = runCatching { json.parseToJsonElement(File(dir, name).readText()).jsonObject }.getOrNull()
            val kit = read("kit.json")
            fun merged(kind: String, items: List<JsonObject>): List<JsonObject> {
                val out = LinkedHashMap<String, JsonObject>()
                items.forEach { out[it.s("id")] = it }
                pending.filter { it.kind == kind }.forEach { c ->
                    if (c.deleted) out.remove(c.id)
                    else out[c.id] = JsonObject((out[c.id] ?: emptyMap()) + c.item + ("id" to JsonPrimitive(c.id)) + ("updated" to JsonPrimitive(c.at)))
                }
                return out.values.toList()
            }
            return KitData(
                equipment = merged("equipment", kit.list("equipment")).map {
                    Equipment(it.s("id"), it.s("code"), it.s("name"), it.s("serial"), it.s("location"), it.s("state"), it.s("notes"), it.l("updated"))
                }.sortedBy { it.name.lowercase() },
                stock = merged("stock", read("kit_stock.json").list("stock")).map {
                    StockItem(it.s("id"), it.s("name"), it.s("unit"), it.l("qty"), it.l("threshold"), it.s("location"), it.s("notes"), it.l("updated"))
                }.sortedBy { it.name.lowercase() },
                interventions = read("kit_log.json").list("interventions").map { o ->
                    Intervention(
                        o.s("id"), o.s("title"), o.s("site"), o.s("client"), o.s("ticket"), o.l("started"), o.l("ended"), o.s("notes"),
                        o.list("journal").map { LogEntry(it.l("at"), it.s("text")) },
                        o.strings("photos"),
                        o.list("consumed").map { Usage(it.s("name"), it.s("unit"), it.l("qty")) },
                    )
                }.sortedByDescending { it.started },
                clients = merged("client", read("kit_clients.json").list("clients")).map { o ->
                    Client(
                        o.s("id"), o.s("name"), o.s("contact"), o.s("phone"), o.s("email"), o.s("address"), o.s("access"), o.s("instructions"), o.s("notes"),
                        o.list("contracts").map { Contract(it.s("ref"), it.s("kind"), it.l("start"), it.l("end"), it.s("amount")) },
                        o.list("quotes").map { Quote(it.s("ref"), it.s("label"), it.l("date"), it.s("amount"), it.s("state")) },
                    )
                }.sortedBy { it.name.lowercase() },
                cables = merged("cable", kit.list("cables")).map {
                    Cable(it.s("id"), it.s("equipment"), it.s("outlet"), it.s("switch"), it.s("port"), it.s("vlan"), it.s("length"), it.s("poe"), it.s("test"), it.s("site"), it.s("notes"))
                }.sortedWith(compareBy({ it.site.lowercase() }, { it.outlet })),
            )
        }

        private fun JsonObject?.list(key: String): List<JsonObject> =
            (this?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.l(key: String): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    }
}
