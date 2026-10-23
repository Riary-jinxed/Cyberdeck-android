package dev.netrunner.launcher.aska

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Her memory of past conversations, kept on the phone: when the runner talks about something
 * they already discussed, the matching old exchange is slipped to her with the message. And
 * what was said during each rendez-vous, so she can bring it up the next time.
 * Plain word matching, no model call: it costs nothing between messages.
 */
object AskaRecall {

    /** [said]: what the runner said; [moments]: what she did, her gestures between asterisks. */
    data class DateMemory(val at: Long, val scene: String, val said: List<String>, val moments: List<String> = emptyList())

    private fun file(context: Context) = File(context.filesDir, "aska_dates.json")

    fun dates(context: Context): List<DateMemory> = runCatching {
        val a = JSONArray(file(context).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val said = o.getJSONArray("said")
            val moments = o.optJSONArray("moments") ?: JSONArray()
            DateMemory(
                o.getLong("at"), o.getString("scene"),
                (0 until said.length()).map { said.getString(it) },
                (0 until moments.length()).map { moments.getString(it) },
            )
        }
    }.getOrDefault(emptyList())

    /** Keeps the longest of [lines], in their order. */
    private fun longest(lines: List<String>, count: Int, max: Int) = lines.map { it.trim() }.filter { it.length >= 12 }
        .withIndex().sortedByDescending { it.value.length }.take(count).sortedBy { it.index }
        .map { it.value.take(max) }

    /** Keeps what the runner said during the date and what she did (the longest ones, in order). */
    fun saveDate(context: Context, scene: AskaDate.Scene, userLines: List<String>, herLines: List<String>) {
        val gestures = herLines.flatMap { line -> Regex("\\*([^*]+)\\*").findAll(line).map { it.groupValues[1] }.toList() }
        saveDate(context, scene.label, longest(userLines, 5, 140), longest(gestures, 3, 120))
    }

    /** A date as the Rendez-vous app tells it: what the runner said, what she did. */
    fun saveDate(context: Context, label: String, said: List<String>, moments: List<String>) {
        if (said.isEmpty()) return
        val all = (listOf(DateMemory(System.currentTimeMillis(), label, said.take(5), moments.take(3))) + dates(context)).take(MAX_DATES)
        val a = JSONArray()
        all.forEach { d ->
            a.put(JSONObject().put("at", d.at).put("scene", d.scene).put("said", JSONArray(d.said)).put("moments", JSONArray(d.moments)))
        }
        runCatching { file(context).writeText(a.toString()) }
    }

    /** The last rendez-vous, for her instructions. Empty when there is none. */
    fun datesForPrompt(context: Context, handle: String, count: Int = 3): String {
        val last = dates(context).take(count)
        if (last.isEmpty()) return ""
        return "\nVos derniers rendez-vous, tes vrais souvenirs (quand tu en parles, cite un détail précis de cette liste, et n'invente pas de souvenir qui n'y est pas) :\n" +
            last.joinToString("\n") { d ->
                "- le ${day(d.at)} (${d.scene.lowercase(Locale.FRENCH)}), $handle t'a dit : " + d.said.joinToString(" ; ") { "« $it »" } +
                    (if (d.moments.isEmpty()) "" else " ; toi, tu as fait : " + d.moments.joinToString(" ; ") { "*$it*" })
            }
    }

    /**
     * Old exchanges about what the runner just said, as a note for her; null when nothing
     * matches well enough. [past] is the saved conversation, oldest first, without the lines
     * the model already has in its context.
     */
    fun recall(context: Context, input: String, past: List<Pair<Boolean, String>>, handle: String): String? {
        val words = words(input)
        if (words.isEmpty()) return null
        // "Tu te souviens…", "je t'avais dit…": one shared word is enough.
        val asked = ASKED.containsMatchIn(fold(input))
        val needed = if (asked) 1 else 2

        val found = mutableListOf<Pair<Int, String>>()
        past.forEachIndexed { i, (fromUser, text) ->
            if (!fromUser) return@forEachIndexed
            val score = words.count { it in words(text) }
            if (score < needed) return@forEachIndexed
            val answer = past.getOrNull(i + 1)?.takeIf { !it.first }?.second
            found += score to ("$handle t'avait dit « ${text.take(160)} »" + (answer?.let { " et tu avais répondu « ${it.take(160)} »" } ?: ""))
        }
        dates(context).forEach { d ->
            val score = words.count { w -> (d.said + d.moments).any { w in words(it) } || w in words(d.scene) }
            if (score >= needed) found += score to "pendant votre rendez-vous du ${day(d.at)} (${d.scene.lowercase(Locale.FRENCH)}), $handle t'avait dit " +
                d.said.joinToString(" ; ") { "« $it »" } + (if (d.moments.isEmpty()) "" else " et tu avais fait " + d.moments.joinToString(" ; ") { "*$it*" })
        }
        if (found.isEmpty()) return null
        return "[Souvenirs de vos conversations passées, à utiliser seulement si c'est utile : " +
            found.sortedByDescending { it.first }.take(2).joinToString(" ; ") { it.second } + ".]"
    }

    private val ASKED = Regex("souviens|rappelles?-toi|te rappelles|je t'avais (dit|parle)|on avait parle|on en avait parle|la derniere fois")
    private val STOP = setOf(
        "alors", "aussi", "avec", "avoir", "bien", "cela", "cette", "comme", "comment", "dans", "depuis", "donc", "elle", "elles",
        "encore", "etre", "fait", "faire", "faut", "juste", "leur", "mais", "meme", "moins", "nous", "parce", "pour", "peux", "plus",
        "pourquoi", "quand", "quel", "quelle", "quoi", "rien", "sais", "sont", "suis", "sans", "tout", "tous", "toute", "tres", "trop",
        "vais", "veux", "vous", "voila", "salut", "merci", "bonjour", "bonsoir", "souviens", "rappelles", "derniere", "parle", "avais",
    )

    private fun words(text: String): Set<String> =
        fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in STOP }.toSet()

    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'')

    private fun day(at: Long) =
        DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    private const val MAX_DATES = 20
}
