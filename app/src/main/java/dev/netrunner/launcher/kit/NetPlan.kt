package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A VLAN of a site with its IPv4 subnet (the addressing plan, IPAM). */
data class VlanPlan(
    val id: String,
    val site: String,
    val vlan: String,
    val name: String,
    /** "192.168.20.0/24" */
    val subnet: String,
    val gateway: String,
    val notes: String,
)

/** An address given out in a VLAN, to an inventory item or just a host name. */
data class IpAssignment(
    val id: String,
    val planId: String,
    val ip: String,
    val host: String,
    val equipmentId: String,
    val notes: String,
)

/** A rack (baie) of a site, [units] high. */
data class Rack(val id: String, val site: String, val name: String, val units: Int)

/** Something mounted in a rack, from [topU] down over [heightU] units. */
data class RackItem(
    val id: String,
    val rackId: String,
    val topU: Int,
    val heightU: Int,
    val label: String,
    val equipmentId: String,
)

/**
 * Addressing plans and racks, in their own JSON file next to the kit's (same rules: private
 * storage, nothing leaves the phone except what the user exports).
 */
object NetPlan {
    val plans = mutableStateListOf<VlanPlan>()
    val assignments = mutableStateListOf<IpAssignment>()
    val racks = mutableStateListOf<Rack>()
    val rackItems = mutableStateListOf<RackItem>()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "kit_net.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            root.optJSONArray("plans")?.objects()?.forEach { o ->
                plans += VlanPlan(o.getString("id"), o.optString("site"), o.optString("vlan"), o.optString("name"),
                    o.optString("subnet"), o.optString("gateway"), o.optString("notes"))
            }
            root.optJSONArray("ips")?.objects()?.forEach { o ->
                assignments += IpAssignment(o.getString("id"), o.optString("plan"), o.optString("ip"), o.optString("host"),
                    o.optString("equipment"), o.optString("notes"))
            }
            root.optJSONArray("racks")?.objects()?.forEach { o ->
                racks += Rack(o.getString("id"), o.optString("site"), o.optString("name"), o.optInt("units", 42))
            }
            root.optJSONArray("rack_items")?.objects()?.forEach { o ->
                rackItems += RackItem(o.getString("id"), o.optString("rack"), o.optInt("top", 1), o.optInt("height", 1),
                    o.optString("label"), o.optString("equipment"))
            }
        }
    }

    private fun save(context: Context) {
        val root = JSONObject()
            .put("plans", JSONArray(plans.map { p ->
                JSONObject().put("id", p.id).put("site", p.site).put("vlan", p.vlan).put("name", p.name)
                    .put("subnet", p.subnet).put("gateway", p.gateway).put("notes", p.notes)
            }))
            .put("ips", JSONArray(assignments.map { a ->
                JSONObject().put("id", a.id).put("plan", a.planId).put("ip", a.ip).put("host", a.host)
                    .put("equipment", a.equipmentId).put("notes", a.notes)
            }))
            .put("racks", JSONArray(racks.map { r ->
                JSONObject().put("id", r.id).put("site", r.site).put("name", r.name).put("units", r.units)
            }))
            .put("rack_items", JSONArray(rackItems.map { i ->
                JSONObject().put("id", i.id).put("rack", i.rackId).put("top", i.topU).put("height", i.heightU)
                    .put("label", i.label).put("equipment", i.equipmentId)
            }))
        val f = file(context)
        val tmp = File(f.parentFile, "kit_net.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }

    private fun <T> upsertIn(list: MutableList<T>, item: T, same: (T) -> Boolean) {
        val i = list.indexOfFirst(same)
        if (i >= 0) list[i] = item else list += item
    }

    // ------------------------------------------------------------------ addressing plan

    fun sites(): List<String> =
        (plans.map { it.site } + racks.map { it.site }).filter { it.isNotBlank() }.distinct().sorted()

    fun savePlan(context: Context, plan: VlanPlan) {
        upsertIn(plans, plan) { it.id == plan.id }
        save(context)
    }

    fun deletePlan(context: Context, id: String) {
        plans.removeAll { it.id == id }
        assignments.removeAll { it.planId == id }
        save(context)
    }

    fun saveAssignment(context: Context, a: IpAssignment) {
        upsertIn(assignments, a) { it.id == a.id }
        save(context)
    }

    fun deleteAssignment(context: Context, id: String) {
        assignments.removeAll { it.id == id }
        save(context)
    }

    fun assignmentsOf(planId: String): List<IpAssignment> =
        assignments.filter { it.planId == planId }.sortedBy { Subnet.ip(it.ip) ?: Long.MAX_VALUE }

    /** The first usable address of the plan's subnet that is neither assigned nor the gateway. */
    fun nextFree(plan: VlanPlan): String? {
        val info = Subnet.parse(plan.subnet) ?: return null
        val used = assignmentsOf(plan.id).mapNotNull { Subnet.ip(it.ip) }.toSet() + listOfNotNull(Subnet.ip(plan.gateway))
        var ip = info.firstHost
        while (ip <= info.lastHost) {
            if (ip !in used) return Subnet.text(ip)
            ip++
        }
        return null
    }

    /** Problems with one assignment: duplicate, outside the subnet, network or broadcast address. */
    fun problems(plan: VlanPlan, a: IpAssignment): List<String> {
        val ip = Subnet.ip(a.ip) ?: return listOf("adresse invalide")
        val out = mutableListOf<String>()
        Subnet.parse(plan.subnet)?.let { info ->
            if (Subnet.of(ip, info.prefix).network != info.network) out += "hors du sous-réseau"
            else if (info.prefix < 31 && (ip == info.network || ip == info.broadcast)) out += "adresse réseau ou broadcast"
        }
        if (Subnet.ip(plan.gateway) == ip) out += "même adresse que la passerelle"
        // Duplicates across the whole plan of the site, not just this VLAN.
        val sitePlans = plans.filter { it.site == plan.site }.map { it.id }.toSet()
        if (assignments.any { it.id != a.id && it.planId in sitePlans && Subnet.ip(it.ip) == ip }) out += "doublon"
        return out
    }

    /** Subnets of a site that overlap each other. */
    fun overlaps(site: String): List<Pair<VlanPlan, VlanPlan>> {
        val list = plans.filter { it.site == site }.mapNotNull { p -> Subnet.parse(p.subnet)?.let { p to it } }
        val out = mutableListOf<Pair<VlanPlan, VlanPlan>>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val (a, ai) = list[i]
            val (b, bi) = list[j]
            val prefix = minOf(ai.prefix, bi.prefix)
            if (Subnet.of(ai.network, prefix).network == Subnet.of(bi.network, prefix).network) out += a to b
        }
        return out
    }

    fun exportCsv(context: Context, uri: Uri): Boolean = runCatching {
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append('﻿')
            append("Site;VLAN;Nom;Sous-réseau;Passerelle;Adresse;Hôte;Équipement;Notes\r\n")
            plans.sortedWith(compareBy({ it.site }, { it.vlan.toIntOrNull() ?: 0 })).forEach { p ->
                val rows = assignmentsOf(p.id)
                if (rows.isEmpty()) {
                    append(listOf(p.site, p.vlan, p.name, p.subnet, p.gateway, "", "", "", p.notes).joinToString(";") { cell(it) }).append("\r\n")
                }
                rows.forEach { a ->
                    val e = KitStore.equipment.firstOrNull { it.id == a.equipmentId }?.let { it.name.ifBlank { it.code } }.orEmpty()
                    append(listOf(p.site, p.vlan, p.name, p.subnet, p.gateway, a.ip, a.host, e, a.notes).joinToString(";") { cell(it) }).append("\r\n")
                }
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) } ?: error("no stream")
    }.isSuccess

    // ------------------------------------------------------------------ racks

    fun saveRack(context: Context, rack: Rack) {
        upsertIn(racks, rack) { it.id == rack.id }
        save(context)
    }

    fun deleteRack(context: Context, id: String) {
        racks.removeAll { it.id == id }
        rackItems.removeAll { it.rackId == id }
        save(context)
    }

    fun itemsOf(rackId: String): List<RackItem> = rackItems.filter { it.rackId == rackId }.sortedByDescending { it.topU }

    /** The units [item] covers, top first. */
    fun unitsOf(item: RackItem): IntRange = (item.topU - item.heightU + 1)..item.topU

    /** Another item already using one of [item]'s units, if any. */
    fun clash(item: RackItem): RackItem? = rackItems.firstOrNull { other ->
        other.id != item.id && other.rackId == item.rackId && unitsOf(other).any { it in unitsOf(item) }
    }

    fun saveRackItem(context: Context, item: RackItem) {
        upsertIn(rackItems, item) { it.id == item.id }
        save(context)
    }

    fun deleteRackItem(context: Context, id: String) {
        rackItems.removeAll { it.id == id }
        save(context)
    }

    /** Equipment removed from the inventory: drop the links, keep the rows. */
    fun forgetEquipment(context: Context, equipmentId: String) {
        assignments.replaceAll { if (it.equipmentId == equipmentId) it.copy(equipmentId = "") else it }
        rackItems.replaceAll { if (it.equipmentId == equipmentId) it.copy(equipmentId = "") else it }
        save(context)
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
