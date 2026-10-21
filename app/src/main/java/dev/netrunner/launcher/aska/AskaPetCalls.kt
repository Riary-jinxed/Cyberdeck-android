package dev.netrunner.launcher.aska

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.MainActivity
import dev.netrunner.launcher.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * When her runner neglects her, As//ka calls: one notification a day at most, during the day.
 * A single inexact alarm, set again each time her state is saved, at the hour her needs
 * should run low. Nothing else runs in the background.
 */
object AskaPetCalls {

    private const val CHANNEL = "aska_pet"
    private const val NOTIFICATION_ID = 41_999
    private const val KEY_DAY = "aska_pet_called"
    private const val ACTION = "dev.netrunner.launcher.aska.PET_CALL"
    private val DAY = LocalTime.of(10, 0)..LocalTime.of(21, 0)

    /** Set when her notification is touched: her screen opens on her nest. */
    var openNest by mutableStateOf(false)

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context)
        val prefs = prefs(context)
        val s = AskaPet.projected(context)
        if (!prefs.getBoolean(AskaPet.KEY_CALLS, true) || s == null) {
            am.cancel(pending)
            return
        }
        // Hours until she is hungry or bored (asleep, only hunger, and slowly).
        val hungry = ((s.food - LOW_FOOD) / if (s.asleep) 2f else 6f).coerceAtLeast(0f)
        val bored = if (s.asleep) Float.MAX_VALUE else ((s.joy - LOW_JOY) / 5f).coerceAtLeast(0f)
        val hours = minOf(hungry, bored).coerceAtLeast(0.5f)
        var at = LocalDateTime.now().plusMinutes((hours * 60).toLong())
        // Never twice the same day, never at night.
        val calledToday = prefs.getLong(KEY_DAY, -1L) == LocalDate.now().toEpochDay()
        if (calledToday && at.toLocalDate() == LocalDate.now()) at = LocalDate.now().plusDays(1).atTime(DAY.start)
        if (at.toLocalTime() < DAY.start) at = at.toLocalDate().atTime(DAY.start)
        if (at.toLocalTime() > DAY.endInclusive) at = at.toLocalDate().plusDays(1).atTime(DAY.start)
        am.set(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending)
    }

    internal fun call(context: Context) {
        val prefs = prefs(context)
        val today = LocalDate.now().toEpochDay()
        val s = AskaPet.projected(context)
        if (s == null || !prefs.getBoolean(AskaPet.KEY_CALLS, true) || prefs.getLong(KEY_DAY, -1L) == today) return schedule(context)
        val text = when {
            s.food < LOW_FOOD + 5 -> "Mon estomac fait des bruits de modem… tu m'apportes un macaron ?"
            s.joy < LOW_JOY + 5 && !s.asleep -> "Je m'ennuie dans ce deck… on fait une partie d'attrape-macarons ?"
            else -> return schedule(context)
        }
        if (notify(context, text)) prefs.edit().putLong(KEY_DAY, today).apply()
        schedule(context)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, text: String): Boolean {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return false
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "As//ka te réclame", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PANEL, "nest")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aska)
            .setColor(0xFFFF4FA3.toInt())
            .setContentTitle("As//ka")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context,
        NOTIFICATION_ID,
        Intent(context, AskaPetCallReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val LOW_FOOD = 25f
    private const val LOW_JOY = 20f

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}

class AskaPetCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AskaPetCalls.call(context)
}
