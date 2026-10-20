/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Full cross products of the UI decisions against hand-written tables in
 * src/test/resources. The tables are typed out row by row, not derived from the code.
 * A missing table file or a missing row is a failure, never a skip.
 */
class DecisionTablesTest {

    private fun table(name: String): List<List<String>> {
        val stream = javaClass.getResourceAsStream(name)
        assertNotNull("decision table $name is missing", stream)
        return stream!!.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }
    }

    private fun bit(s: String) = when (s) {
        "0" -> false
        "1" -> true
        else -> error("not a bit: $s")
    }

    @Test
    fun screenForMatchesTheTableForEveryCombination() {
        val rows = table("screen-table.txt")
        val expected = rows.associate { r ->
            listOf(r[0], r[1], r[2], r[3], r[4]) to Screen.valueOf(r[5])
        }
        assertEquals("duplicate rows in screen-table.txt", rows.size, expected.size)

        var checked = 0
        for (e in Entitlement.entries) for (s in ServerStatus.entries)
            for (m in listOf(false, true)) for (f in listOf(false, true)) for (a in listOf(false, true)) {
                val key = listOf(e.name, s.name, if (m) "1" else "0", if (f) "1" else "0", if (a) "1" else "0")
                val want = expected[key] ?: run { fail("no row for $key"); return }
                assertEquals("row $key", want, screenFor(s, m, f, a, e))
                checked++
            }
        // 2 entitlements x 5 server states x 2 x 2 x 2; a new enum value must extend the table
        assertEquals(80, checked)
        assertEquals(80, rows.size)
    }

    @Test
    fun backActionMatchesTheTableForEveryCombination() {
        val rows = table("back-table.txt")
        val expected = rows.associate { r -> Triple(r[0], bit(r[1]), bit(r[2])) to BackAction.valueOf(r[3]) }
        assertEquals("duplicate rows in back-table.txt", rows.size, expected.size)

        var checked = 0
        for (screen in Screen.entries) for (lic in listOf(false, true)) for (hint in listOf(false, true)) {
            val key = Triple(screen.name, lic, hint)
            val want = expected[key] ?: run { fail("no row for $key"); return }
            assertEquals("row $key", want, backActionFor(screen, lic, hint))
            checked++
        }
        // 9 screens x 2 x 2
        assertEquals(36, checked)
        assertEquals(36, rows.size)
    }

    @Test
    fun catchUpCountMatchesTheTableForEveryCombination() {
        val rows = table("catchup-table.txt")
        val expected = rows.associate { r -> Triple(bit(r[0]), bit(r[1]), bit(r[2])) to bit(r[3]) }
        assertEquals("duplicate rows in catchup-table.txt", rows.size, expected.size)

        var checked = 0
        for (lb in listOf(false, true)) for (counted in listOf(false, true)) for (net in listOf(false, true)) {
            val key = Triple(lb, counted, net)
            val want = expected[key] ?: run { fail("no row for $key"); return }
            assertEquals("row $key", want, countsAsCatchUp(lb, counted, net))
            checked++
        }
        // 2 x 2 x 2
        assertEquals(8, checked)
        assertEquals(8, rows.size)
    }

    @Test
    fun frameActionMatchesTheTableForEveryCombination() {
        val rows = table("frame-table.txt")
        val expected = rows.associate { r ->
            listOf(r[0], r[1], r[2], r[3], r[4], r[5]) to FrameAction.valueOf(r[6])
        }
        assertEquals("duplicate rows in frame-table.txt", rows.size, expected.size)

        val bits = listOf(false, true)
        var checked = 0
        for (t in FrameTarget.entries) for (lt in bits) for (bh in bits) for (ts in bits) for (hl in bits) for (db in bits) {
            val key = listOf(t.name) + listOf(lt, bh, ts, hl, db).map { if (it) "1" else "0" }
            val want = expected[key] ?: run { fail("no row for $key"); return }
            assertEquals("row $key", want, frameAction(t, lt, bh, ts, hl, db))
            checked++
        }
        // 3 targets x 2^5; a new target or input must extend the table
        assertEquals(96, checked)
        assertEquals(96, rows.size)
    }

    @Test
    fun wifiAdviceMatchesTheTableForEveryCombination() {
        val rows = table("wifi-table.txt")
        val expected = rows.associate { r -> Triple(r[0], r[1], r[2]) to WifiAdvice.valueOf(r[3]) }
        assertEquals("duplicate rows in wifi-table.txt", rows.size, expected.size)

        var checked = 0
        for (r in RssiClass.entries) for (b in BandClass.entries) for (c in CatchUpClass.entries) {
            val key = Triple(r.name, b.name, c.name)
            val want = expected[key] ?: run { fail("no row for $key"); return }
            assertEquals("row $key", want, wifiAdvice(r, b, c))
            checked++
        }
        // 3 x 3 x 3
        assertEquals(27, checked)
        assertEquals(27, rows.size)
    }

    @Test
    fun wifiHintPlaceMatchesTheTableForEveryCombination() {
        val rows = table("hint-table.txt")
        val expected = rows.associate { r -> Triple(r[0], r[1], bit(r[2])) to HintPlace.valueOf(r[3]) }
        assertEquals("duplicate rows in hint-table.txt", rows.size, expected.size)

        var checked = 0
        for (screen in Screen.entries) for (a in WifiAdvice.entries) for (p in listOf(false, true)) {
            val key = Triple(screen.name, a.name, p)
            val want = expected[key] ?: run { fail("no row for $key"); return }
            assertEquals("row $key", want, wifiHintPlace(screen, a, p))
            checked++
        }
        // 9 screens x 3 advices x 2
        assertEquals(54, checked)
        assertEquals(54, rows.size)
    }

    @Test
    fun startOutcomeForEveryCombination() {
        // ip present x native ready x port bound, written out
        val table = listOf(
            Triple(null, false, null) to ServerStatus.NO_NETWORK,
            Triple(null, false, 7000) to ServerStatus.NO_NETWORK,
            Triple(null, true, null) to ServerStatus.NO_NETWORK,
            Triple(null, true, 7000) to ServerStatus.NO_NETWORK,
            Triple("192.168.8.11", false, null) to ServerStatus.START_FAILED,
            Triple("192.168.8.11", false, 7000) to ServerStatus.START_FAILED,
            Triple("192.168.8.11", true, null) to ServerStatus.PORT_IN_USE,
            Triple("192.168.8.11", true, 7000) to ServerStatus.RUNNING,
        )
        for ((input, want) in table) {
            assertEquals("$input", want, startOutcome(input.first, input.second, input.third))
        }
        assertEquals(8, table.size)
    }

    @Test
    fun onlyConnectingAndMirroringShowVideo() {
        val video = Screen.entries.filter { it.showsVideo() }.toSet()
        assertEquals(setOf(Screen.CONNECTING, Screen.MIRRORING), video)
        val errors = Screen.entries.filter { it.isError() }.toSet()
        assertEquals(setOf(Screen.NO_NETWORK, Screen.PORT_IN_USE, Screen.START_FAILED), errors)
    }

    @Test
    fun gateStartsClosedAndFollowsTheSubscription() {
        val initial = LicenseGate.access
        // before the stored or fresh status is known, no sender gets in
        assertEquals(Access.CHECKING, initial)
        assertEquals(Entitlement.LOCKED, LicenseGate.current())
        try {
            for (a in Access.entries) {
                LicenseGate.access = a
                assertEquals(a.name, if (a == Access.UNLOCKED) Entitlement.UNLOCKED else Entitlement.LOCKED, LicenseGate.current())
                assertEquals(a.name, a == Access.UNLOCKED, LicenseGate.admitsSender())
            }
        } finally {
            LicenseGate.access = initial
        }
    }

    @Test
    fun fullScreenAlertOnlyFromAndroid10() {
        // Fire OS 7 (28) keeps the plain start; Fire OS 8 (30) gets the alert as well
        val table = mapOf(24 to false, 25 to false, 28 to false, 29 to true, 30 to true, 34 to true)
        for ((sdk, want) in table) assertEquals("sdk $sdk", want, needsFullScreenAlert(sdk))
        assertEquals(6, table.size)
    }
}
