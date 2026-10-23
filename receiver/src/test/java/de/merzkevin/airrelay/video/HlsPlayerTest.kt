/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.video

import androidx.media3.common.C
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs in both unit test variants (debug and release), each against its own runtime classpath:
 * the release run proves that the store package carries the HLS player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HlsPlayerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun factoryAlwaysReturnsTheExoPlayerImplementation() {
        val video = HlsVideoFactory.create(context) { _, _, _, _ -> }
        assertTrue("HlsVideoFactory returned ${video.javaClass.name}", video is ExoHlsVideo)
    }

    /**
     * DefaultMediaSourceFactory finds the HLS module by reflection. Without media3-exoplayer-hls
     * on this variant's classpath it silently drops HLS and a handed-over video never starts.
     */
    @Test fun hlsModuleIsFoundByThePlayer() {
        val types = DefaultMediaSourceFactory(context).supportedTypes
        assertTrue("HLS is not among the supported types ${types.toList()}", C.CONTENT_TYPE_HLS in types)
        Class.forName("androidx.media3.exoplayer.hls.HlsMediaSource\$Factory")
    }
}
