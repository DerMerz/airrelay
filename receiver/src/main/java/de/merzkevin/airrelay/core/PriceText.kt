package de.merzkevin.airrelay.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Amazon hands out prices as display strings in the customer's marketplace format
 * ("14,99 €", "€14.99", "$14.99", "¥1,500"). The yearly offer is compared with the monthly
 * price, so the number inside has to be found. Anything that cannot be read with
 * certainty gives null, and the comparison is simply not shown.
 */
data class ParsedPrice(
    val value: BigDecimal,
    val decimals: Int,
    val decimalSeparator: Char,
    val prefix: String,
    val suffix: String,
)

private val NUMBER = Regex("\\d(?:[\\d.,\\u00A0\\u202F ']*\\d)?")

fun parsePrice(text: String?): ParsedPrice? {
    if (text.isNullOrBlank()) return null
    val matches = NUMBER.findAll(text).toList()
    if (matches.size != 1) return null
    val m = matches[0]
    val run = m.value
    val lastSep = run.indexOfLast { it == '.' || it == ',' }
    var decimals = 0
    var decimalSep = ','
    val digits: String
    if (lastSep >= 0) {
        val tail = run.substring(lastSep + 1)
        val sep = run[lastSep]
        val sameSepCount = run.count { it == sep }
        val isDecimal = tail.length in 1..2 && tail.all { it.isDigit() } && sameSepCount == 1
        if (isDecimal) {
            decimals = tail.length
            decimalSep = sep
            val intPart = run.substring(0, lastSep).filter { it.isDigit() }
            digits = "$intPart.$tail"
        } else if (tail.length == 3) {
            // grouping only ("1,500", "1.234.567")
            digits = run.filter { it.isDigit() }
            decimalSep = if (sep == '.') ',' else '.'
        } else {
            return null
        }
    } else {
        digits = run.filter { it.isDigit() }
    }
    val value = digits.toBigDecimalOrNull() ?: return null
    if (value.signum() <= 0) return null
    return ParsedPrice(value, decimals, decimalSep, text.substring(0, m.range.first), text.substring(m.range.last + 1))
}

/** Formats [value] in the style of [like] (same currency text, separator, decimals). */
fun formatLike(like: ParsedPrice, value: BigDecimal): String {
    val rounded = value.setScale(like.decimals, RoundingMode.HALF_UP).toPlainString()
    val shown = if (like.decimals > 0) rounded.replace('.', like.decimalSeparator) else rounded
    return like.prefix + shown + like.suffix
}

/** "1,25 €" for "14,99 €": a year's price spread over twelve months. */
fun monthlyEquivalent(yearlyPrice: String?): String? {
    val p = parsePrice(yearlyPrice) ?: return null
    return formatLike(p, p.value.divide(BigDecimal(12), 6, RoundingMode.HALF_UP))
}

/**
 * Whole percent saved by the yearly term against twelve monthly payments, rounded down so
 * the claim is never larger than the truth. Null unless both prices are readable, in the
 * same currency text, and the yearly term is actually cheaper.
 */
fun yearlySavingPercent(yearlyPrice: String?, monthlyPrice: String?): Int? {
    val y = parsePrice(yearlyPrice) ?: return null
    val m = parsePrice(monthlyPrice) ?: return null
    if (y.prefix.trim() != m.prefix.trim() || y.suffix.trim() != m.suffix.trim()) return null
    val twelve = m.value.multiply(BigDecimal(12))
    val saved = BigDecimal.ONE.subtract(y.value.divide(twelve, 6, RoundingMode.HALF_UP)).multiply(BigDecimal(100))
    val percent = saved.setScale(0, RoundingMode.FLOOR).toInt()
    return percent.takeIf { it > 0 }
}
