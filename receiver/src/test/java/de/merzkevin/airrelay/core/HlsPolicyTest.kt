/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Who announces HLS video: only the side-by-side test package, and only with a player. */
class HlsPolicyTest {

    /** Expectations written by hand, not derived from HlsPolicy. */
    private val enabledTable = listOf(
        // applicationId, player in the build, expected
        Triple("de.merzkevin.airrelay", true, false),        // store package, even if a player were in it
        Triple("de.merzkevin.airrelay", false, false),       // store package as shipped
        Triple("de.merzkevin.airrelay.dev", true, true),     // test package with player: the Durchstich
        Triple("de.merzkevin.airrelay.dev", false, false),   // test package without player
        Triple("de.merzkevin.airrelay.devx", true, false),   // suffix must be exact
        Triple("de.merzkevin.airrelay.dev.x", true, false),
        Triple("de.merzkevin.airrelay.DEV", true, false),
        Triple("de.merzkevin.airrelaydev", true, false),     // no dot, no test package
        Triple("", true, false),
    )

    @Test fun enabledTable() {
        assertEquals("table must not shrink unnoticed", 9, enabledTable.size)
        for ((id, player, expected) in enabledTable) {
            assertEquals("enabled($id, $player)", expected, HlsPolicy.enabled(id, player))
        }
    }

    private val languageTable = listOf(
        emptyList<String>() to "en",
        listOf("de") to "de",
        listOf("de", "en") to "de:en",
        listOf("en", "de") to "en:de",                 // order of the system list is kept
        listOf("DE", " en ") to "de:en",
        listOf("de", "de", "en") to "de:en",
        listOf("", "  ") to "en",                     // never empty: UxPlay asserts on it
        listOf("", "fr") to "fr",
        listOf("a:b", "it") to "it",                  // a ':' would split one entry into two
    )

    @Test fun systemLanguagesTable() {
        assertEquals("table must not shrink unnoticed", 9, languageTable.size)
        for ((input, expected) in languageTable) {
            assertEquals("systemLanguages($input)", expected, HlsPolicy.systemLanguages(input))
        }
    }
}
