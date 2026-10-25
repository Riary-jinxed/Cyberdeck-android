package dev.netrunner.os.sheet

import kotlin.test.Test
import kotlin.test.assertEquals

class FormulaTest {
    private fun sheet(vararg cells: Pair<String, String>) = Sheet(cells = cells.associate { (r, v) -> Ref.parse(r)!! to v })
    private fun shown(s: Sheet, r: String) = Calc(s).shown(Ref.parse(r)!!)

    @Test fun refs() {
        assertEquals("A1", Ref(0, 0).toString())
        assertEquals("AA10", Ref(26, 9).toString())
        assertEquals(Ref(27, 2), Ref.parse("\$AB\$3"))
        assertEquals(Area(0, 0, 2, 4), Area.parse("C5:A1"))
    }

    @Test fun arithmeticAndFrenchNumbers() {
        val s = sheet("A1" to "2,5", "A2" to "4", "A3" to "=A1*A2+1", "A4" to "=(A1+A2)/2", "A5" to "=2^3^2", "A6" to "=-A2%", "A7" to "=1/3")
        assertEquals("11", shown(s, "A3"))
        assertEquals("3,25", shown(s, "A4"))
        assertEquals("512", shown(s, "A5"))
        assertEquals("-0,04", shown(s, "A6"))
        assertEquals("0,3333333333", shown(s, "A7"))
    }

    @Test fun functionsInFrenchAndEnglish() {
        val s = sheet(
            "A1" to "10", "A2" to "20", "A3" to "texte", "A4" to "30",
            "B1" to "=SOMME(A1:A4)", "B2" to "=SUM(A1:A4)", "B3" to "=MOYENNE(A1:A4)", "B4" to "=NB(A1:A4)", "B5" to "=NBVAL(A1:A4)",
            "B6" to "=SI(A1>5;\"grand\";\"petit\")", "B7" to "=ARRONDI(2,345;2)", "B8" to "=NB.SI(A1:A4;\">15\")", "B9" to "=SOMME.SI(A1:A4;\">15\")",
            "C1" to "=CONCAT(A3;\" \";A1)", "C2" to "=A3&\"!\"", "C3" to "=MAJUSCULE(A3)", "C4" to "=MAX(A1:A4)-MIN(A1:A4)", "C5" to "=SUM(1,2)",
            "C6" to "=SIERREUR(1/0;\"oups\")", "C7" to "=ET(A1>0;A2>0)", "C8" to "=MEDIANE(A1:A4)",
        )
        assertEquals("60", shown(s, "B1"))
        assertEquals("60", shown(s, "B2"))
        assertEquals("20", shown(s, "B3"))
        assertEquals("3", shown(s, "B4"))
        assertEquals("4", shown(s, "B5"))
        assertEquals("grand", shown(s, "B6"))
        assertEquals("2,35", shown(s, "B7"))
        assertEquals("2", shown(s, "B8"))
        assertEquals("50", shown(s, "B9"))
        assertEquals("texte 10", shown(s, "C1"))
        assertEquals("texte!", shown(s, "C2"))
        assertEquals("TEXTE", shown(s, "C3"))
        assertEquals("20", shown(s, "C4"))
        assertEquals("3", shown(s, "C5"))
        assertEquals("oups", shown(s, "C6"))
        assertEquals("VRAI", shown(s, "C7"))
        assertEquals("20", shown(s, "C8"))
    }

    @Test fun errors() {
        val s = sheet("A1" to "=A2", "A2" to "=A1", "B1" to "=1/0", "B2" to "=B1+1", "B3" to "=INCONNU(1)", "B4" to "=1+", "B5" to "=\"a\"*2")
        assertEquals("#CIRC!", shown(s, "A1"))
        assertEquals("#DIV/0!", shown(s, "B1"))
        assertEquals("#DIV/0!", shown(s, "B2"))
        assertEquals("#NOM?", shown(s, "B3"))
        assertEquals("#NOM?", shown(s, "B4"))
        assertEquals("#VALEUR!", shown(s, "B5"))
    }

    @Test fun dialects() {
        assertEquals("SUM(A1:B2,0.5)", FormulaText.toExcel("=SOMME(A1:B2;0,5)"))
        assertEquals("=SOMME(A1:B2;0,5)", FormulaText.fromExcel("SUM(A1:B2,0.5)"))
        assertEquals("of:=SUM([.A1:.B2];[.C3])", FormulaText.toOdf("=SOMME(A1:B2;C3)"))
        assertEquals("=SOMME(A1:B2;C3)", FormulaText.fromOdf("of:=SUM([.A1:.B2];[.C3])"))
        assertEquals("=SI(A1>=2;\"oui\";\"non\")", FormulaText.fromExcel("IF(A1>=2,\"oui\",\"non\")"))
    }

    @Test fun sortKeepsRowsTogether() {
        val s = sheet("A1" to "Zoé", "B1" to "3", "A2" to "Adam", "B2" to "1", "A3" to "Marc", "B3" to "2").sort(Area(0, 0, 1, 2), 1, descending = false)
        assertEquals(listOf("Adam", "Marc", "Zoé"), (0..2).map { s.input(Ref(0, it)) })
        assertEquals(listOf("1", "2", "3"), (0..2).map { s.input(Ref(1, it)) })
    }
}
