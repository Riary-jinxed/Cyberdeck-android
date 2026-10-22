package dev.netrunner.osint.data

import dev.netrunner.osint.data.Report.Flag
import dev.netrunner.osint.data.Report.Link
import dev.netrunner.osint.data.Report.Row
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The lookups on a domain or an IP. All of them are passive: they ask public registries and archives
 * (RDAP, DNS over HTTPS, Certificate Transparency, Wayback Machine, IP databases), never the target itself,
 * apart from the WEB module which loads the home page once, like a browser would.
 */
enum class Module(val label: String, val forDomain: Boolean, val forIp: Boolean) {
    Whois("WHOIS", true, false),
    Dns("DNS", true, true),
    Certs("CERTIFICATS", true, false),
    Ip("IP / ASN", true, true),
    Web("WEB", true, true),
    Tls("TLS", true, true),
    Archive("ARCHIVES", true, false),
    Pivots("PIVOTS", true, true);

    fun applies(t: Target) = if (t.isIp) forIp else forDomain
}

object Recon {

    suspend fun run(module: Module, t: Target): Report = try {
        when (module) {
            Module.Whois -> whois(t)
            Module.Dns -> if (t.isIp) ptr(t.host) else dns(t.host)
            Module.Certs -> certs(t.host)
            Module.Ip -> ip(t)
            Module.Web -> WebProbe.http(t)
            Module.Tls -> WebProbe.tls(t.host)
            Module.Archive -> archive(t)
            Module.Pivots -> pivots(t)
        }
    } catch (e: Net.NotFound) {
        Report.failed(module.label, t.host, "Aucune donnée publique.")
    } catch (e: Exception) {
        Report.failed(module.label, t.host, e.message?.let { "Échec : $it" } ?: "Réseau indisponible.")
    }

    // ---- WHOIS (RDAP) ----

    private suspend fun whois(t: Target): Report {
        val domain = Target.registered(t.host)
        val links = listOf(
            Link("RDAP brut", "https://rdap.org/domain/$domain"),
            Link("who.is", "https://who.is/whois/$domain"),
        )
        val o = try {
            JSONObject(Net.get("https://rdap.org/domain/$domain", accept = "application/rdap+json, application/json"))
        } catch (e: Net.NotFound) {
            return Report("WHOIS", domain, emptyList(), links, error = "Pas de RDAP pour ce domaine (non enregistré, ou extension sans RDAP) : essaie who.is.")
        }
        val rows = mutableListOf<Row>()
        rows += Row("Domaine", o.optString("ldhName", domain).lowercase(Locale.ROOT))
        val registrar = o.optJSONArray("entities").objects().firstOrNull { "registrar" in it.optJSONArray("roles").strings() }
        registrar?.let { r ->
            val name = vcard(r, "fn") ?: r.optString("handle")
            val iana = r.optJSONArray("publicIds").objects().firstOrNull()?.optString("identifier")
            rows += Row("Registrar", name + (iana?.let { " (IANA $it)" } ?: ""))
            abuse(r)?.let { rows += Row("Abus", it) }
        }
        val events = o.optJSONArray("events").objects().associate { it.optString("eventAction") to it.optString("eventDate") }
        events["registration"]?.let { rows += Row("Créé le", day(it)) }
        events["last changed"]?.let { rows += Row("Modifié le", day(it)) }
        events["expiration"]?.let { exp ->
            val left = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), OffsetDateTime.parse(exp).toLocalDate()) }.getOrNull()
            val flag = if (left != null && left < 30) Flag.Warn else Flag.None
            rows += Row("Expire le", day(exp) + (left?.let { " (dans $it j)" } ?: ""), flag)
        }
        o.optJSONArray("status").strings().takeIf { it.isNotEmpty() }?.let { rows += Row("Statut", it.joinToString("\n")) }
        o.optJSONArray("nameservers").objects().map { it.optString("ldhName").lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.let { rows += Row("Serveurs DNS", it.joinToString("\n")) }
        o.optJSONObject("secureDNS")?.let {
            val signed = it.optBoolean("delegationSigned")
            rows += Row("DNSSEC", if (signed) "signé" else "non signé", if (signed) Flag.Good else Flag.Warn)
        }
        return Report("WHOIS", domain, rows, links, remark = "Les coordonnées du titulaire sont masquées (RGPD) et ne sont pas recherchées.")
    }

    /** A vCard property ("fn", "email"…) of an RDAP entity. */
    private fun vcard(entity: JSONObject, key: String): String? {
        val props = entity.optJSONArray("vcardArray")?.optJSONArray(1) ?: return null
        for (i in 0 until props.length()) {
            val p = props.optJSONArray(i) ?: continue
            if (p.optString(0) == key) return p.optString(3).takeIf { it.isNotBlank() }
        }
        return null
    }

    /** The abuse contact of a registrar or network: an organisation's mailbox. */
    private fun abuse(entity: JSONObject): String? {
        val nested = entity.optJSONArray("entities").objects()
        val a = (nested + entity).firstOrNull { "abuse" in it.optJSONArray("roles").strings() } ?: return null
        return vcard(a, "email") ?: vcard(a, "tel")
    }

    private fun day(iso: String) = iso.take(10)

    // ---- DNS (DNS over HTTPS) ----

    private val TYPES = linkedMapOf("A" to 1, "AAAA" to 28, "CNAME" to 5, "MX" to 15, "NS" to 2, "TXT" to 16, "SOA" to 6, "CAA" to 257)

    /** Answers of one type, through Google's DNS over HTTPS (no request reaches the target). */
    suspend fun resolve(name: String, type: String): List<String> {
        val o = JSONObject(Net.get("https://dns.google/resolve?name=${Net.enc(name)}&type=$type", accept = "application/dns-json"))
        val code = TYPES[type] ?: if (type == "PTR") 12 else 0
        return o.optJSONArray("Answer").objects().filter { it.optInt("type") == code }.map { clean(it.optString("data")) }
    }

    private fun clean(data: String): String {
        val s = data.trim()
        // TXT records come quoted, sometimes split into several strings.
        return if (s.startsWith('"')) s.split(Regex("\"\\s+\"")).joinToString("") { it.trim('"') } else s.trimEnd('.')
    }

    private suspend fun dns(host: String): Report = coroutineScope {
        val answers = TYPES.keys.map { type -> async { type to runCatching { resolve(host, type) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
        val dmarc = async { runCatching { resolve("_dmarc.${Target.registered(host)}", "TXT") }.getOrDefault(emptyList()) }.await()
        val rows = mutableListOf<Row>()
        answers.forEach { (type, values) ->
            if (values.isEmpty()) return@forEach
            val shown = if (type == "MX") values.sortedBy { it.substringBefore(' ').toIntOrNull() ?: 0 } else values
            rows += Row(type, shown.joinToString("\n"))
        }
        if (rows.isEmpty()) return@coroutineScope Report.failed("DNS", host, "Aucun enregistrement : le nom n'existe pas ou ne répond pas.")
        val txt = answers["TXT"].orEmpty()
        val spf = txt.firstOrNull { it.startsWith("v=spf1", ignoreCase = true) }
        rows += Row("SPF", spf?.let { if (it.contains("-all")) "strict (-all)" else if (it.contains("~all")) "souple (~all)" else "présent" } ?: "absent",
            if (spf == null) Flag.Warn else Flag.Good)
        val dm = dmarc.firstOrNull { it.startsWith("v=DMARC1", ignoreCase = true) }
        val policy = dm?.let { Regex("""\bp=(\w+)""").find(it)?.groupValues?.get(1) }
        rows += Row("DMARC", dm?.let { "politique ${policy ?: "?"}" } ?: "absent", if (dm == null || policy == "none") Flag.Warn else Flag.Good)
        if (answers["MX"].isNullOrEmpty()) rows += Row("Courrier", "aucun MX : le domaine ne reçoit pas d'e-mail")
        Report("DNS", host, rows, listOf(Link("DNS complet", "https://dns.google/query?name=${Net.enc(host)}")),
            remark = "Résolu via DNS over HTTPS (dns.google), sans contacter les serveurs de la cible.")
    }

    private suspend fun ptr(ip: String): Report {
        val names = resolve(reverseName(ip), "PTR")
        return Report("DNS", ip, listOf(Row("PTR", names.joinToString("\n").ifBlank { "aucun nom inverse" })))
    }

    /** "1.2.3.4" → "4.3.2.1.in-addr.arpa", and the nibble form for IPv6. */
    private fun reverseName(ip: String): String {
        val bytes = InetAddress.getByName(ip).address
        return if (bytes.size == 4) bytes.reversed().joinToString(".") { (it.toInt() and 0xff).toString() } + ".in-addr.arpa"
        else bytes.flatMap { b -> listOf(b.toInt() shr 4 and 0xf, b.toInt() and 0xf) }.reversed().joinToString(".") { it.toString(16) } + ".ip6.arpa"
    }

    // ---- Certificate Transparency (crt.sh) ----

    private suspend fun certs(host: String): Report {
        val page = "https://crt.sh/?q=${Net.enc("%.$host")}"
        val json = try {
            JSONArray(Net.get("$page&output=json", timeout = 45_000, limit = 12_000_000))
        } catch (e: org.json.JSONException) {
            return Report.failed("CERTIFICATS", host, "Réponse tronquée : trop de certificats, ouvre crt.sh.").copy(links = listOf(Link("crt.sh", page)))
        } catch (e: java.io.IOException) {
            if (e is Net.NotFound) throw e
            return Report.failed("CERTIFICATS", host, "crt.sh ne répond pas (souvent surchargé) : réessaie dans un moment.").copy(links = listOf(Link("crt.sh", page)))
        }
        val certs = json.objects()
        if (certs.isEmpty()) return Report("CERTIFICATS", host, listOf(Row("Certificats", "aucun journalisé")), listOf(Link("crt.sh", page)))
        val names = certs.flatMap { it.optString("name_value").lines() }.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.endsWith(host) }.toSortedSet()
        val issuers = certs.groupingBy { issuerName(it.optString("issuer_name")) }.eachCount().entries.sortedByDescending { it.value }.take(5)
        val first = certs.minOfOrNull { it.optString("not_before") }.orEmpty()
        val last = certs.maxOfOrNull { it.optString("not_before") }.orEmpty()
        val shown = names.take(150)
        return Report(
            "CERTIFICATS", host,
            listOf(
                Row("Certificats", "${certs.size} journalisés"),
                Row("Période", "${first.take(10)} → ${last.take(10)}"),
                Row("Émetteurs", issuers.joinToString("\n") { "${it.key} (${it.value})" }),
                Row("Noms (${names.size})", shown.joinToString("\n") + if (names.size > shown.size) "\n… ${names.size - shown.size} de plus sur crt.sh" else ""),
            ),
            listOf(Link("crt.sh", page)),
            remark = "Sous-domaines révélés par les journaux publics Certificate Transparency.",
        )
    }

    private fun issuerName(dn: String): String =
        Regex("""\bO=("[^"]+"|[^,]+)""").find(dn)?.groupValues?.get(1)?.trim('"')
            ?: Regex("""\bCN=([^,]+)""").find(dn)?.groupValues?.get(1) ?: dn

    // ---- IP: location, ASN, network owner ----

    private suspend fun ip(t: Target): Report {
        val ip = if (t.isIp) t.host else {
            resolve(t.host, "A").firstOrNull { Target.isIpv4(it) } ?: resolve(t.host, "AAAA").firstOrNull()
                ?: return Report.failed("IP / ASN", t.host, "Le nom ne pointe vers aucune adresse.")
        }
        val addr = InetAddress.getByName(ip)
        if (addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || isCgnatOrUla(addr)) {
            return Report("IP / ASN", ip, listOf(Row("Adresse", "$ip (privée ou réservée)")), remark = "Adresse non routable sur Internet : aucune base publique ne la connaît.")
        }
        val rows = mutableListOf<Row>()
        if (!t.isIp) rows += Row("Résolu depuis", t.host)
        rows += Row("Adresse", ip)
        var asn: String? = null
        var lat = Double.NaN
        var lon = Double.NaN
        coroutineScope {
            val geo = async { runCatching { JSONObject(Net.get("https://ipwho.is/$ip")) }.getOrNull() }
            val rdap = async { runCatching { JSONObject(Net.get("https://rdap.org/ip/$ip", accept = "application/rdap+json, application/json")) }.getOrNull() }
            val ptr = async { runCatching { resolve(reverseName(ip), "PTR") }.getOrDefault(emptyList()) }
            geo.await()?.takeIf { it.optBoolean("success", false) }?.let { g ->
                val c = g.optJSONObject("connection")
                c?.optLong("asn")?.takeIf { it > 0 }?.let { asn = "AS$it"; rows += Row("ASN", "AS$it") }
                c?.optString("org")?.takeIf { it.isNotBlank() }?.let { rows += Row("Organisation", it) }
                c?.optString("isp")?.takeIf { it.isNotBlank() && it != c.optString("org") }?.let { rows += Row("Opérateur", it) }
                c?.optString("domain")?.takeIf { it.isNotBlank() }?.let { rows += Row("Domaine FAI", it) }
                val place = listOf(g.optString("city"), g.optString("region"), g.optString("country")).filter { it.isNotBlank() }.distinct()
                if (place.isNotEmpty()) rows += Row("Localisation", place.joinToString(", ") + " (approximative)")
                g.optJSONObject("timezone")?.optString("id")?.takeIf { it.isNotBlank() }?.let { rows += Row("Fuseau", it) }
                lat = g.optDouble("latitude")
                lon = g.optDouble("longitude")
            }
            rdap.await()?.let { r ->
                val net = listOf(r.optString("name"), r.optString("handle")).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (net.isNotBlank()) rows += Row("Réseau", net)
                val start = r.optString("startAddress")
                val end = r.optString("endAddress")
                if (start.isNotBlank()) rows += Row("Plage", "$start – $end")
                r.optString("country").takeIf { it.isNotBlank() }?.let { rows += Row("Pays (registre)", it) }
                val entities = r.optJSONArray("entities").objects()
                entities.firstOrNull { e -> e.optJSONArray("roles").strings().any { it == "registrant" } }?.let { e -> vcard(e, "fn")?.let { rows += Row("Titulaire du bloc", it) } }
                entities.firstNotNullOfOrNull { abuse(it) }?.let { rows += Row("Abus", it) }
            }
            ptr.await().takeIf { it.isNotEmpty() }?.let { rows += Row("PTR", it.joinToString("\n")) }
        }
        val links = listOfNotNull(
            if (!lat.isNaN() && !lon.isNaN()) Link("Carte", "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=10/$lat/$lon") else null,
            asn?.let { Link(it, "https://bgp.he.net/$it") },
            Link("BGP", "https://bgp.he.net/ip/$ip"),
            Link("AbuseIPDB", "https://www.abuseipdb.com/check/$ip"),
        )
        return Report("IP / ASN", ip, rows, links, remark = "Géolocalisation d'après les bases IP publiques : la ville est souvent celle de l'opérateur.")
    }

    /** 100.64.0.0/10 (CGNAT) and fc00::/7 (IPv6 unique local), which Java does not flag. */
    private fun isCgnatOrUla(a: InetAddress): Boolean {
        val b = a.address
        return if (b.size == 4) (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 else (b[0].toInt() and 0xfe) == 0xfc
    }

    // ---- Wayback Machine ----

    private suspend fun archive(t: Target): Report {
        val key = t.url ?: t.host
        val cdx = "https://web.archive.org/cdx/search/cdx?url=${Net.enc(key)}&output=json"
        suspend fun rows(q: String) = JSONArray(Net.get(cdx + q, timeout = 30_000)).let { a -> (1 until a.length()).mapNotNull { a.optJSONArray(it)?.strings() } }
        return coroutineScope {
            val first = async { rows("&fl=timestamp,original&limit=1") }
            val last = async { rows("&fl=timestamp,original&fastLatest=true&limit=-1") }
            val years = async { rows("&fl=timestamp&collapse=timestamp:4") }
            val f = first.await().firstOrNull()
            if (f == null) return@coroutineScope Report("ARCHIVES", key, listOf(Row("Captures", "aucune")), listOf(Link("Archiver maintenant", "https://web.archive.org/save/$key")))
            val l = last.await().firstOrNull() ?: f
            val y = years.await().map { it[0].take(4) }.distinct()
            Report(
                "ARCHIVES", key,
                listOf(
                    Row("Première", stamp(f[0])),
                    Row("Dernière", stamp(l[0])),
                    Row("Années", "${y.size} : " + y.joinToString(" ")),
                ),
                listOf(
                    Link("Première", "https://web.archive.org/web/${f[0]}/${f[1]}"),
                    Link("Dernière", "https://web.archive.org/web/${l[0]}/${l[1]}"),
                    Link("Toutes", "https://web.archive.org/web/*/$key*"),
                    Link("archive.today", "https://archive.ph/$key"),
                ),
            )
        }
    }

    /** "20240131120000" → "2024-01-31 12:00". */
    private fun stamp(ts: String) = if (ts.length >= 12) "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} ${ts.substring(8, 10)}:${ts.substring(10, 12)}" else ts

    // ---- Pivots: other public databases, as links ----

    fun pivots(t: Target): Report {
        val h = t.host
        val links = if (t.isIp) listOf(
            Link("VirusTotal", "https://www.virustotal.com/gui/ip-address/$h"),
            Link("Shodan", "https://www.shodan.io/host/$h"),
            Link("Censys", "https://search.censys.io/hosts/$h"),
            Link("urlscan", "https://urlscan.io/search/#ip:%22$h%22"),
            Link("AbuseIPDB", "https://www.abuseipdb.com/check/$h"),
            Link("GreyNoise", "https://viz.greynoise.io/ip/$h"),
            Link("Domaines hébergés", "https://viewdns.info/reverseip/?host=$h&t=1"),
        ) else listOf(
            Link("VirusTotal", "https://www.virustotal.com/gui/domain/$h"),
            Link("urlscan", "https://urlscan.io/search/#domain:$h"),
            Link("Shodan", "https://www.shodan.io/search?query=hostname%3A$h"),
            Link("SecurityTrails", "https://securitytrails.com/domain/$h/dns"),
            Link("crt.sh", "https://crt.sh/?q=${Net.enc("%.$h")}"),
            Link("Wayback", "https://web.archive.org/web/*/$h*"),
            Link("Pages indexées", "https://duckduckgo.com/?q=${Net.enc("site:$h")}"),
            Link("Documents indexés", "https://duckduckgo.com/?q=${Net.enc("site:$h filetype:pdf OR filetype:docx OR filetype:xlsx")}"),
        )
        return Report("PIVOTS", h, emptyList(), links, remark = "Bases publiques à consulter dans le navigateur ; rien n'est envoyé d'ici.")
    }
}
