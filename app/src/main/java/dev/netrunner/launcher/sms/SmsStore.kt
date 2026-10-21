package dev.netrunner.launcher.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager

/** A conversation in the message list: the latest message of one thread. */
data class SmsThread(
    val threadId: Long,
    val address: String,
    val name: String?,
    val snippet: String,
    val date: Long,
    val unread: Int,
    val lastFromMe: Boolean,
)

data class SmsMessage(val id: Long, val body: String, val date: Long, val fromMe: Boolean, val failed: Boolean)

/**
 * Reads and sends SMS through the system message store. The launcher does not replace the default
 * SMS app (which keeps handling MMS, RCS and notifications): it reads the same conversations and
 * sends through the SIM, and Android files sent messages in the shared store.
 */
object SmsStore {
    val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

    fun canRead(context: Context) = granted(context, Manifest.permission.READ_SMS)
    fun canSend(context: Context) = granted(context, Manifest.permission.SEND_SMS)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun threads(context: Context, limit: Int = 60): List<SmsThread> {
        if (!canRead(context)) return emptyList()
        val latest = LinkedHashMap<Long, SmsThread>()
        val unread = HashMap<Long, Int>()
        val names = HashMap<String, String?>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val thread = c.getLong(0)
                    if (c.getInt(5) == 0 && c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX) unread[thread] = (unread[thread] ?: 0) + 1
                    if (thread in latest) continue
                    if (latest.size >= limit) continue
                    val address = c.getString(1) ?: continue
                    latest[thread] = SmsThread(
                        threadId = thread,
                        address = address,
                        name = names.getOrPut(address) { contactName(context, address) },
                        snippet = c.getString(2).orEmpty(),
                        date = c.getLong(3),
                        unread = 0,
                        lastFromMe = c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    )
                }
            }
        }
        return latest.values.map { it.copy(unread = unread[it.threadId] ?: 0) }
    }

    fun messages(context: Context, threadId: Long, limit: Int = 300): List<SmsMessage> {
        if (!canRead(context)) return emptyList()
        val out = ArrayList<SmsMessage>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val type = c.getInt(3)
                    out += SmsMessage(
                        id = c.getLong(0),
                        body = c.getString(1).orEmpty(),
                        date = c.getLong(2),
                        fromMe = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                        failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                    )
                }
            }
        }
        return out.asReversed()
    }

    fun contactName(context: Context, number: String): String? {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** Sends through the default SIM; long texts are split into parts. */
    fun send(context: Context, address: String, body: String): Boolean {
        if (!canSend(context) || body.isBlank()) return false
        return runCatching {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = sms.divideMessage(body)
            if (parts.size > 1) sms.sendMultipartTextMessage(address, null, parts, null, null)
            else sms.sendTextMessage(address, null, body, null, null)
            true
        }.getOrDefault(false)
    }

    /** Calls [onChange] whenever the message store changes; returns the unregister action. */
    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        if (!canRead(context)) return {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        runCatching { context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }
        return { context.contentResolver.unregisterContentObserver(observer) }
    }
}
