/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/**
 * The side-by-side test package (debug build with applicationId suffix ".dev",
 * `./gradlew assembleDebug -PairrelayIdSuffix=.dev`). It is sideloaded, so Amazon IAP never
 * answers it and the subscription gate would turn every sender away as UNREACHABLE.
 *
 * Test package only: no subscription gate when a sender connects (mirroring and HLS), and the
 * receiver announces itself as "<name> Dev" so it can be told apart from the store app on the
 * same stick. The store package (no suffix, release) keeps the gate and the name of 0.1.4.
 * Nothing here is stored.
 */
object TestPackage {
    /** applicationId suffix of the side-by-side test package. */
    const val SUFFIX = ".dev"

    /** Appended to the receiver name of the test package, on both dns-sd services. */
    const val NAME_SUFFIX = " Dev"

    /** Both conditions: a release build never counts, whatever its applicationId. */
    fun isTestPackage(applicationId: String, debugBuild: Boolean): Boolean =
        debugBuild && applicationId.endsWith(SUFFIX)

    /** The gate as the receiver applies it: the subscription decides, except in the test package. */
    fun entitlement(testPackage: Boolean, access: Access): Entitlement =
        if (testPackage) Entitlement.UNLOCKED else access.entitlement()
}
