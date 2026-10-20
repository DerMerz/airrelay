/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.ui

import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import de.merzkevin.airrelay.MainActivity
import de.merzkevin.airrelay.R
import de.merzkevin.airrelay.ReceiverService
import de.merzkevin.airrelay.billing.StoreClient
import de.merzkevin.airrelay.billing.Subscriptions
import de.merzkevin.airrelay.core.ReceiverState
import de.merzkevin.airrelay.core.ServerStatus
import de.merzkevin.airrelay.core.SubscriptionStatus
import de.merzkevin.airrelay.core.WifiAdvice
import de.merzkevin.airrelay.core.WifiThresholds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.TimeUnit

/**
 * What the viewer can do with the Wi-Fi hint (T-31b): it appears once per raise, the remote
 * takes it away, it goes by itself after [WifiThresholds.HINT_SHOW_MS], and it does not come
 * back on its own. Fit on every output and language is ScreenFitTest's job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-w960dp-h540dp-xhdpi")
class WifiHintTest {

    private val fakeStore = object : StoreClient {
        override fun start(context: android.content.Context, listener: StoreClient.Listener) = Unit
        override fun requestUserData() = Unit
        override fun requestProducts(skus: Set<String>) = Unit
        override fun requestReceipts(reset: Boolean) = Unit
        override fun purchase(sku: String) = Unit
        override fun notifyFulfillment(receiptId: String) = Unit
    }

    /** Each test uses its own counter range: the activity remembers the last one per process. */
    private var seq = (System.nanoTime() % 1_000_000).toInt() + 10_000

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Subscriptions.init(app, fakeStore)
        Subscriptions.debugSet(SubscriptionStatus.ACTIVE, null, false, null, null)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, ReceiverService::class.java))
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun waiting(advice: WifiAdvice, s: Int, server: ServerStatus = ServerStatus.RUNNING) =
        ReceiverState(server = server, name = "Fire TV", ip = "192.168.178.20", port = 7000,
            wifiAdvice = advice, wifiHintSeq = s)

    private fun open(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().visible()

    private fun hint(a: MainActivity) = a.findViewById<TextView>(R.id.wifi_hint)

    private fun shown(a: MainActivity) = hint(a).visibility == View.VISIBLE

    @Test fun weakSignalShowsTheGermanLineOnTheWaitingScreen() {
        val a = open().get()
        a.onStateChanged(waiting(WifiAdvice.WEAK_SIGNAL, ++seq))
        assertEquals(true, shown(a))
        assertEquals("Schwaches WLAN: Das Bild kann stocken. Ein WLAN-Verstärker hilft.", hint(a).text.toString())
        a.onStateChanged(waiting(WifiAdvice.BUSY_24GHZ, ++seq))
        assertEquals("Im 2,4-GHz-WLAN stockt das Bild. Mit 5 GHz läuft es flüssiger.", hint(a).text.toString())
    }

    @Test fun itGoesByItselfAfterTwelveSecondsAndStaysGone() {
        val a = open().get()
        val st = waiting(WifiAdvice.WEAK_SIGNAL, ++seq)
        a.onStateChanged(st)
        ShadowLooper.idleMainLooper(WifiThresholds.HINT_SHOW_MS - 100, TimeUnit.MILLISECONDS)
        assertEquals("gone too early", true, shown(a))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertEquals("still there after the timeout", false, shown(a))
        // the service keeps sending the same state: the hint stays gone
        a.onStateChanged(st)
        assertEquals(false, shown(a))
    }

    @Test fun anyKeyOfTheRemoteTakesItAway() {
        val a = open().get()
        a.onStateChanged(waiting(WifiAdvice.WEAK_SIGNAL, ++seq))
        assertEquals(true, shown(a))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(false, shown(a))
        // and the timer of the hint that was taken away does nothing later
        ShadowLooper.idleMainLooper(WifiThresholds.HINT_SHOW_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(false, shown(a))
    }

    @Test fun reopeningTheAppDoesNotRepeatAHintAlreadySeen() {
        val c = open()
        val st = waiting(WifiAdvice.WEAK_SIGNAL, ++seq)
        c.get().onStateChanged(st)
        assertEquals(true, shown(c.get()))
        c.pause().stop().destroy()
        val b = open().get()
        b.onStateChanged(st)
        assertEquals(false, shown(b))
        // a new raise (the service saw another stall) is shown again
        b.onStateChanged(waiting(WifiAdvice.WEAK_SIGNAL, ++seq))
        assertEquals(true, shown(b))
    }

    @Test fun goodWifiOrAnotherScreenTakesItAway() {
        val a = open().get()
        a.onStateChanged(waiting(WifiAdvice.WEAK_SIGNAL, ++seq))
        assertEquals(true, shown(a))
        a.onStateChanged(waiting(WifiAdvice.NONE, seq))
        assertEquals(false, shown(a))
        // error screens carry their own message; a raise there is kept for the waiting screen
        a.onStateChanged(waiting(WifiAdvice.WEAK_SIGNAL, ++seq, server = ServerStatus.NO_NETWORK))
        assertEquals(false, shown(a))
        a.onStateChanged(waiting(WifiAdvice.WEAK_SIGNAL, seq))
        assertEquals(true, shown(a))
    }
}
