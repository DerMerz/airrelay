/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/**
 * HLS video (an app such as YouTube hands over a stream URL instead of mirroring the screen).
 * Test build only for now (0.1.5 Durchstich): it is announced to senders only by the side-by-side
 * test package (applicationId suffix ".dev") and only if that build carries a player. The store
 * package keeps announcing mirroring plus sound exactly as 0.1.4 did.
 */
object HlsPolicy {
    const val TEST_PACKAGE_SUFFIX = ".dev"

    /** Language UxPlay falls back to when the system list is empty (it needs at least one). */
    const val FALLBACK_LANGUAGE = "en"

    fun enabled(applicationId: String, playerAvailable: Boolean): Boolean =
        playerAvailable && applicationId.endsWith(TEST_PACKAGE_SUFFIX)

    /**
     * Colon-separated language list for UxPlay's audio track choice ("de:en"), from the system
     * locales in order. Empty or blank entries are dropped, duplicates kept once, never empty.
     */
    fun systemLanguages(languages: List<String>): String {
        val list = languages.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && ':' !in it }
            .distinct()
        return if (list.isEmpty()) FALLBACK_LANGUAGE else list.joinToString(":")
    }
}
