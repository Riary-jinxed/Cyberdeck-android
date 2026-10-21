package dev.netrunner.clock.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import dev.netrunner.clock.R
import dev.netrunner.clock.data.CountdownTimer
import dev.netrunner.clock.ui.MainActivity
import dev.netrunner.clock.ui.RingActivity
import dev.netrunner.clock.ui.formatDuration

object Notifs {
    const val CH_RING = "ring"
    private const val CH_TIMERS = "timers"
    private const val CH_MISSED = "missed"
    const val ID_RING = 1
    private const val ID_MISSED = 2
    private const val ID_TIMER = 1000

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun channels(context: Context) {
        val nm = nm(context)
        if (nm.getNotificationChannel(CH_RING) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CH_RING, "Alarmes qui sonnent", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Écran de réveil plein écran. Le son est joué par l'appli."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMERS, "Minuteurs en cours", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Alarmes manquées", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** The ringing notification: opens the wake screen over the lock screen. */
    fun ring(context: Context, title: String, text: String, isAlarm: Boolean): Notification {
        channels(context)
        val full = PendingIntent.getActivity(
            context, 0, Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snoozeLabel = if (isAlarm) "Répit" else "+1 min"
        return Notification.Builder(context, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(Color.parseColor("#FCEE0A"))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, snoozeLabel, service(context, RingService.ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "Arrêter", service(context, RingService.ACTION_DISMISS)).build())
            .build()
    }

    private fun service(context: Context, action: String) = PendingIntent.getService(
        context, action.hashCode(), Intent(context, RingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun missed(context: Context, title: String) {
        channels(context)
        nm(context).notify(
            ID_MISSED,
            Notification.Builder(context, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("Alarme manquée")
                .setContentText("$title a sonné sans réponse.")
                .setContentIntent(openApp(context, MainActivity.TAB_ALARMS))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Countdown notification for a started timer, updated only on changes (the system ticks it). */
    fun timer(context: Context, t: CountdownTimer) {
        val started = t.running || t.remainingMs < t.totalMs
        if (!started) return cancelTimer(context, t.id)
        channels(context)
        val title = t.label.ifEmpty { "Minuteur ${formatDuration(t.totalMs)}" }
        val b = Notification.Builder(context, CH_TIMERS)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(Color.parseColor("#00F0FF"))
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, MainActivity.TAB_TIMER))
        if (t.running) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.endAt).setShowWhen(true)
                .setContentText("En cours")
        } else {
            b.setContentText("En pause · reste ${formatDuration(t.remainingMs)}")
        }
        b.addAction(Notification.Action.Builder(null, if (t.running) "Pause" else "Reprendre", timerAction(context, AlarmReceiver.ACTION_TIMER_TOGGLE, t.id)).build())
        b.addAction(Notification.Action.Builder(null, "+1 min", timerAction(context, AlarmReceiver.ACTION_TIMER_PLUS, t.id)).build())
        b.addAction(Notification.Action.Builder(null, "Annuler", timerAction(context, AlarmReceiver.ACTION_TIMER_CANCEL, t.id)).build())
        nm(context).notify(ID_TIMER + t.id, b.build())
    }

    fun cancelTimer(context: Context, id: Int) = nm(context).cancel(ID_TIMER + id)

    private fun timerAction(context: Context, action: String, id: Int) = PendingIntent.getBroadcast(
        context, action.hashCode() * 31 + id,
        Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(Scheduler.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(context: Context, tab: Int) = PendingIntent.getActivity(
        context, 10 + tab, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
