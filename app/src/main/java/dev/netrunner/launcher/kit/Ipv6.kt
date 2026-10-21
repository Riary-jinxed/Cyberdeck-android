package dev.netrunner.launcher.kit

import java.math.BigInteger

/** IPv6 prefix arithmetic and notation, computed locally (nothing is sent on the network). */
object Ipv6 {
    private val ALL = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    data class Info(val address: BigInteger, val prefix: Int) {
        val mask: BigInteger get() = if (prefix == 0) BigInteger.ZERO else ALL.shiftLeft(128 - prefix).and(ALL)
        val network: BigInteger get() = address.and(mask)
        val last: BigInteger get() = network.or(mask.xor(ALL))
        val kind: String get() = when {
            address == BigInteger.ONE -> "boucle locale (::1)"
            address == BigInteger.ZERO -> "non spécifiée (::)"
            inRange(address, "::ffff:0:0", 96) -> "IPv4 mappée"
            inRange(address, "2001:db8::", 32) -> "documentation"
            inRange(address, "fe80::", 10) -> "lien local"
            inRange(address, "fc00::", 7) -> "unique locale (ULA, privée)"
            inRange(address, "ff00::", 8) -> "multicast"
            inRange(address, "2002::", 16) -> "6to4"
            inRange(address, "2000::", 3) -> "globale (publique)"
            else -> "réservée"
        }
    }

    /** Accepts "2001:db8::1/64" or a bare address (then /64, the usual LAN prefix). */
    fun parse(input: String): Info? {
        val parts = input.trim().split('/')
        if (parts.size > 2) return null
        val address = address(parts[0]) ?: return null
        val prefix = if (parts.size == 2) parts[1].trim().toIntOrNull()?.takeIf { it in 0..128 } ?: return null else 64
        return Info(address, prefix)
    }

    /** Parses any valid textual form, including "::" and an embedded IPv4 tail. */
    fun address(text: String): BigInteger? {
        var s = text.trim().substringBefore('%').lowercase()
        if (s.isEmpty() || s.count { it == ':' } < 2) return null
        // An IPv4 tail ("::ffff:192.168.1.1") counts as two groups.
        if (s.contains('.')) {
            val v4 = Subnet.ip(s.substringAfterLast(':')) ?: return null
            s = s.substringBeforeLast(':') + ":" + (v4 shr 16).toString(16) + ":" + (v4 and 0xFFFF).toString(16)
        }
        val halves = s.split("::")
        if (halves.size > 2) return null
        fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
        val head = groups(halves[0])
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        val missing = 8 - head.size - tail.size
        if (halves.size == 1 && missing != 0) return null
        if (halves.size == 2 && missing < 1) return null
        val all = head + List(missing.coerceAtLeast(0)) { "0" } + tail
        var value = BigInteger.ZERO
        for (g in all) {
            if (g.isEmpty() || g.length > 4) return null
            val n = g.toIntOrNull(16) ?: return null
            value = value.shiftLeft(16).or(BigInteger.valueOf(n.toLong()))
        }
        return value
    }

    private fun groups(value: BigInteger): List<Int> =
        (0 until 8).map { value.shiftRight(112 - 16 * it).and(BigInteger.valueOf(0xFFFF)).toInt() }

    /** All eight groups, zero padded: 2001:0db8:0000:…:0001. */
    fun expanded(value: BigInteger): String = groups(value).joinToString(":") { it.toString(16).padStart(4, '0') }

    /** RFC 5952 short form: the longest run of zero groups (2 or more) becomes "::". */
    fun compressed(value: BigInteger): String {
        val g = groups(value)
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen && j - i >= 2) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            } else {
                i++
            }
        }
        val hex = g.map { it.toString(16) }
        if (bestStart < 0) return hex.joinToString(":")
        val left = hex.subList(0, bestStart).joinToString(":")
        val right = hex.subList(bestStart + bestLen, 8).joinToString(":")
        return "$left::$right"
    }

    /** Reverse DNS name of the prefix (whole nibbles only), for PTR zones. */
    fun reverse(info: Info): String {
        val nibbles = expanded(info.network).replace(":", "")
        val keep = maxOf(1, info.prefix / 4)
        return nibbles.take(keep).reversed().toCharArray().joinToString(".") + ".ip6.arpa"
    }

    /** Number of /[child] prefixes in [info], as text ("256", "2^32"). */
    fun count(info: Info, child: Int): String {
        val bits = child - info.prefix
        return when {
            bits < 0 -> "0"
            bits <= 40 -> "%,d".format(1L shl bits).replace(',', ' ')
            else -> "2^$bits"
        }
    }

    /** Splits [info] into prefixes of [newPrefix] (at most [limit] listed). */
    fun split(info: Info, newPrefix: Int, limit: Int = 64): List<Info> {
        if (newPrefix <= info.prefix || newPrefix > 128) return emptyList()
        val size = BigInteger.ONE.shiftLeft(128 - newPrefix)
        val bits = newPrefix - info.prefix
        val count = if (bits >= 31) limit.toLong() else minOf(1L shl bits, limit.toLong())
        return (0 until count).map { Info(info.network.add(size.multiply(BigInteger.valueOf(it))), newPrefix) }
    }

    /** Modified EUI-64 interface ID from a MAC ("aa:bb:cc:dd:ee:ff", "-" or no separator). */
    fun eui64(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }.lowercase()
        if (hex.length != 12 || hex.any { it !in "0123456789abcdef" }) return null
        val bytes = hex.chunked(2).map { it.toInt(16) }.toMutableList()
        bytes[0] = bytes[0] xor 0x02
        val id = bytes.subList(0, 3) + listOf(0xff, 0xfe) + bytes.subList(3, 6)
        return id.chunked(2).joinToString(":") { (a, b) -> ((a shl 8) or b).toString(16) }
    }

    /** The prefix's network with [interfaceId] ("21a:2bff:fe3c:4d5e") as its last 64 bits. */
    fun withInterfaceId(info: Info, interfaceId: String): String? =
        address("::$interfaceId")?.let { compressed(info.network.or(it)) }

    private fun inRange(value: BigInteger, net: String, prefix: Int): Boolean =
        Info(value, prefix).network == address(net)

    /** Text report shared by the KIT panel and the `subnet` terminal command. */
    fun report(info: Info): List<Pair<String, String>> = listOf(
        "Adresse" to "${compressed(info.address)}/${info.prefix}",
        "Développée" to expanded(info.address),
        "Préfixe" to "${compressed(info.network)}/${info.prefix}",
        "Première" to compressed(info.network),
        "Dernière" to compressed(info.last),
        "Sous-réseaux /64" to if (info.prefix <= 64) count(info, 64) else "—",
        "Type" to info.kind,
        "Zone inverse" to reverse(info),
    )
}
