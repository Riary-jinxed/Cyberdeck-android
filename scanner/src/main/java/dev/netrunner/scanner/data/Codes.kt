package dev.netrunner.scanner.data

import java.util.Locale

/** Reads what a scanned code contains from its raw text. Plain Kotlin, tested on the JVM. */
object Codes {
    sealed class Content {
        abstract val label: String
        data class Url(val url: String) : Content() { override val label = "LIEN" }
        data class Wifi(val ssid: String, val password: String, val security: String, val hidden: Boolean) : Content() { override val label = "WI-FI" }
        data class Phone(val number: String) : Content() { override val label = "TÉLÉPHONE" }
        data class Sms(val number: String, val body: String) : Content() { override val label = "SMS" }
        data class Email(val address: String, val subject: String, val body: String) : Content() { override val label = "E-MAIL" }
        data class Geo(val lat: Double, val lon: Double, val query: String) : Content() { override val label = "POSITION" }
        data class Contact(val name: String, val phones: List<String>, val emails: List<String>, val org: String) : Content() { override val label = "CONTACT" }
        data class Product(val code: String) : Content() { override val label = "PRODUIT" }
        data class Text(val text: String) : Content() { override val label = "TEXTE" }
    }

    fun parse(raw: String): Content {
        val t = raw.trim()
        val upper = t.uppercase(Locale.ROOT)
        return when {
            upper.startsWith("WIFI:") -> wifi(t)
            upper.startsWith("TEL:") -> Content.Phone(t.substring(4))
            upper.startsWith("SMSTO:") || upper.startsWith("SMS:") -> sms(t)
            upper.startsWith("MAILTO:") -> mailto(t)
            upper.startsWith("MATMSG:") -> matmsg(t)
            upper.startsWith("GEO:") -> geo(t) ?: Content.Text(t)
            upper.startsWith("BEGIN:VCARD") -> vcard(t)
            upper.startsWith("MECARD:") -> mecard(t)
            URL.matches(t) -> Content.Url(if (upper.startsWith("WWW.")) "https://$t" else t)
            PRODUCT.matches(t) -> Content.Product(t)
            else -> Content.Text(t)
        }
    }

    private val URL = Regex("""^(?i)(https?://|www\.)\S+$""")
    private val PRODUCT = Regex("""^\d{8}$|^\d{12,14}$""")

    /** "WIFI:T:WPA;S:Maison;P:mot\;de passe;H:false;;" with \ escapes. */
    private fun wifi(t: String): Content.Wifi {
        val f = fields(t.substringAfter(':'))
        return Content.Wifi(f["S"].orEmpty(), f["P"].orEmpty(), f["T"].orEmpty().uppercase(Locale.ROOT).ifEmpty { "nopass" }, f["H"].equals("true", true))
    }

    private fun sms(t: String): Content.Sms {
        val rest = t.substringAfter(':')
        return Content.Sms(rest.substringBefore(':'), if (rest.contains(':')) rest.substringAfter(':') else "")
    }

    private fun mailto(t: String): Content.Email {
        val rest = t.substring(7)
        val address = rest.substringBefore('?')
        val query = if (rest.contains('?')) rest.substringAfter('?') else ""
        val params = query.split('&').filter { it.contains('=') }.associate { it.substringBefore('=').lowercase(Locale.ROOT) to decode(it.substringAfter('=')) }
        return Content.Email(address, params["subject"].orEmpty(), params["body"].orEmpty())
    }

    private fun matmsg(t: String): Content.Email {
        val f = fields(t.substringAfter(':'))
        return Content.Email(f["TO"].orEmpty(), f["SUB"].orEmpty(), f["BODY"].orEmpty())
    }

    private fun geo(t: String): Content.Geo? {
        val rest = t.substring(4)
        val coords = rest.substringBefore('?').split(',')
        val lat = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
        val q = if (rest.contains("?q=")) decode(rest.substringAfter("?q=")) else ""
        return Content.Geo(lat, lon, q)
    }

    private fun vcard(t: String): Content.Contact {
        var name = ""
        var org = ""
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        // Folded lines (continuation starting with a space) are joined first.
        t.replace(Regex("\r?\n[ \t]"), "").lines().forEach { line ->
            val key = line.substringBefore(':').substringBefore(';').uppercase(Locale.ROOT)
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "FN" -> name = value
                "N" -> if (name.isEmpty()) name = value.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
                "TEL" -> if (value.isNotEmpty()) phones += value
                "EMAIL" -> if (value.isNotEmpty()) emails += value
                "ORG" -> org = value.replace(';', ' ').trim()
            }
        }
        return Content.Contact(name, phones, emails, org)
    }

    private fun mecard(t: String): Content.Contact {
        val all = fieldsMulti(t.substringAfter(':'))
        val n = all["N"]?.firstOrNull().orEmpty()
        val name = if (n.contains(',')) n.split(',').map { it.trim() }.reversed().joinToString(" ") else n
        return Content.Contact(name, all["TEL"].orEmpty(), all["EMAIL"].orEmpty(), all["ORG"]?.firstOrNull().orEmpty())
    }

    private fun fields(s: String): Map<String, String> = fieldsMulti(s).mapValues { it.value.first() }

    /** "K:v;K2:v2;;" with "\;", "\:", "\\" escapes; repeated keys kept in order. */
    private fun fieldsMulti(s: String): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        val part = StringBuilder()
        fun flush() {
            val p = part.toString()
            part.clear()
            val i = p.indexOf(':')
            if (i > 0) out.getOrPut(p.substring(0, i).uppercase(Locale.ROOT)) { mutableListOf() } += p.substring(i + 1)
        }
        var escape = false
        var keyDone = false
        for (c in s) {
            when {
                escape -> { part.append(c); escape = false }
                c == '\\' -> escape = true
                c == ';' -> { flush(); keyDone = false }
                else -> { part.append(c); if (c == ':') keyDone = true }
            }
        }
        if (keyDone || part.isNotEmpty()) flush()
        return out
    }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
