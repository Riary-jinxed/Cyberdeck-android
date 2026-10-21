package dev.netrunner.clock.alarm

import android.content.Context
import dev.netrunner.clock.data.Alarm
import dev.netrunner.clock.data.AlarmStore
import dev.netrunner.clock.data.CountdownTimer
import dev.netrunner.clock.data.TimerStore

/** Every change to alarms and timers goes through here, so storage, scheduling and notifications agree. */
object Clockwork {

    fun saveAlarm(context: Context, alarm: Alarm) {
        AlarmStore.save(context, alarm)
        Scheduler.schedule(context, alarm)
    }

    fun deleteAlarm(context: Context, id: Int) {
        Scheduler.cancel(context, id)
        AlarmStore.delete(context, id)
    }

    fun setEnabled(context: Context, alarm: Alarm, enabled: Boolean) =
        saveAlarm(context, alarm.copy(enabled = enabled, snoozedUntil = 0L))

    fun newAlarm(context: Context, hour: Int, minute: Int, days: Set<Int> = emptySet(), label: String = "", vibrate: Boolean = true): Alarm {
        val alarm = Alarm(AlarmStore.newId(context), hour, minute, days, label, vibrate = vibrate)
        saveAlarm(context, alarm)
        return alarm
    }

    /** The alarm just rang: line up its next ring (or switch it off when it only rings once). */
    fun afterRing(context: Context, alarm: Alarm) {
        val next = if (alarm.repeats) alarm.copy(snoozedUntil = 0L) else alarm.copy(enabled = false, snoozedUntil = 0L)
        saveAlarm(context, next)
    }

    fun snooze(context: Context, id: Int, minutes: Int) {
        val alarm = AlarmStore.get(context, id) ?: return
        saveAlarm(context, alarm.copy(enabled = true, snoozedUntil = System.currentTimeMillis() + minutes * 60_000L))
    }

    // ---- Timers

    fun newTimer(context: Context, seconds: Int, label: String = "", start: Boolean = true): CountdownTimer {
        var t = CountdownTimer(TimerStore.newId(context), seconds * 1000L, label)
        if (start) t = t.start()
        saveTimer(context, t)
        return t
    }

    fun saveTimer(context: Context, t: CountdownTimer) {
        TimerStore.save(context, t)
        if (t.running) Scheduler.scheduleTimer(context, t.id, t.endAt) else Scheduler.cancelTimer(context, t.id)
        Notifs.timer(context, t)
    }

    fun deleteTimer(context: Context, id: Int) {
        Scheduler.cancelTimer(context, id)
        TimerStore.delete(context, id)
        Notifs.cancelTimer(context, id)
    }

    fun toggleTimer(context: Context, id: Int) {
        val t = TimerStore.get(context, id) ?: return
        saveTimer(context, if (t.running) t.pause() else t.start())
    }

    fun addMinute(context: Context, id: Int) {
        val t = TimerStore.get(context, id) ?: return
        saveTimer(context, t.plus(60_000L))
    }
}
