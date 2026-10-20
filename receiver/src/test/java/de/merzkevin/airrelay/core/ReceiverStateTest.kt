/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverStateTest {

    @Test fun idleNeverHoldsAWakeLock() {
        assertFalse(ReceiverState(server = ServerStatus.RUNNING).needsWakeLock)
        // a sender only probing (control centre open) is connected but streams nothing
        assertFalse(ReceiverState(server = ServerStatus.RUNNING, connections = 2).needsWakeLock)
    }

    @Test fun streamingHoldsAWakeLock() {
        assertTrue(ReceiverState(connections = 1, mirroring = true).needsWakeLock)
        assertTrue(ReceiverState(connections = 1, audioSession = true).needsWakeLock)
        assertFalse(ReceiverState(connections = 0, mirroring = true).needsWakeLock)
    }

    @Test fun endingTheSessionKeepsTheServerFields() {
        val s = ReceiverState(ServerStatus.RUNNING, "Wohnzimmer", "192.168.8.11", 7000, 3, true, true, true, Size(886, 1920))
        assertEquals(ReceiverState(ServerStatus.RUNNING, "Wohnzimmer", "192.168.8.11", 7000), s.withoutSession())
    }

    @Test fun endingTheSessionKeepsTheWifiHint() {
        // the advice belongs to the network, and a hint raised during a landscape session must
        // still reach the waiting screen afterwards
        val s = ReceiverState(ServerStatus.RUNNING, mirroring = true, wifiAdvice = WifiAdvice.WEAK_SIGNAL, wifiHintSeq = 3)
        val after = s.withoutSession()
        assertEquals(WifiAdvice.WEAK_SIGNAL, after.wifiAdvice)
        assertEquals(3, after.wifiHintSeq)
    }

    @Test fun wifiHintIsRaisedForEveryCombination() {
        // previous advice, new advice, announce -> raised? Written by hand.
        val n = WifiAdvice.NONE; val w = WifiAdvice.WEAK_SIGNAL; val b = WifiAdvice.BUSY_24GHZ
        val table = listOf(
            Triple(n, n, false) to false, Triple(n, n, true) to false,
            Triple(n, w, false) to true, Triple(n, w, true) to true,
            Triple(n, b, false) to true, Triple(n, b, true) to true,
            Triple(w, n, false) to false, Triple(w, n, true) to false,
            Triple(w, w, false) to false, Triple(w, w, true) to true,
            Triple(w, b, false) to true, Triple(w, b, true) to true,
            Triple(b, n, false) to false, Triple(b, n, true) to false,
            Triple(b, w, false) to true, Triple(b, w, true) to true,
            Triple(b, b, false) to false, Triple(b, b, true) to true,
        )
        assertEquals("the table must cover every combination", WifiAdvice.entries.size * WifiAdvice.entries.size * 2, table.size)
        assertEquals(18, table.map { it.first }.distinct().size)
        for ((input, raised) in table) {
            val (old, new, announce) = input
            val s = ReceiverState(ServerStatus.RUNNING, wifiAdvice = old, wifiHintSeq = 7).withWifiAdvice(new, announce)
            assertEquals("$input advice", new, s.wifiAdvice)
            assertEquals("$input raised", if (raised) 8 else 7, s.wifiHintSeq)
        }
    }

    @Test fun restartingKeepsOnlyTheHintCounter() {
        val s = ReceiverState(ServerStatus.RUNNING, name = "Alt", ip = "10.0.0.2", port = 7000, connections = 1,
            mirroring = true, wifiAdvice = WifiAdvice.WEAK_SIGNAL, wifiHintSeq = 5)
        assertEquals(ReceiverState(server = ServerStatus.STARTING, name = "Neu", ip = "10.0.0.3", wifiHintSeq = 5),
            s.restarting("Neu", "10.0.0.3"))
    }

    @Test fun screenUsesTheDecisionFunction() {
        val s = ReceiverState(server = ServerStatus.RUNNING, connections = 1, mirroring = true)
        assertEquals(Screen.CONNECTING, s.screen(Entitlement.UNLOCKED))
        assertEquals(Screen.MIRRORING, s.copy(firstFrameShown = true).screen(Entitlement.UNLOCKED))
        assertEquals(Screen.LOCKED, s.screen(Entitlement.LOCKED))
    }

    @Test fun configIsWhatTheProductPromises() {
        assertEquals(listOf(7000, 0), ReceiverConfig.PORTS_TO_TRY)
        assertTrue(ReceiverConfig.MAX_FPS == 30 || ReceiverConfig.MAX_FPS == 60)
        assertFalse(ReceiverConfig.ADVERTISE_ALAC)
    }
}
