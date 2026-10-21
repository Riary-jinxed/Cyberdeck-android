package dev.netrunner.launcher.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FeedKind { Message, Mail, Call, Other }

data class FeedItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val kind: FeedKind,
    val icon: ImageBitmap?,
    val contentIntent: PendingIntent?,
    val replyAction: Notification.Action?,
    val autoCancel: Boolean,
    val clearable: Boolean,
)

/** Shared state between the notification listener service and the HUD. */
object NotificationHub {

    private val _items = MutableStateFlow<List<FeedItem>>(emptyList())
    val items: StateFlow<List<FeedItem>> = _items.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var service: NetrunnerNotificationService? = null

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun openAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the conversation / mail behind a notification, or the app as a fallback. */
    fun open(context: Context, item: FeedItem) {
        val sent = item.contentIntent?.let { intent ->
            runCatching {
                intent.send(context, 0, null, null, null, null, launchOptions())
                true
            }.getOrDefault(false)
        } ?: false
        if (!sent) {
            context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (item.autoCancel) dismiss(item)
    }

    fun dismiss(item: FeedItem) {
        runCatching { service?.cancelNotification(item.key) }
    }

    fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
    }

    /** Sends an inline reply through the app's own reply action (WhatsApp, Messages, Gmail...). */
    fun reply(context: Context, item: FeedItem, text: String): Boolean {
        val action = item.replyAction ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching {
            action.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun launchOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ only lets another app's PendingIntent open an activity when the sender opts in.
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }

    internal fun attach(listener: NetrunnerNotificationService) {
        service = listener
        _connected.value = true
    }

    internal fun detach(listener: NetrunnerNotificationService) {
        if (service === listener) service = null
        _connected.value = false
    }

    internal fun publish(list: List<FeedItem>) {
        _items.value = list
    }
}
