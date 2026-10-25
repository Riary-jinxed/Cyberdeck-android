package dev.netrunner.decklink

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small .xlsx writer (Office Open XML, read by Excel, LibreOffice and Google Sheets): one sheet
 * per table, a bold frozen header with filters, text and numbers. No library needed.
 */
object Excel {
    class Sheet(val name: String, val header: List<String>, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

    fun write(file: File, sheets: List<Sheet>) {
        val tmp = File(file.parentFile, file.name + ".part")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val names = uniqueNames(sheets.map { it.name })
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                names.forEachIndexed { i, n -> append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets><definedNames>")
                sheets.forEachIndexed { i, s ->
                    if (s.header.isNotEmpty()) append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${esc(names[i].replace("'", "''"))}'!${'$'}A${'$'}1:${'$'}${col(s.header.size - 1)}${'$'}${s.rows.size + 1}</definedName>""")
                }
                append("</definedNames></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            // Style 1: bold header on yellow; style 2: wrapped text.
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFCEE0A"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
        file.delete()
        if (!tmp.renameTo(file)) { tmp.delete(); error("impossible d'écrire $file") }
    }

    private fun sheetXml(s: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (s.header.isNotEmpty()) {
            append("<cols>")
            s.header.indices.forEach { c ->
                val w = s.widths.getOrNull(c) ?: (listOf(s.header[c].length + 4) + s.rows.take(200).map { (it.getOrNull(c)?.toString()?.lines()?.maxOfOrNull { l -> l.length } ?: 0) + 2 }).max().coerceIn(8, 60)
                append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        (listOf(s.header) + s.rows).forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, v ->
                val ref = col(c) + (r + 1)
                val style = when {
                    r == 0 -> """ s="1""""
                    v is String && v.contains('\n') -> """ s="2""""
                    else -> ""
                }
                when (v) {
                    null -> {}
                    is Number -> append("""<c r="$ref"$style><v>$v</v></c>""")
                    else -> {
                        val text = clean(v.toString())
                        if (text.isNotEmpty()) append("""<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${esc(text)}</t></is></c>""")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (s.header.isNotEmpty()) append("""<autoFilter ref="A1:${col(s.header.size - 1)}${s.rows.size + 1}"/>""")
        append("</worksheet>")
    }

    /** "A", "B" … "Z", "AA" … for column [i] (0-based). */
    fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** Excel caps a cell at 32 767 characters and rejects XML control characters. */
    private fun clean(text: String) = text.filter { it == '\n' || it == '\t' || it >= ' ' }.take(32_000)

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Sheet names: at most 31 characters, none of []:*?/\ and all different. */
    private fun uniqueNames(names: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return names.map { raw ->
            val base = raw.filter { it !in "[]:*?/\\" }.take(31).ifBlank { "Feuille" }
            var n = base
            var k = 2
            while (!seen.add(n.lowercase())) { n = base.take(28) + " " + k++ }
            n
        }
    }

    // ------------------------------------------------------------------ the kit as a workbook

    private val Day = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())
    private val Time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
    private fun day(ms: Long) = if (ms <= 0) "" else Day.format(Instant.ofEpochMilli(ms))
    private fun time(ms: Long) = if (ms <= 0) "" else Time.format(Instant.ofEpochMilli(ms))

    private val QuoteStates = mapOf("draft" to "Brouillon", "sent" to "Envoyé", "accepted" to "Accepté", "refused" to "Refusé")

    /** Every table of the kit and the fleet, one sheet each. */
    fun kitWorkbook(kit: KitData, fleet: List<Fleet.Report>): List<Sheet> {
        val equipmentName = kit.equipment.associate { it.id to it.name.ifBlank { it.code } }
        return listOf(
            Sheet(
                "Inventaire",
                listOf("Nom", "Code", "N° de série", "Emplacement", "État", "Fin de garantie", "Notes", "Modifié le"),
                kit.equipment.map { listOf(it.name, it.code, it.serial, it.location, it.state, day(it.warranty), it.notes, time(it.updated)) },
            ),
            Sheet(
                "Consommables",
                listOf("Article", "Quantité", "Unité", "Seuil", "Sous le seuil", "Emplacement", "Notes"),
                kit.stock.map { listOf(it.name, it.qty, it.unit, it.threshold, if (it.low) "oui" else "", it.location, it.notes) },
            ),
            Sheet(
                "Clients",
                listOf("Nom", "Contact", "Téléphone", "E-mail", "Adresse", "Accès", "Consignes", "Notes", "Contrats", "Devis"),
                kit.clients.map { listOf(it.name, it.contact, it.phone, it.email, it.address, it.access, it.instructions, it.notes, it.contracts.size, it.quotes.size) },
            ),
            Sheet(
                "Contrats",
                listOf("Client", "Référence", "Type", "Début", "Fin", "Montant"),
                kit.clients.flatMap { c -> c.contracts.map { listOf(c.name, it.ref, it.kind, day(it.start), day(it.end), it.amount) } },
            ),
            Sheet(
                "Devis",
                listOf("Client", "Référence", "Libellé", "Date", "Montant", "État"),
                kit.clients.flatMap { c -> c.quotes.map { listOf(c.name, it.ref, it.label, day(it.date), it.amount, QuoteStates[it.state] ?: it.state) } },
            ),
            Sheet(
                "Câbles",
                listOf("Site", "Prise", "Switch", "Port", "VLAN", "Longueur (m)", "PoE", "Test", "Équipement", "Notes"),
                kit.cables.map { listOf(it.site, it.outlet, it.switchName, it.port, it.vlan, it.length, it.poe, it.test, equipmentName[it.equipmentId].orEmpty(), it.notes) },
            ),
            Sheet(
                "Interventions",
                listOf("Titre", "Client", "Site", "Ticket", "Début", "Fin", "Durée (h)", "Notes", "Consommé", "Photos"),
                kit.interventions.map { i ->
                    listOf(
                        i.title, i.client, i.site, i.ticket, time(i.started), if (i.open) "en cours" else time(i.ended),
                        if (i.open || i.started <= 0) null else Math.round((i.ended - i.started) / 36_000.0) / 100.0,
                        i.notes, i.consumed.joinToString("\n") { "${it.qty} ${it.unit} ${it.name}" }, i.photos.size,
                    )
                },
            ),
            Sheet(
                "Parc",
                listOf("PC", "Domaine", "Utilisateur", "Fabricant", "Modèle", "N° de série", "OS", "Build", "CPU", "RAM (Go)", "Disques", "IP", "MAC", "Mises à jour", "Logiciels", "Vu le"),
                fleet.map { r ->
                    listOf(
                        r.hostname, r.domain, r.user, r.manufacturer, r.model, r.serial, r.os, r.osBuild, r.cpu, r.ramGB,
                        r.disks.joinToString("\n") { "${it.name} ${it.sizeGB} Go" + if (it.freeGB >= 0) " (${it.freeGB} libres)" else "" },
                        r.network.mapNotNull { it.ipv4.ifBlank { null } }.joinToString("\n"),
                        r.network.joinToString("\n") { it.mac },
                        Fleet.updatesLine(r.updates).orEmpty(), r.software.size, time(r.seen),
                    )
                },
            ),
            Sheet(
                "Logiciels",
                listOf("PC", "Logiciel", "Version", "Éditeur"),
                fleet.flatMap { r -> r.software.map { listOf(r.hostname, it.name, it.version, it.publisher) } },
            ),
        )
    }
}
