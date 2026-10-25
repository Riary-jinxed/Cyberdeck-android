package dev.netrunner.os.sheet

/**
 * A sheet: what the user typed in each cell (a number, text, or a formula after "="), its bold
 * cells and column widths. [Calc] computes the values. Immutable: every change makes a new one.
 */
data class Sheet(
    val name: String = "Feuille 1",
    val cells: Map<Ref, String> = emptyMap(),
    val bold: Set<Ref> = emptySet(),
    val widths: Map<Int, Int> = emptyMap(),
) {
    val cols: Int get() = (cells.keys.maxOfOrNull { it.col } ?: -1) + 1
    val rows: Int get() = (cells.keys.maxOfOrNull { it.row } ?: -1) + 1

    fun input(r: Ref): String = cells[r].orEmpty()

    fun set(r: Ref, input: String): Sheet = copy(cells = if (input.isEmpty()) cells - r else cells + (r to input))

    fun clear(area: Area): Sheet = copy(cells = cells.filterKeys { it !in area }, bold = bold.filterNot { it in area }.toSet())

    fun width(col: Int): Int = widths[col] ?: DefaultWidth

    /** Writes rows of inputs from [at] (a paste, As//ka's table). */
    fun paste(at: Ref, rows: List<List<String>>): Sheet {
        var out = cells
        rows.forEachIndexed { dr, row ->
            row.forEachIndexed { dc, v ->
                val r = Ref(at.col + dc, at.row + dr)
                out = if (v.isEmpty()) out - r else out + (r to v)
            }
        }
        return copy(cells = out)
    }

    /** The rows of [area] sorted by the values of column [by] (numbers before text). */
    fun sort(area: Area, by: Int, descending: Boolean): Sheet {
        val calc = Calc(this)
        val order = (area.top..area.bottom).sortedWith { a, b ->
            val c = compare(calc.value(Ref(by, a)), calc.value(Ref(by, b)))
            if (descending) -c else c
        }
        var out = cells.filterKeys { it !in area }
        var bolds = bold.filterNot { it in area }.toSet()
        order.forEachIndexed { i, from ->
            for (c in area.left..area.right) {
                val src = Ref(c, from)
                val dst = Ref(c, area.top + i)
                cells[src]?.let { out = out + (dst to it) }
                if (src in bold) bolds = bolds + dst
            }
        }
        return copy(cells = out, bold = bolds)
    }

    companion object {
        const val DefaultWidth = 110

        private fun compare(a: Value, b: Value): Int = when {
            a is Value.Empty && b is Value.Empty -> 0
            a is Value.Empty -> 1
            b is Value.Empty -> -1
            a is Value.Num && b is Value.Num -> a.v.compareTo(b.v)
            a is Value.Num -> -1
            b is Value.Num -> 1
            else -> a.text().compareTo(b.text(), ignoreCase = true)
        }
    }
}

/** A rectangle of cells, corners included. */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    operator fun contains(r: Ref) = r.col in left..right && r.row in top..bottom
    val size: Int get() = (right - left + 1) * (bottom - top + 1)
    val start: Ref get() = Ref(left, top)
    override fun toString() = if (left == right && top == bottom) Ref(left, top).toString() else "${Ref(left, top)}:${Ref(right, bottom)}"

    companion object {
        fun of(a: Ref, b: Ref) = Area(minOf(a.col, b.col), minOf(a.row, b.row), maxOf(a.col, b.col), maxOf(a.row, b.row))

        /** "B2", "A1:C5" (or "C5:A1"). */
        fun parse(s: String): Area? {
            val parts = s.trim().split(':')
            val a = Ref.parse(parts[0]) ?: return null
            val b = if (parts.size == 2) Ref.parse(parts[1]) ?: return null else if (parts.size == 1) a else return null
            return of(a, b)
        }
    }
}

/** A workbook: its sheets, in order. */
data class Book(val sheets: List<Sheet> = listOf(Sheet())) {
    fun with(i: Int, s: Sheet) = copy(sheets = sheets.toMutableList().also { it[i] = s })
}

/** What an input is before any formula runs: "12,5" and "12.5" are numbers, "=…" a formula, anything else text. */
object Input {
    fun number(s: String): Double? {
        val t = s.trim().replace(' ', ' ').replace(" ", "")
        if (t.isEmpty() || t.startsWith("=")) return null
        val pct = t.endsWith("%")
        val n = t.removeSuffix("%").replace(',', '.').toDoubleOrNull() ?: return null
        return if (pct) n / 100 else n
    }

    fun isFormula(s: String) = s.startsWith("=") && s.length > 1
}

/**
 * The values of a sheet. Each cell is computed once, when first asked; a formula that ends up
 * reading itself gives #CIRC!.
 */
class Calc(private val sheet: Sheet) : Formula.Sheet {
    private val values = HashMap<Ref, Value>()
    private val running = HashSet<Ref>()
    private val parsed = HashMap<String, Result<Formula.Node>>()

    override fun value(ref: Ref): Value {
        values[ref]?.let { return it }
        val input = sheet.cells[ref] ?: return Value.Empty
        if (!running.add(ref)) return Value.Error("#CIRC!")
        val v = try {
            when {
                Input.isFormula(input) -> {
                    val node = parsed.getOrPut(input) { runCatching { Formula.parse(input.substring(1)) } }
                    node.fold({ Formula.eval(it, this) }, { Value.Error((it as? Fail)?.code ?: "#NOM?") })
                }
                else -> Input.number(input)?.let { Value.Num(it) } ?: Value.Text(input.removePrefix("'"))
            }
        } finally {
            running.remove(ref)
        }
        values[ref] = v
        return v
    }

    /** What a cell shows. */
    fun shown(ref: Ref): String = value(ref).text()
}
