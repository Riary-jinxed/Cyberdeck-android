package dev.netrunner.aska

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

@Serializable
data class Fact(val text: String, val day: String)

/**
 * What she keeps about the runner from one week to the next (« je joue main Saryn », « mon
 * anniversaire est le 3 mai »): short facts the runner asked her to remember, in memory.json.
 */
object Memory {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file get() = File(Store.dir, "memory.json")
    private val facts = runCatching { json.decodeFromString<List<Fact>>(file.readText()) }.getOrDefault(emptyList()).toMutableList()

    @Synchronized fun all(): List<Fact> = facts.toList()

    @Synchronized fun remember(text: String): String {
        val t = text.trim().trimEnd('.').take(MAX_LENGTH)
        if (t.isEmpty()) return "Rien à retenir."
        if (facts.any { DeckLink.normalize(it.text) == DeckLink.normalize(t) }) return "Je le savais déjà : $t."
        facts += Fact(t, LocalDate.now().toString())
        while (facts.size > MAX_FACTS) facts.removeAt(0)
        save()
        return "Retenu : $t."
    }

    /** Forgets the facts that contain [query]; « tout » forgets everything. */
    @Synchronized fun forget(query: String): String {
        val q = DeckLink.normalize(query).trim()
        if (q.isEmpty()) return "Qu'est-ce que je dois oublier ?"
        val gone = if (q in setOf("tout", "all", "*")) facts.toList() else facts.filter { DeckLink.normalize(it.text).contains(q) }
        if (gone.isEmpty()) return "Je n'ai rien retenu à propos de « $query »."
        facts -= gone.toSet()
        save()
        return "Oublié : " + gone.joinToString(" ; ") { it.text }
    }

    fun text(): String = all().let { f -> if (f.isEmpty()) "Je n'ai encore rien retenu de durable." else f.joinToString("\n") { "- ${it.text} (${it.day})" } }

    /** For her system prompt. */
    fun prompt(handle: String): String? = all().takeIf { it.isNotEmpty() }?.let { f ->
        "Ce que tu as retenu de $handle au fil des jours (sers-t'en naturellement, sans tout réciter) :\n" + f.joinToString("\n") { "- ${it.text}" }
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(facts.toList())) }
    }

    private const val MAX_FACTS = 80
    private const val MAX_LENGTH = 200
}
