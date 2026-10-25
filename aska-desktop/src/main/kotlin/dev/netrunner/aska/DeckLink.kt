package dev.netrunner.aska

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Properties

/**
 * The deck's kit, as Deck Link keeps it on this PC: she reads what is running low, the jobs
 * still open and when the phone last synced. Read only: changes go through Deck Link.
 */
object DeckLink {

    data class Summary(
        val lastSync: String?,
        val equipment: Int,
        /** "câble RJ45 : 3 m", for the consumables at or under their threshold. */
        val low: List<String>,
        val open: List<String>,
    ) {
        fun note(): String = listOfNotNull(
            lastSync?.let { "dernière synchro du téléphone $it" },
            "$equipment équipements",
            if (low.isEmpty()) "aucun consommable bas" else "consommables bas : " + low.take(5).joinToString(", "),
            if (open.isEmpty()) null else "interventions en cours : " + open.take(3).joinToString(", "),
        ).joinToString(", ")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Deck Link's folder: the one set, $DECKLINK_HOME, its install folder or ~/DeckLink. */
    fun home(chosen: String): File? {
        val home = System.getProperty("user.home")
        val local = System.getenv("LOCALAPPDATA")
        return listOfNotNull(
            chosen.takeIf { it.isNotBlank() },
            System.getenv("DECKLINK_HOME"),
            local?.let { "$it\\Deck Link" },
            local?.let { "$it\\Programs\\Deck Link" },
            "$home/DeckLink",
        ).map(::File).firstOrNull { File(it, "sync").isDirectory || File(it, "config/decklink.properties").isFile }
    }

    fun read(chosen: String): Summary? = runCatching {
        val home = home(chosen) ?: return null
        read(File(home, "sync/kit"), File(home, "config/decklink.properties"))
    }.getOrNull()

    fun read(kit: File, config: File): Summary {
        fun file(name: String): JsonObject? = runCatching { json.parseToJsonElement(File(kit, name).readText()).jsonObject }.getOrNull()
        fun JsonObject?.list(key: String) = this?.get(key)?.let { runCatching { it.jsonArray.map { e -> e.jsonObject } }.getOrNull() }.orEmpty()
        fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun JsonObject.l(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L

        val low = file("kit_stock.json").list("stock").filter { it.l("threshold") > 0 && it.l("qty") <= it.l("threshold") }
            .map { "${it.s("name")} : ${it.l("qty")}${it.s("unit").let { u -> if (u.isBlank()) "" else " $u" }}" }
        val open = file("kit_log.json").list("interventions").filter { it.l("ended") == 0L }
            .map { listOf(it.s("title"), it.s("site")).filter { s -> s.isNotBlank() }.joinToString(" à ") }
        val props = Properties().apply { if (config.isFile) config.inputStream().use { load(it) } }
        return Summary(props.getProperty("last"), file("kit.json").list("equipment").size, low, open)
    }

    // ---- Searching the kit, for her answers.

    private val LABELS = mapOf(
        "equipment" to "équipement", "cables" to "câble", "stock" to "consommable",
        "interventions" to "intervention", "clients" to "client",
    )

    /** Field names that only matter to the sync. */
    private val SKIPPED = setOf("id", "updated", "photos", "at", "started", "ended", "date", "start", "end", "equipment")

    /** Every record of the kit and of the fleet, one line each: "câble · outlet: B12 · switch: SW-2 · port: 14". */
    fun records(chosen: String): List<String> = runCatching {
        val home = home(chosen) ?: return emptyList()
        val kit = File(home, "sync/kit")
        val lines = mutableListOf<String>()
        listOf("kit.json", "kit_stock.json", "kit_log.json", "kit_clients.json").forEach { name ->
            val o = runCatching { json.parseToJsonElement(File(kit, name).readText()).jsonObject }.getOrNull() ?: return@forEach
            o.forEach { (key, value) ->
                val items = runCatching { value.jsonArray }.getOrNull() ?: return@forEach
                items.forEach { item -> runCatching { lines += (LABELS[key] ?: key) + " · " + flatten(item.jsonObject) } }
            }
        }
        fleetFiles(home).forEach { o -> lines += "PC du parc · " + flatten(o) }
        lines
    }.getOrDefault(emptyList())

    private fun flatten(o: JsonObject, depth: Int = 0): String = o.entries.filter { it.key !in SKIPPED }.mapNotNull { (k, v) ->
        when (v) {
            is kotlinx.serialization.json.JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() && it != "0" }?.let { "$k: ${it.take(200)}" }
            is JsonObject -> if (depth < 2) "$k: (" + flatten(v, depth + 1) + ")" else null
            is kotlinx.serialization.json.JsonArray -> if (depth < 2) v.mapNotNull { e ->
                (e as? JsonObject)?.let { flatten(it, depth + 1) } ?: (e as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { "$k: [" + it.joinToString(" ; ") + "]" } else null
        }
    }.joinToString(" · ")

    private fun fleetFiles(home: File): List<JsonObject> = File(home, "sync/parc").listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() }

    /** The lines that hold every word of [query] (accents and case aside), best matches first. */
    fun search(chosen: String, query: String, limit: Int = 15): String {
        val all = records(chosen)
        if (all.isEmpty()) return "Je ne trouve pas le kit de Deck Link sur ce PC."
        val words = normalize(query).split(Regex("[^a-z0-9.:-]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return "Que faut-il chercher dans le kit ?"
        val scored = all.map { line -> line to words.count { normalize(line).contains(it) } }.filter { it.second > 0 }
            .sortedByDescending { it.second }
        val full = scored.filter { it.second == words.size }
        val shown = (full.ifEmpty { scored }).take(limit).map { it.first.take(500) }
        if (shown.isEmpty()) return "Rien dans le kit pour « $query »."
        return (if (full.isEmpty()) "Rien ne contient tous les mots ; les plus proches :\n" else "") + shown.joinToString("\n")
    }

    /** The fleet, one PC a line, for questions on RAM, OS, disks or addresses. */
    fun fleet(chosen: String): String {
        val home = home(chosen) ?: return "Je ne trouve pas Deck Link sur ce PC."
        val pcs = fleetFiles(home)
        if (pcs.isEmpty()) return "Aucun PC dans le parc pour l'instant (Deck Probe n'a rien envoyé)."
        return pcs.joinToString("\n") { flatten(it) }.take(5000)
    }

    /** MAC (AA:BB:…) and IPv4 to PC name, to tell known devices from strangers in a scan. */
    fun fleetAddresses(chosen: String): Map<String, String> {
        val home = home(chosen) ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        fleetFiles(home).forEach { o ->
            val name = o["hostname"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val nics = when (val n = o["network"]) {
                is kotlinx.serialization.json.JsonArray -> n.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(n)
                else -> emptyList()
            }
            nics.forEach { nic ->
                nic["mac"]?.jsonPrimitive?.contentOrNull?.uppercase()?.replace('-', ':')?.takeIf { it.isNotBlank() }?.let { map[it] = name }
                nic["ipv4"]?.jsonPrimitive?.contentOrNull?.split(',', ' ', ';')?.filter { it.isNotBlank() }?.forEach { map[it.trim()] = name }
            }
        }
        return map
    }

    fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
