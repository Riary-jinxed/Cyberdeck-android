package dev.netrunner.launcher.aska

import android.app.AlarmManager
import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * As//ka sleeps while the deck is locked and wakes up with her runner: once the morning alarm
 * has rung (or, without an alarm, at the first look of the morning), she greets them once.
 * No background work: the next alarm is noted whenever the lock or home screen is shown.
 */
object AskaMorning {

    private const val PREFS = "netrunner"
    private const val KEY_DUE = "aska_alarm_due"
    private const val KEY_DONE = "aska_morning_done"

    /** How long after the alarm her morning greeting stays up. */
    private const val WINDOW_MS = 3 * 60 * 60 * 1000L
    private val MORNING_ALARM = LocalTime.of(4, 0)..LocalTime.of(12, 0)
    private val MORNING_NO_ALARM = LocalTime.of(5, 0)..LocalTime.of(10, 30)

    /** Notes the next alarm, without forgetting one that just rang. */
    fun observe(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val due = prefs.getLong(KEY_DUE, 0L)
        if (due in (now - WINDOW_MS)..now) return
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: 0L
        if (next != due) prefs.edit().putLong(KEY_DUE, next).apply()
    }

    /** True when she should greet her runner for the morning. */
    fun isMorning(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        if (prefs.getLong(KEY_DONE, -1L) == today.toEpochDay()) return false
        val now = System.currentTimeMillis()
        val due = prefs.getLong(KEY_DUE, 0L)
        if (due in (now - WINDOW_MS)..now) {
            val rang = LocalDateTime.ofInstant(Instant.ofEpochMilli(due), ZoneId.systemDefault())
            return rang.toLocalDate() == today && rang.toLocalTime() in MORNING_ALARM
        }
        // No alarm rang this morning: the first look of the morning will do.
        val ringsToday = due > now && LocalDateTime.ofInstant(Instant.ofEpochMilli(due), ZoneId.systemDefault())
            .let { it.toLocalDate() == today && it.toLocalTime() in MORNING_ALARM }
        return !ringsToday && LocalTime.now() in MORNING_NO_ALARM
    }

    fun done(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DONE, LocalDate.now().toEpochDay()).apply()
    }

    /** Her first words of the day, to start a conversation. */
    fun greeting(): String {
        val day = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH))
        val lines = listOf(
            "Debout, runner ! J'ai veillé sur le deck toute la nuit… Tu as bien dormi ?",
            "Bonjour ! *bâille* Je rêvais d'un macaron rose géant. Et toi, tu as rêvé de quoi ?",
            "Héhé, te voilà réveillé ! On est $day. Qu'est-ce qu'on fait de beau aujourd'hui ?",
            "Euh… bonjour. Je t'attendais. Un peu. Tu as un programme pour la journée ?",
            "Matin détecté ! Café pour toi, pâtisserie pour moi ? Raconte-moi ce qui t'attend aujourd'hui.",
            "Bonjour runner ! Systèmes au vert, dragonne en ligne. Comment tu te sens ce matin ?",
        )
        return lines[(LocalDate.now().toEpochDay() % lines.size).toInt()]
    }
}
