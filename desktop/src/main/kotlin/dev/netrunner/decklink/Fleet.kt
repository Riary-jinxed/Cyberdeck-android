package dev.netrunner.decklink

import com.sun.net.httpserver.HttpServer
import dev.netrunner.sync.AgentAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The fleet: PCs running Deck Probe (desktop/src/main/resources/agent) post their inventory to
 * `http://<this PC>:47322/deck-probe/report`, signed with the fleet key. Each report is kept in
 * sync/parc/<name>.json and turned into an inventory sheet, which reaches the phone like any
 * other edit made on the PC.
 */
object Fleet {
    const val PORT = 47322
    // The installed software list makes reports far bigger than the rest.
    private const val MAX_BODY = 2 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** [freeGB] is -1 when the agent is older than this field. */
    data class Disk(val name: String, val sizeGB: Long, val freeGB: Long = -1)
    data class Nic(val name: String, val mac: String, val ipv4: String)
    data class Software(val name: String, val version: String, val publisher: String)
    /**
     * Windows Update as the agent saw it: [lastInstalled] is the newest installed update (0 if
     * unknown), [pending] the updates waiting (-1 when the search failed or was not run).
     */
    data class Updates(val lastInstalled: Long = 0, val pending: Int = -1, val rebootRequired: Boolean = false)
    data class Report(
        val hostname: String, val domain: String, val user: String, val manufacturer: String, val model: String, val serial: String,
        val os: String, val osBuild: String, val cpu: String, val ramGB: Long, val disks: List<Disk>, val network: List<Nic>, val seen: Long,
        val software: List<Software> = emptyList(),
        val updates: Updates = Updates(),
    )

    /** Starts the agents' endpoint; [onReport] gets each valid report. Returns a stop function. */
    fun serve(key: () -> String, onReport: (Report) -> Unit, log: (String) -> Unit): () -> Unit {
        val server = HttpServer.create(InetSocketAddress(PORT), 16)
        var auth = AgentAuth(AgentAuth.keyBytes(key())!!)
        var authKey = key()
        server.createContext("/deck-probe/report") { ex ->
            val status = runCatching {
                if (ex.requestMethod != "POST") return@runCatching 405
                val body = ex.requestBody.use { it.readNBytes(MAX_BODY + 1) }
                if (body.size > MAX_BODY) return@runCatching 413
                val time = ex.requestHeaders.getFirst("X-DeckProbe-Time")?.toLongOrNull() ?: return@runCatching 401
                val signature = ex.requestHeaders.getFirst("X-DeckProbe-Signature") ?: return@runCatching 401
                synchronized(this) {
                    if (authKey != key()) { authKey = key(); auth = AgentAuth(AgentAuth.keyBytes(authKey)!!) }
                }
                if (!auth.verify(time, body, signature)) {
                    log("rapport refusé de ${ex.remoteAddress.address.hostAddress} : clé du parc différente ou heure décalée")
                    return@runCatching 401
                }
                val report = parse(String(body, Charsets.UTF_8)) ?: return@runCatching 400
                File(Paths.fleet, safeName(report.hostname) + ".json").writeText(
                    json.encodeToString(JsonElement.serializer(), JsonObject(json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject + ("seen" to JsonPrimitive(report.seen)))),
                )
                onReport(report)
                204
            }.getOrElse { 400 }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "deck-probe").apply { isDaemon = true } }
        server.start()
        return { server.stop(0) }
    }

    /** Keeps [r] in sync/parc, like a report received from an agent. */
    fun save(r: Report) {
        val o = JsonObject(
            mapOf(
                "probe" to JsonPrimitive(1), "hostname" to JsonPrimitive(r.hostname), "domain" to JsonPrimitive(r.domain),
                "user" to JsonPrimitive(r.user), "manufacturer" to JsonPrimitive(r.manufacturer), "model" to JsonPrimitive(r.model),
                "serial" to JsonPrimitive(r.serial), "os" to JsonPrimitive(r.os), "osBuild" to JsonPrimitive(r.osBuild),
                "cpu" to JsonPrimitive(r.cpu), "ramGB" to JsonPrimitive(r.ramGB),
                "disks" to JsonArray(r.disks.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "sizeGB" to JsonPrimitive(it.sizeGB), "freeGB" to JsonPrimitive(it.freeGB))) }),
                "network" to JsonArray(r.network.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "mac" to JsonPrimitive(it.mac), "ipv4" to JsonPrimitive(it.ipv4))) }),
                "seen" to JsonPrimitive(r.seen),
                "software" to JsonArray(r.software.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "version" to JsonPrimitive(it.version), "publisher" to JsonPrimitive(it.publisher))) }),
                "updates" to JsonObject(
                    mapOf(
                        "lastInstalled" to JsonPrimitive(r.updates.lastInstalled), "pending" to JsonPrimitive(r.updates.pending),
                        "rebootRequired" to JsonPrimitive(r.updates.rebootRequired),
                    ),
                ),
            ),
        )
        File(Paths.fleet, safeName(r.hostname) + ".json").writeText(json.encodeToString(JsonElement.serializer(), o))
    }

    private fun safeName(host: String) = host.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "pc" }.take(64)

    fun parse(text: String, seen: Long = System.currentTimeMillis()): Report? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        fun s(k: String, from: JsonObject = o) = (from[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun l(k: String, from: JsonObject = o) = (from[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L
        fun objects(k: String) = when (val e = o[k]) {
            is JsonArray -> e.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(e) // PowerShell 5 turns a one-item array into an object
            else -> emptyList()
        }
        Report(
            s("hostname").ifEmpty { return null }, s("domain"), s("user"), s("manufacturer"), s("model"), s("serial"),
            s("os"), s("osBuild"), s("cpu"), l("ramGB"),
            objects("disks").map { Disk(s("name", it), l("sizeGB", it), if (it.containsKey("freeGB")) l("freeGB", it) else -1) },
            objects("network").map { Nic(s("name", it), s("mac", it), s("ipv4", it)) },
            (o["seen"] as? JsonPrimitive)?.longOrNull ?: seen,
            objects("software").map { Software(s("name", it), s("version", it), s("publisher", it)) }
                .filter { it.name.isNotBlank() }.distinctBy { it.name.lowercase() + "|" + it.version }.sortedBy { it.name.lowercase() },
            (o["updates"] as? JsonObject)?.let { u ->
                Updates(
                    l("lastInstalled", u),
                    if (u.containsKey("pending")) l("pending", u).toInt() else -1,
                    (u["rebootRequired"] as? JsonPrimitive)?.contentOrNull == "true",
                )
            } ?: Updates(),
        )
    }.getOrNull()

    /** Every report received so far, newest first. */
    fun reports(): List<Report> = Paths.fleet.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { parse(f.readText(), f.lastModified()) }.getOrNull() }
        .sortedByDescending { it.seen }

    /** Serial numbers firmware leaves as placeholders: useless to tell PCs apart. */
    private val JunkSerials = setOf("", "0", "none", "default string", "to be filled by o.e.m.", "system serial number", "not specified", "not applicable", "123456789")

    fun usableSerial(r: Report) = r.serial.takeIf { it.lowercase() !in JunkSerials && it.length >= 3 }.orEmpty()

    private const val BLOCK_START = "[Deck Probe]"
    private const val BLOCK_END = "[/Deck Probe]"

    fun block(r: Report): String = buildString {
        appendLine(BLOCK_START)
        if (r.manufacturer.isNotBlank() || r.model.isNotBlank()) appendLine("Modèle : ${listOf(r.manufacturer, r.model).filter { it.isNotBlank() }.joinToString(" ")}")
        if (r.os.isNotBlank()) appendLine("OS : ${r.os}" + if (r.osBuild.isNotBlank()) " (build ${r.osBuild})" else "")
        if (r.cpu.isNotBlank()) appendLine("CPU : ${r.cpu}")
        if (r.ramGB > 0) appendLine("RAM : ${r.ramGB} Go")
        if (r.disks.isNotEmpty()) appendLine("Disques : " + r.disks.joinToString(", ") { "${it.name} ${it.sizeGB} Go" + if (it.freeGB >= 0) " (${it.freeGB} libres)" else "" })
        r.network.forEach { appendLine("Réseau : ${it.name} · ${it.mac}" + if (it.ipv4.isNotBlank()) " · ${it.ipv4}" else "") }
        updatesLine(r.updates)?.let { appendLine("Mises à jour : $it") }
        if (r.software.isNotEmpty()) appendLine("Logiciels : ${r.software.size} (liste dans Deck Link)")
        if (r.domain.isNotBlank()) appendLine("Domaine : ${r.domain}")
        if (r.user.isNotBlank()) appendLine("Utilisateur : ${r.user}")
        append(BLOCK_END)
    }

    /** "dernière le 12.10.26, 3 en attente, redémarrage requis", or null when the agent said nothing. */
    private val Day = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy").withZone(java.time.ZoneId.systemDefault())

    fun updatesLine(u: Updates): String? {
        val parts = listOfNotNull(
            if (u.lastInstalled > 0) "dernière le ${Day.format(java.time.Instant.ofEpochMilli(u.lastInstalled))}" else null,
            when {
                u.pending > 0 -> "${u.pending} en attente"
                u.pending == 0 -> "à jour"
                else -> null
            },
            if (u.rebootRequired) "redémarrage requis" else null,
        )
        return parts.joinToString(", ").ifEmpty { null }
    }

    /** [notes] with its Deck Probe block replaced by [block] (the user's own lines stay). */
    fun mergeNotes(notes: String, block: String): String {
        val start = notes.indexOf(BLOCK_START)
        val end = notes.indexOf(BLOCK_END)
        val own = if (start >= 0 && end > start) (notes.substring(0, start) + notes.substring(end + BLOCK_END.length)).trim() else notes.trim()
        return if (own.isEmpty()) block else "$own\n\n$block"
    }

    /**
     * The inventory fields for [r]: the sheet with the same serial number, else the same name,
     * is updated (its name, place and state stay as the user set them); otherwise a new one.
     * Returns null when the sheet already says all this.
     */
    fun toEquipment(r: Report, kit: KitData): Pair<String, Map<String, JsonElement>>? {
        val serial = usableSerial(r)
        val old = kit.equipment.firstOrNull { serial.isNotEmpty() && it.serial.equals(serial, ignoreCase = true) }
            ?: kit.equipment.firstOrNull { it.name.equals(r.hostname, ignoreCase = true) || it.code.equals("PC-${r.hostname}", ignoreCase = true) }
        val fields = linkedMapOf(
            "code" to (old?.code?.takeIf { it.isNotBlank() } ?: serial.ifEmpty { "PC-${r.hostname}" }),
            "name" to (old?.name?.takeIf { it.isNotBlank() } ?: r.hostname),
            "serial" to serial.ifEmpty { old?.serial.orEmpty() },
            "location" to old?.location.orEmpty(),
            "state" to (old?.state?.takeIf { it.isNotBlank() } ?: "En service"),
            "notes" to mergeNotes(old?.notes.orEmpty(), block(r)),
        )
        if (old != null && fields["code"] == old.code && fields["name"] == old.name && fields["serial"] == old.serial && fields["notes"] == old.notes) return null
        val id = old?.id ?: ("pc" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36))
        return id to fields.mapValues { JsonPrimitive(it.value) }
    }

    /** The agents (Windows and Linux), their install / uninstall scripts and the instructions. */
    val PackFiles = listOf(
        "deck-probe.ps1", "install.cmd", "uninstall.cmd",
        "deck-probe.py", "deck-probe.service", "deck-probe.timer", "install-linux.sh", "uninstall-linux.sh",
        "LISEZMOI.txt",
    )

    /**
     * Writes the deployment pack into [Paths.deployment]: the agent, its config (this PC's
     * name and addresses, the fleet key), install / uninstall scripts and the instructions.
     */
    fun writePack(pcName: String, addresses: List<String>, key: String): File {
        val dir = Paths.deployment.apply { mkdirs() }
        fun resource(name: String) = Fleet::class.java.getResourceAsStream("/agent/$name")!!.use { it.readBytes() }
        for (name in PackFiles) File(dir, name).writeBytes(resource(name))
        val servers = (listOf(pcName) + addresses).distinct()
        val config = JsonObject(
            mapOf(
                "servers" to JsonArray(servers.map { JsonPrimitive(it) }),
                "port" to JsonPrimitive(PORT),
                "key" to JsonPrimitive(key),
            ),
        )
        File(dir, "deck-probe.json").writeText(json.encodeToString(JsonElement.serializer(), config))
        return dir
    }
}
