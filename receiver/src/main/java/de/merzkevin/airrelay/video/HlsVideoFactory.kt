/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.video

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import de.merzkevin.airrelay.R

/** The HLS player is Media3 ExoPlayer, in the store package and in the test package alike. */
object HlsVideoFactory {
    fun create(context: Context, reporter: HlsVideo.Reporter): HlsVideo =
        ExoHlsVideo(context.applicationContext, reporter)
}

/**
 * HLS player: one ExoPlayer on the main thread, created on the first /play and released when
 * the video stops (whoever only mirrors never loads it), picture in HlsActivity. Every step is
 * logged under "AirRelayHls" so a single try on the stick tells where it stops (URL received,
 * prepared, first frame, error code).
 */
internal class ExoHlsVideo(
    private val context: Context,
    private val reporter: HlsVideo.Reporter,
) : HlsVideo {
    private val main = Handler(Looper.getMainLooper())

    // main thread only
    private var player: ExoPlayer? = null
    private var screen: HlsActivity? = null
    private var view: SurfaceView? = null

    private val tick = object : Runnable {
        override fun run() {
            report()
            if (player != null) main.postDelayed(this, REPORT_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(TAG, "state ${stateName(playbackState)}")
            report()
            if (playbackState == Player.STATE_ENDED) end("ended")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error ${error.errorCodeName}", error)
            Toast.makeText(context, R.string.video_not_playable, Toast.LENGTH_LONG).show()
            end("error")
        }

        override fun onRenderedFirstFrame() {
            Log.i(TAG, "first frame rendered")
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.i(TAG, "video size ${videoSize.width}x${videoSize.height}")
        }
    }

    init {
        current = this
    }

    override fun play(url: String, startSec: Float) {
        main.post { start(url, startSec) }
    }

    override fun scrub(sec: Float) {
        main.post {
            Log.i(TAG, "scrub to $sec s")
            player?.seekTo((sec.coerceAtLeast(0f) * 1000f).toLong())
        }
    }

    override fun rate(rate: Float) {
        main.post {
            Log.i(TAG, "rate $rate")
            player?.playWhenReady = rate > 0f
            report()
        }
    }

    override fun stop() {
        main.post { close("sender stop", reportEnd = false) }
    }

    override fun release() {
        main.post { close("service gone", reportEnd = false) }
    }

    private fun start(url: String, startSec: Float) {
        close("new play", reportEnd = false)
        Log.i(TAG, "play $url from $startSec s")
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains("/master.m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val p = ExoPlayer.Builder(context).build()
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(listener)
        p.setMediaItem(item, (startSec.coerceAtLeast(0f) * 1000f).toLong())
        p.playWhenReady = true
        p.prepare()
        player = p
        view?.let { p.setVideoSurfaceView(it) }
        main.post(tick)
        if (screen == null) {
            try {
                context.startActivity(Intent(context, HlsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "could not open the video screen", e)
            }
        }
    }

    /** Ended on the TV (end of video, error, Back): the sender learns it from playback-info. */
    private fun end(reason: String) {
        close(reason, reportEnd = true)
    }

    private fun close(reason: String, reportEnd: Boolean) {
        val p = player ?: run {
            if (reportEnd) reporter.report(0f, -1f, 0f, false)
            return
        }
        Log.i(TAG, "close: $reason")
        player = null
        main.removeCallbacks(tick)
        p.removeListener(listener)
        p.release()
        if (reportEnd) reporter.report(0f, -1f, 0f, false)
        if (reason != "new play") screen?.finish()
    }

    private fun report() {
        val p = player ?: return
        val state = p.playbackState
        val durationMs = p.duration
        val live = p.isCurrentMediaItemLive
        val ready = state == Player.STATE_READY && (live || durationMs != C.TIME_UNSET)
        val rate = if (p.playWhenReady && state == Player.STATE_READY) p.playbackParameters.speed else 0f
        val durationSec = if (durationMs == C.TIME_UNSET || live) 0f else durationMs / 1000f
        reporter.report(p.currentPosition / 1000f, durationSec, rate, ready)
    }

    // ---- HlsActivity (main thread) ----

    fun attach(activity: HlsActivity, surface: SurfaceView) {
        screen = activity
        view = surface
        player?.setVideoSurfaceView(surface)
        Log.i(TAG, "video screen attached")
    }

    fun detach(activity: HlsActivity) {
        if (screen !== activity) return
        view?.let { player?.clearVideoSurfaceView(it) }
        screen = null
        view = null
    }

    /** Back on the remote. */
    fun stopFromTv() = end("back on the TV")

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> s.toString()
    }

    companion object {
        private const val TAG = "AirRelayHls"
        private const val REPORT_MS = 250L

        /** The one player of the process, for HlsActivity. Main thread only. */
        @Volatile var current: ExoHlsVideo? = null
            private set
    }
}
