/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FontScaleTest {
    /** system scale to the scale the app uses, written by hand. */
    private val table = listOf(
        0.85f to 0.85f,
        1.0f to 1.0f,
        1.01f to 1.0f,
        1.15f to 1.0f,
        1.3f to 1.0f,
        2.0f to 1.0f,
        0f to 1.0f,
        -1f to 1.0f,
        Float.NaN to 1.0f,
        Float.POSITIVE_INFINITY to 1.0f,
    )

    @Test fun capsTheSystemScale() {
        for ((system, expected) in table) {
            assertEquals("system scale $system", expected, FontScale.capped(system), 0.0001f)
        }
        assertEquals("the table shrank", 10, table.size)
    }

    @Test fun theCapIsWhatTheScreensWereProvenAt() {
        // ScreenFitTest measures every screen at 0.85, 1.0, 1.15, 1.3 and 2.0 and finds the
        // offer cut off already at 1.15. 1.0 is the highest scale it proves without a cut.
        assertEquals(1.0f, FontScale.MAX, 0f)
    }
}
