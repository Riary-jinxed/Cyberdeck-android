package dev.netrunner.launcher.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat

data class ContactHit(val name: String, val photo: ImageBitmap?)

data class RecentCall(val number: String, val name: String?, val type: Int, val date: Long, val durationSec: Long)

data class PhoneContact(val name: String, val number: String)

object Contacts {

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun lookup(context: Context, number: String): ContactHit? {
        if (number.isBlank() || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0).orEmpty()
                val photo = c.getString(1)?.let { photoUri ->
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                            BitmapFactory.decodeStream(it)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
                ContactHit(name, photo)
            }
        }.getOrNull()
    }

    fun recents(context: Context, limit: Int = 50): List<RecentCall> {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<RecentCall>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += RecentCall(
                        number = c.getString(0).orEmpty(),
                        name = c.getString(1)?.takeIf { it.isNotBlank() },
                        type = c.getInt(2),
                        date = c.getLong(3),
                        durationSec = c.getLong(4),
                    )
                }
            }
        }
        return out
    }

    fun all(context: Context): List<PhoneContact> {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<PhoneContact>()
        val seen = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0).orEmpty()
                    val number = c.getString(1).orEmpty()
                    if (seen.add(name + number.filter { it.isDigit() })) out += PhoneContact(name, number)
                }
            }
        }
        return out
    }
}
