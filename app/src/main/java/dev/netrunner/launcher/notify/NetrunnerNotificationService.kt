package dev.netrunner.launcher.notify

import android.app.Notification
import android.content.BroadcastReceiver
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.netrunner.launcher.lock.CyberLock
import dev.netrunner.launcher.system.BatteryLog
import dev.netrunner.launcher.system.Ice

/**
 * Mirrors the phone's notifications into the HUD. Being an enabled listener is also
 * what lets the launcher read and control media sessions (Spotify widget).
 */
class NetrunnerNotificationService : NotificationListenerService() {

    private val labels = mutableMapOf<String, String>()
    private val icons = mutableMapOf<String, ImageBitmap?>()

    private var screenOff: BroadcastReceiver? = null
    private var battery: BroadcastReceiver? = null

    override fun onListenerConnected() {
        NotificationHub.attach(this)
        if (screenOff == null) screenOff = CyberLock.register(this)
        if (battery == null) battery = BatteryLog.register(this)
        Ice.start(this)
        refresh()
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(pending)
        NotificationHub.detach(this)
        screenOff?.let { runCatching { unregisterReceiver(it) } }
        screenOff = null
        battery?.let { runCatching { unregisterReceiver(it) } }
        battery = null
        Ice.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refreshSoon()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = refreshSoon()

    // Players and downloads repost their notification several times a second: one rebuild of
    // the feed per burst is enough.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pending = Runnable { refresh() }

    private fun refreshSoon() {
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, 250)
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationHub.publish(
            active.mapNotNull { toItem(it) }.sortedByDescending { it.postTime }
        )
    }

    private fun toItem(sbn: StatusBarNotification): FeedItem? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        // Persistent notifications (music, downloads, system) stay out of the feed, calls excepted.
        if (ongoing && n.category != Notification.CATEGORY_CALL) return null

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val pkg = sbn.packageName
        val kind = when {
            n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL -> FeedKind.Call
            n.category == Notification.CATEGORY_EMAIL || pkg in MAIL_APPS -> FeedKind.Mail
            n.category == Notification.CATEGORY_MESSAGE || pkg in MESSAGE_APPS -> FeedKind.Message
            else -> FeedKind.Other
        }
        val reply = n.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

        return FeedItem(
            key = sbn.key,
            packageName = pkg,
            appLabel = labelOf(pkg),
            title = title,
            text = text,
            postTime = sbn.postTime,
            kind = kind,
            icon = iconOf(pkg),
            contentIntent = n.contentIntent,
            replyAction = reply,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            clearable = sbn.isClearable,
        )
    }

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun iconOf(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull()
    }

    private companion object {
        val MESSAGE_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.android.mms",
            "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.instagram.android", "com.discord", "com.snapchat.android",
        )
        val MAIL_APPS = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.android.email",
            "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "com.samsung.android.email.provider",
        )
    }
}
