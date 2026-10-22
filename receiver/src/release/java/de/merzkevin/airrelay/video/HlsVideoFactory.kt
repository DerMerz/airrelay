/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.video

import android.content.Context

/** Release build: no HLS player, HLS video is never announced. */
object HlsVideoFactory {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, reporter: HlsVideo.Reporter): HlsVideo? = null
}
