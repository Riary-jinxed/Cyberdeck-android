package dev.netrunner.scanner.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import dev.netrunner.scanner.data.Codes.Content

/** What can be done with a scanned code: a label and the action, main one first. */
data class Action(val label: String, val run: (Context) -> Unit)

fun actions(content: Content, raw: String): List<Action> {
    val list = mutableListOf<Action>()
    when (content) {
        is Content.Url -> list += Action("OUVRIR") { start(it, Intent(Intent.ACTION_VIEW, Uri.parse(content.url))) }
        is Content.Wifi -> list += Action("CONNECTER") { connect(it, content) }
        is Content.Phone -> list += Action("APPELER") { start(it, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + content.number))) }
        is Content.Sms -> list += Action("ÉCRIRE") {
            start(it, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + content.number)).putExtra("sms_body", content.body))
        }
        is Content.Email -> list += Action("ÉCRIRE") {
            start(
                it,
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(content.address))
                    .putExtra(Intent.EXTRA_SUBJECT, content.subject).putExtra(Intent.EXTRA_TEXT, content.body),
            )
        }
        is Content.Geo -> list += Action("CARTE") {
            val q = if (content.query.isNotEmpty()) "?q=" + Uri.encode(content.query) else "?q=${content.lat},${content.lon}"
            start(it, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${content.lat},${content.lon}$q")))
        }
        is Content.Contact -> list += Action("AJOUTER") { ctx ->
            val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, content.name)
                .putExtra(ContactsContract.Intents.Insert.COMPANY, content.org)
            content.phones.firstOrNull()?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            content.phones.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
            content.emails.firstOrNull()?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            start(ctx, i)
        }
        is Content.Product -> list += Action("RECHERCHER") { search(it, content.code) }
        is Content.Text -> if (raw.length < 200) list += Action("RECHERCHER") { search(it, raw) }
    }
    list += Action("COPIER") { copy(it, if (content is Content.Wifi) content.password.ifEmpty { raw } else raw) }
    list += Action("PARTAGER") { ctx ->
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, raw), "Partager"))
    }
    return list
}

private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune appli pour ça", Toast.LENGTH_SHORT).show()
    }
}

private fun search(context: Context, text: String) =
    start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(text))))

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("scan", text))
    Toast.makeText(context, "Copié", Toast.LENGTH_SHORT).show()
}

/**
 * Android 11+ shows its own "add this network?" sheet. Before that (or for WEP, which
 * suggestions don't support) the password is copied and the Wi-Fi settings open.
 */
private fun connect(context: Context, wifi: Content.Wifi) {
    val security = wifi.security
    if (Build.VERSION.SDK_INT >= 30 && security != "WEP") {
        val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).setIsHiddenSsid(wifi.hidden)
        when {
            security.startsWith("SAE") || security == "WPA3" -> builder.setWpa3Passphrase(wifi.password)
            wifi.password.isNotEmpty() && security != "NOPASS" -> builder.setWpa2Passphrase(wifi.password)
        }
        val suggestion = runCatching { builder.build() }.getOrNull()
        if (suggestion != null) {
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
            start(context, intent)
            return
        }
    }
    if (wifi.password.isNotEmpty()) copy(context, wifi.password)
    Toast.makeText(context, "Mot de passe copié : choisis « ${wifi.ssid} » dans les réglages Wi-Fi", Toast.LENGTH_LONG).show()
    start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
}

/** Opens the launcher's field kit on this code: its sheet if known, else a new pre-filled one (launcher v1.3.8+). */
fun addToKit(context: Context, raw: String, format: String) {
    val intent = Intent("dev.netrunner.action.KIT_ADD")
        .setClassName("dev.netrunner.launcher", "dev.netrunner.launcher.kit.KitLinkActivity")
        .putExtra("code", raw)
        .putExtra("format", format)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Missing launcher, an older one without the kit link, or the permission was not granted.
        Toast.makeText(context, "Mets à jour le launcher Netrunner pour ajouter au kit.", Toast.LENGTH_LONG).show()
    }
}
