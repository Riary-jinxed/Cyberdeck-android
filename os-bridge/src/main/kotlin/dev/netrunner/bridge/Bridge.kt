package dev.netrunner.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The As//ka bridge: how Netrunner OS's apps let As//ka see what is on screen and act in them.
 *
 * Each open app listens on a Unix socket in [dir] (this user's only, mode 700), one per window.
 * A request is one JSON line, the answer one JSON line:
 * - `{"op":"describe"}` → `{"app":"photos","title":"Netrunner Photos","context":"…","tools":[…]}`:
 *   what the user is looking at, in words, and the actions As//ka may take there.
 * - `{"op":"run","tool":"photos_next","args":{…}}` → `{"ok":true,"result":"…"}`.
 * Tools are named `<app>_<action>` so they never clash with As//ka's own. Shared, as source, by
 * the apps (os-apps/) and As//ka (aska-desktop/).
 */
object Bridge {
    data class Param(val name: String, val description: String, val type: String = "string", val required: Boolean = true)

    /** [confirm]: As//ka must get the user's « oui » before running it (deleting, sending…). */
    data class Tool(val name: String, val description: String, val params: List<Param> = emptyList(), val confirm: Boolean = false)

    /** What an app shows As//ka. Called from the bridge's thread: [run] must hop to the UI thread itself. */
    interface App {
        val id: String
        val title: String
        fun context(): String
        fun tools(): List<Tool>
        fun run(tool: String, args: Map<String, String>): String
    }

    class Description(val app: String, val title: String, val context: String, val tools: List<Tool>, val socket: File)

    private val json = Json { ignoreUnknownKeys = true }

    /** $XDG_RUNTIME_DIR/netrunner-bridge, else /tmp/netrunner-bridge-<user>. */
    fun dir(env: Map<String, String> = System.getenv()): File {
        val runtime = env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() && File(it).isDirectory }
        return if (runtime != null) File(runtime, "netrunner-bridge") else File(System.getProperty("java.io.tmpdir"), "netrunner-bridge-" + System.getProperty("user.name"))
    }

    private fun ensureDir(dir: File) {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    /** Starts [app]'s socket on a daemon thread. Returns a stop function (also run at exit). */
    fun serve(app: App, dir: File = dir()): () -> Unit {
        ensureDir(dir)
        val file = File(dir, "${app.id}-${ProcessHandle.current().pid()}.sock")
        file.delete()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(file.toPath()))
        val stop = {
            runCatching { server.close() }
            file.delete()
            Unit
        }
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        Thread({
            while (server.isOpen) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ client.use { answer(app, it) } }, "bridge-client").apply { isDaemon = true }.start()
            }
        }, "bridge-${app.id}").apply { isDaemon = true }.start()
        return stop
    }

    private fun answer(app: App, channel: SocketChannel) {
        val reader = Channels.newInputStream(channel).bufferedReader(Charsets.UTF_8)
        val line = reader.readLine() ?: return
        val reply = runCatching { handle(app, json.parseToJsonElement(line).jsonObject) }
            .getOrElse { e -> buildJsonObject { put("ok", false); put("result", "Erreur : ${e.message ?: e.javaClass.simpleName}") } }
        channel.write(ByteBuffer.wrap((reply.toString() + "\n").toByteArray(Charsets.UTF_8)))
    }

    /** One request to [app], as a JSON answer (also what the tests drive). */
    fun handle(app: App, request: JsonObject): JsonObject = when (request["op"]?.jsonPrimitive?.contentOrNull) {
        "describe" -> buildJsonObject {
            put("app", app.id)
            put("title", app.title)
            put("context", app.context())
            put("tools", encode(app.tools()))
        }
        "run" -> {
            val name = request["tool"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val args = (request["args"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
            if (app.tools().none { it.name == name }) {
                buildJsonObject { put("ok", false); put("result", "Outil inconnu dans ${app.title} : $name") }
            } else {
                buildJsonObject { put("ok", true); put("result", app.run(name, args)) }
            }
        }
        else -> buildJsonObject { put("ok", false); put("result", "Requête inconnue") }
    }

    fun encode(tools: List<Tool>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(
                buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("confirm", t.confirm)
                    putJsonArray("params") {
                        t.params.forEach { p ->
                            add(buildJsonObject { put("name", p.name); put("description", p.description); put("type", p.type); put("required", p.required) })
                        }
                    }
                },
            )
        }
    }

    fun decode(tools: JsonArray): List<Tool> = tools.map { e ->
        val o = e.jsonObject
        fun s(k: String, from: JsonObject = o) = from[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        Tool(
            s("name"), s("description"),
            (o["params"] as? JsonArray).orEmpty().map { p ->
                val po = p.jsonObject
                Param(s("name", po), s("description", po), s("type", po).ifEmpty { "string" }, po["required"]?.jsonPrimitive?.booleanOrNull ?: true)
            },
            o["confirm"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    // ---- As//ka's side.

    private fun call(socket: File, request: JsonObject, timeoutMs: Long): JsonObject? = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socket.toPath()))
            ch.write(ByteBuffer.wrap((request.toString() + "\n").toByteArray(Charsets.UTF_8)))
            // A frozen app must not freeze As//ka: the read gives up after timeoutMs.
            val reader = Channels.newInputStream(ch).bufferedReader(Charsets.UTF_8)
            val result = java.util.concurrent.CompletableFuture.supplyAsync { reader.readLine() }
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
            json.parseToJsonElement(result).jsonObject
        }
    }.getOrNull()

    /** The apps open right now (their stale sockets are removed). */
    fun discover(dir: File = dir(), timeoutMs: Long = 1500): List<Description> =
        dir.listFiles { f -> f.name.endsWith(".sock") }.orEmpty().sortedBy { it.name }.mapNotNull { socket ->
            val o = call(socket, buildJsonObject { put("op", "describe") }, timeoutMs)
            if (o == null) {
                // The app is gone (crashed without cleaning up): forget its socket.
                val pid = socket.name.substringAfterLast('-').removeSuffix(".sock").toLongOrNull()
                if (pid == null || ProcessHandle.of(pid).isEmpty) socket.delete()
                return@mapNotNull null
            }
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            Description(s("app"), s("title"), s("context"), (o["tools"] as? JsonArray)?.let(::decode).orEmpty(), socket)
        }

    /** Runs [tool] in the app behind [socket]; the answer is text, errors included. */
    fun run(socket: File, tool: String, args: Map<String, String>, timeoutMs: Long = 15_000): String {
        val o = call(
            socket,
            buildJsonObject {
                put("op", "run")
                put("tool", tool)
                putJsonObject("args") { args.forEach { (k, v) -> put(k, v) } }
            },
            timeoutMs,
        ) ?: return "L'appli ne répond pas."
        return o["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
