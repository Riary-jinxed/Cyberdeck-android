package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Her brain: a model served by Ollama on this PC (http://localhost:11434 by default). */
class Ollama(private val baseUrl: () -> String) {

    /** Ollama is not running, or not at that address. */
    class Unreachable(cause: Throwable) : Exception(cause.message, cause)

    class Failed(message: String) : Exception(message)

    data class Message(val role: String, val content: String)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private fun uri(path: String) = URI.create(baseUrl().trimEnd('/') + path)

    /** The models installed in Ollama. */
    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val response = try {
            http.send(HttpRequest.newBuilder(uri("/api/tags")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw Unreachable(e)
        }
        if (response.statusCode() != 200) throw Failed("Ollama a répondu ${response.statusCode()}")
        parseModels(response.body())
    }

    /** Streams the answer of [model], piece by piece. */
    fun chat(model: String, messages: List<Message>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            // Kept loaded ten minutes after the last answer: the next one comes without waiting.
            put("keep_alive", "10m")
            putJsonObject("options") {
                put("temperature", 0.8)
                put("num_ctx", 4096)
            }
            putJsonArray("messages") {
                messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
        }.toString()
        val request = HttpRequest.newBuilder(uri("/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofLines())
        } catch (e: Exception) {
            throw Unreachable(e)
        }
        response.body().use { lines ->
            if (response.statusCode() != 200) {
                throw Failed(lines.toList().joinToString(" ").let { errorOf(it) ?: "Ollama a répondu ${response.statusCode()}" })
            }
            for (line in lines.iterator()) {
                currentCoroutineContext().ensureActive()
                val chunk = parseChunk(line) ?: continue
                chunk.error?.let { throw Failed(it) }
                if (chunk.text.isNotEmpty()) emit(chunk.text)
                if (chunk.done) break
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Downloads [model] into Ollama, reporting its progress. */
    fun pull(model: String): Flow<Progress> = flow {
        val body = buildJsonObject { put("model", model); put("stream", true) }.toString()
        val request = HttpRequest.newBuilder(uri("/api/pull"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofLines())
        } catch (e: Exception) {
            throw Unreachable(e)
        }
        response.body().use { lines ->
            for (line in lines.iterator()) {
                currentCoroutineContext().ensureActive()
                val p = parseProgress(line) ?: continue
                p.error?.let { throw Failed(it) }
                emit(p)
            }
        }
        if (response.statusCode() != 200) throw Failed("Ollama a répondu ${response.statusCode()}")
    }.flowOn(Dispatchers.IO)

    data class Chunk(val text: String, val done: Boolean, val error: String? = null)

    data class Progress(val status: String, val completed: Long, val total: Long, val error: String? = null) {
        val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseModels(body: String): List<String> = runCatching {
            json.parseToJsonElement(body).jsonObject["models"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull().orEmpty()

        /** One line of Ollama's stream: {"message":{"content":"…"},"done":false}. */
        fun parseChunk(line: String): Chunk? {
            if (line.isBlank()) return null
            val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
            o.error()?.let { return Chunk("", true, it) }
            val text = o["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val done = o["done"]?.jsonPrimitive?.contentOrNull == "true"
            return Chunk(text, done)
        }

        /** One line of a pull: {"status":"pulling 4c27…","total":4300000000,"completed":120000000}. */
        fun parseProgress(line: String): Progress? {
            if (line.isBlank()) return null
            val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
            o.error()?.let { return Progress("", 0, 0, it) }
            fun l(key: String) = o[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            return Progress(o["status"]?.jsonPrimitive?.contentOrNull.orEmpty(), l("completed"), l("total"))
        }

        private fun JsonObject.error() = this["error"]?.jsonPrimitive?.contentOrNull

        private fun errorOf(body: String) = runCatching { json.parseToJsonElement(body).jsonObject.error() }.getOrNull()

        /** The model to use: the one chosen, else an installed Gemma, else whatever is there. */
        fun pick(chosen: String, installed: List<String>): String? = when {
            chosen.isNotBlank() -> chosen
            else -> installed.firstOrNull { it.contains("gemma", ignoreCase = true) } ?: installed.firstOrNull()
        }
    }
}
