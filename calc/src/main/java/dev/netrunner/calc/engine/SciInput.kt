package dev.netrunner.calc.engine

/** Keypad editing of the scientific expression: the text grows and shrinks at its end. */
object SciInput {

    /** Removed in one go by backspace. */
    private val chunks = listOf(
        "asinh(", "acosh(", "atanh(", "asin(", "acos(", "atan(", "sinh(", "cosh(", "tanh(",
        "sin(", "cos(", "tan(", "log(", "exp(", "abs(", "ln(", "√(", "∛(", "×10^", " mod ", "ans",
    )

    /** Tokens that continue a result just computed (operators, postfix); anything else starts afresh. */
    private fun continues(token: String) =
        token.first() in "+−×÷^²³!%)" || token.startsWith("×10^") || token == " mod "

    /**
     * Appends [token]. After "=" ([fresh] true) an operator keeps the result as its left side,
     * while a digit or function starts a new expression.
     */
    fun append(expression: String, token: String, fresh: Boolean): String {
        if (fresh && !continues(token)) return token
        // A second operator replaces the first: "2+×" becomes "2×". "−" after another operator is a sign.
        val binary = "+−×÷^"
        val last = expression.lastOrNull()
        if (token.length == 1 && token[0] in binary && last != null && last in binary && !(token == "−" && last != '−')) {
            return expression.dropLast(1) + token
        }
        if (token == ",") {
            val number = expression.takeLastWhile { it.isDigit() || it == ',' }
            if (number.contains(',')) return expression
            if (number.isEmpty()) return expression + "0,"
        }
        return expression + token
    }

    fun backspace(expression: String): String {
        val chunk = chunks.firstOrNull { expression.endsWith(it) }
        return if (chunk != null) expression.dropLast(chunk.length) else expression.dropLast(1)
    }

    /** Closes what is still open, so the history shows a well-formed expression. */
    fun closed(expression: String): String {
        val open = expression.count { it == '(' } - expression.count { it == ')' }
        return if (open > 0) expression + ")".repeat(open) else expression
    }
}
