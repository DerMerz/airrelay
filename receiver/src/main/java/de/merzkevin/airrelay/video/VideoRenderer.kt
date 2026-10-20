/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.video

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import de.merzkevin.airrelay.core.DecoderInfo
import de.merzkevin.airrelay.core.Frame
import de.merzkevin.airrelay.core.FrameAction
import de.merzkevin.airrelay.core.FramePacer
import de.merzkevin.airrelay.core.GopBuffer
import de.merzkevin.airrelay.core.InputQueue
import de.merzkevin.airrelay.core.ReceiverConfig
import de.merzkevin.airrelay.core.Size
import de.merzkevin.airrelay.core.chooseDecoder
import de.merzkevin.airrelay.core.frameTarget
import de.merzkevin.airrelay.core.isH264Keyframe
import de.merzkevin.airrelay.core.lowLatencyLadder
import de.merzkevin.airrelay.core.visibleSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * H.264 mirroring stream -> hardware decoder -> SurfaceView. No GL, no copy of decoded
 * pictures: MediaCodec renders straight into the display surface.
 *
 * Threading: one "video" HandlerThread per mirroring session, created on start and quit
 * on stop, so nothing runs while idle. Every MediaCodec call happens on it (async mode
 * callbacks are delivered there too). Frames from the native thread are posted to it.
 *
 * The display surface comes and goes (the app is brought to the front after the first
 * frames already arrived; Home and back). The sender sends keyframes rarely, so the
 * decoder must never lose its reference pictures:
 *  - without a display the decoder writes to a parking surface and releases every picture
 *    unrendered (a SurfaceTexture that is never drawn: no GL context, no copies);
 *  - when the display appears, the decoder restarts on it and replays the frames since the
 *    last keyframe (picture at once, even if the sender shows a still image); if that group
 *    was too large to keep, the running decoder is switched over with setOutputSurface and
 *    the picture appears with the next frame.
 *
 * Pacing (T-31b): a picture is no longer shown the moment the decoder hands it out. After a
 * Wi-Fi stall that replayed the backlog in time-lapse while the sound was already live; now
 * late pictures are skipped and the newest one is shown, the rest keeps its stream distance.
 * Decision in [de.merzkevin.airrelay.core.frameAction], state in [FramePacer].
 */
class VideoRenderer(private val listener: Listener, private val codecSize: Size) {

    interface Listener {
        /** Visible picture size changed (first picture, rotation). Any thread. */
        fun onVideoSize(size: Size)
        /** The first picture of this decoder run is on the display. Any thread. */
        fun onFirstFrameRendered()
        /** The picture fell behind and had to catch up; [phases] counts this session. Video thread. */
        fun onCatchUp(phases: Long)
    }

    private val sessionLock = Any()
    @Volatile private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var surface: Surface? = null
    private var parkingTexture: SurfaceTexture? = null
    private var parkingSurface: Surface? = null
    /** video thread: the running decoder renders into [surface], not the parking surface */
    private var outputOnDisplay = false

    // video thread only
    private val gop = GopBuffer(ReceiverConfig.GOP_MAX_BYTES)
    private val queue = InputQueue(ReceiverConfig.MAX_PENDING_FRAMES)
    private val freeInputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null
    private var renderFromPtsUs = Long.MIN_VALUE
    private var firstFrameSeen = false
    private var decoder: DecoderInfo? = null
    private var lastSize: Size? = null
    private val pacer = FramePacer()

    // counters for logs and the debug feed, written on the video thread
    @Volatile var framesIn = 0L; private set
    @Volatile var framesRendered = 0L; private set
    @Volatile var restarts = 0L; private set
    /** Pictures skipped because they were late (T-31b). */
    @Volatile var drops = 0L; private set
    /** Jumps to the newest picture after skipping. */
    @Volatile var jumps = 0L; private set
    /** Phases in which the picture fell behind (Aufholphasen). */
    @Volatile var catchUps = 0L; private set
    /** Pictures shown faster than their stream distance outside a jump. Must stay 0. */
    @Volatile var fastShown = 0L; private set

    fun startSession() = synchronized(sessionLock) {
        if (thread != null) return@synchronized
        val t = HandlerThread("video", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = t
        handler = Handler(t.looper)
        framesIn = 0
        framesRendered = 0
        restarts = 0
        drops = 0
        jumps = 0
        catchUps = 0
        fastShown = 0
        // the pacer belongs to the video thread; the first thing the new one does
        handler?.post { pacer.resetSession() }
    }

    fun stopSession() = synchronized(sessionLock) {
        val h = handler ?: return@synchronized
        val done = CountDownLatch(1)
        h.post {
            releaseCodec()
            releaseParking()
            gop.clear()
            lastSize = null
            done.countDown()
        }
        done.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "session stopped: in=$framesIn rendered=$framesRendered restarts=$restarts " +
            "drops=$drops jumps=$jumps catchUps=$catchUps fastShown=$fastShown")
    }

    /** Native mirror thread. */
    fun onFrame(data: ByteArray, ptsNs: Long) {
        val h = handler ?: run { startSession(); handler } ?: return
        val frame = Frame(data, ptsNs / 1000, isH264Keyframe(data))
        h.post { handleFrame(frame) }
    }

    /** UI thread, from surfaceCreated. */
    fun setSurface(s: Surface) {
        surface = s
        handler?.post { attachDisplay() }
    }

    /**
     * UI thread, from surfaceDestroyed. Blocks until the decoder has let go of the surface,
     * as SurfaceHolder.Callback requires.
     */
    fun clearSurface(s: Surface) {
        if (surface !== s) return
        val h = handler
        if (h == null) {
            surface = null
            return
        }
        val done = CountDownLatch(1)
        h.post {
            detachDisplay()
            surface = null
            done.countDown()
        }
        if (!done.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "decoder did not release the surface in time")
        }
    }

    // ---- video thread ----

    private fun handleFrame(frame: Frame) {
        framesIn++
        pacer.onReceived(frame.ptsUs)
        gop.add(frame)
        if (codec == null) {
            startCodec()
            return
        }
        if (!queue.offer(frame)) {
            Log.w(TAG, "decoder fell behind, restarting at the last keyframe")
            restartFromGop()
            return
        }
        feedInputs()
    }

    private fun attachDisplay() {
        val s = surface ?: return
        val c = codec
        if (c != null && outputOnDisplay) return
        when {
            gop.hasKeyframe -> {
                // restart on the display and replay: the current picture appears at once
                releaseCodec()
                startCodec()
            }
            c != null && !outputOnDisplay -> try {
                c.setOutputSurface(s)
                outputOnDisplay = true
                Log.i(TAG, "decoder switched to the display, picture follows with the next frame")
            } catch (e: Exception) {
                Log.w(TAG, "setOutputSurface failed, waiting for the next keyframe: ${e.message}")
                releaseCodec()
            }
            c == null -> startCodec()
        }
    }

    private fun detachDisplay() {
        val c = codec ?: return
        if (!outputOnDisplay) return
        try {
            c.setOutputSurface(parking())
            outputOnDisplay = false
            Log.i(TAG, "display gone, decoder parked")
        } catch (e: Exception) {
            Log.w(TAG, "could not park the decoder: ${e.message}")
            releaseCodec()
        }
    }

    private fun parking(): Surface = parkingSurface ?: run {
        val texture = if (Build.VERSION.SDK_INT >= 26) SurfaceTexture(false) else SurfaceTexture(0)
        parkingTexture = texture
        Surface(texture).also { parkingSurface = it }
    }

    private fun releaseParking() {
        parkingSurface?.release()
        parkingTexture?.release()
        parkingSurface = null
        parkingTexture = null
    }

    private fun startCodec() {
        if (!gop.hasKeyframe || codec != null) return
        val display = surface
        val s = display ?: parking()
        val info = decoder ?: findDecoder()?.also { decoder = it } ?: run {
            Log.e(TAG, "no H.264 decoder on this device")
            return
        }
        for (options in lowLatencyLadder(Build.VERSION.SDK_INT)) {
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createByCodecName(info.name)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, codecSize.width, codecSize.height)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(codecSize.width * codecSize.height * 3 / 4, 1 shl 20))
                format.setInteger(MediaFormat.KEY_FRAME_RATE, ReceiverConfig.MAX_FPS)
                options.forEach { (k, v) -> format.setInteger(k, v) }
                c.setCallback(callback, handler)
                c.configure(format, s, null, 0)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                c.setOnFrameRenderedListener(frameRendered, handler)
                c.start()
                codec = c
                outputOnDisplay = display != null
                Log.i(TAG, "decoder ${info.name} started on the ${if (display != null) "display" else "parking surface"}, options=$options")
                break
            } catch (e: Exception) {
                Log.w(TAG, "configure with $options failed: ${e.message}")
                try { c?.release() } catch (_: Exception) {}
            }
        }
        if (codec == null) {
            Log.e(TAG, "decoder ${info.name} could not be started")
            return
        }
        firstFrameSeen = false
        pacer.resetOutput()
        val group = gop.snapshot()
        renderFromPtsUs = group.last().ptsUs
        queue.replaceWith(group)
        freeInputs.clear()
        Log.i(TAG, "replaying ${group.size} frames from the last keyframe")
    }

    private fun restartFromGop() {
        restarts++
        releaseCodec()
        startCodec()
    }

    private fun feedInputs() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty() && !queue.isEmpty()) {
            val frame = queue.poll() ?: break
            val index = freeInputs.first()
            val buf = try { c.getInputBuffer(index) } catch (e: IllegalStateException) { null } ?: return
            if (frame.data.size > buf.capacity()) {
                Log.w(TAG, "frame of ${frame.data.size} bytes exceeds input buffer ${buf.capacity()}, dropped")
                continue
            }
            freeInputs.removeFirst()
            buf.clear()
            buf.put(frame.data)
            try {
                c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            } catch (e: Exception) {
                Log.w(TAG, "queueInputBuffer failed: ${e.message}")
                restartFromGop()
                return
            }
        }
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        outputOnDisplay = false
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        queue.clear()
        freeInputs.clear()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index)
            feedInputs()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            val target = frameTarget(outputOnDisplay, info.presentationTimeUs, renderFromPtsUs)
            val paced = pacer.onOutput(info.presentationTimeUs, System.nanoTime() / 1000, target)
            try {
                when (paced.action) {
                    // as up to 0.1.2: on the display at the next refresh
                    FrameAction.SHOW, FrameAction.JUMP -> c.releaseOutputBuffer(index, true)
                    // same clock as System.nanoTime, as releaseOutputBuffer expects
                    FrameAction.PACE -> c.releaseOutputBuffer(index, paced.renderAtUs!! * 1000)
                    FrameAction.HIDE, FrameAction.DROP -> c.releaseOutputBuffer(index, false)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "releaseOutputBuffer failed: ${e.message}")
            }
            drops = pacer.drops
            jumps = pacer.jumps
            fastShown = pacer.fastShown
            if (pacer.catchUps != catchUps) {
                catchUps = pacer.catchUps
                Log.i(TAG, "picture fell behind, catching up by skipping (phase $catchUps)")
                listener.onCatchUp(catchUps)
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Log.w(TAG, "decoder error ${e.diagnosticInfo}, restarting at the last keyframe")
            restartFromGop()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec) return
            fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else -1
            val size = visibleSize(
                int(MediaFormat.KEY_WIDTH), int(MediaFormat.KEY_HEIGHT),
                int("crop-left"), int("crop-top"), int("crop-right"), int("crop-bottom"),
            )
            if (size.isValid && size != lastSize) {
                lastSize = size
                Log.i(TAG, "picture size ${size.width}x${size.height}")
                listener.onVideoSize(size)
            }
        }
    }

    private val frameRendered = MediaCodec.OnFrameRenderedListener { c, _, _ ->
        if (c !== codec) return@OnFrameRenderedListener
        framesRendered++
        if (!firstFrameSeen) {
            firstFrameSeen = true
            // measure.sh reads this line
            Log.i(TAG, "First frame rendered at ${SystemClock.elapsedRealtime()} ms")
            listener.onFirstFrameRendered()
        }
    }

    private fun findDecoder(): DecoderInfo? {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .map { DecoderInfo(it.name, Build.VERSION.SDK_INT >= 29 && it.isSoftwareOnly) }
        return chooseDecoder(candidates)?.also { Log.i(TAG, "H.264 decoder: ${it.name}") }
    }

    companion object {
        private const val TAG = "AirRelayVideo"
        private const val STOP_TIMEOUT_MS = 1000L
    }
}
