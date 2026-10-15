/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_790_000_000_000L

    private fun bit(s: String) = when (s) {
        "0" -> false
        "1" -> true
        else -> error("not a bit: $s")
    }

    @Test
    fun accessForMatchesTheTableForEveryCombination() {
        val stream = javaClass.getResourceAsStream("access-table.txt")
        assertNotNull("decision table access-table.txt is missing", stream)
        val rows = stream!!.bufferedReader().readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split(Regex("\\s+")) }
        val expected = rows.associate { r ->
            listOf(r[0], r[1], r[2], r[3], r[4]) to Access.valueOf(r[5])
        }
        assertEquals("duplicate rows in access-table.txt", rows.size, expected.size)

        var checked = 0
        for (s in SubscriptionStatus.entries) for (on in listOf(false, true)) for (ca in listOf(false, true))
            for (gr in listOf(false, true)) for (at in listOf(false, true)) {
                fun b(x: Boolean) = if (x) "1" else "0"
                val key = listOf(s.name, b(on), b(ca), b(gr), b(at))
                val want = expected[key] ?: run { fail("no row for $key"); return }
                assertEquals("row $key", want, accessFor(s, on, ca, gr, at))
                checked++
            }
        // 4 statuses x online x cache x grace x attempt; a new enum value must extend the table
        assertEquals(64, checked)
        assertEquals(64, rows.size)
    }

    @Test
    fun onlyUnlockedAdmitsASender() {
        assertEquals(setOf(Access.UNLOCKED), Access.entries.filter { it.admitsSender }.toSet())
        for (a in Access.entries) {
            assertEquals(a.name, if (a == Access.UNLOCKED) Entitlement.UNLOCKED else Entitlement.LOCKED, a.entitlement())
        }
    }

    @Test
    fun offerAndAttemptVariantsAreExactlyThese() {
        assertEquals(setOf(Access.OFFER, Access.OFFER_AFTER_ATTEMPT), Access.entries.filter { it.showsOffer() }.toSet())
        assertEquals(setOf(Access.OFFER_AFTER_ATTEMPT, Access.CHECKING_AFTER_ATTEMPT,
            Access.UNREACHABLE_AFTER_ATTEMPT, Access.CONFIRMATION_OVERDUE_AFTER_ATTEMPT),
            Access.entries.filter { it.afterAttempt() }.toSet())
    }

    // ---- offline grace ----

    @Test fun graceHoldsForExactlyThreeDays() {
        assertTrue(withinGrace(now, now))
        assertTrue(withinGrace(now - 3 * day, now))
        assertFalse(withinGrace(now - 3 * day - 1, now))
        assertFalse(withinGrace(now - 30 * day, now))
        assertFalse("nothing stored means no grace", withinGrace(0L, now))
    }

    @Test fun smallClockCorrectionsDoNotLockButAClockSetFarBackDoes() {
        assertTrue(withinGrace(now + 5 * 60 * 1000, now))
        assertTrue(withinGrace(now + SubscriptionRules.CLOCK_SKEW_TOLERANCE_MS, now))
        assertFalse(withinGrace(now + SubscriptionRules.CLOCK_SKEW_TOLERANCE_MS + 1, now))
        assertFalse(withinGrace(now + 2 * day, now))
    }

    @Test fun cachedSubscriptionIsLockedOfflineAfterThreeDaysWithAClearReason() {
        val stored = now - 3 * day - 60_000
        val a = accessFor(SubscriptionStatus.ACTIVE, online = false, fromCache = true,
            withinGrace = withinGrace(stored, now), attempt = false)
        assertEquals(Access.CONFIRMATION_OVERDUE, a)
        assertFalse(a.admitsSender)
        val b = accessFor(SubscriptionStatus.ACTIVE, online = false, fromCache = true,
            withinGrace = withinGrace(now - 2 * day, now), attempt = true)
        assertEquals(Access.UNLOCKED, b)
    }

    // ---- receipts ----

    private fun receipt(id: String, sku: String = Skus.YEARLY, sub: Boolean = true, canceled: Boolean = false, cancelAt: Long? = null) =
        ReceiptInfo(id, sku, sub, canceled, cancelAt)

    @Test fun runningSubscriptionIsActive() {
        assertEquals(SubscriptionStatus.ACTIVE, statusFromReceipts(listOf(receipt("r1")), now))
        assertEquals(SubscriptionStatus.ACTIVE, statusFromReceipts(listOf(receipt("r1", Skus.MONTHLY)), now))
        assertEquals(SubscriptionStatus.ACTIVE, statusFromReceipts(listOf(receipt("r1", Skus.PARENT)), now))
    }

    @Test fun noReceiptsMeansExpired() {
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(emptyList(), now))
    }

    @Test fun cancelledReceiptsDoNotUnlock() {
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(listOf(receipt("r1", canceled = true)), now))
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(listOf(receipt("r1", canceled = true, cancelAt = now - day)), now))
        assertEquals("cancel date reached", SubscriptionStatus.EXPIRED,
            statusFromReceipts(listOf(receipt("r1", canceled = false, cancelAt = now)), now))
    }

    @Test fun aCancelDateInTheFutureStillRuns() {
        assertEquals(SubscriptionStatus.ACTIVE, statusFromReceipts(listOf(receipt("r1", canceled = true, cancelAt = now + day)), now))
    }

    @Test fun oneRunningReceiptBesideOldCancelledOnesUnlocks() {
        val list = listOf(receipt("old1", canceled = true, cancelAt = now - 400 * day),
            receipt("old2", Skus.MONTHLY, canceled = true, cancelAt = now - 30 * day), receipt("new"))
        assertEquals(SubscriptionStatus.ACTIVE, statusFromReceipts(list, now))
    }

    @Test fun foreignSkusAndNonSubscriptionsAreIgnored() {
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(listOf(receipt("r1", "com.other.pro")), now))
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(listOf(receipt("r1", sub = false)), now))
    }

    @Test fun duplicateReceiptsAreMergedAndACancelledCopyWins() {
        val a = receipt("r1")
        val cancelled = receipt("r1", canceled = true, cancelAt = now - 1)
        assertEquals(1, dedupeReceipts(listOf(a, a, a)).size)
        // pages may arrive in any order; a cancelled copy is never revived
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(listOf(a, cancelled), now))
        assertEquals(SubscriptionStatus.EXPIRED, statusFromReceipts(listOf(cancelled, a), now))
        assertEquals(1, activeReceipts(listOf(receipt("x"), receipt("x"), receipt("y", canceled = true)), now).size)
    }

    @Test fun dedupeKeepsOrderOfFirstAppearance() {
        val list = dedupeReceipts(listOf(receipt("b"), receipt("a"), receipt("b")))
        assertEquals(listOf("b", "a"), list.map { it.receiptId })
    }

    // ---- purchase answers ----

    @Test fun everyPurchaseAnswerHasAVisibleOutcome() {
        val table = mapOf(
            PurchaseResult.SUCCESSFUL to OfferMessage.NONE,
            PurchaseResult.FAILED to OfferMessage.PURCHASE_CANCELLED,
            PurchaseResult.INVALID_SKU to OfferMessage.PRODUCT_NOT_FOUND,
            PurchaseResult.ALREADY_PURCHASED to OfferMessage.ALREADY_SUBSCRIBED,
            PurchaseResult.NOT_SUPPORTED to OfferMessage.AMAZON_UNREACHABLE,
            PurchaseResult.PENDING to OfferMessage.PURCHASE_PENDING,
            PurchaseResult.UNKNOWN to OfferMessage.AMAZON_UNREACHABLE,
        )
        assertEquals(PurchaseResult.entries.size, table.size)
        for ((r, m) in table) assertEquals(r.name, m, messageForPurchase(r))
    }

    @Test fun skusAreThoseInTheConsole() {
        assertEquals("de.merzkevin.airrelay.pro", Skus.PARENT)
        assertEquals("de.merzkevin.airrelay.pro.monthly", Skus.MONTHLY)
        assertEquals("de.merzkevin.airrelay.pro.yearly", Skus.YEARLY)
        assertEquals("yearly is offered first (preselected)", Skus.YEARLY, Skus.TERMS.first())
    }
}
