package dev.netrunner.decklink

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * As//ka on the same PC edits the kit through here, so her edits join the outbox Deck Link
 * holds and reach the phone like any other. Loopback only, behind a token kept in
 * config/aska.token: only programs of this user, who can read that file, get in.
 */
object AskaLink {
    const val PORT = 47323
    private const val MAX_BODY = 16 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The fields each kind of item may get from her (the same ones Deck Link's screens edit). */
    val FIELDS = mapOf(
        "equipment" to setOf("code", "name", "serial", "location", "state", "notes"),
        "stock" to setOf("name", "unit", "qty", "threshold", "location", "notes"),
        "cable" to setOf("outlet", "switch", "port", "vlan", "length", "poe", "test", "site", "notes"),
        "client" to setOf("name", "contact", "phone", "email", "address", "access", "instructions", "notes"),
    )
    private val NUMBERS = setOf("qty", "threshold")

    val tokenFile: File get() = File(Paths.config, "aska.token")

    private fun token(): String = tokenFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 64 } ?: run {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }.also { tokenFile.writeText(it) }
    }

    /** An edit she sent, checked; null when it is not one Deck Link accepts. */
    fun parse(body: String): Triple<String, String, Map<String, Any>>? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val allowed = FIELDS[kind] ?: return null
        val fields = (o["fields"] as? JsonObject ?: return null).mapNotNull { (k, v) ->
            if (k !in allowed) return null
            val text = (v as? JsonPrimitive)?.contentOrNull ?: return null
            k to (if (k in NUMBERS) text.toLongOrNull() ?: return null else text.take(2000))
        }.toMap()
        if (fields.isEmpty()) null else Triple(kind, id, fields)
    }.getOrNull()

    /** Starts the endpoint; [edit] saves an accepted edit. Returns a stop function. */
    fun serve(edit: (String, String, Map<String, Any>) -> Unit, log: (String) -> Unit): () -> Unit {
        val secret = token().toByteArray()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 4)
        server.createContext("/aska/edit") { ex ->
            val status = runCatching {
                if (ex.requestMethod != "POST") return@runCatching 405
                val given = ex.requestHeaders.getFirst("X-Aska-Token")?.toByteArray() ?: return@runCatching 401
                if (!MessageDigest.isEqual(given, secret)) return@runCatching 401
                val body = ex.requestBody.use { it.readNBytes(MAX_BODY + 1) }
                if (body.size > MAX_BODY) return@runCatching 413
                val (kind, id, fields) = parse(String(body, Charsets.UTF_8)) ?: return@runCatching 400
                edit(kind, id, fields)
                log("As//ka : $kind modifié (${fields.keys.joinToString(", ")})")
                204
            }.getOrElse { 500 }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "aska-link").apply { isDaemon = true } }
        server.start()
        return { server.stop(0) }
    }
}
