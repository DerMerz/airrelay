/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.video

/**
 * Player for HLS video handed over by a sender. The calls come from UxPlay's threads and
 * must not block. Only the test build has an implementation (HlsVideoFactory in src/debug);
 * the release build has none, so it carries no player library.
 */
interface HlsVideo {
    fun play(url: String, startSec: Float)
    fun scrub(sec: Float)
    fun rate(rate: Float)
    fun stop()

    /** The service is going away. */
    fun release()

    /** Player state for the sender's /playback-info. durationSec -1 means the video ended. */
    fun interface Reporter {
        fun report(positionSec: Float, durationSec: Float, rate: Float, ready: Boolean)
    }
}
