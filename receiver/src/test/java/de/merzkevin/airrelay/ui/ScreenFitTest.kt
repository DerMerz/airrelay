/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.ui

import android.content.ComponentName
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import de.merzkevin.airrelay.MainActivity
import de.merzkevin.airrelay.R
import de.merzkevin.airrelay.ReceiverService
import de.merzkevin.airrelay.billing.StoreClient
import de.merzkevin.airrelay.billing.Subscriptions
import de.merzkevin.airrelay.core.Access
import de.merzkevin.airrelay.core.OfferMessage
import de.merzkevin.airrelay.core.ReceiverState
import de.merzkevin.airrelay.core.ServerStatus
import de.merzkevin.airrelay.core.Size
import de.merzkevin.airrelay.core.Skus
import de.merzkevin.airrelay.core.SubscriptionStatus
import de.merzkevin.airrelay.core.WifiAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Nothing on any screen may be cut off, on no Fire TV and in no language (hard rule, Kevin
 * 05.10.2026; T-31 after the subscription card was reported cut off on a stick).
 *
 * The real activity with the real layout and the real strings is laid out by the real text
 * engine (Robolectric, native graphics) at the two Fire TV outputs (1920x1080 = 320 dpi and
 * 1280x720 = 213 dpi, both 960x540 dp), in German and English, at the font scales 1.0, 1.15, 1.3 and 2.0, in
 * EVERY subscription state, with every offer message, after a refused iPhone, on every
 * status screen, with short and very long device names, with the licences open.
 *
 * What counts as cut off (each one a failure, with view, state and numbers):
 *  - a text that is ellipsized, has more lines than maxLines, is wider or taller than its
 *    own box (fixed-height buttons),
 *  - a view that reaches outside the visible part of a parent (parents clip children to
 *    their padding) or outside the window,
 *  - two texts, or a text and the picture, that lie on top of each other (steps running into
 *    the IP line).
 * The licence text scrolls by design, so only its width counts.
 *
 * The Wi-Fi hint (T-31b) runs on the waiting screen with every name, beside portrait
 * pictures down to the narrowest strip that carries it, and over a landscape picture
 * (where it must wait). Additionally it must be there where it was raised, and it must never
 * lie on the picture.
 *
 * The expected number of checks is pinned (CASES), so a shrinking matrix cannot go
 * unnoticed. GuardsExistTest pins this file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenFitTest {

    private class Output(val name: String, val qualifier: String, val widthPx: Int, val heightPx: Int)

    private val outputs = listOf(
        Output("1080p", "w960dp-h540dp-xhdpi", 1920, 1080),
        Output("720p", "w960dp-h540dp-tvdpi", 1280, 720),
    )
    private val languages = listOf("de", "en")
    // 1.0 is the highest scale the app follows (FontScale.MAX); 1.15, 1.3 and 2.0 prove the cap holds
    private val fontScales = listOf(0.85f, 1.0f, 1.15f, 1.3f, 2.0f)

    private val names = listOf(
        "Kevins 2. FireTVStick",
        "Fire TV",
        "Wohnzimmer Fire TV Stick 4K",
        // 50 bytes at most are announced; this is the longest kind
        "Gaestezimmer Fernseher im Obergeschoss links",
    )

    private val fakeStore = object : StoreClient {
        override fun start(context: android.content.Context, listener: StoreClient.Listener) = Unit
        override fun requestUserData() = Unit
        override fun requestProducts(skus: Set<String>) = Unit
        override fun requestReceipts(reset: Boolean) = Unit
        override fun purchase(sku: String) = Unit
        override fun notifyFulfillment(receiptId: String) = Unit
    }

    private val day = 24L * 60 * 60 * 1000

    /** The subscription states, as DebugHooks pins them; [expected] is written by hand. */
    private class Sub(val label: String, val expected: Access, val set: (Boolean) -> Unit)

    private fun prices(de: Boolean) =
        if (de) mapOf(Skus.YEARLY to "14,99 €", Skus.MONTHLY to "2,49 €")
        else mapOf(Skus.YEARLY to "€14.99", Skus.MONTHLY to "€2.49")

    private fun subs(): List<Sub> = listOf(
        Sub("ACTIVE", Access.UNLOCKED) { Subscriptions.debugSet(SubscriptionStatus.ACTIVE, null, false, null, null) },
        Sub("EXPIRED", Access.OFFER) { de ->
            Subscriptions.debugSet(SubscriptionStatus.EXPIRED, null, false, prices(de), mapOf(Skus.YEARLY to "Weekly", Skus.MONTHLY to "Weekly"))
        },
        Sub("EXPIRED_NO_TRIAL", Access.OFFER) { de ->
            Subscriptions.debugSet(SubscriptionStatus.EXPIRED, null, false, prices(de), emptyMap())
        },
        Sub("EXPIRED_NO_PRICES", Access.OFFER) { Subscriptions.debugSet(SubscriptionStatus.EXPIRED, null, true, null, null) },
        Sub("CHECKING", Access.CHECKING) { Subscriptions.debugSet(SubscriptionStatus.UNKNOWN, 0L, false, null, null) },
        Sub("UNREACHABLE", Access.UNREACHABLE) { Subscriptions.debugSet(SubscriptionStatus.UNKNOWN, 0L, true, null, null) },
        Sub("OVERDUE", Access.CONFIRMATION_OVERDUE) { Subscriptions.debugSet(SubscriptionStatus.ACTIVE, 4 * day, true, null, null) },
        Sub("OVERDUE_IN_GRACE", Access.UNLOCKED) { Subscriptions.debugSet(SubscriptionStatus.ACTIVE, 2 * day, true, null, null) },
    )

    private var checks = 0
    private val problems = ArrayList<String>()

    private fun activityAt(out: Output, lang: String, fontScale: Float): org.robolectric.android.controller.ActivityController<MainActivity> {
        RuntimeEnvironment.setQualifiers("+$lang-${out.qualifier}")
        RuntimeEnvironment.setFontScale(fontScale)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        return c
    }

    private fun settle(a: MainActivity, out: Output) {
        ShadowLooper.idleMainLooper()
        val decor = a.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(out.widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(out.heightPx, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, out.widthPx, out.heightPx)
    }

    // ---- the measurement ----

    private fun windowRect(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun name(v: View): String {
        val id = if (v.id != View.NO_ID) try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "#${v.id}" } else v.javaClass.simpleName
        val t = (v as? TextView)?.text?.toString()?.take(28)?.let { " \"$it\"" } ?: ""
        return id + t
    }

    private fun visibleViews(root: View): List<View> {
        val all = ArrayList<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            all += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return all
    }

    private fun insideScroll(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p is ScrollView) return true
            p = p.parent
        }
        return false
    }

    /** Everything that is cut off or collides on the screen as it stands, with [where] named. */
    private fun measure(a: MainActivity, out: Output, where: String): List<String> {
        checks++
        val found = ArrayList<String>()
        val decor = a.window.decorView
        val window = Rect(0, 0, out.widthPx, out.heightPx)
        val views = visibleViews(decor).filter { it.width > 0 && it.height > 0 }
        for (v in views) {
            val r = windowRect(v)
            // inside the window
            // (what scrolls may be taller than the window, never wider)
            val inside = if (insideScroll(v) || v is ScrollView) r.left >= window.left && r.right <= window.right else window.contains(r)
            if (!inside) found += "$where: ${name(v)} $r reaches outside the window $window"
            // inside what every parent shows of it (parents clip to their padding)
            if (!insideScroll(v) || v is ScrollView) {
                var p = v.parent
                while (p is ViewGroup) {
                    val pr = windowRect(p)
                    val clip = if (p.clipToPadding) Rect(pr.left + p.paddingLeft, pr.top + p.paddingTop, pr.right - p.paddingRight, pr.bottom - p.paddingBottom) else pr
                    if (p !== decor && !clip.contains(r)) found += "$where: ${name(v)} $r is cut off by ${name(p)} (shows $clip)"
                    p = p.parent
                }
            }
            if (v is TextView) {
                val l = v.layout ?: continue
                for (i in 0 until l.lineCount) if (l.getEllipsisCount(i) > 0) found += "$where: ${name(v)} is ellipsized in line ${i + 1}"
                val maxLines = v.maxLines
                if (maxLines in 1 until Int.MAX_VALUE && l.lineCount > maxLines) found += "$where: ${name(v)} has ${l.lineCount} lines, maxLines $maxLines"
                val boxW = v.width - v.totalPaddingLeft - v.totalPaddingRight
                val boxH = v.height - v.totalPaddingTop - v.totalPaddingBottom
                var widest = 0f
                var widestLine = ""
                // getLineMax, not getLineWidth: the space at a wrap point is not drawn, and
                // getLineWidth counts it (T-31b: "Das " 288 px in a 286 px box, glyphs inside)
                for (i in 0 until l.lineCount) if (l.getLineMax(i) > widest) {
                    widest = l.getLineMax(i)
                    widestLine = l.text.subSequence(l.getLineStart(i), l.getLineEnd(i)).toString()
                }
                if (v.id == R.id.offer_body) {
                    // the subscription text wraps well before the edge of its box, so a font or
                    // a TV that measures a little wider cannot cut a word (the T-31 report)
                    val d = v.resources.displayMetrics.density
                    if (widest / d > 640f) found += "$where: offer_body is ${widest / d} dp wide, it must wrap at 620 dp"
                }
                if (widest > boxW + 0.5f) found += "$where: ${name(v)} text is ${widest.toInt()} px wide in a box of $boxW px (line \"$widestLine\", ${l.lineCount} lines, layout ${l.width} px)"
                if (!insideScroll(v) && l.height > boxH + 0.5f) found += "$where: ${name(v)} text is ${l.height} px high in a box of $boxH px"
            }
        }
        // the Wi-Fi hint never lies on the mirrored picture, and the waiting-screen line is
        // never shown behind the picture screen
        val surface = a.findViewById<View>(R.id.video_surface)
        val frame = a.findViewById<View>(R.id.video_frame)
        if (frame.visibility == View.VISIBLE) {
            for (id in listOf(R.id.wifi_hint, R.id.wifi_hint_video)) {
                val h = a.findViewById<View>(id)
                if (h.visibility != View.VISIBLE) continue
                if (id == R.id.wifi_hint) found += "$where: ${name(h)} is up behind the picture screen"
                val o = windowRect(h)
                if (o.intersect(windowRect(surface)) && o.width() > 0 && o.height() > 0) found += "$where: ${name(h)} ${windowRect(h)} lies on the picture ${windowRect(surface)}"
            }
        }
        // collisions: texts and the picture on top of each other
        val parts = views.filter { (it is TextView || it.id == R.id.hero_image) && !insideScroll(it) }
        for (i in parts.indices) for (j in i + 1 until parts.size) {
            val x = windowRect(parts[i]); val y = windowRect(parts[j])
            val o = Rect(x)
            if (o.intersect(y) && o.width() > 1 && o.height() > 1) {
                found += "$where: ${name(parts[i])} $x lies on ${name(parts[j])} $y"
            }
        }
        return found
    }

    private fun check(a: MainActivity, out: Output, where: String) {
        settle(a, out)
        problems += measure(a, out, where)
    }

    private fun state(server: ServerStatus = ServerStatus.RUNNING, name: String = names[0], mirroring: Boolean = false,
                      firstFrame: Boolean = false, audio: Boolean = false, video: Size = Size(1920, 1080),
                      wifi: WifiAdvice = WifiAdvice.NONE, wifiSeq: Int = 0) =
        ReceiverState(server = server, name = name, ip = "192.168.178.255", port = 7000,
            connections = if (mirroring || audio) 1 else 0, mirroring = mirroring, firstFrameShown = firstFrame,
            audioSession = audio, videoSize = if (mirroring && firstFrame) video else null,
            wifiAdvice = wifi, wifiHintSeq = wifiSeq)

    /** Hint counter for the Wi-Fi cases; the activity shows each value once per process. */
    private var wifiSeq = 1000

    private fun expectShown(a: MainActivity, id: Int, where: String) {
        if (a.findViewById<View>(id).visibility != View.VISIBLE) problems += "$where: ${a.resources.getResourceEntryName(id)} is not shown"
    }

    private fun expectNoHint(a: MainActivity, where: String) {
        for (id in listOf(R.id.wifi_hint, R.id.wifi_hint_video)) {
            if (a.findViewById<View>(id).visibility == View.VISIBLE) problems += "$where: ${a.resources.getResourceEntryName(id)} is shown"
        }
    }

    /** A picture screen: the frame gets its size first, as on the stick, then the state again. */
    private fun showPicture(a: MainActivity, out: Output, st: ReceiverState) {
        a.onStateChanged(st)
        settle(a, out)
        a.onStateChanged(st)
    }

    @Test
    fun noTextOnAnyScreenIsCutOff() {
        Subscriptions.init(RuntimeEnvironment.getApplication(), fakeStore)
        // Robolectric would call onServiceConnected without a service; the screen is driven
        // directly through onStateChanged, so the service is declared not bindable
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
            ComponentName(RuntimeEnvironment.getApplication(), ReceiverService::class.java))
        // the stick is online unless a state says otherwise; Robolectric needs the network told
        val cm = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        for (out in outputs) for (lang in languages) for (scale in fontScales) {
            val tag = "${out.name} $lang x$scale"
            val c = activityAt(out, lang, scale)
            val a = c.get()
            val de = lang == "de"
            check(a, out, "$tag start")

            // every subscription state, with every device name
            for (s in subs()) for (n in names) {
                s.set(de)
                assertEquals("state ${s.label} did not produce the access it stands for", s.expected, Subscriptions.access)
                a.onStateChanged(state(name = n))
                check(a, out, "$tag ${s.label} [$n]")
            }
            // the same offer with every message, and after a refused iPhone
            subs()[1].set(de)
            for (m in OfferMessage.values().filter { it != OfferMessage.NONE }) {
                Subscriptions.debugMessage(m)
                a.onStateChanged(state())
                check(a, out, "$tag EXPIRED message $m")
            }
            Subscriptions.debugMessage(OfferMessage.NONE)
            for (s in subs().filter { it.expected != Access.UNLOCKED }) {
                s.set(de)
                Subscriptions.onSenderRefused()
                ShadowLooper.idleMainLooper() // onSenderRefused posts to the main thread
                a.onStateChanged(state())
                assertTrue("after a refused iPhone ${s.label} still shows no attempt line", Subscriptions.access.name.endsWith("_AFTER_ATTEMPT"))
                check(a, out, "$tag ${s.label} after a refused iPhone")
            }
            // the status screens, subscription active
            subs()[0].set(de)
            for (n in names) {
                for (st in ServerStatus.values()) {
                    a.onStateChanged(state(server = st, name = n))
                    check(a, out, "$tag status $st [$n]")
                }
                a.onStateChanged(state(audio = true, name = n))
                check(a, out, "$tag audio [$n]")
            }
            // the picture screens
            a.onStateChanged(state(mirroring = true, firstFrame = false))
            check(a, out, "$tag connecting")
            a.onStateChanged(state(mirroring = true, firstFrame = true))
            a.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            check(a, out, "$tag mirroring with stop hint")
            a.onStateChanged(state())

            // the Wi-Fi hint on the waiting screen, both texts, every name
            for (w in listOf(WifiAdvice.WEAK_SIGNAL, WifiAdvice.BUSY_24GHZ)) for (n in names) {
                a.onStateChanged(state(name = n, wifi = w, wifiSeq = ++wifiSeq))
                check(a, out, "$tag wifi $w [$n]")
                expectShown(a, R.id.wifi_hint, "$tag wifi $w [$n]")
            }
            // beside portrait pictures: phone, iPad, and a strip just above the minimum
            for (v in listOf(Size(886, 1920), Size(1536, 2048), Size(1590, 1960))) for (w in listOf(WifiAdvice.WEAK_SIGNAL, WifiAdvice.BUSY_24GHZ)) {
                showPicture(a, out, state(mirroring = true, firstFrame = true, video = v, wifi = w, wifiSeq = ++wifiSeq))
                check(a, out, "$tag wifi $w beside $v")
                expectShown(a, R.id.wifi_hint_video, "$tag wifi $w beside $v")
            }
            // a landscape picture has no free place: the hint waits for the waiting screen
            showPicture(a, out, state(mirroring = true, firstFrame = true, wifi = WifiAdvice.WEAK_SIGNAL, wifiSeq = ++wifiSeq))
            check(a, out, "$tag wifi over landscape")
            expectNoHint(a, "$tag wifi over landscape")
            a.onStateChanged(state(wifi = WifiAdvice.WEAK_SIGNAL, wifiSeq = wifiSeq))
            check(a, out, "$tag wifi after landscape")
            expectShown(a, R.id.wifi_hint, "$tag wifi after landscape")
            a.onStateChanged(state())
            // licences open
            a.findViewById<View>(R.id.licenses_button).performClick()
            check(a, out, "$tag licences")

            c.pause().stop().destroy()
        }
        problems.distinct().forEach { println("PROBLEM $it") }
        assertTrue("$checks screens measured, ${problems.size} problems:\n" + problems.distinct().take(60).joinToString("\n"), problems.isEmpty())
        assertEquals("the matrix has shrunk or grown: update CASES on purpose", CASES, checks)
    }

    companion object {
        /** 2 outputs x 2 languages x 5 font scales x (1 start + 8 states x 4 names + 7 messages
         *  + 6 refused + 5 statuses x 4 names + 4 audio + 1 connecting + 1 mirroring + 1 licences
         *  + Wi-Fi: 2 texts x 4 names waiting + 3 portrait pictures x 2 texts + 1 landscape + 1 after). */
        const val CASES = 2 * 2 * 5 * (1 + 8 * 4 + 7 + 6 + 5 * 4 + 4 + 1 + 1 + 1 + 2 * 4 + 3 * 2 + 1 + 1)
    }
}
