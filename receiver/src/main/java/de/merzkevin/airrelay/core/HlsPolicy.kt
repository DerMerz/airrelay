/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/**
 * HLS video: a video app hands over a stream URL instead of mirroring the screen. Since 0.1.5
 * the store package and the side-by-side test package both announce it and both carry the
 * player. Who may play is the subscription gate's question, as for mirroring: native code asks
 * it at /play (patch 0009) and ReceiverService.onVideoPlay asks it again.
 */
object HlsPolicy {
    /** Language UxPlay falls back to when the system list is empty (it needs at least one). */
    const val FALLBACK_LANGUAGE = "en"

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
