package dev.netrunner.decklink

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Excel export, PDF reports and labels, alerts, and the dated kit backups. */
class DocumentsTest {
    private fun tempDir(): File = Files.createTempDirectory("dl").toFile()
    private val day = 24 * 60 * 60 * 1000L
    private val now = System.currentTimeMillis()

    private val kit = KitData(
        equipment = listOf(
            KitData.Equipment("e1", "SW-01", "Switch « cœur » 24 ports", "ABC123", "Baie A", "En service", "PoE+ → caméras", now, warranty = now + 20 * day),
            KitData.Equipment("e2", "", "Onduleur", "", "Baie A", "HS", "", now, warranty = now - 400 * day),
        ),
        stock = listOf(KitData.StockItem("s1", "RJ45 Cat6", "pcs", 3, 10, "Valise", "", now)),
        interventions = listOf(
            KitData.Intervention(
                "i1", "Remplacement switch", "Siège", "ACME", "T-42", now - 3 * 3600_000, now, "Switch remplacé, VLAN 10/20 reconfigurés.\nTests OK.",
                listOf(KitData.LogEntry(now - 3 * 3600_000, "Arrivée sur site"), KitData.LogEntry(now - 3600_000, "Câblage terminé ✓")),
                listOf("p1.jpg", "manquante.jpg"), listOf(KitData.Usage("RJ45 Cat6", "pcs", 12)),
            ),
        ),
        clients = listOf(KitData.Client("c1", "ACME", "Jeanne Dupont", "01 02 03 04 05", "j@acme.fr", "1 rue de la Paix\n75000 Paris", "", "", "", emptyList(), emptyList())),
    )
    private val report = Fleet.Report(
        "PC-01", "corp", "jdupont", "Dell", "OptiPlex", "7XK2", "Windows 11", "22631", "i5", 16,
        listOf(Fleet.Disk("C:", 237, 4), Fleet.Disk("D:", 931, 500)), listOf(Fleet.Nic("Eth", "00:11", "192.168.1.2")), now - 20 * day,
        listOf(Fleet.Software("7-Zip", "24.08", "Igor Pavlov"), Fleet.Software("Firefox", "131", "Mozilla")),
        Fleet.Updates(lastInstalled = now - 60 * day, pending = 3, rebootRequired = true),
    )

    @Test fun excelWorkbookIsValidXml() {
        val f = File(tempDir(), "kit.xlsx")
        Excel.write(f, Excel.kitWorkbook(kit, listOf(report)))
        ZipFile(f).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue("xl/worksheets/sheet9.xml" in names, names.toString())
            val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { n -> z.getInputStream(z.getEntry(n)).use { parser.parse(it) } }
            val inventory = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString()
            assertTrue("Switch « cœur » 24 ports" in inventory)
            val workbook = z.getInputStream(z.getEntry("xl/workbook.xml")).readBytes().decodeToString()
            assertTrue("Câbles" in workbook && "Logiciels" in workbook)
        }
        assertEquals("AA", Excel.col(26))
    }

    @Test fun interventionReportHasTextAndPhotos() {
        val photos = tempDir()
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(img, "jpg", File(photos, "p1.jpg"))
        val out = File(tempDir(), "rapport.pdf")
        Pdf.interventionReport(kit.interventions[0], kit, photos, out)
        Loader.loadPDF(out).use { doc ->
            val text = PDFTextStripper().getText(doc)
            listOf("Remplacement switch", "ACME", "Jeanne Dupont", "T-42", "VLAN 10/20", "12 pcs", "SIGNATURES").forEach { assertTrue(it in text, it) }
            val images = doc.pages.sumOf { p -> p.resources.xObjectNames.count { p.resources.isImageXObject(it) } }
            assertEquals(1, images)
        }
    }

    @Test fun labelSheetsFillPages() {
        val out = File(tempDir(), "etiquettes.pdf")
        Pdf.labels(List(30) { Pdf.Label("NR-$it", "Équipement n°$it avec un nom très long qui ne tient pas", "Baie A") }, out, skip = 2)
        Loader.loadPDF(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertTrue("NR-29" in PDFTextStripper().getText(doc))
        }
    }

    @Test fun alertsCoverFleetInventoryAndStock() {
        val alerts = Alerts.compute(kit, listOf(report), now)
        val keys = alerts.map { it.key }
        assertTrue("disk:PC-01:C:" in keys)
        assertTrue(keys.none { it == "disk:PC-01:D:" })
        listOf("quiet:PC-01", "reboot:PC-01", "pending:PC-01", "stale:PC-01", "stock:s1").forEach { assertTrue(it in keys, it) }
        assertTrue(alerts.any { it.key.startsWith("warranty:e1") })
        // Expired long ago: old news.
        assertTrue(alerts.none { it.key.startsWith("warranty:e2") })
        assertEquals(Alerts.Level.Urgent, alerts.first().level)
    }

    @Test fun reportRoundTripsNewFields() {
        val back = Fleet.parse(
            """{"hostname":"X","disks":[{"name":"C:","sizeGB":100,"freeGB":7}],"software":{"name":"Seul","version":"1","publisher":"Moi"},"updates":{"lastInstalled":5,"pending":0,"rebootRequired":true}}""",
        )!!
        assertEquals(7, back.disks[0].freeGB)
        assertEquals(listOf(Fleet.Software("Seul", "1", "Moi")), back.software)
        assertEquals(Fleet.Updates(5, 0, true), back.updates)
        // An older agent: unknown, not "full" or "up to date".
        val old = Fleet.parse("""{"hostname":"Y","disks":{"name":"C:","sizeGB":100}}""")!!
        assertEquals(-1, old.disks[0].freeGB)
        assertEquals(-1, old.updates.pending)
        assertTrue(Alerts.compute(KitData(), listOf(old.copy(seen = now)), now).isEmpty())
    }

    @Test fun backupsAreDatedAndSkippedWhenUnchanged() {
        val kitDir = tempDir()
        val dir = File(tempDir(), "sauvegardes")
        val backups = Backups(kitDir, dir)
        assertNull(backups.take())
        File(kitDir, "kit.json").writeText("{}")
        File(kitDir, "logbook/i1").mkdirs()
        File(kitDir, "logbook/i1/p.jpg").writeBytes(ByteArray(100))
        File(kitDir, ".corbeille").mkdirs()
        File(kitDir, ".corbeille/old.json").writeText("x")
        val first = assertNotNull(backups.take())
        assertEquals(2, first.files)
        ZipFile(first.file).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("netrunner-kit.json", "kit.json", "logbook/i1/p.jpg"), names)
        }
        assertNull(backups.take())
        Thread.sleep(1100)
        assertNotNull(backups.take(force = true))
        assertEquals(2, backups.list().size)
        backups.open(backups.list()[0].path).close()
        assertFailsWith<IllegalArgumentException> { backups.open("../kit.json") }
    }
}
