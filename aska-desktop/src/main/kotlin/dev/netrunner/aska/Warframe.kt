package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Warframe's world state (open-world cycles, Baro Ki'Teer, sortie, archon hunt, fissures,
 * Nightwave), from the community API warframestat.us (free, no key), for the runner who plays.
 */
object Warframe {
    private const val URL = "https://api.warframestat.us/pc/?language=fr"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** The game's process, as the foreground watch sees it. */
    fun isRunning(process: String?) = process?.lowercase()?.startsWith("warframe") == true

    data class Cycle(val place: String, val state: String, val ends: Instant)

    data class World(
        val cycles: List<Cycle>,
        val baroActive: Boolean,
        val baro: String?,
        val sortie: String?,
        val archon: String?,
        val fissures: List<String>,
        val nightwave: List<String>,
        val steelPath: String?,
    ) {
        /** Plains of Eidolon: night is the Eidolon hunt. */
        val cetus get() = cycles.firstOrNull { it.place == "Plaines d'Eidolon" }

        fun text(now: Instant = Instant.now()): String = buildString {
            appendLine("Cycles : " + cycles.joinToString(" · ") { "${it.place} ${it.state} (encore ${left(it.ends, now)})" })
            baro?.let { appendLine(it) }
            sortie?.let { appendLine("Sortie : $it") }
            archon?.let { appendLine("Chasse à l'archonte : $it") }
            steelPath?.let { appendLine("Steel Path : $it") }
            if (fissures.isNotEmpty()) appendLine("Fissures : " + fissures.joinToString(" ; "))
            if (nightwave.isNotEmpty()) appendLine("Nightwave : " + nightwave.joinToString(" ; "))
        }.trim()
    }

    suspend fun world(): World? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI(URL)).timeout(Duration.ofSeconds(12)).header("User-Agent", "Aska").build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) null else parse(response.body())
        }.getOrNull()
    }

    fun parse(body: String, now: Instant = Instant.now()): World {
        val o = json.parseToJsonElement(body).jsonObject
        val cycles = listOfNotNull(
            o.obj("cetusCycle")?.let { c -> cycle("Plaines d'Eidolon", if (c.bool("isDay") == true) "jour" else "nuit", c) },
            o.obj("vallisCycle")?.let { c -> cycle("Vallée Orbis", if (c.bool("isWarm") == true) "chaud" else "froid", c) },
            o.obj("cambionCycle")?.let { c -> cycle("Désert de Cambion", c.s("state").ifBlank { c.s("active") }, c) },
            o.obj("zarimanCycle")?.let { c -> cycle("Zariman", if (c.bool("isCorpus") == true) "Corpus" else "Grineer", c) },
        )
        val trader = o.obj("voidTrader")
        val baroActive = trader?.let { it.bool("active") == true || time(it.s("activation"))?.isBefore(now) == true && time(it.s("expiry"))?.isAfter(now) == true } == true
        val baro = trader?.let { t ->
            val place = t.s("location")
            if (baroActive) {
                val items = t.arr("inventory").mapNotNull { (it as? JsonObject)?.s("item")?.takeIf { s -> s.isNotBlank() } }
                "Baro Ki'Teer est là ($place, encore ${time(t.s("expiry"))?.let { left(it, now) } ?: "?"})" +
                    if (items.isEmpty()) "" else " : " + items.take(12).joinToString(", ")
            } else time(t.s("activation"))?.let { "Baro Ki'Teer arrive dans ${left(it, now)} ($place)" }
        }
        val sortie = o.obj("sortie")?.let { s ->
            val missions = s.arr("variants").mapNotNull { v -> (v as? JsonObject)?.let { "${it.s("missionType")} (${it.s("modifier")}, ${it.s("node")})" } }
            if (missions.isEmpty()) null else "${s.s("boss")}, ${s.s("faction")} : " + missions.joinToString(" ; ")
        }
        val archon = o.obj("archonHunt")?.let { a ->
            val missions = a.arr("missions").mapNotNull { m -> (m as? JsonObject)?.let { "${it.s("type")} (${it.s("node")})" } }
            if (missions.isEmpty()) null else "${a.s("boss")} : " + missions.joinToString(", ")
        }
        val fissures = o.arr("fissures").mapNotNull { it as? JsonObject }
            .filter { f -> f.bool("active") != false && (time(f.s("expiry"))?.isAfter(now) ?: true) }
            .sortedBy { f -> TIERS.indexOf(f.s("tier")).let { i -> if (i < 0) 99 else i } }
            .map { f ->
                listOf(f.s("tier"), f.s("missionType"), f.s("node")).filter { it.isNotBlank() }.joinToString(" ") +
                    (if (f.bool("isHard") == true) " [Steel Path]" else "") + (if (f.bool("isStorm") == true) " [Railjack]" else "")
            }.take(12)
        val nightwave = o.obj("nightwave")?.arr("activeChallenges").orEmpty().mapNotNull { it as? JsonObject }
            .map { c -> c.s("title") + (c.s("desc").takeIf { it.isNotBlank() }?.let { " : $it" } ?: "") + (c.s("reputation").takeIf { it.isNotBlank() }?.let { " ($it réputation)" } ?: "") }
            .take(10)
        val steel = o.obj("steelPath")?.obj("currentReward")?.let { r -> "${r.s("name")} pour ${r.s("cost")} essences d'acier".takeIf { r.s("name").isNotBlank() } }
        return World(cycles, baroActive, baro, sortie, archon, fissures, nightwave, steel)
    }

    private val TIERS = listOf("Lith", "Meso", "Neo", "Axi", "Requiem", "Omnia")

    /** "1 h 05", "12 min". */
    fun left(until: Instant, now: Instant = Instant.now()): String {
        val minutes = Duration.between(now, until).toMinutes().coerceAtLeast(0)
        return when {
            minutes >= 24 * 60 -> "${minutes / (24 * 60)} j ${minutes % (24 * 60) / 60} h"
            minutes >= 60 -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
            else -> "$minutes min"
        }
    }

    private fun cycle(place: String, state: String, c: JsonObject) = time(c.s("expiry"))?.let { Cycle(place, state, it) }

    private fun time(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.s(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.bool(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
}
