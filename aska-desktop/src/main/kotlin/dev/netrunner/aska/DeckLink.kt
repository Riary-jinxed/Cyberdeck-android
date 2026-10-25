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
}
