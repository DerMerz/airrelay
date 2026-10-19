/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/**
 * The only screen is laid out for the Fire TV canvas of 960x540 dp. Its texts are already
 * large (the device name 60 sp), and at the system font scale 1.3 the subscription box, the
 * how-to and the IP line no longer fit on 540 dp: texts were cut off or ran into each other
 * (T-31, measured by ScreenFitTest: already 1.15 cuts the third step off in the offer). The
 * app therefore never enlarges its texts beyond the size it was laid out for ([MAX]) and
 * follows the system only downwards; ScreenFitTest proves that every screen fits, in both
 * languages, at 0.85 and 1.0 and with the system set to 1.15, 1.3 and 2.0.
 */
object FontScale {
    const val MAX = 1.0f

    /** The scale the app uses for a system scale of [system]. */
    fun capped(system: Float): Float = when {
        system.isNaN() || system <= 0f -> 1f
        system > MAX -> MAX
        else -> system
    }
}
