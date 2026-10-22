package dev.netrunner.launcher.kit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Network map of a site drawn from what the technician recorded: switches and their ports from
 * the cable survey, outlets and the equipment at the end, VLAN colours and addresses from the
 * addressing plan, racks with their contents. Nothing is discovered on the network: the map
 * shows the documentation, on a white background so it prints and fits a report.
 */
object NetMap {
    data class Port(val port: String, val outlet: String, val vlan: String, val equipment: String, val ip: String, val fault: Boolean)
    data class Switch(val name: String, val ip: String, val ports: List<Port>)
    data class Model(
        val site: String,
        val switches: List<Switch>,
        val vlans: List<VlanPlan>,
        val racks: List<Pair<Rack, List<RackItem>>>,
        val others: List<String>,
    ) {
        val empty get() = switches.isEmpty() && racks.isEmpty() && others.isEmpty()
    }

    private const val COL_W = 540
    private const val ROW_H = 46
    private const val U_H = 22
    private const val PAD = 40
    private const val PER_BAND = 4

    private val Palette = intArrayOf(
        Color.rgb(0, 150, 199), Color.rgb(230, 126, 34), Color.rgb(39, 174, 96), Color.rgb(142, 68, 173),
        Color.rgb(192, 57, 43), Color.rgb(22, 160, 133), Color.rgb(211, 84, 0), Color.rgb(41, 128, 185),
    )

    fun sites(): List<String> =
        (KitStore.cables.map { it.site } + NetPlan.sites()).map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    private fun ipOf(equipmentId: String): String =
        if (equipmentId.isBlank()) "" else NetPlan.assignments.firstOrNull { it.equipmentId == equipmentId }?.ip.orEmpty()

    private fun equipmentNamed(name: String): Equipment? =
        KitStore.equipment.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) || it.code.equals(name.trim(), ignoreCase = true) }

    fun model(site: String): Model {
        val s = site.trim()
        val cables = KitStore.cables.filter { it.site.trim().equals(s, ignoreCase = true) }
        val usedEquipment = mutableSetOf<String>()
        val switches = cables.filter { it.switchName.isNotBlank() }
            .groupBy { it.switchName.trim() }
            .map { (name, list) ->
                val sw = equipmentNamed(name)
                sw?.let { usedEquipment += it.id }
                Switch(
                    name,
                    sw?.let { ipOf(it.id) }.orEmpty(),
                    list.sortedWith(compareBy({ it.port.length }, { it.port })).map { c ->
                        val e = KitStore.equipment.firstOrNull { it.id == c.equipmentId }
                        e?.let { usedEquipment += it.id }
                        Port(
                            c.port.ifBlank { "?" }, c.outlet, Regex("""\d+""").find(c.vlan)?.value.orEmpty(),
                            e?.let { it.name.ifBlank { it.code } }.orEmpty(), e?.let { ipOf(it.id) }.orEmpty(),
                            c.test == KitStore.TestResults.getOrNull(2),
                        )
                    },
                )
            }
            .sortedBy { it.name.lowercase() }
        val racks = NetPlan.racks.filter { it.site.trim().equals(s, ignoreCase = true) }.map { r ->
            NetPlan.itemsOf(r.id).also { items -> items.forEach { if (it.equipmentId.isNotBlank()) usedEquipment += it.equipmentId } }.let { r to it }
        }
        val others = KitStore.equipment
            .filter { it.id !in usedEquipment && it.location.contains(s, ignoreCase = true) }
            .map { e -> listOf(e.name.ifBlank { e.code }, ipOf(e.id), e.location).filter { it.isNotBlank() }.joinToString(" · ") }
        val vlans = NetPlan.plans.filter { it.site.trim().equals(s, ignoreCase = true) }.sortedBy { it.vlan.toIntOrNull() ?: 0 }
        return Model(s, switches, vlans, racks, others)
    }

    private fun vlanColor(model: Model, vlan: String): Int {
        if (vlan.isBlank()) return Color.LTGRAY
        val k = model.vlans.indexOfFirst { it.vlan == vlan }.takeIf { it >= 0 } ?: (vlan.toIntOrNull() ?: vlan.hashCode())
        return Palette[Math.floorMod(k, Palette.size)]
    }

    fun render(model: Model): Bitmap {
        val bands = model.switches.chunked(PER_BAND)
        val bandHeights = bands.map { band -> 110 + (band.maxOfOrNull { it.ports.size } ?: 0) * ROW_H + 30 }
        val rackHeight = model.racks.maxOfOrNull { it.first.units }?.let { it * U_H + 90 } ?: 0
        val vlanRows = (model.vlans.size + 2) / 3
        val width = maxOf(
            1200,
            PAD * 2 + (bands.maxOfOrNull { it.size } ?: 0) * COL_W,
            PAD * 2 + model.racks.size.coerceAtMost(6) * 380,
        )
        val rackBands = model.racks.chunked(6)
        val height = PAD + 110 + vlanRows * 44 + 20 + bandHeights.sum() + rackBands.size * rackHeight +
            (if (model.others.isNotEmpty()) 60 + model.others.size * 30 else 0) + PAD

        val bitmap = Bitmap.createBitmap(width, height.coerceAtMost(12000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        val ink = Color.rgb(30, 34, 40)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 40f; typeface = Typeface.DEFAULT_BOLD }
        val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 90, 120); textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 21f }
        val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 20f; typeface = Typeface.MONOSPACE }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 18f }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(120, 130, 140) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fun fit(p: Paint, s: String, max: Float): String {
            if (p.measureText(s) <= max) return s
            var cut = s
            while (cut.isNotEmpty() && p.measureText("$cut…") > max) cut = cut.dropLast(1)
            return "$cut…"
        }

        var y = PAD.toFloat() + 40
        c.drawText(fit(title, "Plan réseau · ${model.site}", width - PAD * 2f), PAD.toFloat(), y, title)
        y += 32
        c.drawText("Établi le ${LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} d'après le relevé du kit (câbles, inventaire, adressage, baies)", PAD.toFloat(), y, dim)
        y += 38

        // VLAN legend, three per row.
        model.vlans.chunked(3).forEach { row ->
            row.forEachIndexed { k, p ->
                val x = PAD + k * ((width - PAD * 2) / 3f)
                fill.color = vlanColor(model, p.vlan)
                c.drawRect(x, y - 18, x + 24, y + 4, fill)
                c.drawText(fit(text, "VLAN ${p.vlan} ${p.name} · ${p.subnet}" + if (p.gateway.isNotBlank()) " · gw ${p.gateway}" else "", (width - PAD * 2) / 3f - 40), x + 34, y, text)
            }
            y += 44
        }
        y += 20

        bands.forEachIndexed { b, band ->
            val top = y
            band.forEachIndexed { k, sw ->
                val x = PAD + k * COL_W.toFloat()
                // Switch box.
                fill.color = Color.rgb(30, 34, 40)
                val box = RectF(x, top, x + COL_W - 40, top + 70)
                c.drawRoundRect(box, 10f, 10f, fill)
                val white = Paint(title).apply { color = Color.WHITE; textSize = 26f }
                c.drawText(fit(white, sw.name, box.width() - 24), x + 14, top + 32, white)
                val sub = Paint(dim).apply { color = Color.rgb(200, 210, 220) }
                c.drawText(fit(sub, listOf(sw.ip, "${sw.ports.size} ports relevés").filter { it.isNotBlank() }.joinToString(" · "), box.width() - 24), x + 14, top + 58, sub)
                // Trunk line down the column.
                val trunkX = x + 30
                if (sw.ports.isNotEmpty()) c.drawLine(trunkX, top + 70, trunkX, top + 96 + (sw.ports.size - 1) * ROW_H + 18f, stroke)
                sw.ports.forEachIndexed { r, p ->
                    val ry = top + 96 + r * ROW_H
                    c.drawLine(trunkX, ry + 18, trunkX + 30, ry + 18, stroke)
                    // Port tag coloured by VLAN.
                    fill.color = vlanColor(model, p.vlan)
                    val tag = RectF(trunkX + 30, ry, trunkX + 150, ry + 36)
                    c.drawRoundRect(tag, 6f, 6f, fill)
                    val tagText = Paint(mono).apply { color = Color.WHITE; textSize = 18f }
                    c.drawText(fit(tagText, p.port, tag.width() - 12), tag.left + 6, ry + 25, tagText)
                    // Outlet → equipment.
                    val label = listOf(p.outlet.ifBlank { "?" }, p.equipment, p.ip).filter { it.isNotBlank() }.joinToString("  →  ")
                    val lp = if (p.fault) Paint(text).apply { color = Color.rgb(192, 57, 43) } else text
                    c.drawText(fit(lp, (if (p.fault) "⚠ " else "") + label + if (p.vlan.isNotBlank()) "  [${p.vlan}]" else "", COL_W - 220f), tag.right + 12, ry + 25, lp)
                }
            }
            y += bandHeights[b]
        }

        rackBands.forEach { band ->
            val top = y
            band.forEachIndexed { k, (rack, items) ->
                val x = PAD + k * 380f
                c.drawText(fit(h2, "Baie ${rack.name} · ${rack.units}U", 340f), x, top + 30, h2)
                val frame = RectF(x + 36, top + 50, x + 340, top + 50 + rack.units * U_H)
                c.drawRect(frame, stroke)
                for (u in 1..rack.units) {
                    val uy = frame.bottom - u * U_H
                    if (u % 2 == 0 || rack.units <= 24) c.drawText("$u", x, uy + U_H - 5, Paint(dim).apply { textSize = 14f })
                }
                items.forEach { item ->
                    val r = RectF(frame.left + 3, frame.bottom - item.topU * U_H + 2, frame.right - 3, frame.bottom - (item.topU - item.heightU) * U_H - 2)
                    fill.color = Color.rgb(222, 236, 244)
                    c.drawRect(r, fill)
                    c.drawRect(r, stroke)
                    val name = item.label.ifBlank { KitStore.equipment.firstOrNull { it.id == item.equipmentId }?.name.orEmpty() }
                    c.drawText(fit(Paint(text).apply { textSize = 17f }, name, r.width() - 12), r.left + 6, r.top + minOf(r.height(), 22f) - 5, Paint(text).apply { textSize = 17f })
                }
            }
            y += rackHeight
        }

        if (model.others.isNotEmpty()) {
            y += 30
            c.drawText("Autres équipements du site", PAD.toFloat(), y, h2)
            y += 12
            model.others.forEach { line ->
                y += 30
                c.drawText(fit(text, "• $line", width - PAD * 2f), PAD.toFloat(), y, text)
            }
        }
        return bitmap
    }
}
