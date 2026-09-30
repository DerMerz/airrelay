package de.merzkevin.airrelay

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Debug build only: plays an Annex B H.264 file through exactly the path a sender uses
 * (service callbacks -> state -> activity -> SurfaceView -> VideoRenderer -> decoder), so
 * decoding, first-frame time and picture fitting can be measured on the stick without an
 * iPhone. The file must contain access unit delimiters (x264 aud=1).
 *
 *   adb push test.h264 /sdcard/Android/data/<appId>/files/
 *   adb shell am start -n <appId>/de.merzkevin.airrelay.MainActivity \
 *       --es debug_feed test.h264 --ei debug_fps 60
 *
 * Error screens without breaking the network (RUNNING goes back):
 *   adb shell am start -n <appId>/de.merzkevin.airrelay.MainActivity --es debug_status NO_NETWORK
 */
object DebugHooks {
    private const val TAG = "AirRelayFeed"
    @Volatile private var running = false

    fun onIntent(context: Context, intent: Intent?, service: ReceiverService) {
        intent?.getStringExtra("debug_status")?.let {
            intent.removeExtra("debug_status")
            service.debugShowStatus(de.merzkevin.airrelay.core.ServerStatus.valueOf(it))
        }
        val name = intent?.getStringExtra("debug_feed") ?: return
        intent.removeExtra("debug_feed")
        val fps = intent.getIntExtra("debug_fps", 60).coerceIn(1, 120)
        if (running) return
        val file = File(context.getExternalFilesDir(null), name)
        running = true
        Thread({
            try { feed(file, fps, service) } catch (e: Exception) { Log.e(TAG, "feed failed", e) }
            running = false
        }, "debug-feed").start()
    }

    private fun feed(file: File, fps: Int, service: ReceiverService) {
        val bytes = file.readBytes()
        val starts = audOffsets(bytes)
        val count = starts.size - 1
        Log.i(TAG, "feeding $count access units from ${file.name} at $fps fps")
        service.onConnectionInit()
        service.onMirrorRunning(true)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val frameNs = 1_000_000_000L / fps
        var late = 0
        for (i in 0 until count) {
            val au = bytes.copyOfRange(starts[i], starts[i + 1])
            val due = t0 + i * frameNs
            val wait = due - SystemClock.elapsedRealtimeNanos()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else if (wait < -frameNs) late++
            service.onVideoData(au, i * frameNs)
        }
        Thread.sleep(500)
        val secs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        val r = service.renderer
        Log.i(TAG, "feed done: fed=$count decoderIn=${r.framesIn} rendered=${r.framesRendered} " +
            "restarts=${r.restarts} lateFeeds=$late seconds=${"%.1f".format(secs)}")
        service.onMirrorRunning(false)
        service.onConnectionDestroy()
    }

    /** Offsets of access unit delimiters (NAL type 9) plus the end of the data. */
    private fun audOffsets(b: ByteArray): List<Int> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 4 < b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 0.toByte() &&
                b[i + 3] == 1.toByte() && (b[i + 4].toInt() and 0x1F) == 9) starts.add(i)
            i++
        }
        starts.add(b.size)
        return starts
    }
}
