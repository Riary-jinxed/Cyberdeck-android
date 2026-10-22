package dev.netrunner.launcher.aska

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.netrunner.launcher.data.Agenda
import dev.netrunner.launcher.notify.NotificationHub
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * What As//ka knows of her runner's day without the model: the morning briefing, a summary
 * of the notifications, and how the phone is doing (battery, the hour). All read on demand,
 * when a screen is shown or a question asked; nothing listens in the background.
 */
object AskaDay {

    // ---- The phone right now.

    data class Battery(val percent: Int, val charging: Boolean)

    /** The last battery broadcast, kept by the system: reading it costs nothing. */
    fun battery(context: Context): Battery? {
        val intent = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(level * 100 / scale, charging)
    }

    enum class Moment { LowBattery, Charging, LateNight }

    /** Something about the phone she would react to, most pressing first. */
    fun moment(context: Context): Moment? {
        val b = battery(context)
        val now = LocalTime.now()
        return when {
            b != null && !b.charging && b.percent <= LOW_BATTERY -> Moment.LowBattery
            now >= LocalTime.of(0, 30) && now < LocalTime.of(5, 0) -> Moment.LateNight
            b != null && b.charging && b.percent < 100 -> Moment.Charging
            else -> null
        }
    }

    /** Her line for that moment; [asleep] when the deck is locked. */
    fun momentLine(context: Context, moment: Moment, asleep: Boolean = false): String {
        val b = battery(context)
        return when (moment) {
            Moment.LowBattery -> if (asleep) "Zzz… ${b?.percent ?: 0} %… j'ai faim d'électrons…"
            else "Batterie à ${b?.percent ?: 0} %… je m'affaiblis, runner. Tu me branches ?"
            Moment.Charging -> if (asleep) "Zzz… ça chatouille, la recharge…"
            else "Mmh, je sens le courant passer… ${b?.percent ?: 0} %, ça fait du bien."
            Moment.LateNight -> if (asleep) "Zzz… il est tard… dors, toi aussi…"
            else "Il est ${LocalTime.now().let { hhmm(it.hour, it.minute) }}… Tu devrais dormir, runner. Je veille sur le deck."
        }
    }

    // ---- The morning briefing.

    /** Today's reminders and events, and the battery. Null when there is nothing to say. */
    fun briefing(context: Context): String? {
        val today = LocalDate.now()
        val parts = mutableListOf<String>()
        val reminders = AskaReminders.upcoming(context).filter { localDate(it.at) == today }
        val events = Agenda.upcoming(context, hours = 24, limit = 6).filter { it.allDay || localDate(it.begin) == today }
        val items = events.map { (if (it.allDay) "journée" else time(it.begin)) to it.title } +
            reminders.map { time(it.at) to "rappel : ${it.text}" }
        if (items.isNotEmpty()) {
            parts += "Au programme aujourd'hui :\n" + items.sortedBy { it.first }.joinToString("\n") { "▸ ${it.first} · ${it.second}" }
        } else {
            parts += "Rien au programme aujourd'hui. Une journée libre !"
        }
        AskaBriefing.todayHint(context)?.let { parts += it }
        battery(context)?.let { b ->
            parts += when {
                b.charging -> "Batterie ${b.percent} %, en charge."
                b.percent <= LOW_BATTERY -> "Batterie ${b.percent} % seulement : pense à me brancher."
                else -> "Batterie ${b.percent} %."
            }
        }
        return parts.joinToString("\n")
    }

    // ---- Notifications.

    /** "Quoi de neuf ?": the waiting notifications, by app. */
    fun news(context: Context): String {
        if (!NotificationHub.isAccessGranted(context)) {
            return "Je n'ai pas accès à tes notifications. Autorise Netrunner dans Réglages › Accès aux notifications et je te ferai le point."
        }
        val items = NotificationHub.items.value.filter { it.packageName != context.packageName }
        if (items.isEmpty()) return "Rien de neuf sur le réseau. Calme plat… héhé, profites-en."
        val byApp = items.groupBy { it.appLabel }.entries.sortedByDescending { it.value.maxOf { n -> n.postTime } }
        val lines = byApp.take(6).map { (app, list) ->
            val latest = list.maxBy { it.postTime }
            val from = latest.title.ifBlank { null }?.let { " · $it" }.orEmpty()
            val count = if (list.size > 1) " (${list.size})" else ""
            "▸ $app$count$from"
        }
        val more = if (byApp.size > 6) "\n…et ${byApp.size - 6} autres applis." else ""
        val total = items.size
        return (if (total == 1) "Une notification t'attend :\n" else "$total notifications t'attendent :\n") + lines.joinToString("\n") + more
    }

    // ---- Understanding the questions.

    sealed class Request {
        object Briefing : Request()
        object News : Request()
        object Battery : Request()
    }

    private val NEWS = Regex("quoi de neuf|du nouveau|mes notifs|mes notifications|(des|j'ai|ai-je) (des )?(messages|notifs|notifications)|fais (moi )?le point sur mes (notifs|notifications|messages)")
    private val BRIEFING = Regex("(programme|planning|agenda) (du jour|d'aujourd'hui|de la journee)|point du (matin|jour)|qu'est-ce que j'ai (de prevu )?aujourd'hui|j'ai quoi aujourd'hui|ma journee|briefing")
    private val BATTERY = Regex("\\b(batterie|autonomie)\\b")

    fun parse(input: String): Request? {
        val t = Normalizer.normalize(input.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
        return when {
            NEWS.containsMatchIn(t) -> Request.News
            BRIEFING.containsMatchIn(t) -> Request.Briefing
            BATTERY.containsMatchIn(t) && t.length < 60 -> Request.Battery
            else -> null
        }
    }

    fun batteryText(context: Context): String {
        val b = battery(context) ?: return "Je n'arrive pas à lire la batterie…"
        return when {
            b.charging -> "Batterie ${b.percent} %, en charge. Je sens le courant !"
            b.percent <= LOW_BATTERY -> "Batterie ${b.percent} %… c'est bas, runner. Branche le deck vite."
            b.percent >= 80 -> "Batterie ${b.percent} %. Pleine forme !"
            else -> "Batterie ${b.percent} %."
        }
    }

    private const val LOW_BATTERY = 15

    private fun localDate(at: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toLocalDate()
    private fun time(at: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).let { hhmm(it.hour, it.minute) }
}
