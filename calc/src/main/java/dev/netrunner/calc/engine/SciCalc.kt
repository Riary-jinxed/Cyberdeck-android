package dev.netrunner.calc.engine

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Scientific expression evaluator. Understands the keypad's symbols (× ÷ − π √ ∛ ² ³), functions,
 * postfix ! and %, implicit multiplication ("2π", "3(4+1)", "2sin30") and unclosed parentheses
 * at the end, so the live preview works while typing. Commas count as decimal points.
 */
object SciCalc {

    class CalcError(message: String) : Exception(message)

    private val functions: Map<String, (Double, Boolean) -> Double> = mapOf(
        "asinh" to { x, _ -> ln(x + sqrt(x * x + 1)) },
        "acosh" to { x, _ -> ln(x + sqrt(x * x - 1)) },
        "atanh" to { x, _ -> 0.5 * ln((1 + x) / (1 - x)) },
        "asin" to { x, deg -> asin(x).let { if (deg) Math.toDegrees(it) else it } },
        "acos" to { x, deg -> acos(x).let { if (deg) Math.toDegrees(it) else it } },
        "atan" to { x, deg -> atan(x).let { if (deg) Math.toDegrees(it) else it } },
        "sinh" to { x, _ -> sinh(x) },
        "cosh" to { x, _ -> cosh(x) },
        "tanh" to { x, _ -> tanh(x) },
        "sin" to { x, deg -> trig(x, deg, ::sin) },
        "cos" to { x, deg -> trig(x, deg, ::cos) },
        "tan" to { x, deg ->
            val r = if (deg) Math.toRadians(x) else x
            if (deg && abs((x % 180.0 + 180.0) % 180.0 - 90.0) < 1e-12) throw CalcError("tangente infinie")
            snap(tan(r))
        },
        "sqrt" to { x, _ -> if (x < 0) throw CalcError("racine d'un nombre négatif") else sqrt(x) },
        "cbrt" to { x, _ -> cbrt(x) },
        "log2" to { x, _ -> log(x, ::log2) },
        "log" to { x, _ -> log(x, ::log10) },
        "ln" to { x, _ -> log(x, ::ln) },
        "exp" to { x, _ -> exp(x) },
        "abs" to { x, _ -> abs(x) },
    )

    // Longest names first, so "asinh" is not read as "asin" followed by "h".
    private val names = functions.keys.sortedByDescending { it.length }

    private fun trig(x: Double, deg: Boolean, f: (Double) -> Double): Double = snap(f(if (deg) Math.toRadians(x) else x))

    /** sin(180°) is 1.2e-16 in floating point: show 0. */
    private fun snap(v: Double) = if (abs(v) < 1e-14) 0.0 else v

    private fun log(x: Double, f: (Double) -> Double): Double = if (x <= 0) throw CalcError("logarithme d'un nombre ≤ 0") else f(x)

    /** Evaluates [expression]; [degrees] for trigonometry, [ans] for the "ans" token. Throws [CalcError]. */
    fun eval(expression: String, degrees: Boolean = true, ans: Double = 0.0): Double {
        val s = normalize(expression)
        if (s.isEmpty()) throw CalcError("expression vide")
        val v = Parser(s, degrees, ans).run()
        if (v.isNaN()) throw CalcError("résultat indéfini")
        if (v.isInfinite()) throw CalcError("dépassement")
        return v
    }

    private fun normalize(e: String) = e.lowercase(Locale.ROOT)
        .replace(" ", "").replace(',', '.')
        .replace('×', '*').replace('÷', '/').replace('−', '-')
        .replace("π", "pi").replace("√", "sqrt").replace("∛", "cbrt")
        .replace("²", "^2").replace("³", "^3")

    private class Parser(val s: String, val degrees: Boolean, val ans: Double) {
        var i = 0

        fun run(): Double {
            val v = sum()
            if (i < s.length) throw CalcError(if (s[i] == ')') "parenthèse en trop" else "caractère inattendu « ${s[i]} »")
            return v
        }

        fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun product(): Double {
            var v = unary()
            while (i < s.length) {
                val c = s[i]
                v = when {
                    c == '*' -> { i++; v * unary() }
                    c == '/' -> {
                        i++
                        val r = unary()
                        if (r == 0.0) throw CalcError("division par zéro")
                        v / r
                    }
                    s.startsWith("mod", i) -> {
                        i += 3
                        val r = unary()
                        if (r == 0.0) throw CalcError("division par zéro")
                        v - r * floor(v / r)
                    }
                    startsAtom() -> v * power()
                    else -> return v
                }
            }
            return v
        }

        /** Something that can follow a value with an implied "×": 2π, 3(1+1), 2sin30, (1)(2). */
        fun startsAtom(): Boolean {
            val c = s[i]
            return c.isDigit() || c == '.' || c == '(' || c.isLetter()
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return power()
        }

        fun power(): Double {
            val base = postfix()
            if (i < s.length && s[i] == '^') {
                i++
                val exponent = unary()
                if (base < 0 && exponent != floor(exponent)) throw CalcError("puissance d'un nombre négatif")
                return base.pow(exponent)
            }
            return base
        }

        fun postfix(): Double {
            var v = atom()
            while (i < s.length) {
                when (s[i]) {
                    '!' -> { i++; v = factorial(v) }
                    '%' -> { i++; v /= 100.0 }
                    else -> return v
                }
            }
            return v
        }

        fun atom(): Double {
            if (i >= s.length) throw CalcError("expression incomplète")
            val c = s[i]
            if (c == '(') {
                i++
                val v = sum()
                // An unclosed parenthesis at the very end is closed implicitly.
                if (i < s.length) {
                    if (s[i] != ')') throw CalcError("parenthèse manquante")
                    i++
                }
                return v
            }
            for (name in names) {
                if (s.startsWith(name, i)) {
                    i += name.length
                    val arg = if (i < s.length && s[i] == '(') atom() else unaryArg()
                    return functions.getValue(name)(arg, degrees)
                }
            }
            if (s.startsWith("pi", i)) { i += 2; return PI }
            if (s.startsWith("ans", i)) { i += 3; return ans }
            if (c == 'e' && !(i + 1 < s.length && s[i + 1].isLetter())) { i++; return E }
            if (c.isDigit() || c == '.') return number()
            throw CalcError("caractère inattendu « $c »")
        }

        /** Argument without parentheses: "sin30", "√2", "sin-30". */
        fun unaryArg(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unaryArg() }
            return postfix()
        }

        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            // Scientific notation: 1.5e3, 2e-4 (but "2e" alone stays 2×e).
            if (i < s.length && s[i] == 'e') {
                var j = i + 1
                if (j < s.length && (s[j] == '-' || s[j] == '+')) j++
                if (j < s.length && s[j].isDigit()) {
                    i = j
                    while (i < s.length && s[i].isDigit()) i++
                }
            }
            return s.substring(start, i).toDoubleOrNull() ?: throw CalcError("nombre invalide")
        }
    }

    fun factorial(v: Double): Double {
        if (v < 0 || v != floor(v)) throw CalcError("factorielle d'un entier positif seulement")
        if (v > 170) throw CalcError("dépassement")
        var r = 1.0
        for (k in 2..v.toInt()) r *= k
        return r
    }

    /** 12 significant digits, French decimal comma, scientific notation for huge or tiny values. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
        val a = abs(v)
        val text = if (a >= 1e15 || a < 1e-9) {
            val (mantissa, exponent) = "%.10e".format(Locale.ROOT, v).split('e')
            "${trimZeros(mantissa)}e${exponent.toInt()}"
        } else {
            BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
        }
        return text.replace('.', ',')
    }

    private fun trimZeros(t: String) = if (t.contains('.')) t.trimEnd('0').trimEnd('.') else t
}
