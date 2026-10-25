package dev.netrunner.os.sheet

import dev.netrunner.bridge.Bridge
import java.io.File
import javax.swing.SwingUtilities

/**
 * What As//ka sees of Netrunner Tableur (the sheet, the selection) and what she can do: read
 * cells, write values and formulas, clear, sort, bold, change sheet, save.
 */
class SheetBridge(private val state: SheetState) : Bridge.App {
    override val id = "tableur"
    override val title = "Netrunner Tableur"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun used(s: Sheet) = if (s.cells.isEmpty()) "vide" else "données en ${Area(0, 0, s.cols - 1, s.rows - 1)}"

    override fun context(): String = ui {
        val s = state.sheet
        val input = s.input(state.cursor)
        "Netrunner Tableur édite « ${state.file?.name ?: "Sans titre"} »" + (if (state.dirty) " (non enregistré)" else "") +
            ", feuille « ${s.name} » (${used(s)})" +
            (if (state.book.sheets.size > 1) ", feuilles : " + state.book.sheets.joinToString(", ") { it.name } else "") +
            ". Sélection ${state.area}" + (if (input.isNotEmpty()) " : ${state.cursor} contient « $input »" + (if (Input.isFormula(input)) " = ${state.calc.shown(state.cursor)}" else "") else "") +
            ". Formules en français ou en anglais (=SOMME(A1:A5), =MOYENNE, =SI(A1>0;\"oui\";\"non\"), =NB.SI…), décimales avec une virgule. Pour lire, utilise tableur_read."
    }

    override fun tools() = listOf(
        Bridge.Tool("tableur_read", "Lit les cellules non vides de la feuille ouverte (ou d'une plage) : adresse, ce qui est tapé, valeur calculée.", listOf(Bridge.Param("range", "plage comme A1:D20 (toute la feuille sinon)", required = false))),
        Bridge.Tool("tableur_write", "Écrit un tableau à partir d'une cellule : une ligne par ligne du tableau, cellules séparées par des tabulations ou par « | ». Nombres, texte ou formules (=SOMME(B2:B9)).", listOf(Bridge.Param("cell", "cellule de départ, comme A1"), Bridge.Param("data", "le tableau"))),
        Bridge.Tool("tableur_clear", "Vide une plage de cellules.", listOf(Bridge.Param("range", "plage comme B2:C10"))),
        Bridge.Tool("tableur_select", "Sélectionne une cellule ou une plage (pour la montrer à l'utilisateur).", listOf(Bridge.Param("range", "plage comme A1:C3"))),
        Bridge.Tool("tableur_sort", "Trie les lignes d'une plage selon une de ses colonnes.", listOf(Bridge.Param("range", "plage à trier, sans la ligne de titres"), Bridge.Param("column", "lettre de la colonne de tri"), Bridge.Param("descending", "true pour du plus grand au plus petit", "boolean", false))),
        Bridge.Tool("tableur_bold", "Met une plage en gras (ou l'enlève).", listOf(Bridge.Param("range", "plage"), Bridge.Param("on", "false pour enlever le gras", "boolean", false))),
        Bridge.Tool("tableur_sheet", "Va sur une feuille du classeur, ou la crée si elle n'existe pas.", listOf(Bridge.Param("name", "nom de la feuille"))),
        Bridge.Tool("tableur_undo", "Annule la dernière modification."),
        Bridge.Tool("tableur_save", "Enregistre le classeur (là où il est déjà, ou au chemin donné : .xlsx, .ods ou .csv).", listOf(Bridge.Param("path", "chemin, seulement pour un nouveau fichier", required = false))),
        Bridge.Tool("tableur_open", "Ouvre un classeur (.xlsx, .xls, .ods, .csv) dans Netrunner Tableur.", listOf(Bridge.Param("path", "chemin du fichier, ~ pour le dossier personnel"))),
    )

    private fun path(s: String?) = File(s.orEmpty().trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))

    override fun run(tool: String, args: Map<String, String>): String = ui {
        val s = state.sheet
        fun area(name: String = "range"): Area? = args[name]?.let { Area.parse(it) }
        when (tool) {
            "tableur_read" -> {
                if (s.cells.isEmpty()) return@ui "La feuille « ${s.name} » est vide."
                val a = args["range"]?.takeIf { it.isNotBlank() }?.let { Area.parse(it) ?: return@ui "Plage incomprise : ${args["range"]}" }
                val calc = state.calc
                val out = StringBuilder("Feuille « ${s.name} », ${used(s)} :\n")
                for ((ref, input) in s.cells.entries.filter { a == null || it.key in a }.sortedWith(compareBy({ it.key.row }, { it.key.col }))) {
                    out.append(ref).append(" : ").append(input)
                    if (Input.isFormula(input)) out.append(" → ").append(calc.shown(ref))
                    out.append('\n')
                    if (out.length > 14_000) { out.append("[… coupé : lis la suite avec une plage plus loin que $ref]"); break }
                }
                out.toString()
            }
            "tableur_write" -> {
                val at = args["cell"]?.let { Ref.parse(it) } ?: return@ui "Cellule de départ incomprise : ${args["cell"]}"
                val rows = SheetState.rows(args["data"].orEmpty())
                if (rows.all { r -> r.all { it.isEmpty() } }) return@ui "Rien à écrire."
                state.paste(at, rows)
                val end = Ref(at.col + rows.maxOf { it.size } - 1, at.row + rows.size - 1)
                state.selectArea(Area.of(at, end))
                val errors = Formula.cells(at, end).filter { state.calc.value(it) is Value.Error }
                "Écrit en ${Area.of(at, end)}." + (if (errors.isNotEmpty()) " Attention, erreurs en " + errors.take(10).joinToString(", ") { "$it (${state.calc.shown(it)})" } + "." else "") +
                    " L'utilisateur peut annuler avec Ctrl+Z."
            }
            "tableur_clear" -> { val a = area() ?: return@ui "Plage incomprise."; state.clear(a); "Vidé : $a." }
            "tableur_select" -> { val a = area() ?: return@ui "Plage incomprise."; state.selectArea(a); "Sélectionné : $a." }
            "tableur_sort" -> {
                val a = area() ?: return@ui "Plage incomprise."
                val col = Ref.parse(args["column"].orEmpty().trim() + "1")?.col ?: return@ui "Colonne incomprise : ${args["column"]}"
                if (col !in a.left..a.right) return@ui "La colonne ${args["column"]} n'est pas dans $a."
                state.sort(a, col, args["descending"]?.lowercase() == "true")
                "Trié : $a selon la colonne ${Ref.colName(col)}."
            }
            "tableur_bold" -> { val a = area() ?: return@ui "Plage incomprise."; state.bold(a, args["on"]?.lowercase() != "false"); "Fait : $a." }
            "tableur_sheet" -> {
                val name = args["name"].orEmpty().trim()
                if (name.isEmpty()) return@ui "Quelle feuille ?"
                val i = state.book.sheets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                if (i >= 0) { state.goTo(i); "Sur la feuille « ${state.sheet.name} »." } else { state.addSheet(name); "Feuille « $name » créée." }
            }
            "tableur_undo" -> { state.undo(); "Annulé." }
            "tableur_save" -> {
                val p = args["path"]?.takeIf { it.isNotBlank() }?.let(::path)
                val target = p ?: state.file ?: return@ui "Le classeur n'a pas encore de fichier : donne un chemin (.xlsx, .ods…)."
                val f = SheetFiles.target(target)
                if (state.saveTo(f)) "Enregistré : ${f.path}" else state.message
            }
            "tableur_open" -> {
                val f = path(args["path"])
                if (!f.isFile) return@ui "Pas de fichier « ${f.path} »."
                if (state.dirty) return@ui "Le classeur ouvert a des modifications non enregistrées : enregistre-le d'abord (tableur_save) ou demande à l'utilisateur."
                state.open(f)
                state.message.ifEmpty { "« ${f.name} » ouvert : " + state.book.sheets.joinToString(", ") { "${it.name} (${used(it)})" } + "." }
            }
            else -> "Outil inconnu : $tool"
        }
    }
}
