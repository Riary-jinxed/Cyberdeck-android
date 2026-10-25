package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Folders she keeps an eye on (Downloads, the LoRA training output…): new files, once they stop growing. */
class FolderWatch {
    /** Files already seen, with their size at the last look. */
    private val seen = HashMap<File, Long>()
    private val growing = HashMap<File, Long>()
    private var primed = false

    /** The files finished since the last call. The first call only takes stock. */
    fun check(folders: List<File>): List<File> {
        val now = folders.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && !partial(it.name) } }
        val done = mutableListOf<File>()
        for (f in now) {
            val size = f.length()
            if (!primed) { seen[f] = size; continue }
            if (f in seen) continue
            // A file still being written grows between two looks.
            if (growing[f] == size && size > 0) {
                growing.remove(f)
                seen[f] = size
                done += f
            } else growing[f] = size
        }
        primed = true
        seen.keys.retainAll(now.toSet())
        return done
    }

    companion object {
        /** Downloads in progress: Chrome, Firefox, Edge, qBittorrent, Steam, aria2… */
        private val PARTIAL = listOf(".crdownload", ".part", ".partial", ".tmp", ".download", ".!qb", ".!ut", ".aria2", ".opdownload")

        fun partial(name: String) = name.lowercase().let { n -> n.startsWith(".") || n.startsWith("~$") || PARTIAL.any { n.endsWith(it) } }

        val downloads: File = File(System.getProperty("user.home"), "Downloads").let { d ->
            if (d.isDirectory) d else File(System.getProperty("user.home"), "Téléchargements")
        }

        /** What she says about it: a LoRA, an image, anything else. */
        fun describe(f: File): String = when (f.extension.lowercase()) {
            "safetensors", "ckpt", "pt" -> "Le modèle ${f.name} vient d'arriver (${Health.gb(f.length())}) : entraînement ou téléchargement fini !"
            "png", "jpg", "jpeg", "webp" -> "Nouvelle image : ${f.name}."
            else -> "Téléchargement terminé : ${f.name} (${Health.gb(f.length())})."
        }
    }
}

/** The local ComfyUI, through its HTTP API (127.0.0.1:8188 by default). */
object Comfy {
    val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val json = Json { ignoreUnknownKeys = true }

    /** Prompts running or waiting; null when ComfyUI does not answer. */
    suspend fun queue(base: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val r = http.send(HttpRequest.newBuilder(URI(base.trimEnd('/') + "/queue")).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString())
            if (r.statusCode() != 200) return@runCatching null
            val o = json.parseToJsonElement(r.body()).jsonObject
            listOf("queue_running", "queue_pending").sumOf { (o[it] as? JsonArray)?.size ?: 0 }
        }.getOrNull()
    }
}
