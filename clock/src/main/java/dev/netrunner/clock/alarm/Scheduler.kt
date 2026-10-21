package dev.netrunner.clock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.netrunner.clock.data.Alarm
import dev.netrunner.clock.data.AlarmStore
import dev.netrunner.clock.data.TimerStore
import dev.netrunner.clock.ui.MainActivity

/**
 * Alarms go through AlarmManager.setAlarmClock: exact, allowed in Doze, shown in the status bar,
 * and reported as the phone's "next alarm" (what As//ka and her morning card read).
 * Timers use plain exact alarms, so they never pass for the morning alarm.
 */
object Scheduler {
    const val ACTION_ALARM = "dev.netrunner.clock.ALARM_FIRE"
    const val ACTION_TIMER = "dev.netrunner.clock.TIMER_FIRE"
    const val EXTRA_ID = "id"
    private const val TIMER_CODE = 100_000

    private fun am(context: Context) = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(context).canScheduleExactAlarms()

    fun schedule(context: Context, alarm: Alarm) {
        val fire = firePending(context, alarm.id)
        am(context).cancel(fire)
        val at = alarm.nextTrigger() ?: return
        val show = PendingIntent.getActivity(
            context, alarm.id,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ALARMS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am(context).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } catch (_: SecurityException) {
            // Exact alarms refused (Android 12 with the permission revoked): still ring, a bit late at worst.
            am(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    fun cancel(context: Context, id: Int) = am(context).cancel(firePending(context, id))

    /** After boot, a clock change or an update: everything again from the saved state. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        AlarmStore.all(context).forEach { a ->
            var alarm = a
            if (alarm.snoozedUntil in 1..now) {
                // A snooze that came due while the phone was off.
                alarm = alarm.copy(snoozedUntil = 0L, enabled = alarm.repeats && alarm.enabled)
                AlarmStore.save(context, alarm)
            }
            schedule(context, alarm)
        }
        TimerStore.all(context).filter { it.running }.forEach { t ->
            scheduleTimer(context, t.id, t.endAt.coerceAtLeast(now + 1000))
        }
    }

    fun scheduleTimer(context: Context, id: Int, endAt: Long) {
        val fire = timerPending(context, id)
        try {
            am(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, fire)
        } catch (_: SecurityException) {
            am(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, fire)
        }
    }

    fun cancelTimer(context: Context, id: Int) = am(context).cancel(timerPending(context, id))

    private fun firePending(context: Context, id: Int) = PendingIntent.getBroadcast(
        context, id,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun timerPending(context: Context, id: Int) = PendingIntent.getBroadcast(
        context, TIMER_CODE + id,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_TIMER).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
