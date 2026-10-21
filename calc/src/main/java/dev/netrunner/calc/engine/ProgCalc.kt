package dev.netrunner.calc.engine

/**
 * Programmer calculator: integers of a fixed width (8 to 64 bits), signed or not, in base 2, 8,
 * 10 or 16. The expression is kept as tokens holding values, not text, so switching base
 * re-renders the numbers instead of re-reading them.
 */
object ProgCalc {

    class CalcError(message: String) : Exception(message)

    enum class Base(val radix: Int, val label: String) { HEX(16, "HEX"), DEC(10, "DEC"), OCT(8, "OCT"), BIN(2, "BIN") }

    enum class Width(val bits: Int, val label: String) { QWORD(64, "QWORD"), DWORD(32, "DWORD"), WORD(16, "WORD"), BYTE(8, "BYTE") }

    data class Mode(val width: Width = Width.QWORD, val signed: Boolean = true) {
        val mask: Long get() = if (width.bits == 64) -1L else (1L shl width.bits) - 1

        /** Wraps [v] to the word: keeps the low bits, then sign-extends when signed. */
        fun wrap(v: Long): Long {
            val low = v and mask
            return if (signed && width.bits < 64 && low and (1L shl (width.bits - 1)) != 0L) low or mask.inv() else low
        }
    }

    sealed interface Token
    data class Num(val value: Long) : Token
    /** Binary operators, by their display symbol. */
    data class Op(val symbol: String) : Token
    data object Not : Token
    data object Neg : Token
    data object Open : Token
    data object Close : Token

    val operators = listOf("+", "−", "×", "÷", "MOD", "AND", "OR", "XOR", "NAND", "NOR", "<<", ">>")

    private fun precedence(op: String) = when (op) {
        "×", "÷", "MOD" -> 6
        "+", "−" -> 5
        "<<", ">>" -> 4
        "AND", "NAND" -> 3
        "XOR" -> 2
        "OR", "NOR" -> 1
        else -> 0
    }

    /** Text of [v] in [base]: two's complement in HEX/OCT/BIN, signed or not in DEC. */
    fun format(v: Long, base: Base, mode: Mode, group: Boolean = false): String {
        val w = mode.wrap(v)
        val raw = if (base == Base.DEC) {
            if (mode.signed) w.toString() else w.toULong().toString()
        } else {
            java.lang.Long.toUnsignedString(w and mode.mask, base.radix).uppercase()
        }
        if (!group) return raw
        val size = when (base) { Base.BIN -> 4; Base.HEX -> 4; Base.OCT -> 3; Base.DEC -> 3 }
        val negative = raw.startsWith('-')
        val digits = raw.removePrefix("-")
        val grouped = digits.reversed().chunked(size).joinToString(" ").reversed()
        return if (negative) "-$grouped" else grouped
    }

    fun render(tokens: List<Token>, base: Base, mode: Mode): String = tokens.joinToString(" ") {
        when (it) {
            is Num -> format(it.value, base, mode)
            is Op -> it.symbol
            Not -> "NOT"
            Neg -> "−"
            Open -> "("
            Close -> ")"
        }
    }.replace("( ", "(").replace(" )", ")").replace("NOT (", "NOT(").replace("− (", "−(")

    /**
     * Appends digit [d] to the number being typed, or starts a new one. Returns the new tokens,
     * unchanged when the digit would overflow the word.
     */
    fun typeDigit(tokens: List<Token>, d: Int, base: Base, mode: Mode): List<Token> {
        if (d >= base.radix) return tokens
        val last = tokens.lastOrNull()
        if (last is Close) return tokens + Op("×") + Num(d.toLong())
        if (last !is Num) return tokens + Num(d.toLong())
        val current = last.value and mode.mask
        // Unsigned growth check: current × radix + d must fit in the word.
        val limit = mode.mask.toULong()
        val grown = current.toULong() * base.radix.toULong() + d.toULong()
        if (current.toULong() > limit / base.radix.toULong() || grown > limit) return tokens
        return tokens.dropLast(1) + Num(mode.wrap(grown.toLong()))
    }

    fun backspace(tokens: List<Token>, base: Base, mode: Mode): List<Token> {
        val last = tokens.lastOrNull() ?: return tokens
        if (last is Num) {
            val u = (last.value and mode.mask).toULong()
            if (u >= base.radix.toULong()) return tokens.dropLast(1) + Num(mode.wrap((u / base.radix.toULong()).toLong()))
        }
        return tokens.dropLast(1)
    }

    /** Adds a binary operator, or replaces a trailing one. A leading "−" is a negation. */
    fun typeOperator(tokens: List<Token>, symbol: String): List<Token> {
        val last = tokens.lastOrNull()
        return when {
            last == null -> if (symbol == "−") listOf(Neg) else tokens
            last is Op -> if (symbol == "−") tokens + Neg else tokens.dropLast(1) + Op(symbol)
            last is Open || last is Not || last is Neg -> if (symbol == "−" && last !is Neg) tokens + Neg else tokens
            else -> tokens + Op(symbol)
        }
    }

    fun typeOpen(tokens: List<Token>): List<Token> {
        val last = tokens.lastOrNull()
        return if (last is Num || last is Close) tokens + Op("×") + Open else tokens + Open
    }

    fun typeClose(tokens: List<Token>): List<Token> {
        val open = tokens.count { it == Open } - tokens.count { it == Close }
        val last = tokens.lastOrNull()
        return if (open > 0 && (last is Num || last is Close)) tokens + Close else tokens
    }

    fun typeNot(tokens: List<Token>): List<Token> {
        val last = tokens.lastOrNull()
        return if (last is Num || last is Close) tokens + Op("×") + Not else tokens + Not
    }

    /** Evaluates the tokens; unclosed parentheses are closed. Throws [CalcError]. */
    fun eval(tokens: List<Token>, mode: Mode): Long {
        if (tokens.isEmpty()) throw CalcError("expression vide")
        val p = Parser(tokens, mode)
        val v = p.expr(0)
        if (p.i < tokens.size) throw CalcError("expression invalide")
        return mode.wrap(v)
    }

    private class Parser(val t: List<Token>, val mode: Mode) {
        var i = 0

        /** Precedence climbing over the binary operators. */
        fun expr(minPrecedence: Int): Long {
            var left = unary()
            while (i < t.size) {
                val op = (t[i] as? Op)?.symbol ?: break
                val p = precedence(op)
                if (p < minPrecedence || p == 0) break
                i++
                val right = expr(p + 1)
                left = mode.wrap(apply(op, left, right))
            }
            return left
        }

        fun unary(): Long {
            if (i >= t.size) throw CalcError("expression incomplète")
            return when (val tok = t[i]) {
                Not -> { i++; mode.wrap(unary().inv()) }
                Neg -> { i++; mode.wrap(-unary()) }
                Open -> {
                    i++
                    val v = expr(0)
                    if (i < t.size) {
                        if (t[i] != Close) throw CalcError("parenthèse manquante")
                        i++
                    }
                    v
                }
                is Num -> { i++; mode.wrap(tok.value) }
                else -> throw CalcError("expression incomplète")
            }
        }

        fun apply(op: String, a: Long, b: Long): Long {
            val bits = mode.width.bits
            return when (op) {
                "+" -> a + b
                "−" -> a - b
                "×" -> a * b
                "÷" -> {
                    if (mode.wrap(b) == 0L) throw CalcError("division par zéro")
                    if (mode.signed) a / b else java.lang.Long.divideUnsigned(a and mode.mask, b and mode.mask)
                }
                "MOD" -> {
                    if (mode.wrap(b) == 0L) throw CalcError("division par zéro")
                    if (mode.signed) a % b else java.lang.Long.remainderUnsigned(a and mode.mask, b and mode.mask)
                }
                "AND" -> a and b
                "OR" -> a or b
                "XOR" -> a xor b
                "NAND" -> (a and b).inv()
                "NOR" -> (a or b).inv()
                "<<" -> { val n = shift(b); if (n >= bits) 0L else a shl n }
                ">>" -> {
                    val n = shift(b)
                    // Arithmetic shift when signed (a is sign-extended), logical otherwise.
                    if (mode.signed) a shr minOf(n, 63) else if (n >= bits) 0L else (a and mode.mask) ushr n
                }
                else -> throw CalcError("opérateur inconnu")
            }
        }

        fun shift(b: Long): Int {
            if (b < 0) throw CalcError("décalage négatif")
            return if (b > 64) 64 else b.toInt()
        }
    }

    /** Bit grid tap: flips [bit] of the number being typed, or starts a number with only that bit. */
    fun toggleBit(tokens: List<Token>, bit: Int, mode: Mode): List<Token> {
        val last = tokens.lastOrNull()
        return if (last is Num) tokens.dropLast(1) + Num(mode.wrap(last.value xor (1L shl bit)))
        else if (last is Close) tokens + Op("XOR") + Num(mode.wrap(1L shl bit))
        else tokens + Num(mode.wrap(1L shl bit))
    }
}
