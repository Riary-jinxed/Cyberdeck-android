package dev.netrunner.os.sheet

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** A cell address: column and row from 0 ("B3" is 1, 2). */
data class Ref(val col: Int, val row: Int) {
    override fun toString() = colName(col) + (row + 1)

    companion object {
        fun colName(c: Int): String {
            var n = c + 1
            val sb = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                sb.insert(0, 'A' + r)
                n = (n - 1) / 26
            }
            return sb.toString()
        }

        fun parse(s: String): Ref? {
            val m = Regex("^\\$?([A-Za-z]{1,3})\\$?(\\d{1,7})$").matchEntire(s.trim()) ?: return null
            val col = m.groupValues[1].uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
            val row = m.groupValues[2].toInt() - 1
            return if (row >= 0) Ref(col, row) else null
        }
    }
}

/** What a cell holds once computed. */
sealed class Value {
    data class Num(val v: Double) : Value()
    data class Text(val v: String) : Value()
    data class Bool(val v: Boolean) : Value()
    data class Error(val code: String) : Value()
    object Empty : Value()

    fun number(): Double = when (this) {
        is Num -> v
        is Bool -> if (v) 1.0 else 0.0
        is Empty -> 0.0
        is Text -> v.replace(',', '.').trim().toDoubleOrNull() ?: throw Fail("#VALEUR!")
        is Error -> throw Fail(code)
    }

    fun text(): String = when (this) {
        is Num -> format(v)
        is Text -> v
        is Bool -> if (v) "VRAI" else "FAUX"
        is Error -> code
        is Empty -> ""
    }

    fun truth(): Boolean = when (this) {
        is Bool -> v
        is Num -> v != 0.0
        is Empty -> false
        is Text -> when (v.uppercase()) { "VRAI", "TRUE" -> true; "FAUX", "FALSE" -> false; else -> throw Fail("#VALEUR!") }
        is Error -> throw Fail(code)
    }

    companion object {
        /** 3, 2,5 or 0,333333333 (French decimal comma, at most 10 significant digits). */
        fun format(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "#NOMBRE!"
            if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
            val s = "%.10g".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
            return (if (s.contains('e')) s else s.toBigDecimal().stripTrailingZeros().toPlainString()).replace('.', ',')
        }
    }
}

class Fail(val code: String) : Exception(code)

/**
 * Formulas as typed after "=": numbers (2,5 or 2.5), "text", A1, $A$1, A1:B5, + - * / ^ & %,
 * comparisons (= <> < > <= >=), and functions with their French or English names separated by
 * ";" or ",". Parsed once into a tree, then evaluated against a sheet.
 */
object Formula {
    sealed class Node {
        data class Num(val v: Double) : Node()
        data class Str(val v: String) : Node()
        data class Bool(val v: Boolean) : Node()
        data class Cell(val ref: Ref) : Node()
        data class Range(val from: Ref, val to: Ref) : Node()
        data class Unary(val op: Char, val x: Node) : Node()
        data class Binary(val op: String, val a: Node, val b: Node) : Node()
        data class Call(val name: String, val args: List<Node>) : Node()
        data class Percent(val x: Node) : Node()
    }

    /** How a formula reads the sheet. */
    interface Sheet {
        fun value(ref: Ref): Value
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(): Nothing = throw Fail("#NOM?")

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun peek(): Char? { skip(); return s.getOrNull(i) }

        fun eat(t: String): Boolean {
            skip()
            if (s.startsWith(t, i)) { i += t.length; return true }
            return false
        }

        fun parse(): Node {
            val n = comparison()
            skip()
            if (i != s.length) fail()
            return n
        }

        fun comparison(): Node {
            var a = concat()
            while (true) {
                val op = listOf("<=", ">=", "<>", "=", "<", ">").firstOrNull { eat(it) } ?: return a
                a = Node.Binary(op, a, concat())
            }
        }

        fun concat(): Node {
            var a = additive()
            while (eat("&")) a = Node.Binary("&", a, additive())
            return a
        }

        fun additive(): Node {
            var a = term()
            while (true) {
                a = when {
                    eat("+") -> Node.Binary("+", a, term())
                    eat("-") -> Node.Binary("-", a, term())
                    else -> return a
                }
            }
        }

        fun term(): Node {
            var a = power()
            while (true) {
                a = when {
                    eat("*") -> Node.Binary("*", a, power())
                    eat("/") -> Node.Binary("/", a, power())
                    else -> return a
                }
            }
        }

        fun power(): Node {
            val a = unary()
            return if (eat("^")) Node.Binary("^", a, power()) else a
        }

        fun unary(): Node = when {
            eat("-") -> Node.Unary('-', unary())
            eat("+") -> unary()
            else -> postfix(primary())
        }

        fun postfix(n: Node): Node = if (eat("%")) postfix(Node.Percent(n)) else n

        fun primary(): Node {
            val c = peek() ?: fail()
            if (c == '(') {
                i++
                val n = comparison()
                if (!eat(")")) fail()
                return n
            }
            if (c == '"') {
                i++
                val sb = StringBuilder()
                while (true) {
                    if (i >= s.length) fail()
                    if (s[i] == '"') {
                        if (s.getOrNull(i + 1) == '"') { sb.append('"'); i += 2; continue }
                        i++
                        break
                    }
                    sb.append(s[i++])
                }
                return Node.Str(sb.toString())
            }
            if (c.isDigit() || (c == '.' && s.getOrNull(i + 1)?.isDigit() == true)) {
                val m = Regex("\\d*(?:[.,]\\d+)?(?:[eE][+-]?\\d+)?").find(s, i)!!
                // "2,5" is a number; "1,2" inside SOMME(1,2) too: function arguments use ";" (or "," after a space).
                var text = m.value
                if (text.contains(',') && inArgs > 0 && !semicolons) text = text.substringBefore(',')
                i += text.length
                return Node.Num(text.replace(',', '.').toDouble())
            }
            if (c.isLetter() || c == '$' || c == '_') {
                val m = Regex("[A-Za-z_$][A-Za-z0-9_.$]*").find(s, i)!!
                val word = m.value
                i += word.length
                if (peek() == '(') {
                    i++
                    val args = mutableListOf<Node>()
                    inArgs++
                    if (!eat(")")) {
                        do args += comparison() while (eat(";") || eat(","))
                        if (!eat(")")) fail()
                    }
                    inArgs--
                    return Node.Call(word.uppercase(), args)
                }
                when (word.uppercase()) {
                    "VRAI", "TRUE" -> return Node.Bool(true)
                    "FAUX", "FALSE" -> return Node.Bool(false)
                }
                val from = Ref.parse(word) ?: fail()
                if (eat(":")) {
                    skip()
                    val m2 = Regex("[A-Za-z$]+\\$?\\d+").find(s, i) ?: fail()
                    if (m2.range.first != i) fail()
                    i += m2.value.length
                    return Node.Range(from, Ref.parse(m2.value) ?: fail())
                }
                return Node.Cell(from)
            }
            fail()
        }

        var inArgs = 0
        val semicolons = s.contains(';')
    }

    fun parse(text: String): Node = Parser(text).parse()

    /** Cells a formula reads, for recomputing what depends on what. */
    fun refs(n: Node): List<Ref> = when (n) {
        is Node.Cell -> listOf(n.ref)
        is Node.Range -> cells(n.from, n.to)
        is Node.Unary -> refs(n.x)
        is Node.Percent -> refs(n.x)
        is Node.Binary -> refs(n.a) + refs(n.b)
        is Node.Call -> n.args.flatMap { refs(it) }
        else -> emptyList()
    }

    fun cells(a: Ref, b: Ref): List<Ref> {
        val c0 = minOf(a.col, b.col)
        val c1 = maxOf(a.col, b.col)
        val r0 = minOf(a.row, b.row)
        val r1 = maxOf(a.row, b.row)
        if ((c1 - c0 + 1).toLong() * (r1 - r0 + 1) > 1_000_000) throw Fail("#REF!")
        return (r0..r1).flatMap { r -> (c0..c1).map { c -> Ref(c, r) } }
    }

    fun eval(n: Node, sheet: Sheet): Value = try {
        value(n, sheet)
    } catch (f: Fail) {
        Value.Error(f.code)
    } catch (e: ArithmeticException) {
        Value.Error("#DIV/0!")
    }

    private fun value(n: Node, sh: Sheet): Value = when (n) {
        is Node.Num -> Value.Num(n.v)
        is Node.Str -> Value.Text(n.v)
        is Node.Bool -> Value.Bool(n.v)
        is Node.Cell -> sh.value(n.ref).also { if (it is Value.Error) throw Fail(it.code) }
        is Node.Range -> throw Fail("#VALEUR!")
        is Node.Unary -> Value.Num(-value(n.x, sh).number())
        is Node.Percent -> Value.Num(value(n.x, sh).number() / 100)
        is Node.Binary -> binary(n, sh)
        is Node.Call -> call(n, sh)
    }

    private fun num(d: Double): Value = if (d.isNaN() || d.isInfinite()) throw Fail("#NOMBRE!") else Value.Num(d)

    private fun binary(n: Node.Binary, sh: Sheet): Value {
        val a = value(n.a, sh)
        val b = value(n.b, sh)
        return when (n.op) {
            "+" -> num(a.number() + b.number())
            "-" -> num(a.number() - b.number())
            "*" -> num(a.number() * b.number())
            "/" -> b.number().let { if (it == 0.0) throw Fail("#DIV/0!") else num(a.number() / it) }
            "^" -> num(a.number().pow(b.number()))
            "&" -> Value.Text(a.text() + b.text())
            else -> {
                val c = compare(a, b)
                Value.Bool(
                    when (n.op) {
                        "=" -> c == 0
                        "<>" -> c != 0
                        "<" -> c < 0
                        ">" -> c > 0
                        "<=" -> c <= 0
                        else -> c >= 0
                    },
                )
            }
        }
    }

    private fun compare(a: Value, b: Value): Int {
        val an = (a as? Value.Num)?.v ?: (a as? Value.Empty)?.let { 0.0 }
        val bn = (b as? Value.Num)?.v ?: (b as? Value.Empty)?.let { 0.0 }
        return if (an != null && bn != null) an.compareTo(bn) else a.text().compareTo(b.text(), ignoreCase = true)
    }

    /** All values of the arguments, ranges flattened. */
    private fun flat(args: List<Node>, sh: Sheet): List<Value> = args.flatMap { a ->
        if (a is Node.Range) cells(a.from, a.to).map { sh.value(it) } else listOf(value(a, sh))
    }

    /** The numbers among the arguments: in ranges, text and empty cells are skipped (as spreadsheets do). */
    private fun numbers(args: List<Node>, sh: Sheet): List<Double> = args.flatMap { a ->
        if (a is Node.Range) cells(a.from, a.to).map { sh.value(it) }.mapNotNull {
            when (it) {
                is Value.Num -> it.v
                is Value.Error -> throw Fail(it.code)
                else -> null
            }
        } else listOf(value(a, sh).number())
    }

    private fun need(args: List<Node>, min: Int, max: Int = min) { if (args.size !in min..max) throw Fail("#N/A") }

    /** Every function, by its English and French names. */
    val Functions = listOf(
        "SUM" to "SOMME", "AVERAGE" to "MOYENNE", "MIN" to "MIN", "MAX" to "MAX", "COUNT" to "NB", "COUNTA" to "NBVAL",
        "IF" to "SI", "AND" to "ET", "OR" to "OU", "NOT" to "NON", "ROUND" to "ARRONDI", "ABS" to "ABS", "SQRT" to "RACINE",
        "INT" to "ENT", "MOD" to "MOD", "POWER" to "PUISSANCE", "CONCAT" to "CONCAT", "LEN" to "NBCAR", "UPPER" to "MAJUSCULE",
        "LOWER" to "MINUSCULE", "TRIM" to "SUPPRESPACE", "LEFT" to "GAUCHE", "RIGHT" to "DROITE", "COUNTIF" to "NB.SI", "SUMIF" to "SOMME.SI",
        "IFERROR" to "SIERREUR", "TODAY" to "AUJOURDHUI", "PRODUCT" to "PRODUIT", "MEDIAN" to "MEDIANE",
    )
    private val french = Functions.associate { (en, fr) -> fr to en }

    private fun call(n: Node.Call, sh: Sheet): Value {
        val name = french[n.name] ?: n.name
        val a = n.args
        return when (name) {
            "SUM" -> num(numbers(a, sh).sum())
            "PRODUCT" -> num(numbers(a, sh).fold(1.0) { x, y -> x * y })
            "AVERAGE" -> numbers(a, sh).let { if (it.isEmpty()) throw Fail("#DIV/0!") else num(it.average()) }
            "MEDIAN" -> numbers(a, sh).sorted().let { if (it.isEmpty()) throw Fail("#NOMBRE!") else num(if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2) }
            "MIN" -> num(numbers(a, sh).minOrNull() ?: 0.0)
            "MAX" -> num(numbers(a, sh).maxOrNull() ?: 0.0)
            "COUNT" -> Value.Num(flat(a, sh).count { it is Value.Num }.toDouble())
            "COUNTA" -> Value.Num(flat(a, sh).count { it !is Value.Empty }.toDouble())
            "IF" -> { need(a, 2, 3); if (value(a[0], sh).truth()) value(a[1], sh) else if (a.size == 3) value(a[2], sh) else Value.Bool(false) }
            "IFERROR" -> { need(a, 2); try { value(a[0], sh) } catch (f: Fail) { value(a[1], sh) } }
            "AND" -> Value.Bool(flat(a, sh).filter { it !is Value.Empty }.all { it.truth() })
            "OR" -> Value.Bool(flat(a, sh).filter { it !is Value.Empty }.any { it.truth() })
            "NOT" -> { need(a, 1); Value.Bool(!value(a[0], sh).truth()) }
            "ROUND" -> {
                need(a, 1, 2)
                val d = if (a.size == 2) value(a[1], sh).number().toInt() else 0
                val f = 10.0.pow(d)
                num(round(value(a[0], sh).number() * f) / f)
            }
            "ABS" -> { need(a, 1); num(abs(value(a[0], sh).number())) }
            "SQRT" -> { need(a, 1); value(a[0], sh).number().let { if (it < 0) throw Fail("#NOMBRE!") else num(sqrt(it)) } }
            "INT" -> { need(a, 1); num(floor(value(a[0], sh).number())) }
            "MOD" -> { need(a, 2); val d = value(a[1], sh).number(); if (d == 0.0) throw Fail("#DIV/0!"); val x = value(a[0], sh).number(); num(x - d * floor(x / d)) }
            "POWER" -> { need(a, 2); num(value(a[0], sh).number().pow(value(a[1], sh).number())) }
            "CONCAT" -> Value.Text(flat(a, sh).joinToString("") { it.text() })
            "LEN" -> { need(a, 1); Value.Num(value(a[0], sh).text().length.toDouble()) }
            "UPPER" -> { need(a, 1); Value.Text(value(a[0], sh).text().uppercase()) }
            "LOWER" -> { need(a, 1); Value.Text(value(a[0], sh).text().lowercase()) }
            "TRIM" -> { need(a, 1); Value.Text(value(a[0], sh).text().trim().replace(Regex("\\s+"), " ")) }
            "LEFT" -> { need(a, 1, 2); val t = value(a[0], sh).text(); Value.Text(t.take(if (a.size == 2) value(a[1], sh).number().toInt() else 1)) }
            "RIGHT" -> { need(a, 1, 2); val t = value(a[0], sh).text(); Value.Text(t.takeLast(if (a.size == 2) value(a[1], sh).number().toInt() else 1)) }
            "COUNTIF" -> { need(a, 2); val test = criterion(value(a[1], sh)); Value.Num(flat(listOf(a[0]), sh).count(test).toDouble()) }
            "SUMIF" -> {
                need(a, 2, 3)
                val test = criterion(value(a[1], sh))
                val range = a[0] as? Node.Range ?: throw Fail("#VALEUR!")
                val keys = cells(range.from, range.to)
                val sums = (a.getOrNull(2) as? Node.Range)?.let { r -> keys.map { Ref(r.from.col + it.col - range.from.col, r.from.row + it.row - range.from.row) } } ?: keys
                num(keys.indices.filter { test(sh.value(keys[it])) }.sumOf { (sh.value(sums[it]) as? Value.Num)?.v ?: 0.0 })
            }
            "TODAY" -> Value.Num((java.time.LocalDate.now().toEpochDay() + 25569).toDouble())
            else -> throw Fail("#NOM?")
        }
    }

    /** ">10", "<>0", "=abc" or a plain value, as NB.SI / SOMME.SI take them. */
    private fun criterion(c: Value): (Value) -> Boolean {
        val t = c.text()
        val m = Regex("^(<=|>=|<>|<|>|=)(.*)$").matchEntire(t)
        val op = m?.groupValues?.get(1) ?: "="
        val rhs = (m?.groupValues?.get(2) ?: t)
        val n = rhs.replace(',', '.').toDoubleOrNull()
        return { v ->
            val cmp = when {
                n != null && v is Value.Num -> v.v.compareTo(n)
                n != null -> null
                else -> v.text().compareTo(rhs, ignoreCase = true)
            }
            if (cmp == null) op == "<>" else
            when (op) {
                "=" -> cmp == 0
                "<>" -> cmp != 0
                "<" -> cmp < 0
                ">" -> cmp > 0
                "<=" -> cmp <= 0
                else -> cmp >= 0
            }
        }
    }
}

/** Formulas written back out: for Excel (English names, ","), for OpenDocument (of:=, [.A1], ";"), or for the user (French names, ";"). */
object FormulaText {
    enum class Dialect { Excel, Odf, French }

    fun write(n: Formula.Node, d: Dialect): String = when (n) {
        is Formula.Node.Num -> Value.format(n.v).let { if (d == Dialect.French) it else it.replace(',', '.') }
        is Formula.Node.Str -> "\"" + n.v.replace("\"", "\"\"") + "\""
        is Formula.Node.Bool -> when (d) { Dialect.French -> if (n.v) "VRAI" else "FAUX"; Dialect.Excel -> if (n.v) "TRUE" else "FALSE"; Dialect.Odf -> if (n.v) "TRUE()" else "FALSE()" }
        is Formula.Node.Cell -> if (d == Dialect.Odf) "[.${n.ref}]" else n.ref.toString()
        is Formula.Node.Range -> if (d == Dialect.Odf) "[.${n.from}:.${n.to}]" else "${n.from}:${n.to}"
        is Formula.Node.Unary -> "-" + wrap(n.x, d)
        is Formula.Node.Percent -> wrap(n.x, d) + "%"
        is Formula.Node.Binary -> wrap(n.a, d) + n.op + wrap(n.b, d)
        is Formula.Node.Call -> {
            val en = Formula.Functions.firstOrNull { it.second == n.name }?.first ?: n.name
            val name = if (d == Dialect.French) Formula.Functions.firstOrNull { it.first == en }?.second ?: en else en
            name + "(" + n.args.joinToString(if (d == Dialect.Excel) "," else ";") { write(it, d) } + ")"
        }
    }

    private fun wrap(n: Formula.Node, d: Dialect) = if (n is Formula.Node.Binary) "(" + write(n, d) + ")" else write(n, d)

    /** "=SOMME(A1;A2)" as Excel stores it ("SUM(A1,A2)", without "="); unparsable text as it is. */
    fun toExcel(input: String): String = runCatching { write(Formula.parse(input.removePrefix("=")), Dialect.Excel) }.getOrDefault(input.removePrefix("="))

    fun toOdf(input: String): String = "of:=" + runCatching { write(Formula.parse(input.removePrefix("=")), Dialect.Odf) }.getOrDefault(input.removePrefix("="))

    /** An OpenDocument formula ("of:=SUM([.A1:.B2])") as the user types it. */
    fun fromOdf(f: String): String {
        val body = f.substringAfter(":=", f.removePrefix("="))
        val plain = body.replace(Regex("\\[\\$?[^\\[\\].]*\\.(\\$?[A-Za-z]+\\$?\\d+)(?::\\$?[^\\[\\].]*\\.(\\$?[A-Za-z]+\\$?\\d+))?]")) { m ->
            m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
        }
        return "=" + fromExcel(plain).removePrefix("=")
    }

    /** An Excel formula ("SUM(A1,A2)") as the user types it, with French names: "=SOMME(A1;A2)". */
    fun fromExcel(f: String): String = "=" + runCatching { write(Formula.parse(f.removePrefix("=")), Dialect.French) }.getOrDefault(f.removePrefix("="))
}
