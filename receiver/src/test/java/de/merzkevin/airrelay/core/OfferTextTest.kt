package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferTextTest {

    @Test fun labelForEveryCombinationOfPriceAndTrial() {
        // price known/unknown x trial none / readable / unreadable, written out
        val table = listOf(
            Triple(null, null, TermText(TermLabel.TERM_ONLY)),
            Triple(null, "Weekly", TermText(TermLabel.TERM_ONLY)),
            Triple(null, "irgendwas", TermText(TermLabel.TERM_ONLY)),
            Triple("", "Weekly", TermText(TermLabel.TERM_ONLY)),
            Triple("14,99 €", null, TermText(TermLabel.PRICE_ONLY)),
            Triple("14,99 €", "", TermText(TermLabel.PRICE_ONLY)),
            Triple("14,99 €", "Weekly", TermText(TermLabel.TRIAL_DAYS_THEN_PRICE, 7)),
            Triple("14,99 €", "irgendwas", TermText(TermLabel.TRIAL_THEN_PRICE)),
        )
        for ((price, trial, want) in table) assertEquals("$price / $trial", want, termText(price, trial))
        assertEquals(8, table.size)
    }

    @Test fun noTrialFromAmazonMeansNoFreePromise() {
        // Amazon only sends a trial period while the customer can still get it
        assertEquals(TermLabel.PRICE_ONLY, termText("2,49 €", null).label)
        assertNull(offerHeadlineTrialDays(emptyMap()))
    }

    @Test fun trialLengthsAmazonMayReport() {
        for (s in listOf("Weekly", "WEEKLY", "weekly", "7 Days", "7 days", "7days", "P7D", "p7d", "1 Week", "P1W", "7 Tage"))
            assertEquals(s, 7, trialDays(s))
        assertEquals(14, trialDays("BiWeekly"))
        assertEquals(14, trialDays("14 Days"))
        assertEquals(30, trialDays("Monthly"))
        assertEquals(30, trialDays("1 Month"))
        for (s in listOf(null, "", "  ", "0 Days", "P0D", "Quarterly", "sieben Tage", "1000 Days"))
            assertNull("$s", trialDays(s))
    }

    @Test fun headlineUsesTheYearlyTrialFirst() {
        assertEquals(7, offerHeadlineTrialDays(mapOf(Skus.YEARLY to "Weekly", Skus.MONTHLY to "BiWeekly")))
        assertEquals(14, offerHeadlineTrialDays(mapOf(Skus.MONTHLY to "BiWeekly")))
        assertNull(offerHeadlineTrialDays(mapOf(Skus.YEARLY to "unklar")))
    }
}
