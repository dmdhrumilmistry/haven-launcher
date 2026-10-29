package app.haven.launcher.data

import java.math.BigDecimal
import java.math.MathContext

/**
 * Tiny offline calculator for the search box: + - * / % ^, parentheses and decimals.
 * Returns null for anything that isn't clearly arithmetic, so normal searches are untouched.
 */
object Calculator {
    private val allowed = Regex("^[0-9.,+\\-*/×÷x%^() ]+$")
    private val hasOperator = Regex("[0-9)]\\s*[+\\-*/×÷x%^]\\s*[0-9(.-]")

    fun evaluate(input: String): String? {
        val text = input.trim()
        if (text.length < 3 || !allowed.matches(text) || !hasOperator.containsMatchIn(text)) return null
        val normalized = text.replace('×', '*').replace('÷', '/').replace('x', '*').replace(',', '.').replace(" ", "")
        return try {
            val parser = Parser(normalized)
            val value = parser.parseExpression()
            if (parser.pos != normalized.length || value.isNaN() || value.isInfinite()) null else format(value)
        } catch (e: Exception) {
            null
        }
    }

    private fun format(value: Double): String {
        val rounded = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()
        return if (rounded.compareTo(BigDecimal.ZERO) == 0) "0" else rounded.toPlainString()
    }

    private class Parser(val s: String) {
        var pos = 0

        fun parseExpression(): Double {
            var v = parseTerm()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
            return v
        }

        fun parseTerm(): Double {
            var v = parseFactor()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> { pos++; v *= parseFactor() }
                    '/' -> { pos++; v /= parseFactor() }
                    '%' -> {
                        pos++
                        // "50%" alone means 0.5; "7 % 3" is modulo.
                        if (pos >= s.length || s[pos] in "+-*/)") v /= 100 else v %= parseFactor()
                    }
                    else -> return v
                }
            }
            return v
        }

        fun parseFactor(): Double {
            val base = parseUnary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                return Math.pow(base, parseFactor())
            }
            return base
        }

        fun parseUnary(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -parseUnary() }
            if (pos < s.length && s[pos] == '+') { pos++; return parseUnary() }
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = parseExpression()
                require(pos < s.length && s[pos] == ')')
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start)
            return s.substring(start, pos).toDouble()
        }
    }
}
