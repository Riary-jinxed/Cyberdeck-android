package dev.netrunner.os.sheet

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SheetFilesTest {
    private val sample = Book(
        listOf(
            Sheet(
                "Budget",
                mapOf(
                    Ref(0, 0) to "Poste", Ref(1, 0) to "Montant",
                    Ref(0, 1) to "Loyer", Ref(1, 1) to "750",
                    Ref(0, 2) to "Courses; repas", Ref(1, 2) to "312,5",
                    Ref(0, 3) to "Total", Ref(1, 3) to "=SOMME(B2:B3)",
                    Ref(2, 3) to "=SI(B4>1000;\"trop\";\"ok\")",
                    Ref(0, 5) to "'0042",
                ),
                bold = setOf(Ref(0, 0), Ref(1, 0)),
                widths = mapOf(0 to 200),
            ),
            Sheet("Notes", mapOf(Ref(1, 1) to "rien")),
        ),
    )

    private fun roundTrip(ext: String): Book {
        val f = File(Files.createTempDirectory("tableur").toFile(), "budget.$ext")
        SheetFiles.write(sample, f)
        return SheetFiles.read(f).book
    }

    @Test fun xlsxKeepsInputs() {
        val back = roundTrip("xlsx")
        assertEquals(listOf("Budget", "Notes"), back.sheets.map { it.name })
        assertEquals(sample.sheets[0].cells, back.sheets[0].cells)
        assertEquals(sample.sheets[0].bold, back.sheets[0].bold)
        assertEquals(sample.sheets[1].cells, back.sheets[1].cells)
        assertEquals("1062,5", Calc(back.sheets[0]).shown(Ref(1, 3)))
    }

    @Test fun odsKeepsInputs() {
        val back = roundTrip("ods")
        assertEquals(listOf("Budget", "Notes"), back.sheets.map { it.name })
        assertEquals(sample.sheets[0].cells, back.sheets[0].cells)
        assertEquals(sample.sheets[0].bold, back.sheets[0].bold)
        assertEquals(200, back.sheets[0].width(0))
        assertEquals(sample.sheets[1].cells, back.sheets[1].cells)
    }

    @Test fun csvHasValues() {
        val text = SheetFiles.writeCsv(sample.sheets[0])
        assertEquals("Total;1062,5;trop", text.lines()[3])
        assertEquals("\"Courses; repas\";312,5;", text.lines()[2])
        val back = SheetFiles.readCsv(text, "x")
        assertEquals("Courses; repas", back.input(Ref(0, 2)))
        assertEquals("1062,5", back.input(Ref(1, 3)))
    }
}
