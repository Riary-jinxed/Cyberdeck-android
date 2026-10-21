package dev.netrunner.clock.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import dev.netrunner.clock.alarm.Clockwork
import dev.netrunner.clock.alarm.RingService
import dev.netrunner.clock.data.AlarmStore

/**
 * Answers the standard AlarmClock intents (the ones As//ka, Assistant or other apps send), so
 * they land in this clock once it is chosen as the default. No UI of its own.
 */
class IntentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val skipUi = i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
        when (i.action) {
            AlarmClock.ACTION_SET_ALARM -> setAlarm(i, skipUi)
            AlarmClock.ACTION_DISMISS_ALARM -> dismissAlarm(i)
            AlarmClock.ACTION_SNOOZE_ALARM -> RingService.command(this, RingService.ACTION_SNOOZE)
            AlarmClock.ACTION_SET_TIMER -> setTimer(i, skipUi)
            AlarmClock.ACTION_SHOW_TIMERS -> open(MainActivity.TAB_TIMER)
            else -> open(MainActivity.TAB_ALARMS)
        }
        finish()
    }

    private fun setAlarm(i: Intent, skipUi: Boolean) {
        if (!i.hasExtra(AlarmClock.EXTRA_HOUR)) return open(MainActivity.TAB_ALARMS, newAlarm = true)
        val hour = i.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23)
        val minute = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
        val days = i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.toSet() ?: emptySet()
        val label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
        val vibrate = i.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true)
        // Same time and days already set: switch it on rather than adding a twin.
        val twin = AlarmStore.all(this).firstOrNull { it.hour == hour && it.minute == minute && it.days == days }
        val alarm = if (twin != null) {
            Clockwork.saveAlarm(this, twin.copy(enabled = true, label = label.ifEmpty { twin.label }))
            twin
        } else {
            Clockwork.newAlarm(this, hour, minute, days, label, vibrate)
        }
        val at = AlarmStore.get(this, alarm.id)?.nextTrigger()
        toast("Alarme ${hhmm(hour, minute)} armée" + (at?.let { ", ${untilLabel(it - System.currentTimeMillis())}" } ?: ""))
        if (!skipUi) open(MainActivity.TAB_ALARMS)
    }

    private fun dismissAlarm(i: Intent) {
        val mode = i.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE)
        if (mode == null && RingService.ringing.value != null) {
            RingService.command(this, RingService.ACTION_DISMISS)
            return
        }
        val alarms = AlarmStore.all(this).filter { it.enabled }
        val matches = when (mode) {
            AlarmClock.ALARM_SEARCH_MODE_TIME -> {
                val h = i.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                val m = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
                alarms.filter { it.hour == h && it.minute == m }
            }
            AlarmClock.ALARM_SEARCH_MODE_NEXT -> listOfNotNull(alarms.minByOrNull { it.nextTrigger() ?: Long.MAX_VALUE })
            AlarmClock.ALARM_SEARCH_MODE_LABEL -> {
                val label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
                alarms.filter { label.isNotEmpty() && it.label.equals(label, ignoreCase = true) }
            }
            AlarmClock.ALARM_SEARCH_MODE_ALL -> alarms
            else -> emptyList()
        }
        if (matches.isEmpty()) {
            toast("Aucune alarme correspondante.")
            return open(MainActivity.TAB_ALARMS)
        }
        matches.forEach { Clockwork.setEnabled(this, it, false) }
        toast(if (matches.size == 1) "Alarme ${hhmm(matches[0].hour, matches[0].minute)} désactivée" else "${matches.size} alarmes désactivées")
    }

    private fun setTimer(i: Intent, skipUi: Boolean) {
        val seconds = i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        if (seconds <= 0) return open(MainActivity.TAB_TIMER)
        Clockwork.newTimer(this, seconds.coerceAtMost(24 * 3600), i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty())
        toast("Minuteur ${formatDuration(seconds * 1000L)} lancé")
        if (!skipUi) open(MainActivity.TAB_TIMER)
    }

    private fun open(tab: Int, newAlarm: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, tab)
                .putExtra(MainActivity.EXTRA_NEW_ALARM, newAlarm)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}
