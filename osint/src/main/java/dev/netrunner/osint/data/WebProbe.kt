package dev.netrunner.osint.data

import dev.netrunner.osint.data.Report.Flag
import dev.netrunner.osint.data.Report.Link
import dev.netrunner.osint.data.Report.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** One page load (headers and redirects) and one TLS handshake: what any browser sees of the site. */
object WebProbe {

    private val SECURITY = listOf(
        "Strict-Transport-Security" to "HSTS",
        "Content-Security-Policy" to "CSP",
        "X-Frame-Options" to "X-Frame-Options",
        "X-Content-Type-Options" to "X-Content-Type-Options",
        "Referrer-Policy" to "Referrer-Policy",
        "Permissions-Policy" to "Permissions-Policy",
    )
    private val TELLS = listOf("Server", "X-Powered-By", "X-AspNet-Version", "X-Generator", "Via", "CF-Ray", "X-Cache", "X-Served-By")

    suspend fun http(t: Target): Report = withContext(Dispatchers.IO) {
        var url = t.site
        val chain = mutableListOf<String>()
        var conn: HttpURLConnection? = null
        try {
            for (hop in 0..8) {
                conn?.disconnect()
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.instanceFollowRedirects = false
                c.connectTimeout = 12_000
                c.readTimeout = 12_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ${Net.AGENT}")
                val code = c.responseCode
                chain += "$code  $url"
                val location = c.getHeaderField("Location")
                if (code !in 300..399 || location == null || hop == 8) break
                url = URL(URL(url), location).toString()
            }
            val c = conn!!
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(" | ")
            val rows = mutableListOf<Row>()
            rows += Row("Chaîne", chain.joinToString("\n"))
            rows += Row("Statut", "${c.responseCode} ${c.responseMessage.orEmpty()}".trim(), if (c.responseCode in 200..299) Flag.Good else Flag.Warn)
            header("Content-Type")?.let { rows += Row("Type", it) }
            TELLS.forEach { name -> header(name)?.let { rows += Row(name, it) } }
            val https = url.startsWith("https://")
            rows += Row("HTTPS", if (https) "oui" else "non : la page finale est en clair", if (https) Flag.Good else Flag.Warn)
            SECURITY.forEach { (name, label) ->
                val v = header(name)
                rows += Row(label, v?.take(160) ?: "absent", if (v != null) Flag.Good else Flag.Warn)
            }
            val cookies = headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
            if (cookies.isNotEmpty()) {
                val weak = cookies.count { !it.contains("Secure", ignoreCase = true) || !it.contains("HttpOnly", ignoreCase = true) }
                rows += Row("Cookies", "${cookies.size} posés" + if (weak > 0) ", $weak sans Secure/HttpOnly" else "", if (weak > 0) Flag.Warn else Flag.None)
            }
            Report(
                "WEB", t.site, rows,
                listOf(Link("Ouvrir", url), Link("Analyse des en-têtes", "https://securityheaders.com/?q=${Net.enc(url)}&hide=on")),
                remark = "Une seule requête GET de la page d'accueil, comme un navigateur.",
            )
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun tls(host: String): Report = withContext(Dispatchers.IO) {
        val links = listOf(Link("SSL Labs", "https://www.ssllabs.com/ssltest/analyze.html?d=${Net.enc(host)}&hideResults=on"))
        var problem: String? = null
        val socket = try {
            handshake(host, SSLSocketFactory.getDefault() as SSLSocketFactory)
        } catch (e: SSLException) {
            // Not trusted (expired, self-signed, wrong name…): look at it anyway, to show why.
            problem = e.message?.substringAfterLast(": ")?.take(160) ?: "certificat refusé"
            handshake(host, trustAll())
        }
        socket.use { s ->
            val session = s.session
            val chain = session.peerCertificates.filterIsInstance<X509Certificate>()
            val leaf = chain.firstOrNull() ?: return@withContext Report.failed("TLS", host, "Aucun certificat présenté.")
            val rows = mutableListOf<Row>()
            rows += Row("Confiance", problem?.let { "REFUSÉ : $it" } ?: "reconnu par Android", if (problem == null) Flag.Good else Flag.Warn)
            val proto = session.protocol
            rows += Row("Protocole", proto, if (proto == "TLSv1.3" || proto == "TLSv1.2") Flag.Good else Flag.Warn)
            rows += Row("Chiffrement", session.cipherSuite)
            rows += Row("Sujet", dnPart(leaf.subjectX500Principal.name, "CN") ?: leaf.subjectX500Principal.name)
            rows += Row("Émetteur", listOfNotNull(dnPart(leaf.issuerX500Principal.name, "O"), dnPart(leaf.issuerX500Principal.name, "CN")).distinct().joinToString(" · "))
            val end = leaf.notAfter.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            val left = ChronoUnit.DAYS.between(LocalDate.now(), end)
            rows += Row("Validité", "${leaf.notBefore.toInstant().toString().take(10)} → $end (${if (left < 0) "expiré" else "$left j"})", if (left < 15) Flag.Warn else Flag.Good)
            val sans = runCatching { leaf.subjectAlternativeNames?.filter { it[0] == 2 || it[0] == 7 }?.map { it[1].toString() } }.getOrNull().orEmpty()
            if (sans.isNotEmpty()) rows += Row("Noms (${sans.size})", sans.take(40).joinToString("\n") + if (sans.size > 40) "\n…" else "")
            val match = sans.any { matches(it, host) }
            rows += Row("Nom couvert", if (match) "oui" else "non : $host absent du certificat", if (match) Flag.Good else Flag.Warn)
            val key = leaf.publicKey
            val bits = when (key) {
                is RSAPublicKey -> key.modulus.bitLength()
                is ECPublicKey -> key.params.curve.field.fieldSize
                else -> null
            }
            rows += Row("Clé", key.algorithm + (bits?.let { " $it bits" } ?: "") + " · " + leaf.sigAlgName)
            rows += Row("Chaîne", "${chain.size} certificat(s)")
            rows += Row("Série", leaf.serialNumber.toString(16).uppercase(Locale.ROOT))
            Report("TLS", "$host:443", rows, links)
        }
    }

    private fun handshake(host: String, factory: SSLSocketFactory): SSLSocket {
        val s = factory.createSocket() as SSLSocket
        try {
            s.soTimeout = 12_000
            s.connect(InetSocketAddress(host, 443), 12_000)
            val f = factory.createSocket(s, host, 443, true) as SSLSocket
            // SNI, so a shared host presents this site's certificate (not for a bare IP).
            if (!Target.isIpv4(host) && !Target.isIpv6(host)) f.sslParameters = f.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            f.startHandshake()
            return f
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    /** Only used to read a refused certificate; nothing is sent over this connection. */
    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustAll(): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    private fun dnPart(dn: String, key: String) = Regex("""(?:^|,)$key=((?:\\,|[^,])+)""").find(dn)?.groupValues?.get(1)?.replace("\\,", ",")

    private fun matches(pattern: String, host: String): Boolean {
        val p = pattern.lowercase(Locale.ROOT)
        val h = host.lowercase(Locale.ROOT)
        return p == h || (p.startsWith("*.") && h.substringAfter('.', "") == p.removePrefix("*."))
    }
}
