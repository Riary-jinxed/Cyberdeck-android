package dev.netrunner.launcher.aska

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.MainActivity
import dev.netrunner.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something As//ka reminds her runner of, at a given time. */
data class Reminder(val id: Long, val at: Long, val text: String, val done: Boolean = false) {
    /** "aujourd'hui 15:00", "demain 09:30", "lundi 3 novembre 18:00". */
    val whenLabel: String get() = dayLabel(at) + " " + LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).let { hhmm(it.hour, it.minute) }
}

/**
 * Her reminders: kept in the launcher's preferences, each one an exact alarm that posts a
 * notification at its time. Nothing runs in between. The Agenda app reads them through
 * [AskaRemindersProvider].
 */
object AskaReminders {

    private const val KEY = "aska_reminders"
    private const val CHANNEL = "aska_reminders"
    private const val PINK = 0xFFFF4FA3.toInt()
    const val ACTION_FIRE = "dev.netrunner.launcher.aska.REMINDER_FIRE"
    const val ACTION_SNOOZE = "dev.netrunner.launcher.aska.REMINDER_SNOOZE"
    const val ACTION_DONE = "dev.netrunner.launcher.aska.REMINDER_DONE"
    const val EXTRA_ID = "id"
    private const val SNOOZE_MS = 10 * 60_000L

    /** Bumped on each change, for the screens that show them. */
    val revision = mutableIntStateOf(0)

    fun list(context: Context): List<Reminder> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY, null) ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Reminder(o.getLong("id"), o.getLong("at"), o.getString("text"), o.optBoolean("done"))
        }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    /** Those still to come (or just rung, not yet dismissed), soonest first. */
    fun upcoming(context: Context): List<Reminder> = list(context).filter { !it.done }

    fun next(context: Context): Reminder? = upcoming(context).firstOrNull()

    fun add(context: Context, at: Long, text: String): Reminder {
        val reminder = Reminder(System.currentTimeMillis(), at, text)
        save(context, list(context).filter { !it.done || it.at > System.currentTimeMillis() - KEEP_DONE_MS } + reminder)
        schedule(context, reminder)
        return reminder
    }

    fun remove(context: Context, id: Long) {
        cancelAlarm(context, id)
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        save(context, list(context).filter { it.id != id })
    }

    private fun markDone(context: Context, id: Long) =
        save(context, list(context).map { if (it.id == id) it.copy(done = true) else it })

    /** After a reboot or an update: alarms are lost, set them again; the ones missed ring now. */
    fun rescheduleAll(context: Context) {
        upcoming(context).forEach { schedule(context, it) }
    }

    // ---- Alarms.

    @SuppressLint("MissingPermission")
    private fun schedule(context: Context, reminder: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = reminder.at.coerceAtLeast(System.currentTimeMillis() + 1_000)
        val pending = firePending(context, reminder.id)
        // Exact when allowed (Android 13+ grants it to apps that set reminders), within minutes otherwise.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancelAlarm(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java)?.cancel(firePending(context, id))
    }

    private fun firePending(context: Context, id: Long) = PendingIntent.getBroadcast(
        context,
        requestCode(id),
        Intent(context, AskaReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- Notification.

    internal fun fire(context: Context, id: Long) {
        val reminder = list(context).firstOrNull { it.id == id && !it.done } ?: return
        markDone(context, id)
        notify(context, reminder)
    }

    internal fun snooze(context: Context, id: Long) {
        val reminder = list(context).firstOrNull { it.id == id } ?: return
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        save(context, list(context).filter { it.id != id })
        add(context, System.currentTimeMillis() + SNOOZE_MS, reminder.text)
    }

    internal fun dismiss(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        markDone(context, id)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, reminder: Reminder) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Rappels d'As//ka", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context,
            requestCode(reminder.id),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PANEL, "aska")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(name: String) = PendingIntent.getBroadcast(
            context,
            requestCode(reminder.id) + name.hashCode(),
            Intent(context, AskaReminderReceiver::class.java).setAction(name).putExtra(EXTRA_ID, reminder.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aska)
            .setColor(PINK)
            .setContentTitle("As//ka · rappel")
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Runner, n'oublie pas : ${reminder.text}"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "+10 MIN", action(ACTION_SNOOZE))
            .addAction(0, "FAIT", action(ACTION_DONE))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(reminder.id), notification)
    }

    // ---- Storage.

    private fun save(context: Context, reminders: List<Reminder>) {
        val array = JSONArray()
        reminders.forEach {
            array.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("done", it.done))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
        revision.intValue++
        context.contentResolver.notifyChange(AskaRemindersProvider.URI, null)
    }

    private fun requestCode(id: Long) = (id xor (id ushr 32)).toInt()
    private fun notificationId(id: Long) = 41_000 + (requestCode(id) and 0xFFFF)

    // Rung reminders stay visible a day (the Agenda can show them), then go.
    private const val KEEP_DONE_MS = 24 * 3_600_000L

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}

internal fun dayLabel(at: Long): String {
    val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "aujourd'hui"
        today.plusDays(1) -> "demain"
        today.plusDays(2) -> "après-demain"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.isBefore(today.plusDays(7))) "EEEE" else "EEEE d MMMM", Locale.FRENCH))
    }
}

/** Reminder time, snooze and "done", from the alarm and the notification's buttons. */
class AskaReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AskaReminders.EXTRA_ID, -1L).takeIf { it >= 0 } ?: return
        when (intent.action) {
            AskaReminders.ACTION_FIRE -> AskaReminders.fire(context, id)
            AskaReminders.ACTION_SNOOZE -> AskaReminders.snooze(context, id)
            AskaReminders.ACTION_DONE -> AskaReminders.dismiss(context, id)
        }
    }
}

/** Alarms do not survive a reboot or an update of the app: sets them again. */
class AskaReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AskaReminders.rescheduleAll(context)
        }
    }
}

/**
 * Read-only view of her reminders for the Agenda app (same signing key only):
 * content://dev.netrunner.launcher.reminders/reminders, columns _id, at (epoch ms), text, done (0/1).
 */
class AskaRemindersProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "dev.netrunner.launcher.reminders"
        val URI: Uri = Uri.parse("content://$AUTHORITY/reminders")
        private val COLUMNS = arrayOf("_id", "at", "text", "done")
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        AskaReminders.list(context).forEach { cursor.addRow(arrayOf<Any>(it.id, it.at, it.text, if (it.done) 1 else 0)) }
        cursor.setNotificationUri(context.contentResolver, URI)
        return cursor
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.dev.netrunner.reminder"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
