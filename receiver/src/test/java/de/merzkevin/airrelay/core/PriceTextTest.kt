/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceTextTest {

    @Test fun monthlyEquivalentKeepsTheMarketplaceFormat() {
        assertEquals("1,25 €", monthlyEquivalent("14,99 €"))
        assertEquals("€1.25", monthlyEquivalent("€14.99"))
        assertEquals("$1.25", monthlyEquivalent("$14.99"))
        assertEquals("1,25 €", monthlyEquivalent("14,99 €"))
        assertEquals("EUR 1.25", monthlyEquivalent("EUR 14.99"))
        assertEquals("£1.08", monthlyEquivalent("£12.99"))
    }

    @Test fun groupingSeparatorsAreNotTakenForDecimals() {
        assertEquals("¥125", monthlyEquivalent("¥1,500"))
        assertEquals("102,88 €", monthlyEquivalent("1.234,56 €"))
        assertEquals("$102.88", monthlyEquivalent("$1,234.56"))
    }

    @Test fun unreadablePricesGiveNothingInsteadOfAWrongNumber() {
        assertNull(monthlyEquivalent(null))
        assertNull(monthlyEquivalent(""))
        assertNull(monthlyEquivalent("gratis"))
        assertNull(monthlyEquivalent("0,00 €"))
        assertNull("two numbers", monthlyEquivalent("14,99 € / 12 Monate"))
        assertNull("four decimals", monthlyEquivalent("14,9999 €"))
        assertNull("mixed separators twice", monthlyEquivalent("1.2.3,4,5"))
    }

    @Test fun savingIsRoundedDownAndOnlyForTheSameCurrency() {
        // 1 - 14.99 / 29.88 = 49.8 %: claim 49, never 50
        assertEquals(49, yearlySavingPercent("14,99 €", "2,49 €"))
        assertEquals(49, yearlySavingPercent("$14.99", "$2.49"))
        assertNull(yearlySavingPercent("14,99 €", "$2.49"))
        assertNull("yearly not cheaper", yearlySavingPercent("40,00 €", "2,49 €"))
        assertNull(yearlySavingPercent(null, "2,49 €"))
        assertNull(yearlySavingPercent("14,99 €", "kaputt"))
    }
}
