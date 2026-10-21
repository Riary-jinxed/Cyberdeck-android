package dev.netrunner.launcher.terminal

import kotlin.math.PI
import kotlin.math.E
import kotlin.math.pow
import kotlin.math.sqrt

/** Small expression evaluator: + - * / % ^, parentheses, sqrt(), pi and e. Commas count as decimal points. */
object Calc {
    fun eval(expression: String): Double = Parser(expression.replace(',', '.').replace(" ", "").lowercase()).run()

    private class Parser(val s: String) {
        var i = 0

        fun run(): Double {
            val v = sum()
            if (i < s.length) error("caractère inattendu « ${s[i]} »")
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
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%' || s[i] == 'x' || s[i] == '×')) {
                val op = s[i++]
                val r = power()
                v = when (op) {
                    '/' -> if (r == 0.0) error("division par zéro") else v / r
                    '%' -> v % r
                    else -> v * r
                }
            }
            return v
        }

        fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return base.pow(power())
            }
            return base
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return atom()
        }

        fun atom(): Double {
            if (i >= s.length) error("expression incomplète")
            if (s[i] == '(') {
                i++
                val v = sum()
                if (i >= s.length || s[i] != ')') error("parenthèse manquante")
                i++
                return v
            }
            if (s.startsWith("sqrt", i) || s.startsWith("√", i)) {
                i += if (s[i] == '√') 1 else 4
                val v = atom()
                if (v < 0) error("racine d'un nombre négatif")
                return sqrt(v)
            }
            if (s.startsWith("pi", i)) { i += 2; return PI }
            if (s[i] == 'e' && (i + 1 >= s.length || !s[i + 1].isLetter())) { i++; return E }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) error("caractère inattendu « ${s[i]} »")
            return s.substring(start, i).toDoubleOrNull() ?: error("nombre invalide")
        }
    }

    fun format(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.10g".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
}
