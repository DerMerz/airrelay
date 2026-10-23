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

/**
 * Test package (".dev", debug) skips the subscription gate and announces "<name> Dev";
 * the store package keeps the gate and the name of 0.1.4. Expectations written by hand, not derived
 * from TestPackage.
 */
class TestPackageTest {

    private val store = "de.merzkevin.airrelay"
    private val dev = "de.merzkevin.airrelay.dev"
    private val ids = listOf(store, dev, "de.merzkevin.airrelay.devx", "de.merzkevin.airrelay.DEV",
        "de.merzkevin.airrelaydev", "")

    /** The only (applicationId, debug build) pair that skips the gate. */
    private val skipsGate = setOf(dev to true)

    /** Store behaviour of 0.1.4: which subscription answer lets a sender in. */
    private val admitsInStore = mapOf(
        Access.UNLOCKED to true,
        Access.OFFER to false,
        Access.OFFER_AFTER_ATTEMPT to false,
        Access.CHECKING to false,
        Access.CHECKING_AFTER_ATTEMPT to false,
        Access.UNREACHABLE to false,
        Access.UNREACHABLE_AFTER_ATTEMPT to false,
        Access.CONFIRMATION_OVERDUE to false,
        Access.CONFIRMATION_OVERDUE_AFTER_ATTEMPT to false,
    )

    @Test fun gateTableSuffixTimesBuildTypeTimesSubscription() {
        assertEquals("a new Access value needs a row here", Access.entries.toSet(), admitsInStore.keys)
        var cases = 0
        for (id in ids) for (debug in listOf(true, false)) for (access in Access.entries) {
            val expected = if ((id to debug) in skipsGate) true else admitsInStore.getValue(access)
            val got = TestPackage.entitlement(TestPackage.isTestPackage(id, debug), access)
            assertEquals("entitlement($id, debug=$debug, $access)",
                if (expected) Entitlement.UNLOCKED else Entitlement.LOCKED, got)
            cases++
        }
        assertEquals("table must not shrink unnoticed", 108, cases)
    }

    @Test fun deviceLog23OctoberIsAdmittedOnlyInTheTestPackage() {
        // Fire TV 23.10. 10:18:45: "sender refused: UNREACHABLE" in the sideloaded .dev package
        assertTrue(TestPackage.isTestPackage(dev, debugBuild = true))
        assertEquals(Entitlement.UNLOCKED, TestPackage.entitlement(true, Access.UNREACHABLE))
        assertEquals(Entitlement.UNLOCKED, TestPackage.entitlement(true, Access.UNREACHABLE_AFTER_ATTEMPT))
        // the store package (release, no suffix) keeps refusing as 0.1.4 did
        assertFalse(TestPackage.isTestPackage(store, debugBuild = false))
        assertEquals(Entitlement.LOCKED, TestPackage.entitlement(false, Access.UNREACHABLE))
        // a release build never skips, even with the suffix
        assertFalse(TestPackage.isTestPackage(dev, debugBuild = false))
        // a plain debug build without suffix does not either
        assertFalse(TestPackage.isTestPackage(store, debugBuild = true))
    }

    @Test fun licenseGateAppliesTheTestPackageFlag() {
        val initialAccess = LicenseGate.access
        val initialFlag = LicenseGate.testPackage
        assertFalse("gate must start with store behaviour", initialFlag)
        try {
            for (flag in listOf(false, true)) for (a in Access.entries) {
                LicenseGate.testPackage = flag
                LicenseGate.access = a
                val expected = flag || admitsInStore.getValue(a)
                assertEquals("admitsSender(flag=$flag, $a)", expected, LicenseGate.admitsSender())
                assertEquals("current(flag=$flag, $a)",
                    if (expected) Entitlement.UNLOCKED else Entitlement.LOCKED, LicenseGate.current())
            }
        } finally {
            LicenseGate.access = initialAccess
            LicenseGate.testPackage = initialFlag
        }
    }

    /** deviceName, test package, expected announced name. */
    private val nameTable = listOf(
        Triple("Kevins 2. FireTVStick", false, "Kevins 2. FireTVStick"),
        Triple("Kevins 2. FireTVStick", true, "Kevins 2. FireTVStick Dev"),
        Triple(null, false, "Fire TV"),
        Triple(null, true, "Fire TV Dev"),
        Triple("", false, "Fire TV"),
        Triple("", true, "Fire TV Dev"),
        Triple("  Wohnzimmer  ", false, "Wohnzimmer"),
        Triple("  Wohnzimmer  ", true, "Wohnzimmer Dev"),
        Triple("A".repeat(60), false, "A".repeat(50)),
        Triple("A".repeat(60), true, "A".repeat(46) + " Dev"),
        Triple("Ä".repeat(30), false, "Ä".repeat(25)),   // 2 bytes each
        Triple("Ä".repeat(30), true, "Ä".repeat(23) + " Dev"),
    )

    @Test fun announcedNameTable() {
        assertEquals("table must not shrink unnoticed", 12, nameTable.size)
        for ((device, testPackage, expected) in nameTable) {
            val got = announcedReceiverName(device, testPackage)
            assertEquals("announcedReceiverName($device, $testPackage)", expected, got)
            assertTrue("$got is longer than a dns-sd label allows",
                got.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        }
    }

    @Test fun storeNameIsExactlyTheOldName() {
        for (device in listOf(null, "", "Fire TV", "Kevins 2. FireTVStick", "local", "x".repeat(80), "Küche\tTV.")) {
            assertEquals("store name for $device", receiverName(device), announcedReceiverName(device, false))
            assertFalse(announcedReceiverName(device, false).endsWith(" Dev"))
        }
    }
}
