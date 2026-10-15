/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {

    @Test fun volumeEndpoints() {
        assertEquals(GAIN_UNITY_Q15, airplayVolumeToGainQ15(0f))
        assertEquals(GAIN_UNITY_Q15, airplayVolumeToGainQ15(3f))
        assertEquals(0, airplayVolumeToGainQ15(-144f))
        assertEquals(0, airplayVolumeToGainQ15(-200f))
        assertEquals(0, airplayVolumeToGainQ15(Float.NaN))
    }

    @Test fun volumeIsDecibels() {
        assertEquals(16423, airplayVolumeToGainQ15(-6f)) // 0.5012
        assertEquals(3277, airplayVolumeToGainQ15(-20f)) // 0.1
        assertEquals(1036, airplayVolumeToGainQ15(-30f)) // 0.0316, quietest step still audible
    }

    @Test fun belowMinusThirtyButNotMuteStaysAtTheQuietestStep() {
        assertEquals(1036, airplayVolumeToGainQ15(-60f))
    }

    @Test fun volumeIsMonotonic() {
        var last = -1
        var db = -30f
        while (db <= 0f) {
            val g = airplayVolumeToGainQ15(db)
            assertTrue("$db", g >= last)
            last = g
            db += 0.25f
        }
    }

    @Test fun hardwareDecoderIsPreferred() {
        val list = listOf(
            DecoderInfo("OMX.google.h264.decoder", false),
            DecoderInfo("c2.android.avc.decoder", true),
            DecoderInfo("OMX.MTK.VIDEO.DECODER.AVC", false),
        )
        assertEquals("OMX.MTK.VIDEO.DECODER.AVC", chooseDecoder(list)!!.name)
    }

    @Test fun softwareDecoderIsTheFallback() {
        assertEquals("OMX.google.h264.decoder", chooseDecoder(listOf(DecoderInfo("OMX.google.h264.decoder", false)))!!.name)
        assertNull(chooseDecoder(emptyList()))
    }

    @Test fun softwareOnlyFlagWinsOverAName() {
        val list = listOf(DecoderInfo("OMX.vendor.avc", true), DecoderInfo("OMX.hw.avc", false))
        assertEquals("OMX.hw.avc", chooseDecoder(list)!!.name)
    }

    @Test fun softwareNames() {
        assertTrue(looksLikeSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(looksLikeSoftwareDecoder("c2.android.avc.decoder"))
        assertTrue(looksLikeSoftwareDecoder("OMX.SEC.avc.sw.dec"))
        assertTrue(!looksLikeSoftwareDecoder("OMX.MTK.VIDEO.DECODER.AVC"))
        assertTrue(!looksLikeSoftwareDecoder("c2.mtk.avc.decoder"))
    }

    @Test fun ladderOnAndroid9() {
        assertEquals(
            listOf(
                mapOf("vdec-lowlatency" to 1, "priority" to 0),
                mapOf("priority" to 0),
                emptyMap(),
            ),
            lowLatencyLadder(28),
        )
    }

    @Test fun ladderOnAndroid11StartsWithTheStandardKey() {
        val l = lowLatencyLadder(30)
        assertEquals(mapOf("low-latency" to 1, "vdec-lowlatency" to 1, "priority" to 0), l.first())
        assertEquals(emptyMap<String, Int>(), l.last())
        assertEquals(4, l.size)
    }

    @Test fun ipPrefersWifiAndSkipsUnusableAddresses() {
        val ifs = listOf(
            "lo" to listOf("127.0.0.1"),
            "eth0" to listOf("10.0.0.2"),
            "wlan0" to listOf("169.254.3.4", "192.168.8.11"),
        )
        assertEquals("192.168.8.11", pickIpv4(ifs))
    }

    @Test fun ipFallsBackToEthernetAndIgnoresOtherInterfaces() {
        assertEquals("10.0.0.2", pickIpv4(listOf("eth0" to listOf("10.0.0.2"), "wlan0" to listOf("169.254.1.1"))))
        assertNull(pickIpv4(listOf("p2p0" to listOf("192.168.49.1"), "rmnet0" to listOf("10.1.1.1"))))
        assertNull(pickIpv4(emptyList()))
        assertNull(pickIpv4(listOf("wlan0" to listOf("fe80::1", "300.1.1.1", "0.0.0.0", "1.2.3"))))
    }
}
