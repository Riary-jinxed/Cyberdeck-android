package dev.netrunner.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.netrunner.clock.data.AlarmStore
import dev.netrunner.clock.data.TimerStore

/** Alarm and timer rings, timer notification buttons, and rescheduling after boot or a clock change. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        when (intent.action) {
            Scheduler.ACTION_ALARM -> {
                val alarm = AlarmStore.get(context, id) ?: return
                if (!alarm.enabled) return
                Clockwork.afterRing(context, alarm)
                RingService.ring(context, RingService.Kind.Alarm, alarm.id, alarm.label, alarm.tone, alarm.vibrate)
            }
            Scheduler.ACTION_TIMER -> {
                val timer = TimerStore.get(context, id) ?: return
                Clockwork.saveTimer(context, timer.reset())
                Notifs.cancelTimer(context, id)
                RingService.ring(context, RingService.Kind.Timer, timer.id, timer.label, null, true)
            }
            ACTION_TIMER_TOGGLE -> Clockwork.toggleTimer(context, id)
            ACTION_TIMER_PLUS -> Clockwork.addMinute(context, id)
            ACTION_TIMER_CANCEL -> Clockwork.deleteTimer(context, id)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                Scheduler.rescheduleAll(context)
                TimerStore.all(context).forEach { Notifs.timer(context, it) }
            }
        }
    }

    companion object {
        const val ACTION_TIMER_TOGGLE = "dev.netrunner.clock.TIMER_TOGGLE"
        const val ACTION_TIMER_PLUS = "dev.netrunner.clock.TIMER_PLUS"
        const val ACTION_TIMER_CANCEL = "dev.netrunner.clock.TIMER_CANCEL"
    }
}
