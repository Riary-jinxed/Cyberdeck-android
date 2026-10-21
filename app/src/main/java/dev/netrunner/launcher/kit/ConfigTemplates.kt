package dev.netrunner.launcher.kit

/**
 * A config snippet to paste into a switch or router console. Variables are written {{NAME}} and
 * filled in on the phone; the text is only copied, never sent anywhere by the app.
 */
data class ConfigTemplate(val id: String, val vendor: String, val title: String, val body: String) {
    /** Variable names in order of first appearance. */
    val variables: List<String>
        get() = VAR.findAll(body).map { it.groupValues[1] }.distinct().toList()

    /** The body with each known variable replaced; empty ones stay as {{NAME}}. */
    fun render(values: Map<String, String>): String =
        VAR.replace(body) { m -> values[m.groupValues[1]]?.takeIf { it.isNotBlank() } ?: m.value }

    companion object {
        private val VAR = Regex("""\{\{([A-Z0-9_]+)\}\}""")
    }
}

object ConfigTemplates {
    val Vendors = listOf("Cisco", "HP / Aruba", "Ubiquiti", "Autre")

    /** Example values offered the first time a variable is seen. */
    val Hints = mapOf(
        "VLAN_ID" to "20",
        "VLAN_NAME" to "VOIX",
        "PORT" to "GigabitEthernet1/0/12",
        "PORTS" to "1-24",
        "UPLINK" to "GigabitEthernet1/0/48",
        "ALLOWED_VLANS" to "10,20,30",
        "NATIVE_VLAN" to "1",
        "DESCRIPTION" to "Bureau 12",
        "HOSTNAME" to "SW-ETAGE1",
        "DOMAIN" to "exemple.local",
        "USER" to "admin",
        "IP" to "192.168.10.2",
        "MASK" to "255.255.255.0",
        "PREFIX" to "24",
        "GATEWAY" to "192.168.10.1",
        "INTERFACE" to "eth1",
        "MGMT_NET" to "192.168.10.0",
        "WILDCARD" to "0.0.0.255",
    )

    val defaults: List<ConfigTemplate> = listOf(
        ConfigTemplate(
            "cfg-cisco-vlan", "Cisco", "Créer un VLAN",
            """
            configure terminal
             vlan {{VLAN_ID}}
              name {{VLAN_NAME}}
             exit
            end
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-cisco-access", "Cisco", "Port d'accès",
            """
            configure terminal
             interface {{PORT}}
              description {{DESCRIPTION}}
              switchport mode access
              switchport access vlan {{VLAN_ID}}
              spanning-tree portfast
              no shutdown
             exit
            end
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-cisco-trunk", "Cisco", "Port trunk",
            """
            configure terminal
             interface {{UPLINK}}
              description {{DESCRIPTION}}
              switchport mode trunk
              switchport trunk native vlan {{NATIVE_VLAN}}
              switchport trunk allowed vlan {{ALLOWED_VLANS}}
              no shutdown
             exit
            end
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-cisco-ssh", "Cisco", "Activer SSH (désactiver Telnet)",
            """
            configure terminal
             hostname {{HOSTNAME}}
             ip domain-name {{DOMAIN}}
             crypto key generate rsa modulus 2048
             ip ssh version 2
             username {{USER}} privilege 15 secret <mot de passe>
             line vty 0 15
              transport input ssh
              login local
             exit
             access-list 10 permit {{MGMT_NET}} {{WILDCARD}}
             line vty 0 15
              access-class 10 in
             exit
            end
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-cisco-mgmt", "Cisco", "IP de gestion",
            """
            configure terminal
             interface vlan {{VLAN_ID}}
              ip address {{IP}} {{MASK}}
              no shutdown
             exit
             ip default-gateway {{GATEWAY}}
            end
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-hp-vlan", "HP / Aruba", "VLAN et ports (ProCurve / ArubaOS-Switch)",
            """
            configure
            vlan {{VLAN_ID}}
               name "{{VLAN_NAME}}"
               untagged {{PORTS}}
               tagged {{UPLINK}}
               exit
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-hp-mgmt", "HP / Aruba", "IP de gestion (ProCurve / ArubaOS-Switch)",
            """
            configure
            vlan {{VLAN_ID}}
               ip address {{IP}} {{MASK}}
               exit
            ip default-gateway {{GATEWAY}}
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-hp-ssh", "HP / Aruba", "Activer SSH (ProCurve / ArubaOS-Switch)",
            """
            configure
            hostname "{{HOSTNAME}}"
            crypto key generate ssh rsa bits 2048
            ip ssh
            no telnet-server
            password manager user-name {{USER}}
            ip authorized-managers {{MGMT_NET}} {{MASK}}
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-aoscx-vlan", "HP / Aruba", "VLAN, accès et trunk (AOS-CX)",
            """
            configure terminal
            vlan {{VLAN_ID}}
                name {{VLAN_NAME}}
            interface {{PORT}}
                no shutdown
                no routing
                vlan access {{VLAN_ID}}
            interface {{UPLINK}}
                no shutdown
                no routing
                vlan trunk native {{NATIVE_VLAN}}
                vlan trunk allowed {{ALLOWED_VLANS}}
            end
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-edgeswitch-vlan", "Ubiquiti", "VLAN et port (EdgeSwitch)",
            """
            enable
            vlan database
            vlan {{VLAN_ID}}
            vlan name {{VLAN_ID}} {{VLAN_NAME}}
            exit
            configure
            interface {{PORT}}
            vlan participation include {{VLAN_ID}}
            vlan pvid {{VLAN_ID}}
            exit
            interface {{UPLINK}}
            vlan participation include {{VLAN_ID}}
            vlan tagging {{VLAN_ID}}
            exit
            exit
            write memory
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-edgeos-vlan", "Ubiquiti", "Interface VLAN (EdgeRouter)",
            """
            configure
            set interfaces ethernet {{INTERFACE}} vif {{VLAN_ID}} description '{{VLAN_NAME}}'
            set interfaces ethernet {{INTERFACE}} vif {{VLAN_ID}} address {{IP}}/{{PREFIX}}
            commit
            save
            exit
            """.trimIndent(),
        ),
        ConfigTemplate(
            "cfg-unifi-adopt", "Ubiquiti", "Adopter un appareil UniFi (SSH)",
            """
            set-inform http://{{IP}}:8080/inform
            """.trimIndent(),
        ),
    )
}
