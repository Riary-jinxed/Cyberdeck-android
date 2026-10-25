package dev.netrunner.os.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URLEncoder

/** What the address bar does with what was typed: a web address, or a search. */
object Address {
    const val Home = "https://duckduckgo.com/?kl=fr-fr"

    fun search(q: String) = "https://duckduckgo.com/?kl=fr-fr&q=" + URLEncoder.encode(q.trim(), Charsets.UTF_8)

    fun resolve(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return Home
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t) && !t.contains(' ') && !Regex("^[^/:]+:\\d+(/|$)").containsMatchIn(t)) {
            // "https://…", "file:///…", "about:blank"; "localhost:8080" is a host and port, below.
            return t
        }
        if (t.startsWith("/")) return "file://$t"
        val host = t.substringBefore('/').substringBefore('?')
        val looksLikeHost = !t.contains(' ') && (host.contains('.') && !host.endsWith('.') || host.startsWith("localhost") || Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?$").matches(host))
        return if (looksLikeHost) (if (host.startsWith("localhost") || host.first().isDigit()) "http://" else "https://") + t else search(t)
    }

    /** "https://www.exemple.fr/page" shown as "exemple.fr/page". */
    fun short(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")
}

/** Favourites and history, kept in ~/.config/netrunner/web as JSON. */
class Places(private val dir: File) {
    data class Place(val url: String, val title: String, val at: Long)

    var bookmarks: List<Place> = load("favoris.json")
        private set
    var history: List<Place> = load("historique.json")
        private set

    private fun load(name: String): List<Place> = runCatching {
        Json.parseToJsonElement(File(dir, name).readText()).jsonArray.map {
            val o = it.jsonObject
            Place(o["url"]!!.jsonPrimitive.content, o["title"]?.jsonPrimitive?.content.orEmpty(), o["at"]?.jsonPrimitive?.longOrNull ?: 0)
        }
    }.getOrDefault(emptyList())

    private fun save(name: String, list: List<Place>) {
        runCatching {
            dir.mkdirs()
            val json = JsonArray(list.map { JsonObject(mapOf("url" to JsonPrimitive(it.url), "title" to JsonPrimitive(it.title), "at" to JsonPrimitive(it.at))) })
            val tmp = File(dir, ".$name.part")
            tmp.writeText(json.toString())
            tmp.renameTo(File(dir, name))
        }
    }

    private fun worthKeeping(url: String) = url.startsWith("http://") || url.startsWith("https://")

    /** A page seen: first in history, once (2000 pages at most). */
    fun visit(url: String, title: String, now: Long = System.currentTimeMillis()) {
        if (!worthKeeping(url)) return
        val old = history.firstOrNull { it.url == url }
        history = (listOf(Place(url, title.ifBlank { old?.title ?: Address.short(url) }, now)) + history.filter { it.url != url }).take(2000)
        save("historique.json", history)
    }

    /** A better title for a page already in history. */
    fun retitle(url: String, title: String) {
        if (title.isBlank() || history.firstOrNull()?.url != url || history.first().title == title) return
        history = listOf(history.first().copy(title = title)) + history.drop(1)
        save("historique.json", history)
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    /** Adds or removes a favourite; true when it is one now. */
    fun toggleBookmark(url: String, title: String, now: Long = System.currentTimeMillis()): Boolean {
        val on = !isBookmarked(url)
        bookmarks = if (on) bookmarks + Place(url, title.ifBlank { Address.short(url) }, now) else bookmarks.filter { it.url != url }
        save("favoris.json", bookmarks)
        return on
    }

    fun clearHistory() {
        history = emptyList()
        save("historique.json", history)
    }

    /** Favourites then history whose title or address has every word of [q], accents ignored. */
    fun search(q: String, limit: Int = 50): List<Place> {
        val words = fold(q).split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun hit(p: Place) = fold(p.title + " " + p.url).let { s -> words.all { it in s } }
        return (bookmarks.filter(::hit) + history.filter(::hit)).distinctBy { it.url }.take(limit)
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
