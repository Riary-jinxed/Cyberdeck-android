package dev.netrunner.decklink

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deck Probe reports → inventory sheets, and the PC's pending edits laid over the phone's kit. */
class FleetTest {
    val sample = """{"probe":1,"hostname":"PC-COMPTA-01","domain":"corp.local","user":"CORP\\jdupont","manufacturer":"Dell Inc.","model":"OptiPlex 7090","serial":"7XK2LM3","os":"Microsoft Windows 11 Pro 23H2","osBuild":"22631","cpu":"Intel(R) Core(TM) i5-10500","ramGB":16,"disks":{"name":"C:","sizeGB":477},"network":[{"name":"Intel Ethernet","mac":"00:11:22:33:44:55","ipv4":"192.168.1.23"}]}"""
    @Test fun reportBecomesSheetThenUpdatesInPlace() {
        val r = Fleet.parse(sample)!!
        assertEquals(1, r.disks.size)
        val tmp = Files.createTempDirectory("dl").toFile()
        val outbox = Outbox(File(tmp, "outbox.json"))
        val first = Fleet.toEquipment(r, KitData.load(tmp, outbox.changes()))!!
        assertEquals("7XK2LM3", first.second["code"]!!.toString().trim('"'))
        outbox.put("equipment", first.first, first.second)
        val kit = KitData.load(tmp, outbox.changes())
        assertEquals(1, kit.equipment.size)
        assertTrue(kit.equipment[0].notes.contains("OptiPlex 7090"))
        // Same report again: nothing to change.
        assertNull(Fleet.toEquipment(r, kit))
        // The user adds a line and moves it: kept when the PC reports again with more RAM.
        outbox.put("equipment", first.first, mapOf("notes" to JsonPrimitive("Bureau 12, écran Dell 24\"\n\n" + kit.equipment[0].notes), "location" to JsonPrimitive("Compta")))
        val kit2 = KitData.load(tmp, outbox.changes())
        val again = Fleet.toEquipment(r.copy(ramGB = 32), kit2)!!
        assertEquals(first.first, again.first)
        val notes = again.second["notes"]!!.toString()
        assertTrue(notes.contains("Bureau 12") && notes.contains("32 Go") && !notes.contains("16 Go"), notes)
        assertTrue(again.second["location"].toString().contains("Compta"))
        // Reloading the outbox from disk keeps the pending edits.
        assertEquals(1, Outbox(File(tmp, "outbox.json")).changes().size)
        assertEquals(1, Outbox(File(tmp, "outbox.json")).pending("kit").size)
    }
    @Test fun junkSerialFallsBackToName() {
        val r = Fleet.parse(sample.replace("7XK2LM3", "To be filled by O.E.M."))!!
        val tmp = Files.createTempDirectory("dl").toFile()
        val e = Fleet.toEquipment(r, KitData.load(tmp))!!
        assertEquals("\"PC-PC-COMPTA-01\"", e.second["code"].toString())
    }
    @Test fun deleteOverlayHidesItem() {
        val tmp = Files.createTempDirectory("dl").toFile()
        File(tmp, "kit_stock.json").writeText("""{"stock":[{"id":"s1","name":"RJ45","unit":"pcs","qty":40,"threshold":10,"updated":5}]}""")
        val outbox = Outbox(File(tmp, "o.json"))
        outbox.put("stock", "s1", mapOf("qty" to JsonPrimitive(3)))
        assertEquals(3, KitData.load(tmp, outbox.changes()).stock[0].qty)
        assertTrue(KitData.load(tmp, outbox.changes()).stock[0].low)
        outbox.delete("stock", "s1")
        assertEquals(0, KitData.load(tmp, outbox.changes()).stock.size)
        outbox.applied("kit", outbox.changes().map { it.op })
        assertEquals(1, KitData.load(tmp, outbox.changes()).stock.size)
    }
}
