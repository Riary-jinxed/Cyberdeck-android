package dev.netrunner.launcher.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class LanInfo(
    val interfaceName: String,
    val address: String,
    val prefixLength: Int,
    val gateway: String?,
    val dns: List<String>,
    val onWifi: Boolean,
)

data class HostResult(val ip: String, val openPorts: List<Int>)

enum class PortState { Open, Refused, Silent }

object NetTools {

    /** Ports tried to decide whether a host is up: an answer, even a refusal, means it is there. */
    val DiscoveryPorts = listOf(80, 443, 22, 445, 53, 62078, 8080, 554)

    val KnownPorts = linkedMapOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns", 80 to "http",
        110 to "pop3", 139 to "netbios", 143 to "imap", 443 to "https", 445 to "smb",
        548 to "afp", 554 to "rtsp", 631 to "ipp", 1883 to "mqtt", 1900 to "upnp",
        3000 to "dev-http", 3389 to "rdp", 5000 to "upnp/nas", 5555 to "adb", 5900 to "vnc",
        7000 to "airplay", 8008 to "chromecast", 8009 to "cast", 8080 to "http-alt",
        8443 to "https-alt", 8888 to "http-alt", 9100 to "imprimante", 32400 to "plex",
        49152 to "upnp", 62078 to "iphone-sync",
    )

    fun lanInfo(context: Context): LanInfo? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        val link = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gateway = props.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        val wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return LanInfo(
            interfaceName = props.interfaceName ?: "?",
            address = link.address.hostAddress ?: "?",
            prefixLength = link.prefixLength,
            gateway = gateway,
            dns = props.dnsServers.mapNotNull { it.hostAddress },
            onWifi = wifi,
        )
    }

    /** Signal in dBm and link speed in Mbit/s, when on Wi-Fi. */
    @Suppress("DEPRECATION")
    fun wifiSignal(context: Context): Pair<Int, Int>? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val info = wifi.connectionInfo ?: return null
        if (info.networkId == -1 && info.rssi <= -127) return null
        return info.rssi to info.linkSpeed
    }

    fun tcp(host: String, port: Int, timeoutMs: Int): PortState = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        PortState.Open
    } catch (e: ConnectException) {
        if (e.message.orEmpty().contains("refused", ignoreCase = true)) PortState.Refused else PortState.Silent
    } catch (e: IOException) {
        PortState.Silent
    }

    suspend fun probeHost(ip: String, timeoutMs: Int = 400): HostResult? = withContext(Dispatchers.IO) {
        val states = DiscoveryPorts
            .map { port -> async(Dispatchers.IO) { port to tcp(ip, port, timeoutMs) } }
            .awaitAll()
        val alive = states.any { it.second != PortState.Silent } ||
            runCatching { InetAddress.getByName(ip).isReachable(timeoutMs) }.getOrDefault(false)
        if (alive) HostResult(ip, states.filter { it.second == PortState.Open }.map { it.first }) else null
    }

    /** Reverse DNS with a hard time limit (the lookup itself cannot be interrupted). */
    suspend fun hostname(ip: String, timeoutMs: Long = 1200): String? {
        val lookup = CoroutineScope(Dispatchers.IO).async {
            runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
        }
        return withTimeoutOrNull(timeoutMs) { lookup.await() }?.takeIf { it != ip }
    }

    /** The 254 neighbours of [address] in its /24. */
    fun subnet24(address: String): List<String> {
        val base = address.substringBeforeLast('.')
        return (1..254).map { "$base.$it" }
    }

    fun portLabel(port: Int): String = KnownPorts[port]?.let { "$port/$it" } ?: "$port"
}
