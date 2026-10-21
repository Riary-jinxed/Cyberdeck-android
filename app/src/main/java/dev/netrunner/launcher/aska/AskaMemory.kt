package dev.netrunner.launcher.aska

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * What As//ka keeps about her runner beyond the conversation: short facts the user asked her
 * to remember. They go into her instructions, so she knows them in every new conversation.
 */
class AskaMemory(context: Context) {

    private val file = File(context.filesDir, "aska_memory.json")

    var facts: List<String> = load()
        private set

    sealed class Request {
        data class Remember(val fact: String) : Request()
        data class Forget(val topic: String) : Request()
        object ForgetAll : Request()
        object Recall : Request()
    }

    fun parse(input: String): Request? {
        val t = normalize(input)
        REMEMBER.find(t)?.let {
            // Keep the user's own spelling: cut the original text at the same length.
            val fact = input.substring(it.range.last + 1).trim().trimEnd('.', '!')
            return if (fact.length >= 3) Request.Remember(fact.replaceFirstChar { c -> c.uppercase() }) else null
        }
        if (FORGET_ALL.containsMatchIn(t)) return Request.ForgetAll
        FORGET.find(t)?.let { return Request.Forget(it.groupValues[2].trim()) }
        if (RECALL.containsMatchIn(t)) return Request.Recall
        return null
    }

    fun remember(fact: String) {
        facts = (facts.filterNot { normalize(it) == normalize(fact) } + fact).takeLast(MAX_FACTS)
        save()
    }

    /** Forgets the facts that mention [topic]. Returns how many. */
    fun forget(topic: String): Int {
        val key = normalize(topic).trim()
        val before = facts.size
        facts = facts.filterNot { key.isNotEmpty() && key in normalize(it) }
        save()
        return before - facts.size
    }

    fun clear() {
        facts = emptyList()
        save()
    }

    private fun load(): List<String> = runCatching {
        val array = JSONArray(file.readText())
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun save() {
        runCatching { file.writeText(JSONArray(facts).toString()) }
    }

    companion object {
        const val MAX_FACTS = 60

        // Matched on normalized text, whose indices line up with the user's original text.
        private val REMEMBER = Regex("^\\s*(?:as//ka\\s*,?\\s*)?(?:retiens|souviens[- ]toi|rappelle[- ]toi|n'oublie pas|oublie pas|memorise|note)\\s+(?:bien\\s+)?(?:que\\s+|qu'|de\\s+|:\\s*)?")
        private val FORGET_ALL = Regex("^\\s*(?:oublie|efface)\\s+(?:tout\\b|ta memoire|tout ce que tu sais)")
        private val FORGET = Regex("^\\s*oublie\\s+(?!pas\\b)(que\\s+|qu'|ce que je t'ai dit sur\\s+|l'histoire de\\s+)?(.+)")
        private val RECALL = Regex("(qu'est[- ]ce que|que|ce que) tu (sais|te souviens|retiens) (sur|de) moi|sais[- ]tu (sur|de) moi|ta memoire|tu te souviens de quoi")

        /** Lowercase, accents removed character by character (keeps indices aligned with the input). */
        private fun normalize(s: String): String = buildString(s.length) {
            for (c in s) {
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                append(base.lowercaseChar().let { if (it == '’') '\'' else it })
            }
        }.lowercase(Locale.FRENCH).let { if (it.length == s.length) it else s.lowercase(Locale.FRENCH) }
    }
}
