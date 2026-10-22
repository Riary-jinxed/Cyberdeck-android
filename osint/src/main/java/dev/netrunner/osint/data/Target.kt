package dev.netrunner.osint.data

import java.net.IDN
import java.net.URI
import java.util.Locale

/** What was typed: a domain, an IP address or a full link, reduced to what the lookups need. */
data class Target(val raw: String, val kind: Kind, val host: String, val url: String?) {
    enum class Kind { Domain, Ip, Url }

    val isIp get() = kind == Kind.Ip

    /** The site to fetch for the WEB module. */
    val site get() = url ?: "https://${if (host.contains(':')) "[$host]" else host}/"

    companion object {
        private val V4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
        private val LABEL = Regex("""^[a-z0-9_]([a-z0-9-]{0,61}[a-z0-9])?$""")

        fun isIpv4(s: String) = V4.matches(s)
        fun isIpv6(s: String) = s.count { it == ':' } >= 2 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

        /** null when [input] is neither a domain, an IP nor a link. */
        fun parse(input: String): Target? {
            val s = input.trim().trimEnd('.', '/')
            if (s.isEmpty() || s.contains(' ')) return null
            if (isIpv4(s)) return Target(s, Kind.Ip, s, null)
            val bare = s.removePrefix("[").removeSuffix("]")
            if (isIpv6(bare)) return Target(s, Kind.Ip, bare.lowercase(Locale.ROOT), null)
            if (s.contains("://")) {
                val uri = runCatching { URI(s) }.getOrNull() ?: return null
                if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
                val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
                val kindIp = isIpv4(host) || isIpv6(host)
                val h = if (kindIp) host.lowercase(Locale.ROOT) else domain(host) ?: return null
                return Target(s, Kind.Url, h, s)
            }
            // "example.com/page" without a scheme.
            if (s.contains('/')) return parse("https://$s")
            val d = domain(s.removePrefix("*.")) ?: return null
            return Target(s, Kind.Domain, d, null)
        }

        /** Lower-case ASCII (punycode) form of a host name, or null if it isn't one. */
        fun domain(host: String): String? {
            val ascii = runCatching { IDN.toASCII(host.trim().trimEnd('.'), IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
            val labels = ascii.split('.')
            if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
            if (labels.last().all { it.isDigit() }) return null
            return ascii
        }

        /** "www.shop.example.co.uk" → a best guess at the registered domain, for WHOIS. */
        fun registered(host: String): String {
            val labels = host.split('.')
            if (labels.size <= 2) return host
            // Common second-level suffixes; good enough for the lookup, RDAP redirects the rest.
            val sld = labels[labels.size - 2]
            val twoLevel = sld in setOf("co", "com", "net", "org", "gov", "ac", "edu", "gouv", "asso", "nom", "ltd", "plc") && labels.last().length == 2
            return labels.takeLast(if (twoLevel) 3 else 2).joinToString(".")
        }
    }
}
