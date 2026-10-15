/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import de.merzkevin.airrelay.billing.Subscriptions
import de.merzkevin.airrelay.core.OfferMessage
import de.merzkevin.airrelay.core.Skus
import de.merzkevin.airrelay.core.SubscriptionStatus
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
 *
 * Subscription states without Amazon (pinned until the app process ends; the prices are
 * made up and exist only here):
 *   --es debug_subscription ACTIVE | EXPIRED | EXPIRED_NO_TRIAL | EXPIRED_NO_PRICES |
 *                           CHECKING | UNREACHABLE | OVERDUE | OVERDUE_IN_GRACE
 *   --es debug_message PURCHASE_CANCELLED | PRODUCT_NOT_FOUND | ... (OfferMessage)
 * A feed (debug_feed) asks the subscription gate first, exactly like a sender's SETUP.
 *
 * App language without touching the system setting (Fire TV guards it with the parental
 * PIN): --es debug_locale en   (or de; "system" goes back). Lives until the process ends.
 */
object DebugHooks {
    private const val TAG = "AirRelayFeed"
    @Volatile private var running = false
    @Volatile private var locale: java.util.Locale? = null

    /** Wraps the activity's base context with the debug language, if one is set. */
    fun wrapLocale(base: Context): Context {
        val l = locale ?: return base
        val c = android.content.res.Configuration(base.resources.configuration)
        c.setLocale(l)
        return base.createConfigurationContext(c)
    }

    /** Activity only: switches the app language and recreates the activity. */
    fun onActivityIntent(activity: android.app.Activity, intent: Intent?) {
        val v = intent?.getStringExtra("debug_locale") ?: return
        intent.removeExtra("debug_locale")
        locale = if (v == "system") null else java.util.Locale.forLanguageTag(v)
        Log.i(TAG, "debug locale $locale")
        activity.recreate()
    }

    fun onIntent(context: Context, intent: Intent?, service: ReceiverService) {
        intent?.getStringExtra("debug_subscription")?.let {
            intent.removeExtra("debug_subscription")
            debugSubscription(context, it)
        }
        intent?.getStringExtra("debug_message")?.let {
            intent.removeExtra("debug_message")
            Subscriptions.debugMessage(OfferMessage.valueOf(it))
        }
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
        if (!service.onClientRequest()) {
            // what the iPhone sees: 403 at SETUP, then it closes the connection
            Log.i(TAG, "feed refused by the subscription gate")
            Thread.sleep(200)
            service.onConnectionDestroy()
            return
        }
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

    private fun debugSubscription(context: Context, what: String) {
        val de = context.resources.configuration.locales[0].language == "de"
        val prices = if (de) mapOf(Skus.YEARLY to "14,99 €", Skus.MONTHLY to "2,49 €")
        else mapOf(Skus.YEARLY to "€14.99", Skus.MONTHLY to "€2.49")
        val trials = mapOf(Skus.YEARLY to "Weekly", Skus.MONTHLY to "Weekly")
        val day = 24L * 60 * 60 * 1000
        when (what) {
            "ACTIVE" -> Subscriptions.debugSet(SubscriptionStatus.ACTIVE, null, false, null, null)
            "EXPIRED" -> Subscriptions.debugSet(SubscriptionStatus.EXPIRED, null, false, prices, trials)
            "EXPIRED_NO_TRIAL" -> Subscriptions.debugSet(SubscriptionStatus.EXPIRED, null, false, prices, emptyMap())
            "EXPIRED_NO_PRICES" -> Subscriptions.debugSet(SubscriptionStatus.EXPIRED, null, true, null, null)
            "CHECKING" -> Subscriptions.debugSet(SubscriptionStatus.UNKNOWN, 0L, false, null, null)
            "UNREACHABLE" -> Subscriptions.debugSet(SubscriptionStatus.UNKNOWN, 0L, true, null, null)
            "OVERDUE" -> Subscriptions.debugSet(SubscriptionStatus.ACTIVE, 4 * day, true, null, null)
            "OVERDUE_IN_GRACE" -> Subscriptions.debugSet(SubscriptionStatus.ACTIVE, 2 * day, true, null, null)
            else -> Log.w(TAG, "unknown debug_subscription $what")
        }
        Log.i(TAG, "debug subscription $what -> ${Subscriptions.access}")
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
