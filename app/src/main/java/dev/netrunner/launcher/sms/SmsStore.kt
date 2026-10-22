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
 *
 * Only the default SMS app may mark messages read or delete them, so "read" and "hidden" are
 * kept by Netrunner itself: per conversation, the date up to which it was seen, and the date it
 * was hidden at (a newer message brings it back).
 */
object SmsStore {
    val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

    fun canRead(context: Context) = granted(context, Manifest.permission.READ_SMS)
    fun canSend(context: Context) = granted(context, Manifest.permission.SEND_SMS)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner_sms", Context.MODE_PRIVATE)

    private fun seenUpTo(context: Context, threadId: Long) = prefs(context).getLong("seen_$threadId", 0L)

    private fun hiddenAt(context: Context, threadId: Long) = prefs(context).getLong("hidden_$threadId", 0L)

    /** Everything in the conversation up to [date] counts as read in Netrunner. */
    fun markSeen(context: Context, threadId: Long, date: Long) {
        if (threadId < 0 || date <= seenUpTo(context, threadId)) return
        prefs(context).edit().putLong("seen_$threadId", date).apply()
    }

    fun markAllSeen(context: Context, threads: List<SmsThread>) {
        val edit = prefs(context).edit()
        threads.forEach { edit.putLong("seen_${it.threadId}", maxOf(it.date, seenUpTo(context, it.threadId))) }
        edit.apply()
    }

    /** Hides the conversation from the list until a message newer than [date] arrives. */
    fun hide(context: Context, threadId: Long, date: Long) {
        prefs(context).edit().putLong("hidden_$threadId", date).putLong("seen_$threadId", maxOf(date, seenUpTo(context, threadId))).apply()
    }

    fun unhide(context: Context, threadId: Long) {
        prefs(context).edit().remove("hidden_$threadId").apply()
    }

    fun isHidden(context: Context, t: SmsThread) = t.date <= hiddenAt(context, t.threadId)

    /** Conversations, newest first; hidden ones are left out unless [includeHidden]. */
    fun threads(context: Context, limit: Int = 60, includeHidden: Boolean = false): List<SmsThread> =
        allThreads(context, limit).filter { includeHidden || !isHidden(context, it) }

    private fun allThreads(context: Context, limit: Int): List<SmsThread> {
        if (!canRead(context)) return emptyList()
        val latest = LinkedHashMap<Long, SmsThread>()
        val unread = HashMap<Long, Int>()
        val names = HashMap<String, String?>()
        val seen = HashMap<Long, Long>()
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
                    if (c.getInt(5) == 0 && c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getLong(3) > seen.getOrPut(thread) { seenUpTo(context, thread) }) {
                        unread[thread] = (unread[thread] ?: 0) + 1
                    }
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

    /** The conversation with [address], if one exists (numbers compared on their last 9 digits). */
    fun threadFor(context: Context, address: String): SmsThread? {
        val key = address.filter(Char::isDigit).takeLast(9)
        if (key.isEmpty()) return null
        return allThreads(context, 500).firstOrNull { it.address.filter(Char::isDigit).takeLast(9) == key }
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
