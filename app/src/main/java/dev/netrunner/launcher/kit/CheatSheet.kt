package dev.netrunner.launcher.kit

/** Offline network memo: what a technician looks up on site, bundled in the app. */
object CheatSheet {
    data class Entry(val key: String, val value: String, val detail: String = "")

    data class Section(val title: String, val entries: List<Entry>)

    val sections: List<Section> = listOf(
        Section(
            "PORTS COURANTS",
            listOf(
                Entry("20-21", "FTP", "TCP · données / contrôle"),
                Entry("22", "SSH / SFTP", "TCP"),
                Entry("23", "Telnet", "TCP · en clair, à éviter"),
                Entry("25", "SMTP", "TCP · relais entre serveurs"),
                Entry("53", "DNS", "UDP + TCP"),
                Entry("67-68", "DHCP", "UDP · serveur / client"),
                Entry("69", "TFTP", "UDP · firmwares, téléphones IP"),
                Entry("80", "HTTP", "TCP"),
                Entry("88", "Kerberos", "TCP + UDP · Active Directory"),
                Entry("110", "POP3", "TCP · 995 en TLS"),
                Entry("123", "NTP", "UDP"),
                Entry("135", "RPC (MS)", "TCP"),
                Entry("137-139", "NetBIOS", "UDP 137-138, TCP 139"),
                Entry("143", "IMAP", "TCP · 993 en TLS"),
                Entry("161-162", "SNMP", "UDP · requêtes / traps"),
                Entry("389", "LDAP", "TCP + UDP · 636 en LDAPS"),
                Entry("443", "HTTPS", "TCP · aussi UDP pour QUIC"),
                Entry("445", "SMB", "TCP · partages Windows"),
                Entry("465 / 587", "SMTP (envoi)", "TCP · TLS implicite / STARTTLS"),
                Entry("514", "Syslog", "UDP · 6514 en TLS"),
                Entry("853", "DNS over TLS", "TCP"),
                Entry("1194", "OpenVPN", "UDP par défaut"),
                Entry("1433", "SQL Server", "TCP"),
                Entry("1812-1813", "RADIUS", "UDP · auth / comptabilité"),
                Entry("3306", "MySQL / MariaDB", "TCP"),
                Entry("3389", "RDP", "TCP + UDP"),
                Entry("5060-5061", "SIP", "UDP/TCP · 5061 en TLS"),
                Entry("5432", "PostgreSQL", "TCP"),
                Entry("5900", "VNC", "TCP"),
                Entry("8080 / 8443", "HTTP(S) alternatif", "TCP · interfaces d'admin"),
                Entry("8291", "Winbox (MikroTik)", "TCP"),
                Entry("8443", "Contrôleur UniFi", "TCP · 8080 inform, 3478 UDP STUN"),
                Entry("10001", "Découverte Ubiquiti", "UDP"),
                Entry("51820", "WireGuard", "UDP par défaut"),
            ),
        ),
        Section(
            "RJ45 · T568B (le plus courant)",
            listOf(
                Entry("1", "Blanc / orange", "TX+ · PoE mode A"),
                Entry("2", "Orange", "TX− · PoE mode A"),
                Entry("3", "Blanc / vert", "RX+ · PoE mode A"),
                Entry("4", "Bleu", "Gigabit · PoE mode B"),
                Entry("5", "Blanc / bleu", "Gigabit · PoE mode B"),
                Entry("6", "Vert", "RX− · PoE mode A"),
                Entry("7", "Blanc / marron", "Gigabit · PoE mode B"),
                Entry("8", "Marron", "Gigabit · PoE mode B"),
            ),
        ),
        Section(
            "RJ45 · T568A",
            listOf(
                Entry("1", "Blanc / vert"),
                Entry("2", "Vert"),
                Entry("3", "Blanc / orange"),
                Entry("4", "Bleu"),
                Entry("5", "Blanc / bleu"),
                Entry("6", "Orange"),
                Entry("7", "Blanc / marron"),
                Entry("8", "Marron"),
                Entry("Droit", "B ↔ B (ou A ↔ A)", "Câble standard"),
                Entry("Croisé", "A ↔ B", "Rarement utile : l'auto MDI-X le gère"),
            ),
        ),
        Section(
            "CATÉGORIES DE CÂBLE",
            listOf(
                Entry("Cat 5e", "1 Gb/s", "100 MHz · 100 m"),
                Entry("Cat 6", "1 Gb/s · 10 Gb/s ≤ 55 m", "250 MHz"),
                Entry("Cat 6A", "10 Gb/s", "500 MHz · 100 m"),
                Entry("Cat 7 / 7A", "10 Gb/s", "600-1000 MHz · blindé S/FTP"),
                Entry("Cat 8", "25-40 Gb/s", "2000 MHz · 30 m, salle serveur"),
                Entry("Longueur", "100 m max", "90 m de liaison fixe + 10 m de cordons"),
            ),
        ),
        Section(
            "POE",
            listOf(
                Entry("802.3af", "PoE · 15,4 W", "12,95 W au terminal · type 1"),
                Entry("802.3at", "PoE+ · 30 W", "25,5 W au terminal · type 2"),
                Entry("802.3bt type 3", "PoE++ · 60 W", "51 W au terminal · 4 paires"),
                Entry("802.3bt type 4", "PoE++ · 90-100 W", "71 W au terminal · 4 paires"),
                Entry("Passif 24 V", "Ubiquiti ancien", "Non compatible 802.3af/at : vérifier avant de brancher"),
            ),
        ),
        Section(
            "NORMES WI-FI",
            listOf(
                Entry("802.11b", "11 Mb/s", "2,4 GHz · 1999"),
                Entry("802.11a", "54 Mb/s", "5 GHz · 1999"),
                Entry("802.11g", "54 Mb/s", "2,4 GHz · 2003"),
                Entry("Wi-Fi 4 · 802.11n", "600 Mb/s", "2,4 + 5 GHz · MIMO 4×4 · 2009"),
                Entry("Wi-Fi 5 · 802.11ac", "6,9 Gb/s", "5 GHz · MU-MIMO descendant · 2013"),
                Entry("Wi-Fi 6 · 802.11ax", "9,6 Gb/s", "2,4 + 5 GHz · OFDMA · 2019"),
                Entry("Wi-Fi 6E", "9,6 Gb/s", "+ 6 GHz · 2021"),
                Entry("Wi-Fi 7 · 802.11be", "46 Gb/s", "2,4 + 5 + 6 GHz · canaux 320 MHz, MLO · 2024"),
                Entry("Canaux 2,4 GHz", "1, 6, 11", "Seuls canaux sans chevauchement (20 MHz)"),
                Entry("Canaux 5 GHz", "36-48 sans DFS", "52-144 avec DFS (radar), 149-165 selon pays"),
                Entry("Sécurité", "WPA3-SAE, sinon WPA2-AES", "Éviter WEP, WPA et TKIP"),
            ),
        ),
        Section(
            "OID SNMP UTILES",
            listOf(
                Entry("sysDescr", "1.3.6.1.2.1.1.1.0", "Description de l'équipement"),
                Entry("sysObjectID", "1.3.6.1.2.1.1.2.0", "Modèle (OID constructeur)"),
                Entry("sysUpTime", "1.3.6.1.2.1.1.3.0", "Temps depuis le démarrage (centièmes de s)"),
                Entry("sysContact", "1.3.6.1.2.1.1.4.0"),
                Entry("sysName", "1.3.6.1.2.1.1.5.0", "Nom d'hôte"),
                Entry("sysLocation", "1.3.6.1.2.1.1.6.0"),
                Entry("ifNumber", "1.3.6.1.2.1.2.1.0", "Nombre d'interfaces"),
                Entry("ifDescr", "1.3.6.1.2.1.2.2.1.2", "Nom de chaque interface (table)"),
                Entry("ifOperStatus", "1.3.6.1.2.1.2.2.1.8", "1 up · 2 down"),
                Entry("ifHCInOctets", "1.3.6.1.2.1.31.1.1.1.6", "Octets reçus, compteur 64 bits"),
                Entry("ifHCOutOctets", "1.3.6.1.2.1.31.1.1.1.10", "Octets émis, compteur 64 bits"),
                Entry("ifAlias", "1.3.6.1.2.1.31.1.1.1.18", "Description saisie sur le port"),
                Entry("hrProcessorLoad", "1.3.6.1.2.1.25.3.3.1.2", "Charge CPU (HOST-RESOURCES)"),
                Entry("hrStorageUsed", "1.3.6.1.2.1.25.2.3.1.6", "Stockage utilisé (table)"),
                Entry("dot1dTpFdbPort", "1.3.6.1.2.1.17.4.3.1.2", "Table MAC → port (BRIDGE-MIB)"),
                Entry("lldpRemSysName", "1.0.8802.1.1.2.1.4.1.1.9", "Voisins LLDP"),
                Entry("pethPsePortDetectionStatus", "1.3.6.1.2.1.105.1.1.1.6", "État PoE par port"),
            ),
        ),
        Section(
            "RÉSEAUX RÉSERVÉS",
            listOf(
                Entry("10.0.0.0/8", "Privé", "RFC 1918"),
                Entry("172.16.0.0/12", "Privé", "RFC 1918"),
                Entry("192.168.0.0/16", "Privé", "RFC 1918"),
                Entry("100.64.0.0/10", "CGNAT", "RFC 6598"),
                Entry("169.254.0.0/16", "APIPA / lien local", "Pas de DHCP reçu"),
                Entry("fe80::/10", "IPv6 lien local"),
                Entry("fc00::/7", "IPv6 ULA (privé)", "En pratique fd00::/8"),
                Entry("2001:db8::/32", "IPv6 documentation"),
            ),
        ),
    )

    /** Sections filtered by [query] (matches any field, case and accents ignored). */
    fun search(query: String): List<Section> {
        val q = fold(query.trim())
        if (q.isEmpty()) return sections
        return sections.mapNotNull { s ->
            if (fold(s.title).contains(q)) return@mapNotNull s
            val hits = s.entries.filter { e -> listOf(e.key, e.value, e.detail).any { fold(it).contains(q) } }
            if (hits.isEmpty()) null else s.copy(entries = hits)
        }
    }

    private fun fold(s: String) =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
