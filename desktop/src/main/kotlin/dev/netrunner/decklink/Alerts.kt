package dev.netrunner.decklink

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What needs looking at, from the fleet reports and the kit: a disk nearly full, a PC that went
 * quiet, updates waiting or missing, a warranty running out, stock under its threshold.
 */
object Alerts {
    enum class Level { Urgent, Warning }
    enum class Kind { Fleet, Inventory, Stock }

    /** [key] stays the same while the problem lasts, so a notification is sent only once. */
    data class Alert(val key: String, val level: Level, val kind: Kind, val subject: String, val text: String)

    const val DISK_FREE_GB = 10L
    const val DISK_FREE_PERCENT = 10L
    const val QUIET_DAYS = 14L
    const val UPDATE_DAYS = 45L
    const val WARRANTY_DAYS = 60L
    /** An expired warranty stays listed this long, then it is old news. */
    const val EXPIRED_DAYS = 30L

    private const val DAY = 24 * 60 * 60 * 1000L
    private val Day = DateTimeFormatter.ofPattern("dd.MM.yy").withZone(ZoneId.systemDefault())
    private fun day(ms: Long) = Day.format(Instant.ofEpochMilli(ms))

    fun compute(kit: KitData, fleet: List<Fleet.Report>, now: Long = System.currentTimeMillis()): List<Alert> {
        val out = mutableListOf<Alert>()
        fleet.forEach { r ->
            val pc = r.hostname
            r.disks.filter { it.freeGB >= 0 && it.sizeGB > 0 }.forEach { d ->
                val percent = d.freeGB * 100 / d.sizeGB
                if (d.freeGB < DISK_FREE_GB || percent < DISK_FREE_PERCENT) {
                    val level = if (d.freeGB < DISK_FREE_GB / 2 || percent < DISK_FREE_PERCENT / 2) Level.Urgent else Level.Warning
                    out += Alert("disk:$pc:${d.name}", level, Kind.Fleet, pc, "disque ${d.name} presque plein : ${d.freeGB} Go libres sur ${d.sizeGB} ($percent %)")
                }
            }
            val quiet = (now - r.seen) / DAY
            if (quiet >= QUIET_DAYS) out += Alert("quiet:$pc", Level.Warning, Kind.Fleet, pc, "aucun rapport depuis $quiet jours (vu le ${day(r.seen)})")
            val u = r.updates
            if (u.rebootRequired) out += Alert("reboot:$pc", Level.Warning, Kind.Fleet, pc, "redémarrage requis pour finir les mises à jour")
            if (u.pending > 0) out += Alert("pending:$pc", Level.Warning, Kind.Fleet, pc, "${u.pending} mise(s) à jour Windows en attente")
            if (u.lastInstalled > 0) {
                val old = (now - u.lastInstalled) / DAY
                if (old >= UPDATE_DAYS) out += Alert("stale:$pc", Level.Urgent, Kind.Fleet, pc, "aucune mise à jour installée depuis $old jours (${day(u.lastInstalled)})")
            }
        }
        kit.equipment.filter { it.warranty > 0 }.forEach { e ->
            val left = Math.floorDiv(e.warranty - now, DAY)
            val name = e.name.ifBlank { e.code }
            when {
                left < -EXPIRED_DAYS -> {}
                left < 0 -> out += Alert("warranty:${e.id}:${e.warranty}", Level.Urgent, Kind.Inventory, name, "garantie expirée le ${day(e.warranty)}")
                left <= WARRANTY_DAYS -> out += Alert(
                    "warranty:${e.id}:${e.warranty}", if (left <= 15) Level.Urgent else Level.Warning, Kind.Inventory, name,
                    "garantie jusqu'au ${day(e.warranty)} (encore $left jour${if (left > 1) "s" else ""})",
                )
            }
        }
        kit.stock.filter { it.low }.forEach { s ->
            out += Alert("stock:${s.id}", if (s.qty == 0L) Level.Urgent else Level.Warning, Kind.Stock, s.name, "stock bas : ${s.qty} ${s.unit} (seuil ${s.threshold})")
        }
        return out.sortedWith(compareBy({ it.level }, { it.kind }, { it.subject.lowercase() }))
    }
}
