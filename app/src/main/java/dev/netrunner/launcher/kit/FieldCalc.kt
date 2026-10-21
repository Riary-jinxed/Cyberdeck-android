package dev.netrunner.launcher.kit

import kotlin.math.max

/** PoE budget, fibre link loss and MAC formats: pocket calculations, all local. */
object FieldCalc {
    // ------------------------------------------------------------------ PoE budget

    /**
     * A powered device type. [reservedW] is what a switch reserves for its PoE class (power at
     * the port), [typicalW] what it really draws most of the time.
     */
    data class PoeDevice(val name: String, val standard: String, val reservedW: Double, val typicalW: Double)

    val PoeDevices = listOf(
        PoeDevice("Téléphone IP", "PoE classe 2", 7.0, 4.0),
        PoeDevice("Téléphone IP + écran couleur", "PoE classe 3", 15.4, 7.0),
        PoeDevice("Caméra fixe", "PoE classe 3", 15.4, 6.0),
        PoeDevice("Caméra PTZ", "PoE+ classe 4", 30.0, 20.0),
        PoeDevice("Caméra PTZ + chauffage", "PoE++ classe 6", 60.0, 45.0),
        PoeDevice("Borne Wi-Fi 5", "PoE classe 3", 15.4, 11.0),
        PoeDevice("Borne Wi-Fi 6", "PoE+ classe 4", 30.0, 18.0),
        PoeDevice("Borne Wi-Fi 6E / 7", "PoE++ classe 5", 45.0, 28.0),
        PoeDevice("Lecteur de badge", "PoE classe 2", 7.0, 4.0),
        PoeDevice("Interphone vidéo", "PoE classe 3", 15.4, 9.0),
        PoeDevice("Mini-switch alimenté", "PoE+ classe 4", 30.0, 12.0),
        PoeDevice("Client léger / écran", "PoE++ classe 8", 90.0, 60.0),
    )

    data class PoeResult(val reserved: Double, val typical: Double, val ports: Int, val budget: Double) {
        val reservedLeft get() = budget - reserved
        val typicalLeft get() = budget - typical
        /** Share of the budget reserved, in percent (0 when no budget is set). */
        val load get() = if (budget > 0) reserved / budget * 100 else 0.0
    }

    fun poe(counts: Map<String, Int>, budget: Double): PoeResult {
        var reserved = 0.0
        var typical = 0.0
        var ports = 0
        PoeDevices.forEach { d ->
            val n = counts[d.name] ?: 0
            reserved += n * d.reservedW
            typical += n * d.typicalW
            ports += n
        }
        return PoeResult(reserved, typical, ports, budget)
    }

    // ------------------------------------------------------------------ fibre link loss

    /** Fibre type and wavelength, with the TIA-568 maximum attenuation in dB/km. */
    data class Fiber(val name: String, val dbPerKm: Double)

    val Fibers = listOf(
        Fiber("Monomode 1310 nm", 0.4),
        Fiber("Monomode 1550 nm", 0.3),
        Fiber("Multimode 850 nm", 3.5),
        Fiber("Multimode 1300 nm", 1.5),
    )

    /** TIA-568 maximum values, the usual basis for an acceptance test. */
    const val CONNECTOR_DB = 0.75
    const val SPLICE_DB = 0.3

    data class FiberResult(
        val cable: Double,
        val connectors: Double,
        val splices: Double,
        val margin: Double,
        /** Transmitter power minus receiver sensitivity, null when unknown. */
        val budget: Double?,
    ) {
        val loss get() = cable + connectors + splices
        /** What is left once the link loss and the safety margin are taken from the budget. */
        val headroom get() = budget?.let { it - loss - margin }
    }

    fun fiber(fiber: Fiber, km: Double, connectors: Int, splices: Int, margin: Double, txDbm: Double?, rxDbm: Double?): FiberResult =
        FiberResult(
            cable = max(0.0, km) * fiber.dbPerKm,
            connectors = max(0, connectors) * CONNECTOR_DB,
            splices = max(0, splices) * SPLICE_DB,
            margin = max(0.0, margin),
            budget = if (txDbm != null && rxDbm != null) txDbm - rxDbm else null,
        )

    // ------------------------------------------------------------------ MAC addresses

    data class Mac(val bytes: List<Int>) {
        private val hex get() = bytes.joinToString("") { it.toString(16).padStart(2, '0') }
        val colon get() = hex.chunked(2).joinToString(":")
        val dash get() = hex.chunked(2).joinToString("-").uppercase()
        val cisco get() = hex.chunked(4).joinToString(".")
        val bare get() = hex
        val multicast get() = bytes[0] and 1 == 1
        /** Locally administered: set by software, typically a phone's random Wi-Fi address or a VM. */
        val local get() = bytes[0] and 2 == 2
        val broadcast get() = bytes.all { it == 0xff }
        val oui get() = hex.take(6).uppercase()
        val vendor get() = Vendors[oui]
    }

    /** Accepts aa:bb:cc:dd:ee:ff, AA-BB-…, aabb.ccdd.eeff or aabbccddeeff. */
    fun mac(text: String): Mac? {
        val hex = text.trim().filter { it !in ":-. " }.lowercase()
        if (hex.length != 12 || hex.any { it !in "0123456789abcdef" }) return null
        return Mac(hex.chunked(2).map { it.toInt(16) })
    }

    /** A short offline list of vendors common on business networks (not the full IEEE registry). */
    private val Vendors: Map<String, String> = buildMap {
        fun add(vendor: String, vararg ouis: String) = ouis.forEach { put(it, vendor) }
        add("Cisco", "00000C")
        add("Ubiquiti", "24A43C", "0418D6", "802AA8", "F09FC2", "7483C2", "FCECDA", "788A20")
        add("Aruba (HPE)", "000B86", "001A1E", "24DEC6")
        add("MikroTik", "4C5E0C", "64D154", "D4CA6D", "488F5A")
        add("Fortinet", "00090F")
        add("Palo Alto Networks", "001B17")
        add("Check Point", "001C7F")
        add("Polycom", "0004F2")
        add("Grandstream", "000B82")
        add("Yealink", "001565", "805E0C")
        add("Axis", "00408C", "ACCC8E", "B8A44F")
        add("Hikvision", "4419B6", "C056E3", "BCAD28")
        add("Synology", "001132")
        add("QNAP", "245EBE")
        add("Raspberry Pi", "B827EB", "DCA632", "E45F01")
        add("PC Engines", "000DB9")
        add("Philips Hue", "001788")
        add("Intel", "00A0C9")
        add("VMware", "005056", "000C29", "000569")
        add("VirtualBox", "080027")
        add("Hyper-V", "00155D")
        add("QEMU / KVM", "525400")
    }
}
