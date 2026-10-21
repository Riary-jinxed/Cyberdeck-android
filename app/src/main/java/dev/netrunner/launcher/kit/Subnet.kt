package dev.netrunner.launcher.kit

/** IPv4 subnet arithmetic, computed locally (nothing is sent on the network). */
object Subnet {
    data class Info(
        val address: Long,
        val prefix: Int,
        val network: Long,
        val broadcast: Long,
        val mask: Long,
    ) {
        val wildcard: Long get() = mask.inv() and 0xFFFFFFFFL
        val total: Long get() = 1L shl (32 - prefix)
        /** Usable hosts: /31 and /32 are special cases (point-to-point and single host). */
        val hosts: Long get() = when (prefix) {
            32 -> 1
            31 -> 2
            else -> total - 2
        }
        val firstHost: Long get() = if (prefix >= 31) network else network + 1
        val lastHost: Long get() = if (prefix >= 31) broadcast else broadcast - 1
        val kind: String get() = when {
            inRange(address, "10.0.0.0", 8) || inRange(address, "172.16.0.0", 12) || inRange(address, "192.168.0.0", 16) -> "privée (RFC 1918)"
            inRange(address, "100.64.0.0", 10) -> "CGNAT (RFC 6598)"
            inRange(address, "127.0.0.0", 8) -> "boucle locale"
            inRange(address, "169.254.0.0", 16) -> "lien local (APIPA)"
            inRange(address, "224.0.0.0", 4) -> "multicast"
            else -> "publique"
        }
    }

    /** Accepts "192.168.1.10/24", "192.168.1.10 255.255.255.0" or "192.168.1.10/255.255.255.0". */
    fun parse(input: String): Info? {
        val parts = input.trim().split('/', ' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val ip = ip(parts[0]) ?: return null
        val prefix = when {
            parts.size == 1 -> 24
            parts[1].contains('.') -> prefixOf(ip(parts[1]) ?: return null) ?: return null
            else -> parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        }
        return of(ip, prefix)
    }

    fun of(ip: Long, prefix: Int): Info {
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return Info(ip, prefix, network, broadcast, mask)
    }

    /** Splits [info] into equal subnets of the new prefix (at most [limit] listed). */
    fun split(info: Info, newPrefix: Int, limit: Int = 64): List<Info> {
        if (newPrefix <= info.prefix || newPrefix > 32) return emptyList()
        val size = 1L shl (32 - newPrefix)
        val count = 1L shl (newPrefix - info.prefix)
        return (0 until minOf(count, limit.toLong())).map { of(info.network + it * size, newPrefix) }
    }

    /** Smallest prefix that holds [hosts] usable addresses. */
    fun prefixForHosts(hosts: Long): Int {
        for (p in 32 downTo 0) if (of(0, p).hosts >= hosts) return p
        return 0
    }

    fun ip(text: String): Long? {
        val octets = text.trim().split('.')
        if (octets.size != 4) return null
        var value = 0L
        for (o in octets) {
            val n = o.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    fun text(ip: Long): String = "${ip shr 24 and 255}.${ip shr 16 and 255}.${ip shr 8 and 255}.${ip and 255}"

    fun binary(ip: Long): String =
        (0..3).joinToString(".") { i -> (ip shr (24 - 8 * i) and 255).toString(2).padStart(8, '0') }

    /** Prefix length of a contiguous mask, null if the mask has holes. */
    private fun prefixOf(mask: Long): Int? {
        val p = java.lang.Long.bitCount(mask)
        return p.takeIf { of(0, it).mask == mask }
    }

    private fun inRange(ip: Long, net: String, prefix: Int): Boolean = of(ip, prefix).network == ip(net)

    /** Text report shared by the KIT panel and the `subnet` terminal command. */
    fun report(info: Info): List<Pair<String, String>> = listOf(
        "Adresse" to "${text(info.address)}/${info.prefix}",
        "Masque" to text(info.mask),
        "Wildcard" to text(info.wildcard),
        "Réseau" to text(info.network),
        "Broadcast" to text(info.broadcast),
        "1er hôte" to text(info.firstHost),
        "Dernier hôte" to text(info.lastHost),
        "Hôtes utilisables" to "%,d".format(info.hosts).replace(',', ' '),
        "Type" to info.kind,
    )
}
