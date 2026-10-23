/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The language list UxPlay gets for the audio track of an HLS video. */
class HlsPolicyTest {

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
