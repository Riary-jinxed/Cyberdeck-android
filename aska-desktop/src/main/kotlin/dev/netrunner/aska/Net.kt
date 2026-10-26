package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Her network toolbox. Everything only reads and probes: nothing changes the PC's or the
 * network's configuration. Each tool answers in plain text, for her to explain.
 */
object Net {

    // ---- The PC's own configuration.

    data class Interface(val name: String, val label: String, val mac: String, val ipv4: String, val prefix: Int)

    /** Up interfaces with an IPv4, loopback and virtual adapters last. */
    fun interfaces(): List<Interface> = runCatching {
        NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nic ->
            val mac = nic.hardwareAddress?.joinToString(":") { "%02X".format(it) }.orEmpty()
            nic.interfaceAddresses.filter { it.address is Inet4Address }.map {
                Interface(nic.name, nic.displayName ?: nic.name, mac, it.address.hostAddress, it.networkPrefixLength.toInt())
            }
        }.sortedBy { if (virtual(it.label)) 1 else 0 }
    }.getOrDefault(emptyList())

    private fun virtual(label: String) = Regex("(?i)virtual|vmware|hyper-v|vethernet|docker|wsl|tap|tun|vpn|loopback|bluetooth").containsMatchIn(label)

    /** The default gateway, from the routing table. */
    fun gateway(): String? = if (isWindows) {
        run(listOf("route", "print", "-4", "0.0.0.0"))?.lines()
            ?.map { it.trim().split(Regex("\\s+")) }
            ?.firstOrNull { it.size >= 3 && it[0] == "0.0.0.0" && it[1] == "0.0.0.0" }?.get(2)
    } else {
        run(listOf("ip", "route", "show", "default"))?.let { Regex("via (\\S+)").find(it)?.groupValues?.get(1) }
    }

    fun dnsServers(): List<String> = if (isWindows) {
        run(listOf("powershell", "-NoProfile", "-Command", "(Get-DnsClientServerAddress -AddressFamily IPv4).ServerAddresses"))
            ?.lines()?.map { it.trim() }?.filter { IPV4.matches(it) }?.distinct().orEmpty()
    } else {
        runCatching { File("/etc/resolv.conf").readLines() }.getOrDefault(emptyList())
            .mapNotNull { Regex("^nameserver\\s+(\\S+)").find(it.trim())?.groupValues?.get(1) }
    }

    fun config(): String = buildString {
        val nics = interfaces()
        if (nics.isEmpty()) appendLine("Aucune carte réseau active avec une IPv4.")
        nics.forEach {
            val apipa = if (it.ipv4.startsWith("169.254.")) " (adresse APIPA : pas de réponse du DHCP)" else ""
            appendLine("${it.label} : ${it.ipv4}/${it.prefix}$apipa, MAC ${it.mac.ifEmpty { "?" }}")
        }
        appendLine("Passerelle : ${gateway() ?: "aucune"}")
        appendLine("DNS : ${dnsServers().ifEmpty { listOf("aucun trouvé") }.joinToString(", ")}")
    }.trim()

    // ---- Probes.

    /**
     * True when [host] resolves only to this PC or the local network (private, link-local, CGNAT):
     * where the model may probe on its own. Anything else waits for a command typed by the runner.
     */
    fun local(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host.trim().substringBefore('/')).all { a ->
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
                (a is Inet4Address && (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64) ||
                (a !is Inet4Address && (a.address[0].toInt() and 0xFE) == 0xFC)
        }
    }.getOrDefault(false)

    data class Ping(val host: String, val sent: Int, val received: Int, val avgMs: Double?) {
        fun text() = if (received == 0) "$host : aucune réponse sur $sent ($sent perdus)."
        else "$host : $received/$sent réponses, ${sent - received} perdu(s), moyenne ${"%.0f".format(avgMs ?: 0.0)} ms."
    }

    fun ping(host: String, count: Int = 4): Ping {
        val command = if (isWindows) listOf("ping", "-n", "$count", "-w", "1000", host) else listOf("ping", "-c", "$count", "-W", "1", host)
        return parsePing(host, count, run(command, timeoutSeconds = count * 2L + 5).orEmpty())
    }

    /** Works on French and English outputs: each reply carries "TTL=" and "temps=12 ms" or "time=12.3 ms". */
    fun parsePing(host: String, sent: Int, output: String): Ping {
        val replies = output.lines().filter { it.contains("ttl=", ignoreCase = true) }
        val times = replies.mapNotNull { Regex("(?i)(?:temps|time)\\s*[=<]\\s*(\\d+(?:[.,]\\d+)?)").find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
        return Ping(host, sent, replies.size.coerceAtMost(sent), times.takeIf { it.isNotEmpty() }?.average())
    }

    fun traceroute(host: String): String {
        val out = if (isWindows) run(listOf("tracert", "-d", "-h", "15", "-w", "500", host), timeoutSeconds = 60)
        else run(listOf("traceroute", "-n", "-m", "15", "-w", "1", host), timeoutSeconds = 60) ?: run(listOf("tracepath", "-n", "-m", "15", host), timeoutSeconds = 60)
        return out?.lines()?.filter { it.isNotBlank() }?.joinToString("\n")?.take(2500) ?: "traceroute indisponible sur ce PC."
    }

    fun dns(name: String): String = runCatching {
        if (IPV4.matches(name)) "$name : nom inverse ${InetAddress.getByName(name).canonicalHostName}"
        else "$name : " + InetAddress.getAllByName(name).joinToString(", ") { it.hostAddress }
    }.getOrElse { "$name : résolution impossible (${it.javaClass.simpleName})." }

    fun port(host: String, port: Int): String = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        "$host:$port est ouvert."
    } catch (e: SocketTimeoutException) {
        "$host:$port ne répond pas (filtré par un pare-feu, ou hôte éteint)."
    } catch (e: Exception) {
        "$host:$port est fermé ou injoignable (${e.javaClass.simpleName})."
    }

    fun wifi(): String = (if (isWindows) run(listOf("netsh", "wlan", "show", "interfaces"))
    else run(listOf("nmcli", "-t", "-f", "ACTIVE,SSID,SIGNAL,CHAN,FREQ,SECURITY", "dev", "wifi")))
        ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && (isWindows || it.startsWith("yes:") || it.startsWith("oui:")) }
        ?.joinToString("\n")?.takeIf { it.isNotBlank() }?.take(1500)
        ?: "Pas de Wi-Fi actif sur ce PC."

    // ---- Subnets.

    data class Subnet(val network: Long, val prefix: Int) {
        val mask get() = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val broadcast get() = network or (mask.inv() and 0xFFFFFFFFL)
        val hosts get() = if (prefix >= 31) (1L shl (32 - prefix)) else (1L shl (32 - prefix)) - 2
        val first get() = if (prefix >= 31) network else network + 1
        val last get() = if (prefix >= 31) broadcast else broadcast - 1

        fun text() = "Réseau ${ip(network)}/$prefix, masque ${ip(mask)}, broadcast ${ip(broadcast)}, " +
            "hôtes ${ip(first)} à ${ip(last)} ($hosts adresses utilisables)."

        companion object {
            /** "192.168.1.37/24" or "192.168.1.0 255.255.255.0". */
            fun parse(text: String): Subnet? {
                val parts = text.trim().split('/', ' ').filter { it.isNotBlank() }
                if (parts.isEmpty()) return null
                val address = toLong(parts[0]) ?: return null
                val prefix = when {
                    parts.size == 1 -> 24
                    parts[1].contains('.') -> toLong(parts[1])?.let { java.lang.Long.bitCount(it) } ?: return null
                    else -> parts[1].toIntOrNull() ?: return null
                }
                if (prefix !in 0..32) return null
                val s = Subnet(0, prefix)
                return Subnet(address and s.mask, prefix)
            }
        }
    }

    fun subnet(text: String): String = Subnet.parse(text)?.text() ?: "« $text » n'est pas un réseau valide (exemple : 192.168.1.0/24)."

    fun toLong(ip: String): Long? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        return p.fold(0L) { acc, s -> (acc shl 8) or (s.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toLong() }
    }

    fun ip(value: Long) = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xFF).toString() }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    // ---- Scan.

    data class Device(val ip: String, val mac: String?, val vendor: String?, val name: String?, val known: String?)

    /**
     * Who answers on [cidr] (the PC's own subnet by default, at most 1024 addresses): ping and a
     * few common ports, then the ARP table for the MACs, the IEEE list for the makers, and
     * Deck Link's fleet to tell known PCs from strangers.
     */
    suspend fun scan(cidr: String?, fleet: Map<String, String>): String = withContext(Dispatchers.IO) {
        val own = interfaces().firstOrNull { !it.ipv4.startsWith("169.254.") }
        val subnet = (cidr?.takeIf { it.isNotBlank() } ?: own?.let { "${it.ipv4}/${it.prefix}" })?.let { Subnet.parse(it) }
            ?: return@withContext "Je ne trouve pas de sous-réseau à scanner."
        val scanned = if (subnet.prefix < 22) Subnet(Subnet.parse("${ip(subnet.network)}/22")!!.network, 22) else subnet
        val gate = Semaphore(64)
        val alive = coroutineScope {
            (scanned.first..scanned.last).map { n ->
                async { gate.withPermit { ip(n).takeIf { alive(it) } } }
            }.awaitAll().filterNotNull()
        }
        val arp = arpTable()
        val vendors = Oui.load()
        val devices = coroutineScope {
            alive.map { address ->
                async {
                    gate.withPermit {
                        val mac = arp[address] ?: own?.takeIf { it.ipv4 == address }?.mac
                        val name = runCatching { InetAddress.getByName(address).canonicalHostName }.getOrNull()?.takeIf { it != address }
                        Device(address, mac, mac?.let { Oui.vendor(vendors, it) }, name, fleet[mac?.uppercase()] ?: fleet[address])
                    }
                }
            }.awaitAll()
        }
        buildString {
            append("Scan de ${ip(scanned.network)}/${scanned.prefix}")
            if (scanned != subnet) append(" (limité à 1024 adresses)")
            appendLine(" : ${devices.size} appareil(s) répondent.")
            devices.sortedBy { toLong(it.ip) }.forEach { d ->
                append(d.ip)
                d.name?.let { append(" · $it") }
                d.mac?.let { append(" · $it") }
                d.vendor?.let { append(" · $it") }
                append(if (d.known != null) " · parc : ${d.known}" else if (fleet.isNotEmpty()) " · inconnu du parc" else "")
                appendLine()
            }
        }.trim().take(4000)
    }

    private val PROBE_PORTS = listOf(80, 443, 22, 445, 139, 3389, 53, 9100, 8080)

    private fun alive(address: String): Boolean {
        val target = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
        if (runCatching { target.isReachable(600) }.getOrDefault(false)) return true
        return PROBE_PORTS.any { port ->
            try {
                Socket().use { it.connect(InetSocketAddress(target, port), 250) }
                true
            } catch (e: java.net.ConnectException) {
                // Refused: the device is there, the port is just closed.
                e.message?.contains("refused", ignoreCase = true) == true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** IP to MAC, from the system's neighbour table (filled by the scan itself). */
    fun arpTable(): Map<String, String> {
        val out = if (isWindows) run(listOf("arp", "-a")) else run(listOf("ip", "neigh")) ?: run(listOf("arp", "-an"))
        return parseArp(out.orEmpty())
    }

    fun parseArp(output: String): Map<String, String> = output.lines().mapNotNull { line ->
        val ip = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3})").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val mac = Regex("([0-9a-fA-F]{2}[-:]){5}[0-9a-fA-F]{2}").find(line)?.value?.replace('-', ':')?.uppercase() ?: return@mapNotNull null
        if (mac == "FF:FF:FF:FF:FF:FF" || mac.startsWith("01:00:5E")) null else ip to mac
    }.toMap()

    // ---- Diagnosis.

    /** "No internet": card, address, gateway, DNS, internet, web, in that order, with where it breaks. */
    fun diagnose(): String {
        val steps = mutableListOf<String>()
        fun ok(text: String) = steps.add("✓ $text")
        fun ko(text: String, advice: String): String {
            steps.add("✗ $text")
            return (steps + "Piste : $advice").joinToString("\n")
        }

        val nic = interfaces().firstOrNull() ?: return ko("aucune carte réseau active", "câble branché ? Wi-Fi activé ? Carte désactivée dans les paramètres réseau ?")
        if (nic.ipv4.startsWith("169.254.")) return ko("${nic.label} a une adresse APIPA (${nic.ipv4})", "le DHCP ne répond pas : box ou routeur allumé ? Câble ou prise murale ? Essaie ipconfig /renew.")
        ok("carte ${nic.label} en ${nic.ipv4}/${nic.prefix}")
        val gw = gateway() ?: return ko("aucune passerelle par défaut", "config IP fixe incomplète, ou DHCP qui ne donne pas de passerelle.")
        val gwPing = ping(gw, 3)
        if (gwPing.received == 0 && !alive(gw)) return ko("la passerelle $gw ne répond pas", "câble, switch, VLAN ou Wi-Fi entre le PC et le routeur. Le voyant de la prise ? Un autre PC sur la même prise ?")
        ok("passerelle $gw joignable" + (gwPing.avgMs?.let { " (${it.toInt()} ms)" } ?: ""))
        val net = ping("1.1.1.1", 3)
        val netTcp = runCatching { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2_000) }; true }.getOrDefault(false)
        if (net.received == 0 && !netTcp) return ko("internet injoignable (1.1.1.1)", "le routeur n'a pas de lien vers l'extérieur : box synchronisée ? Pare-feu sortant ? Panne de l'opérateur ?")
        ok("internet joignable" + (net.avgMs?.let { " (${it.toInt()} ms)" } ?: ""))
        val resolved = runCatching { InetAddress.getByName("www.google.com").hostAddress }.getOrNull()
            ?: return ko("le DNS ne résout pas (serveurs : ${dnsServers().joinToString(", ").ifEmpty { "aucun" }})", "change de DNS (1.1.1.1 ou 8.8.8.8) pour tester, ou vide le cache : ipconfig /flushdns.")
        ok("DNS ok (www.google.com → $resolved)")
        val web = runCatching {
            val c = URI.create("http://www.msftconnecttest.com/connecttest.txt").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 4_000
            c.readTimeout = 4_000
            c.instanceFollowRedirects = false
            c.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull()
        if (web != "Microsoft Connect Test") return ko("le web est détourné ou bloqué", "portail captif (hôtel, invité) à valider dans le navigateur, ou proxy obligatoire.")
        ok("web ok")
        return (steps + "Tout répond : si un site précis bloque, teste-le avec un ping ou un port.").joinToString("\n")
    }

    // ---- Commands.

    private fun run(command: List<String>, timeoutSeconds: Long = 10): String? = runCatching {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader(consoleCharset()).forEachLine { out.appendLine(it) } } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) p.destroyForcibly()
        reader.join(1_000)
        out.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Windows consoles speak their OEM code page (850 in France), not UTF-8. */
    private fun consoleCharset() = if (isWindows) runCatching { charset("IBM850") }.getOrDefault(Charsets.UTF_8) else Charsets.UTF_8
}

/** MAC prefixes to makers, from the IEEE list, downloaded once next to her settings. */
object Oui {
    private const val URL = "https://standards-oui.ieee.org/oui/oui.txt"
    private val file get() = File(Store.dir, "oui.txt")
    @Volatile private var cache: Map<String, String>? = null

    fun load(): Map<String, String> {
        cache?.let { return it }
        if (!file.isFile || file.length() < 100_000) runCatching {
            val c = URI.create(URL).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Aska")
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return parse(runCatching { file.readLines() }.getOrDefault(emptyList())).also { cache = it }
    }

    /** "28-6F-B9   (hex)\t\tNokia Shanghai Bell Co., Ltd." lines. */
    fun parse(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
        val m = Regex("^([0-9A-F]{2})-([0-9A-F]{2})-([0-9A-F]{2})\\s+\\(hex\\)\\s+(.+)$").find(line.trim()) ?: return@mapNotNull null
        "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}" to m.groupValues[4].trim()
    }.toMap()

    fun vendor(table: Map<String, String>, mac: String): String? {
        val prefix = mac.uppercase().replace('-', ':').take(8)
        // A locally administered MAC (second bit of the first byte): phones and their random addresses.
        val first = prefix.take(2).toIntOrNull(16) ?: return null
        if (first and 0x02 != 0) return "adresse aléatoire (téléphone ?)"
        return table[prefix]
    }
}
