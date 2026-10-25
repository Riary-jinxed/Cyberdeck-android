package dev.netrunner.aska

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class Reminder(val id: Long, val at: Long, val text: String)

/** « Rappelle-moi dans 20 min » : her reminders and timers, kept across restarts. */
object Reminders {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file get() = File(Store.dir, "reminders.json")
    private val list = runCatching { json.decodeFromString<List<Reminder>>(file.readText()) }.getOrDefault(emptyList()).toMutableList()

    @Synchronized fun all(): List<Reminder> = list.sortedBy { it.at }

    @Synchronized fun add(at: LocalDateTime, text: String): Reminder {
        val r = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), text.trim().ifEmpty { "c'est l'heure" })
        list += r
        save()
        return r
    }

    /** Removes the reminders that match [query] (its number, or words of its text); « tout » removes all. */
    @Synchronized fun cancel(query: String): List<Reminder> {
        val q = DeckLink.normalize(query).trim()
        val gone = when {
            q in setOf("tout", "tous", "all", "*") -> list.toList()
            q.toLongOrNull() != null -> list.filter { it.id == q.toLong() }
            else -> list.filter { DeckLink.normalize(it.text).contains(q) }
        }
        list -= gone.toSet()
        save()
        return gone
    }

    /** The reminders whose time has come, taken out of the list. */
    @Synchronized fun due(now: Long = System.currentTimeMillis()): List<Reminder> {
        val due = list.filter { it.at <= now }
        if (due.isNotEmpty()) {
            list -= due.toSet()
            save()
        }
        return due
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(list.toList())) }
    }

    fun text(now: LocalDateTime = LocalDateTime.now()): String {
        val all = all()
        if (all.isEmpty()) return "Aucun rappel en attente."
        return all.joinToString("\n") { "${it.id}. ${label(it.at, now)} : ${it.text}" }
    }

    private val hour = DateTimeFormatter.ofPattern("HH:mm")
    private val day = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRENCH)

    /** « à 18:30 », « demain à 09:00 », « jeudi 3 juillet à 14:00 ». */
    fun label(at: Long, now: LocalDateTime = LocalDateTime.now()): String {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
        return when (t.toLocalDate()) {
            now.toLocalDate() -> "à " + hour.format(t)
            now.toLocalDate().plusDays(1) -> "demain à " + hour.format(t)
            else -> day.format(t)
        }
    }

    /**
     * When the runner means: « 20 », « 20 min », « 1h30 », « 2 h », « 45s », « dans 10 minutes »,
     * « 18:30 », « 18h », « à 7h15 », « demain 9h ». Null when it is not a time.
     */
    fun parseWhen(input: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        var text = DeckLink.normalize(input).trim().removePrefix("dans ")
        val explicit = text.startsWith("a ")
        text = text.removePrefix("a ").trim()
        var tomorrow = false
        if (text.startsWith("demain")) {
            tomorrow = true
            text = text.removePrefix("demain").trim().removePrefix("a ").trim().ifEmpty { "9h" }
        }
        // A clock time: 18:30, 18h30, 18h, 7 h 15.
        Regex("^(\\d{1,2})\\s*(:|h)\\s*(\\d{2})?$").find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h > 23 || min > 59) return null
            // « 1h30 » or « 2h » alone is a duration more often than a time in the night.
            if (!tomorrow && !explicit && m.groupValues[2] == "h" && h < 5) return now.plusMinutes(h * 60L + min)
            var at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(h, min))
            if (tomorrow) at = at.plusDays(1) else if (!at.isAfter(now)) at = at.plusDays(1)
            return at
        }
        if (tomorrow) return null
        // A duration: 20, 20 min, 1 h 30, 1h30min, 90s, 2 heures.
        val m = Regex("^(?:(\\d+)\\s*(?:h|heures?)\\s*)?(?:(\\d+)\\s*(?:m|mn|min|mins|minutes?)?\\s*)?(?:(\\d+)\\s*(?:s|sec|secondes?))?$").find(text) ?: return null
        val (h, min, s) = m.destructured
        if (h.isEmpty() && min.isEmpty() && s.isEmpty()) return null
        val seconds = (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        if (seconds <= 0 || seconds > 366L * 24 * 3600) return null
        return now.plusSeconds(seconds)
    }

    /** « 20 min sortir le linge » → when and what: the longest start of the words that reads as a time. */
    fun split(input: String, now: LocalDateTime = LocalDateTime.now()): Pair<LocalDateTime, String>? {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (n in minOf(5, words.size) downTo 1) {
            val at = parseWhen(words.take(n).joinToString(" "), now) ?: continue
            return at to words.drop(n).joinToString(" ").removePrefix("pour ").removePrefix("de ")
        }
        return null
    }
}

/** Pomodoro: work, then a short break, four times, then a long break. Her remarks stay quiet while the runner works. */
class Focus(val workMinutes: Int = 25, val breakMinutes: Int = 5, val longBreakMinutes: Int = 15, start: Long = System.currentTimeMillis()) {
    var working = true
        private set
    /** Work rounds finished. */
    var rounds = 0
        private set
    var endsAt: Long = start + workMinutes * 60_000L
        private set

    /** Moves on when the phase is over: what she should say, or null. */
    fun step(now: Long = System.currentTimeMillis()): String? {
        if (now < endsAt) return null
        return if (working) {
            rounds++
            working = false
            val long = rounds % 4 == 0
            endsAt = now + (if (long) longBreakMinutes else breakMinutes) * 60_000L
            if (long) "Quatre sessions d'affilée : grande pause de $longBreakMinutes min. Lève-toi, bois un verre d'eau !"
            else "Pomodoro n°$rounds terminé ! Pause de $breakMinutes min : décroche des yeux de l'écran."
        } else {
            working = true
            endsAt = now + workMinutes * 60_000L
            "Fin de la pause : on repart pour $workMinutes min de concentration."
        }
    }

    fun text(now: Long = System.currentTimeMillis()): String {
        val left = ((endsAt - now) / 60_000L).coerceAtLeast(0)
        return (if (working) "Concentration" else "Pause") + " : encore $left min ($rounds session(s) terminée(s))."
    }
}
