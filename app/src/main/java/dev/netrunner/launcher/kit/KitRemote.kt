package dev.netrunner.launcher.kit

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.netrunner.sync.Op
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Applies the changes made in Deck Link on the PC (inventory, consumables, clients, cables).
 * Each change is `{"kind", "id", "at", "deleted", "item": {fields}}`; fields left out keep the
 * phone's value. The most recent edit wins: a change older than the phone's own edit of the same
 * item is dropped. Either way it is acknowledged, so the PC stops sending it.
 */
object KitRemote {
    /** Runs on the main thread (the stores are Compose state) and returns the ids handled. */
    fun apply(context: Context, ops: List<Op>): List<String> {
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) return applyNow(app, ops)
        var done: List<String> = emptyList()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { done = applyNow(app, ops) } finally { latch.countDown() }
        }
        latch.await(30, TimeUnit.SECONDS)
        return done
    }

    private fun applyNow(context: Context, ops: List<Op>): List<String> {
        KitStore.load(context)
        Stock.load(context)
        Clients.load(context)
        NetPlan.load(context)
        return ops.mapNotNull { op ->
            val o = runCatching { JSONObject(op.json) }.getOrNull() ?: return@mapNotNull op.id
            runCatching { applyOne(context, o) }.fold({ op.id }, { null })
        }
    }

    private fun JSONObject.str(key: String, fallback: String) = if (has(key)) optString(key) else fallback
    private fun JSONObject.int(key: String, fallback: Int) = if (has(key)) optInt(key, fallback) else fallback

    private fun applyOne(context: Context, o: JSONObject) {
        val id = o.getString("id")
        val at = o.optLong("at")
        val deleted = o.optBoolean("deleted")
        val item = o.optJSONObject("item") ?: JSONObject()
        when (o.optString("kind")) {
            "equipment" -> {
                val old = KitStore.equipment.firstOrNull { it.id == id }
                if (old != null && old.updated > at) return
                if (deleted) { if (old != null) KitStore.delete(context, id); return }
                KitStore.upsert(
                    context,
                    Equipment(
                        id, item.str("code", old?.code.orEmpty()), item.str("name", old?.name.orEmpty()),
                        item.str("serial", old?.serial.orEmpty()), item.str("location", old?.location.orEmpty()),
                        item.str("state", old?.state ?: KitStore.States[0]), item.str("notes", old?.notes.orEmpty()), 0,
                    ),
                )
            }
            "stock" -> {
                val old = Stock.get(id)
                if (old != null && old.updated > at) return
                if (deleted) { if (old != null) Stock.delete(context, id); return }
                Stock.upsert(
                    context,
                    StockItem(
                        id, item.str("name", old?.name.orEmpty()), item.str("unit", old?.unit ?: Stock.Units[0]),
                        item.int("qty", old?.qty ?: 0).coerceAtLeast(0), item.int("threshold", old?.threshold ?: 0).coerceAtLeast(0),
                        item.str("location", old?.location.orEmpty()), item.str("notes", old?.notes.orEmpty()), 0,
                    ),
                )
            }
            "client" -> {
                val old = Clients.items.firstOrNull { it.id == id }
                if (old != null && old.updated > at) return
                if (deleted) { if (old != null) Clients.delete(context, id); return }
                Clients.upsert(
                    context,
                    Client(
                        id, item.str("name", old?.name.orEmpty()), item.str("contact", old?.contact.orEmpty()),
                        item.str("phone", old?.phone.orEmpty()), item.str("email", old?.email.orEmpty()),
                        item.str("address", old?.address.orEmpty()), item.str("access", old?.access.orEmpty()),
                        item.str("instructions", old?.instructions.orEmpty()), item.str("notes", old?.notes.orEmpty()), 0,
                        // Contracts and quotes are edited on the phone only: they stay as they are.
                        old?.contracts.orEmpty(), old?.quotes.orEmpty(),
                    ),
                )
            }
            "cable" -> {
                val old = KitStore.cables.firstOrNull { it.id == id }
                if (old != null && old.updated > at) return
                if (deleted) { if (old != null) KitStore.deleteCable(context, id); return }
                KitStore.upsertCable(
                    context,
                    CableRecord(
                        id, item.str("outlet", old?.outlet.orEmpty()), item.str("switch", old?.switchName.orEmpty()),
                        item.str("port", old?.port.orEmpty()), item.str("vlan", old?.vlan.orEmpty()),
                        item.str("length", old?.lengthM.orEmpty()), item.str("poe", old?.poe ?: KitStore.PoeModes[0]),
                        item.str("test", old?.test ?: KitStore.TestResults[0]), item.str("equipment", old?.equipmentId.orEmpty()),
                        item.str("site", old?.site.orEmpty()), item.str("notes", old?.notes.orEmpty()), 0,
                    ),
                )
            }
            // An unknown kind (newer Deck Link): acknowledged and ignored.
        }
    }
}
