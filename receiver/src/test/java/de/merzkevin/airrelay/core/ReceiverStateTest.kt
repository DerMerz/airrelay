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
