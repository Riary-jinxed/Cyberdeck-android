package dev.netrunner.launcher.kit

/**
 * Builds a whole switch config from the field data: the site's VLANs (addressing plan), the
 * ports surveyed on that switch (cable records) and a management address. The text is only
 * shown and copied; it is never sent to the switch by the app.
 */
object SwitchConfig {
    enum class Os(val label: String, val portHint: String) {
        Ios("Cisco IOS", "GigabitEthernet1/0/"),
        ProCurve("HP ProCurve", ""),
        AosCx("Aruba AOS-CX", "1/1/"),
    }

    data class Input(
        val os: Os,
        val hostname: String,
        val site: String,
        /** Matches CableRecord.switchName (case ignored). */
        val switchName: String,
        /** Prefix put before bare port numbers ("24" → "GigabitEthernet1/0/24"). */
        val portPrefix: String,
        /** Uplink ports, comma separated, trunked with every VLAN of the site. */
        val uplinks: String,
        val mgmtPlanId: String,
        val mgmtIp: String,
    )

    /** The ports of [input] as (port, vlan, description), from the cable survey. */
    fun ports(input: Input): List<Triple<String, String, String>> =
        KitStore.cables
            .filter { it.switchName.equals(input.switchName.trim(), ignoreCase = true) && it.port.isNotBlank() }
            .map { c ->
                val vlan = Regex("""\d+""").find(c.vlan)?.value.orEmpty()
                Triple(portName(input, c.port), vlan, c.outlet.ifBlank { c.notes.take(30) })
            }
            .sortedWith(compareBy({ it.first.length }, { it.first }))

    private fun portName(input: Input, port: String): String {
        val p = port.trim()
        return if (p.all { it.isDigit() || it == '/' }) input.portPrefix + p else p
    }

    private fun uplinkList(input: Input) = input.uplinks.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { portName(input, it) }

    private fun plans(input: Input) = NetPlan.plans
        .filter { it.site == input.site && it.vlan.toIntOrNull() != null }
        .sortedBy { it.vlan.toInt() }

    fun build(input: Input, date: String): String {
        val plans = plans(input)
        val ports = ports(input)
        val uplinks = uplinkList(input)
        val mgmt = plans.firstOrNull { it.id == input.mgmtPlanId }
        val mgmtInfo = mgmt?.let { Subnet.parse(it.subnet) }
        val vlanIds = plans.joinToString(",") { it.vlan }
        val host = input.hostname.trim().ifBlank { "SWITCH" }
        val header = "Généré par Netrunner · ${input.site} · ${input.switchName} · $date"
        return when (input.os) {
            Os.Ios -> buildString {
                appendLine("! $header")
                appendLine("configure terminal")
                appendLine("hostname $host")
                appendLine("!")
                plans.forEach { p ->
                    appendLine("vlan ${p.vlan}")
                    if (p.name.isNotBlank()) appendLine(" name ${p.name.replace(' ', '_')}")
                }
                appendLine("!")
                ports.forEach { (port, vlan, desc) ->
                    appendLine("interface $port")
                    if (desc.isNotBlank()) appendLine(" description $desc")
                    appendLine(" switchport mode access")
                    if (vlan.isNotBlank()) appendLine(" switchport access vlan $vlan")
                    appendLine(" spanning-tree portfast")
                    appendLine(" no shutdown")
                    appendLine("!")
                }
                uplinks.forEach { port ->
                    appendLine("interface $port")
                    appendLine(" description UPLINK")
                    appendLine(" switchport mode trunk")
                    if (vlanIds.isNotBlank()) appendLine(" switchport trunk allowed vlan $vlanIds")
                    appendLine(" no shutdown")
                    appendLine("!")
                }
                if (mgmt != null && mgmtInfo != null && input.mgmtIp.isNotBlank()) {
                    appendLine("interface vlan ${mgmt.vlan}")
                    appendLine(" ip address ${input.mgmtIp} ${Subnet.text(mgmtInfo.mask)}")
                    appendLine(" no shutdown")
                    appendLine("!")
                    if (mgmt.gateway.isNotBlank()) appendLine("ip default-gateway ${mgmt.gateway}")
                }
                appendLine("end")
                appendLine("write memory")
            }
            Os.ProCurve -> buildString {
                appendLine("; $header")
                appendLine("configure")
                appendLine("hostname \"$host\"")
                plans.forEach { p ->
                    val untagged = ports.filter { it.second == p.vlan }.joinToString(",") { it.first }
                    appendLine("vlan ${p.vlan}")
                    if (p.name.isNotBlank()) appendLine("   name \"${p.name}\"")
                    if (untagged.isNotBlank()) appendLine("   untagged $untagged")
                    if (uplinks.isNotEmpty()) appendLine("   tagged ${uplinks.joinToString(",")}")
                    if (p.id == mgmt?.id && mgmtInfo != null && input.mgmtIp.isNotBlank()) {
                        appendLine("   ip address ${input.mgmtIp} ${Subnet.text(mgmtInfo.mask)}")
                    }
                    appendLine("   exit")
                }
                if (mgmt != null && mgmt.gateway.isNotBlank()) appendLine("ip default-gateway ${mgmt.gateway}")
                ports.filter { it.third.isNotBlank() }.forEach { (port, _, desc) ->
                    appendLine("interface $port")
                    appendLine("   name \"$desc\"")
                    appendLine("   exit")
                }
                appendLine("write memory")
            }
            Os.AosCx -> buildString {
                appendLine("! $header")
                appendLine("configure terminal")
                appendLine("hostname $host")
                plans.forEach { p ->
                    appendLine("vlan ${p.vlan}")
                    if (p.name.isNotBlank()) appendLine("    name ${p.name.replace(' ', '_')}")
                }
                ports.forEach { (port, vlan, desc) ->
                    appendLine("interface $port")
                    if (desc.isNotBlank()) appendLine("    description $desc")
                    appendLine("    no shutdown")
                    appendLine("    no routing")
                    if (vlan.isNotBlank()) appendLine("    vlan access $vlan")
                }
                uplinks.forEach { port ->
                    appendLine("interface $port")
                    appendLine("    description UPLINK")
                    appendLine("    no shutdown")
                    appendLine("    no routing")
                    if (vlanIds.isNotBlank()) appendLine("    vlan trunk allowed $vlanIds")
                }
                if (mgmt != null && mgmtInfo != null && input.mgmtIp.isNotBlank()) {
                    appendLine("interface vlan ${mgmt.vlan}")
                    appendLine("    ip address ${input.mgmtIp}/${mgmtInfo.prefix}")
                    if (mgmt.gateway.isNotBlank()) appendLine("ip route 0.0.0.0/0 ${mgmt.gateway}")
                }
                appendLine("end")
                appendLine("write memory")
            }
        }.trimEnd()
    }

    /** Things worth a look before pasting: ports without VLAN, VLANs missing from the plan, duplicates. */
    fun warnings(input: Input): List<String> {
        val plans = plans(input).map { it.vlan }.toSet()
        val ports = ports(input)
        val out = mutableListOf<String>()
        if (ports.isEmpty()) out += "aucun câble relevé sur « ${input.switchName} » (onglet CÂBLES, champ switch)"
        ports.filter { it.second.isBlank() }.forEach { out += "port ${it.first} sans VLAN" }
        ports.map { it.second }.filter { it.isNotBlank() && it !in plans }.distinct().forEach { out += "VLAN $it absent du plan d'adressage du site" }
        ports.groupBy { it.first }.filter { it.value.size > 1 }.keys.forEach { out += "port $it relevé plusieurs fois" }
        if (plans.isEmpty()) out += "aucun VLAN pour le site « ${input.site} » (onglet ADRESSAGE)"
        return out
    }
}
