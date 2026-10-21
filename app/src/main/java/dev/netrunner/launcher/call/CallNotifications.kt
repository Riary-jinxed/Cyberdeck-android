package dev.netrunner.launcher.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.R

object CallNotifications {

    private const val CHANNEL_INCOMING = "holocall_incoming"
    private const val CHANNEL_ONGOING = "holocall_ongoing"
    private const val NOTIFICATION_ID = 7077
    private const val YELLOW = 0xFFFCEE0A.toInt()

    /**
     * True while the holocall screen is in front. The ringing card is then posted silently,
     * otherwise Android drops a heads-up card on top of our own screen.
     */
    @Volatile
    var screenVisible = false

    const val ACTION_ANSWER = "dev.netrunner.launcher.call.ANSWER"
    const val ACTION_DECLINE = "dev.netrunner.launcher.call.DECLINE"
    const val ACTION_HANG_UP = "dev.netrunner.launcher.call.HANG_UP"

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Appels entrants", NotificationManager.IMPORTANCE_HIGH).apply {
                // Telecom already plays the ringtone.
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Appel en cours", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission")
    fun update(context: Context, calls: List<CallInfo>) {
        val nm = NotificationManagerCompat.from(context)
        val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val main = calls.firstOrNull { it.ringing } ?: calls.firstOrNull()
        if (main == null || !allowed) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannels(context)
        val name = main.displayName ?: main.number.ifBlank { "Numéro masqué" }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val headsUp = main.ringing && !screenVisible
        val builder = NotificationCompat.Builder(context, if (headsUp) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_holocall)
            .setColor(YELLOW)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentTitle(name)

        if (main.ringing && !headsUp) {
            builder
                .setContentText("Holo-appel entrant")
                .setSilent(true)
                .setOnlyAlertOnce(true)
        } else if (main.ringing) {
            val caller = Person.Builder().setName(name).setImportant(true).build()
            builder
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(open, true)
                .setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        caller,
                        action(context, ACTION_DECLINE, 1),
                        action(context, ACTION_ANSWER, 2),
                    )
                )
        } else {
            builder
                .setContentText(if (main.onHold) "En attente" else "Holo-appel en cours")
                .setUsesChronometer(main.connectTimeMillis > 0)
                .setWhen(if (main.connectTimeMillis > 0) main.connectTimeMillis else System.currentTimeMillis())
                .addAction(0, "Raccrocher", action(context, ACTION_HANG_UP, 3))
        }
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

/** Answer / decline / hang up from the notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = CallManager.calls.value
        when (intent.action) {
            CallNotifications.ACTION_ANSWER -> calls.firstOrNull { it.ringing }?.let {
                CallManager.answer(it)
                context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            CallNotifications.ACTION_DECLINE -> calls.firstOrNull { it.ringing }?.let { CallManager.reject(it) }
            CallNotifications.ACTION_HANG_UP -> calls.firstOrNull { !it.ringing }?.let { CallManager.hangUp(it) }
        }
    }
}
