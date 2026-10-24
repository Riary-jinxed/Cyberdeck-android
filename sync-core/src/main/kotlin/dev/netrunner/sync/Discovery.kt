package dev.netrunner.sync

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Finding the PC on the local network without typing an address: the phone broadcasts a
 * question on UDP [SyncServer.DEFAULT_PORT], the PC answers with its sync port. The answer is
 * not trusted: only a PC holding the pairing code can complete a sync.
 */
object Discovery {
    private const val ASK = "DECKLINK?"
    private const val ANSWER = "DECKLINK!"

    /** PC side: answers the phones' questions until [stop] is called. */
    class Responder(private val port: Int = SyncServer.DEFAULT_PORT, private val name: () -> String) {
        private var socket: DatagramSocket? = null

        fun start() {
            val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
            socket = s
            Thread({
                val buffer = ByteArray(256)
                while (!s.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { s.receive(packet) } catch (e: SocketException) { break }
                    if (String(packet.data, 0, packet.length, Charsets.UTF_8) != ASK) continue
                    val reply = "$ANSWER$port!${name().take(60)}".toByteArray(Charsets.UTF_8)
                    runCatching { s.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
                }
            }, "decklink-discovery").apply { isDaemon = true }.start()
        }

        fun stop() { runCatching { socket?.close() } }
    }

    /** Phone side: the PCs that answered within [timeoutMs]. */
    fun find(timeoutMs: Int = 1500, port: Int = SyncServer.DEFAULT_PORT): List<InetSocketAddress> {
        val found = LinkedHashSet<InetSocketAddress>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 300
            val ask = ASK.toByteArray(Charsets.UTF_8)
            (listOf(InetAddress.getByName("255.255.255.255")) + broadcasts()).distinct().forEach { target ->
                runCatching { s.send(DatagramPacket(ask, ask.size, target, port)) }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { s.receive(packet) } catch (e: SocketTimeoutException) { if (found.isNotEmpty()) break else continue }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith(ANSWER)) continue
                val tcp = text.removePrefix(ANSWER).substringBefore('!').toIntOrNull() ?: continue
                found += InetSocketAddress(packet.address, tcp)
            }
        }
        return found.toList()
    }

    private fun broadcasts(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
    }.getOrDefault(emptyList())

    /** The PC's own IPv4 addresses on the local network, for the QR code. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
}
