package dev.netrunner.launcher.aska

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** The only things As//ka may do on the phone: alarms and timers through the clock app, and her own reminders. */
sealed class AlarmAction {
    /** Waits for the user's go before running. */
    open val needsConfirm = true

    /** What she says while waiting for that go. */
    open val ask = "Je prépare ça. Valide et je l'envoie à l'horloge."

    data class Set(val hour: Int, val minute: Int, val label: String?, val days: List<Int>?) : AlarmAction()
    data class Dismiss(val hour: Int, val minute: Int) : AlarmAction()
    data class Timer(val seconds: Int) : AlarmAction()
    object Show : AlarmAction() {
        override val needsConfirm = false
    }
    data class Remind(val at: Long, val text: String) : AlarmAction() {
        override val ask = "Je le note ? Valide et je te le rappellerai à l'heure."
    }
    data class Forget(val reminders: List<Reminder>) : AlarmAction() {
        override val ask = if (reminders.size > 1) "Je les efface tous ? Valide." else "J'efface ce rappel ? Valide."
    }

    val summary: String
        get() = when (this) {
            is Set -> "ALARME ${hhmm(hour, minute)}" + (days?.let { " · ${daysLabel(it)}" } ?: "") + (label?.let { " · « $it »" } ?: "")
            is Dismiss -> "SUPPRIMER L'ALARME DE ${hhmm(hour, minute)}"
            is Timer -> "MINUTEUR ${durationLabel(seconds)}"
            Show -> "OUVRIR L'HORLOGE"
            is Remind -> "RAPPEL ${Reminder(0, at, text).whenLabel.uppercase(Locale.FRENCH)} · « $text »"
            is Forget -> if (reminders.size == 1) "EFFACER LE RAPPEL « ${reminders[0].text} »" else "EFFACER ${reminders.size} RAPPELS"
        }
}

object AskaAlarms {

    /** Runs the action. Returns the line As//ka says afterwards. */
    fun run(context: Context, action: AlarmAction): String {
        when (action) {
            is AlarmAction.Remind -> {
                AskaReminders.add(context, action.at, action.text)
                return "C'est noté. ${Reminder(0, action.at, action.text).whenLabel.replaceFirstChar { it.uppercase() }}, je te le rappelle."
            }
            is AlarmAction.Forget -> {
                action.reminders.forEach { AskaReminders.remove(context, it.id) }
                return if (action.reminders.size == 1) "Rappel effacé." else "Rappels effacés."
            }
            else -> Unit
        }
        val intent = when (action) {
            is AlarmAction.Set -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, action.label ?: "As//ka")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { action.days?.let { putExtra(AlarmClock.EXTRA_DAYS, ArrayList(it)) } }
            is AlarmAction.Dismiss -> Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            is AlarmAction.Timer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "As//ka")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            AlarmAction.Show -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            is AlarmAction.Remind, is AlarmAction.Forget -> error("handled above")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            when (action) {
                is AlarmAction.Set -> "C'est gravé : alarme à ${hhmm(action.hour, action.minute)}."
                is AlarmAction.Dismiss -> "Je transmets à l'horloge. Confirme-lui si elle te le demande."
                is AlarmAction.Timer -> "Minuteur lancé : ${durationLabel(action.seconds)}."
                AlarmAction.Show -> "J'ouvre l'horloge."
                is AlarmAction.Remind, is AlarmAction.Forget -> ""
            }
        } catch (_: ActivityNotFoundException) {
            "Aucune appli d'horloge ne répond à mes signaux."
        }
    }

    /** The next alarm the system knows about, said in words. */
    fun nextAlarm(context: Context): String {
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            ?: return "Aucune alarme programmée. Tu dors sans filet."
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(next.triggerTime), ZoneId.systemDefault())
        val day = when (at.toLocalDate()) {
            LocalDateTime.now().toLocalDate() -> "aujourd'hui"
            LocalDateTime.now().toLocalDate().plusDays(1) -> "demain"
            else -> at.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))
        }
        return "Prochaine alarme : $day à ${hhmm(at.hour, at.minute)}."
    }

    // ---- Understanding requests in plain French, without the model (instant, and works with no model loaded).

    sealed class Request {
        data class Act(val action: AlarmAction) : Request()
        object Next : Request()
        /** Lists her reminders. */
        object Reminders : Request()
        /** A reminder without a time: she asks when. */
        object NeedTime : Request()
    }

    /** Her reminders, numbered as "supprime le rappel 2" expects. */
    fun remindersText(context: Context): String {
        val all = AskaReminders.upcoming(context)
        if (all.isEmpty()) return "Aucun rappel en attente. Dis « rappelle-moi de… à 15h » et je veille."
        return "Tes rappels :\n" + all.mapIndexed { i, r -> "▸ ${i + 1} · ${r.whenLabel} · ${r.text}" }.joinToString("\n")
    }

    private val REMIND_WORDS = Regex(
        "\\b(rappelle[- ]?(moi|nous)|tu peux me rappeler|peux-tu me rappeler|fais[- ]moi penser|pense a me rappeler|n'oublie pas de me rappeler" +
            "|(mets?|met|cree|ajoute|programme)[- ]?(moi )?un rappel)"
    )
    private val REMINDER_LIST = Regex("\\b(mes rappels|liste (des |de mes )?rappels|quels (sont mes )?rappels|rappels? (prevus?|en attente))")

    private fun parseReminder(context: Context, input: String, t: String): Request? {
        val mentionsReminder = Regex("\\brappels?\\b").containsMatchIn(t)
        if (mentionsReminder && REMOVE_WORDS.containsMatchIn(t)) {
            val all = AskaReminders.upcoming(context)
            if (all.isEmpty()) return Request.Reminders
            val picked = when {
                Regex("\\btous\\b|\\bmes rappels\\b|\\bles rappels\\b").containsMatchIn(t) -> all
                else -> Regex("\\brappel\\s*(n°|numero|no)?\\s*(\\d+)").find(t)?.groupValues?.get(2)?.toIntOrNull()
                    ?.let { all.getOrNull(it - 1) }?.let { listOf(it) }
                    ?: if (all.size == 1) all else null
            } ?: return Request.Reminders
            return Request.Act(AlarmAction.Forget(picked))
        }
        if (REMINDER_LIST.containsMatchIn(t) && !REMIND_WORDS.containsMatchIn(t)) return Request.Reminders
        if (!REMIND_WORDS.containsMatchIn(t)) return null
        val at = reminderTime(t) ?: return Request.NeedTime
        return Request.Act(AlarmAction.Remind(at, reminderText(input)))
    }

    /** When the reminder rings: "dans 20 minutes", "demain à 9h", "lundi à 18h30", "à 15h" (today, or tomorrow if past). */
    private fun reminderTime(t: String): Long? {
        val zone = ZoneId.systemDefault()
        Regex("\\bdans\\s+(.+)").find(t)?.let { m ->
            duration(m.groupValues[1])?.let { return System.currentTimeMillis() + it * 1000L }
        }
        var (hour, minute) = clockTime(t) ?: return null
        if (hour < 12 && Regex("\\bce soir\\b|\\bcet? apres-?midi\\b|\\bcet aprem\\b").containsMatchIn(t) && !CLOCK_MORNING.containsMatchIn(t)) hour += 12
        val today = java.time.LocalDate.now()
        var date = when {
            Regex("\\bapres[- ]demain\\b").containsMatchIn(t) -> today.plusDays(2)
            Regex("\\bdemain\\b").containsMatchIn(t) -> today.plusDays(1)
            else -> DAY_NAMES.firstOrNull { (name, _) -> Regex("\\b$name\\b").containsMatchIn(t) }?.let { (_, day) ->
                val target = java.time.DayOfWeek.of(if (day == Calendar.SUNDAY) 7 else day - 1)
                var ahead = (target.value - today.dayOfWeek.value + 7) % 7
                if (ahead == 0 && java.time.LocalTime.of(hour, minute) <= java.time.LocalTime.now()) ahead = 7
                today.plusDays(ahead.toLong())
            } ?: today
        }
        var at = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
        if (!at.isAfter(LocalDateTime.now())) {
            date = date.plusDays(1)
            at = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
        }
        return at.atZone(zone).toInstant().toEpochMilli()
    }

    private val CLOCK_MORNING = Regex("\\b(du|ce) matin\\b")

    /** "Rappelle-moi d'appeler le client à 15h" → "appeler le client". */
    private fun reminderText(input: String): String {
        var s = input.trim()
        val trigger = Regex(
            "(?i)^.*?\\b(rappelle[- ]?(moi|nous)|tu peux me rappeler|peux-tu me rappeler|fais[- ]moi penser|pense à me rappeler|pense a me rappeler" +
                "|n'oublie pas de me rappeler|(mets?|met|crée|cree|ajoute|programme)[- ]?(moi )?un rappel( pour)?)\\s*"
        )
        s = s.replace(trigger, "")
        val removals = listOf(
            "(?i)\\bdans\\s+(une demi[- ]heure|une?\\s+(heure|minute)s?|\\d+\\s*(h|heures?|min|minutes?|mn|secondes?|sec|s)\\b(\\s*\\d+)?)",
            "(?i)\\b(à|a|vers|pour)\\s+(\\d{1,2}\\s*(h|heures?|:)\\s*\\d{0,2}|midi|minuit)(\\s*(et (demie|quart)|moins (le )?quart))?(\\s*(du|de l'|de)\\s*(matin|soir|après[- ]midi|apres[- ]midi|aprem))?",
            "(?i)\\b(après[- ]demain|apres[- ]demain|demain|aujourd'hui|ce soir|ce matin|cet après[- ]midi|cet apres[- ]midi)\\b",
            "(?i)\\b(ce |le |)(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)( prochain)?\\b",
        )
        removals.forEach { s = s.replace(Regex(it), " ") }
        s = s.replace(Regex("\\s+"), " ").trim().trim(',', '.', '!', '?', ':', ' ')
        s = s.replace(Regex("(?i)^(de |d'|d’|que |qu'|qu’|pour |à |a )"), "").trim()
        return s.replaceFirstChar { it.lowercase() }.ifEmpty { "ton rappel" }.take(80)
    }

    private val ALARM_WORDS = Regex("\\b(alarme|alarmes|reveil|reveille|reveiller|sonnerie|alarm|wake)")
    private val TIMER_WORDS = Regex("\\b(minuteur|timer|compte a rebours|chrono)")
    private val REMOVE_WORDS = Regex("\\b(supprime|supprimer|annule|annuler|desactive|desactiver|enleve|enlever|retire|retirer|efface|effacer)")
    private val NEXT_WORDS = Regex("prochaine alarme|prochain reveil|a quelle heure (je me reveille|sonne|mon reveil)")
    private val SHOW_WORDS = Regex("\\b(montre|affiche|liste|ouvre|voir)\\b.*\\b(alarmes?|reveils?|horloge)|mes alarmes")

    fun parse(context: Context, input: String): Request? {
        val t = normalize(input)
        parseReminder(context, input, t)?.let { return it }
        val alarm = ALARM_WORDS.containsMatchIn(t)
        val timer = TIMER_WORDS.containsMatchIn(t)
        if (!alarm && !timer) return null

        if (alarm && NEXT_WORDS.containsMatchIn(t)) return Request.Next
        if (timer) duration(t)?.let { return Request.Act(AlarmAction.Timer(it)) }
        if (!alarm) return null

        val time = relativeTime(t) ?: clockTime(t)
        if (REMOVE_WORDS.containsMatchIn(t)) {
            return time?.let { Request.Act(AlarmAction.Dismiss(it.first, it.second)) } ?: Request.Act(AlarmAction.Show)
        }
        if (time == null) return if (SHOW_WORDS.containsMatchIn(t)) Request.Act(AlarmAction.Show) else null
        return Request.Act(AlarmAction.Set(time.first, time.second, label(input), days(t)))
    }

    /** Tags the model may add to its answer: [[ALARME 07:30 | label]], [[MINUTEUR 10]] (minutes), [[RAPPEL 15:00 | texte]]. */
    private val TAG = Regex("\\[\\[\\s*(ALARME|MINUTEUR|RAPPEL)\\s+([^\\]|]+?)\\s*(?:\\|\\s*([^\\]]*?)\\s*)?]]", RegexOption.IGNORE_CASE)

    /** Removes the tags from a model answer and turns the first one into an action. */
    fun extractTag(answer: String): Pair<String, AlarmAction?> {
        val match = TAG.find(answer) ?: return answer to null
        val clean = answer.replace(TAG, "").trim()
        val value = normalize(match.groupValues[2])
        val label = match.groupValues[3].trim().ifEmpty { null }
        val action = when (match.groupValues[1].uppercase()) {
            "ALARME" -> clockTime(value)?.let { AlarmAction.Set(it.first, it.second, label, null) }
            "RAPPEL" -> reminderTime(value)?.let { AlarmAction.Remind(it, label ?: "ton rappel") }
            else -> (duration(value) ?: value.trim().toIntOrNull()?.times(60))?.let { AlarmAction.Timer(it) }
        }
        return clean to action
    }

    // ---- Parsing helpers. Input is normalized: lowercase, no accents.

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'')

    private val CLOCK = Regex("\\b([01]?\\d|2[0-3])\\s*(?:heures?|h|:)\\s*([0-5]\\d)?(\\s*et (demie|quart)|\\s*moins (le )?quart)?(\\s*(du|de l'|de) (matin|soir|apres-midi|apres midi|aprem))?")

    private fun clockTime(t: String): Pair<Int, Int>? {
        if (Regex("\\bmidi\\b").containsMatchIn(t) && !CLOCK.containsMatchIn(t)) return 12 to 0
        if (Regex("\\bminuit\\b").containsMatchIn(t) && !CLOCK.containsMatchIn(t)) return 0 to 0
        val m = CLOCK.find(t) ?: return null
        var hour = m.groupValues[1].toInt()
        var minute = m.groupValues[2].toIntOrNull() ?: 0
        val extra = m.groupValues[3]
        when {
            "demie" in extra -> minute = 30
            "quart" in extra && "moins" in extra -> { hour = (hour + 23) % 24; minute = 45 }
            "quart" in extra -> minute = 15
        }
        val part = m.groupValues[8]
        if (part.isNotEmpty() && part != "matin" && hour < 12) hour += 12
        return hour to minute
    }

    /** "dans 20 minutes", "dans 1h30" → the clock time it lands on. */
    private fun relativeTime(t: String): Pair<Int, Int>? {
        val m = Regex("\\bdans\\s+(.+)").find(t) ?: return null
        val seconds = duration(m.groupValues[1]) ?: return null
        val at = LocalDateTime.now().plusSeconds(seconds.toLong())
        return at.hour to at.minute
    }

    private val DURATION = Regex("(\\d+)\\s*(h|heures?|min|minutes?|mn|m|s|sec|secondes?)(?![a-z])\\s*(\\d+)?")

    /** "10 min", "1h30", "90 s", "une heure" → seconds. */
    private fun duration(t: String): Int? {
        val text = t.replace(Regex("\\bune? (heure)"), "1 heure").replace(Regex("\\bune? (minute)"), "1 minute")
            .replace(Regex("\\bune demi-heure|\\bune demi heure"), "30 min")
        var total = 0
        var found = false
        DURATION.findAll(text).forEach { m ->
            val n = m.groupValues[1].toInt()
            when (m.groupValues[2].first()) {
                'h' -> {
                    total += n * 3600
                    m.groupValues[3].toIntOrNull()?.let { total += it * 60 }
                }
                'm' -> total += n * 60
                's' -> total += n
            }
            found = true
        }
        return total.takeIf { found && it > 0 }
    }

    private val DAY_NAMES = listOf(
        "lundi" to Calendar.MONDAY, "mardi" to Calendar.TUESDAY, "mercredi" to Calendar.WEDNESDAY,
        "jeudi" to Calendar.THURSDAY, "vendredi" to Calendar.FRIDAY, "samedi" to Calendar.SATURDAY, "dimanche" to Calendar.SUNDAY,
    )

    /** Repeat days, only when asked to repeat ("tous les jours", "en semaine", "chaque lundi"). */
    private fun days(t: String): List<Int>? {
        val week = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        return when {
            Regex("tous les jours|chaque jour|quotidien").containsMatchIn(t) -> week + Calendar.SATURDAY + Calendar.SUNDAY
            Regex("en semaine|jours de semaine|du lundi au vendredi").containsMatchIn(t) -> week
            Regex("le week-?end|les week-?ends").containsMatchIn(t) -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
            Regex("tous les|chaque|les ").containsMatchIn(t) ->
                DAY_NAMES.filter { (name, _) -> Regex("\\b${name}s?\\b").containsMatchIn(t) }.map { it.second }.ifEmpty { null }
            else -> null
        }
    }

    /** "… pour le sport" → "le sport". */
    private fun label(input: String): String? =
        Regex("\\bpour\\s+(.+)$", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1)
            ?.trim()?.trimEnd('.', '!', '?')
            ?.takeIf { it.isNotEmpty() && !Regex("\\d").containsMatchIn(it) }
            ?.take(40)
}

internal fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

private fun durationLabel(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" },
        s.takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ")
}

private fun daysLabel(days: List<Int>): String = when (days.toSet()) {
    setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
        Calendar.SATURDAY, Calendar.SUNDAY) -> "TOUS LES JOURS"
    setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> "EN SEMAINE"
    setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "LE WEEK-END"
    else -> days.joinToString(" ") { d ->
        when (d) {
            Calendar.MONDAY -> "LUN"; Calendar.TUESDAY -> "MAR"; Calendar.WEDNESDAY -> "MER"
            Calendar.THURSDAY -> "JEU"; Calendar.FRIDAY -> "VEN"; Calendar.SATURDAY -> "SAM"; else -> "DIM"
        }
    }
}
