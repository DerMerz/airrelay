package de.merzkevin.airrelay.core

/**
 * Wording of the offer. The button text is what the customer gets and pays, never a
 * command ("7 Tage gratis, dann 14,99 €/Jahr", not "Jetzt kaufen!"). A free trial is only
 * mentioned while Amazon offers it to this customer (Product.getFreeTrialPeriod() is only
 * sent then), so nobody is promised a trial they already had.
 */
enum class TermLabel {
    /** "7 days free, then 14,99 €/year" */
    TRIAL_DAYS_THEN_PRICE,
    /** Trial offered, length not readable: "Free trial, then 14,99 €/year" */
    TRIAL_THEN_PRICE,
    /** "14,99 €/year" */
    PRICE_ONLY,
    /** Price not known (Amazon not asked yet or not reachable): "Yearly" */
    TERM_ONLY,
}

/** Label choice plus the trial length it shows (only for [TermLabel.TRIAL_DAYS_THEN_PRICE]). */
data class TermText(val label: TermLabel, val trialDays: Int? = null)

fun termText(price: String?, trialPeriod: String?): TermText {
    if (price.isNullOrBlank()) return TermText(TermLabel.TERM_ONLY)
    if (trialPeriod.isNullOrBlank()) return TermText(TermLabel.PRICE_ONLY)
    val days = trialDays(trialPeriod) ?: return TermText(TermLabel.TRIAL_THEN_PRICE)
    return TermText(TermLabel.TRIAL_DAYS_THEN_PRICE, days)
}

/**
 * Days of a free trial period as Amazon may report it ("Weekly", "7 Days", "P7D",
 * "1 Month", "BiWeekly"). Null if it cannot be read with certainty; then no number is shown.
 */
fun trialDays(period: String?): Int? {
    val p = period?.trim()?.lowercase() ?: return null
    if (p.isEmpty()) return null
    when (p.replace(Regex("[\\s_-]"), "")) {
        "weekly", "week", "oneweek", "1week", "p1w" -> return 7
        "biweekly", "twoweeks", "2weeks", "p2w" -> return 14
        "monthly", "month", "onemonth", "1month", "p1m" -> return 30
    }
    Regex("^p(\\d{1,3})d$").find(p)?.let { return it.groupValues[1].toInt().takeIf { d -> d > 0 } }
    Regex("^(\\d{1,3})\\s*(day|days|tag|tage)$").find(p)?.let { return it.groupValues[1].toInt().takeIf { d -> d > 0 } }
    return null
}

/** Headline of the offer box: the trial of the preselected (yearly) term, if offered. */
fun offerHeadlineTrialDays(trials: Map<String, String>): Int? =
    trialDays(trials[Skus.YEARLY]) ?: trialDays(trials[Skus.MONTHLY])
