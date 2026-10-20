/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Edges of the classifiers in front of the Wi-Fi tables, and the free strip beside the picture. */
class WifiAdviceTest {

    @Test fun signalIsWeakBelowMinusSeventy() {
        val table = listOf(
            null to RssiClass.UNKNOWN,
            -127 to RssiClass.UNKNOWN, // WifiManager.INVALID_RSSI
            -200 to RssiClass.UNKNOWN,
            0 to RssiClass.UNKNOWN,
            5 to RssiClass.UNKNOWN,
            -95 to RssiClass.WEAK,
            -78 to RssiClass.WEAK, // measured on the stick, 19.10.2026
            -71 to RssiClass.WEAK,
            -70 to RssiClass.STRONG,
            -67 to RssiClass.STRONG,
            -40 to RssiClass.STRONG,
            -1 to RssiClass.STRONG,
        )
        for ((dbm, want) in table) assertEquals("$dbm dBm", want, rssiClass(dbm))
        assertEquals(12, table.size)
    }

    @Test fun bandFollowsTheFrequency() {
        val table = listOf(
            null to BandClass.UNKNOWN,
            -1 to BandClass.UNKNOWN,
            0 to BandClass.UNKNOWN,
            2399 to BandClass.UNKNOWN,
            2400 to BandClass.GHZ_24,
            2412 to BandClass.GHZ_24, // channel 1
            2472 to BandClass.GHZ_24, // channel 13
            2484 to BandClass.GHZ_24, // channel 14
            2500 to BandClass.GHZ_24,
            2501 to BandClass.UNKNOWN,
            4899 to BandClass.UNKNOWN,
            4900 to BandClass.GHZ_5_PLUS,
            5180 to BandClass.GHZ_5_PLUS, // channel 36
            5955 to BandClass.GHZ_5_PLUS, // 6 GHz channel 1
        )
        for ((mhz, want) in table) assertEquals("$mhz MHz", want, bandClass(mhz))
        assertEquals(14, table.size)
    }

    // ---- catch-ups per network (T-31c, Prüfer 0.1.3 Befund 2) ----

    private val homeA = NetworkKey(networkHandle = 100L, band = BandClass.GHZ_24)
    private val homeB = NetworkKey(networkHandle = 101L, band = BandClass.GHZ_24)

    @Test fun catchUpsCountOnlyOnTheNetworkTheyHappenedOn() {
        val c = NetworkCatchUps()
        assertEquals(0L, c.countOn(homeA))
        assertEquals(1L, c.add(homeA))
        assertEquals(2L, c.add(homeA))
        assertEquals(2L, c.countOn(homeA))
        // the stick joined another network: its first stall is one, not three
        assertEquals(0L, c.countOn(homeB))
        assertEquals(1L, c.add(homeB))
        // and back: the earlier network starts again, the count is not kept per network
        assertEquals(0L, c.countOn(homeA))
        assertEquals(1L, c.add(homeA))
    }

    @Test fun aStallOnANewNetworkStartsItsCountAtOne() {
        val c = NetworkCatchUps()
        c.add(homeA)
        c.add(homeA)
        assertEquals("first phase on B, without a reading in between", 1L, c.add(homeB))
    }

    @Test fun theSameNetworkOnAnotherBandIsAnotherNetwork() {
        val c = NetworkCatchUps()
        c.add(homeA)
        c.add(homeA)
        assertEquals(0L, c.countOn(homeA.copy(band = BandClass.GHZ_5_PLUS)))
        assertEquals(0L, c.countOn(homeA.copy(networkHandle = null)))
    }

    @Test fun resetForgetsTheNetworkAndTheCount() {
        val c = NetworkCatchUps()
        c.add(homeA)
        c.add(homeA)
        c.reset()
        assertEquals(0L, c.countOn(homeA))
        assertEquals(1L, c.add(homeA))
    }

    @Test fun twoStallsOnDifferentNetworksShowNoTwoPointFourHint() {
        val c = NetworkCatchUps()
        val rssi = rssiClass(-55)
        var state = ReceiverState()
        state = state.withWifiAdvice(wifiAdvice(rssi, homeA.band, catchUpClass(c.add(homeA))), announce = false)
        state = state.withWifiAdvice(wifiAdvice(rssi, homeB.band, catchUpClass(c.add(homeB))), announce = false)
        assertEquals(WifiAdvice.NONE, state.wifiAdvice)
        assertEquals(0, state.wifiHintSeq)
        state = state.withWifiAdvice(wifiAdvice(rssi, homeB.band, catchUpClass(c.add(homeB))), announce = false)
        assertEquals("two on the same network still warn", WifiAdvice.BUSY_24GHZ, state.wifiAdvice)
        assertEquals(1, state.wifiHintSeq)
    }

    @Test fun twoCatchUpsAreAPattern() {
        val table = listOf(0L to CatchUpClass.NONE, 1L to CatchUpClass.ONE, 2L to CatchUpClass.MANY,
            3L to CatchUpClass.MANY, 1000L to CatchUpClass.MANY, -1L to CatchUpClass.NONE)
        for ((n, want) in table) assertEquals("$n", want, catchUpClass(n))
        assertEquals(6, table.size)
    }

    @Test fun theStickMeasuredOn19OctoberGetsTheHint() {
        // 2.4 GHz, -78 dBm: the case Kevin saw
        assertEquals(WifiAdvice.WEAK_SIGNAL, wifiAdvice(rssiClass(-78), bandClass(2437), catchUpClass(0)))
    }

    @Test fun portraitPictureLeavesTheWiderStrip() {
        // 1080p, portrait 886x1920 scaled to 498x1080, centred
        val container = Size(1920, 1080)
        val video = fitCentered(Size(886, 1920), container)
        val slot = hintSlot(video, container, 400)!!
        assertEquals(video.left + video.width, slot.left)
        assertEquals(container.width, slot.left + slot.width)
        assertEquals(0, slot.top)
        assertEquals(container.height, slot.height)
    }

    @Test fun theWiderSideIsChosen() {
        val container = Size(1000, 500)
        assertEquals(Rect(0, 0, 600, 500), hintSlot(Rect(600, 0, 300, 500), container, 200))
        assertEquals(Rect(400, 0, 600, 500), hintSlot(Rect(100, 0, 300, 500), container, 200))
        // equal strips: the right one
        assertEquals(Rect(650, 0, 350, 500), hintSlot(Rect(350, 0, 300, 500), container, 200))
    }

    @Test fun landscapeOrNarrowStripsLeaveNoPlace() {
        val container = Size(1920, 1080)
        assertNull(hintSlot(fitCentered(Size(1920, 1080), container), container, 400))
        assertNull(hintSlot(fitCentered(Size(2532, 1170), container), container, 400))
        // 4:3 leaves 240 px a side: below the minimum
        assertNull(hintSlot(fitCentered(Size(1440, 1080), container), container, 400))
        // exactly the minimum fits
        assertEquals(400, hintSlot(Rect(0, 0, 1520, 1080), container, 400)!!.width)
        assertNull(hintSlot(Rect(0, 0, 1521, 1080), container, 400))
        assertNull(hintSlot(Rect(0, 0, 1920, 1080), container, 0))
    }
}
