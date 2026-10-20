/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.merzkevin.airrelay.billing.Subscriptions
import de.merzkevin.airrelay.core.Access
import de.merzkevin.airrelay.core.BackAction
import de.merzkevin.airrelay.core.FontScale
import de.merzkevin.airrelay.core.HintPlace
import de.merzkevin.airrelay.core.LicenseGate
import de.merzkevin.airrelay.core.OfferMessage
import de.merzkevin.airrelay.core.ReceiverConfig
import de.merzkevin.airrelay.core.ReceiverState
import de.merzkevin.airrelay.core.Rect
import de.merzkevin.airrelay.core.Screen
import de.merzkevin.airrelay.core.Size
import de.merzkevin.airrelay.core.Skus
import de.merzkevin.airrelay.core.TermLabel
import de.merzkevin.airrelay.core.WifiAdvice
import de.merzkevin.airrelay.core.WifiThresholds
import de.merzkevin.airrelay.core.afterAttempt
import de.merzkevin.airrelay.core.backActionFor
import de.merzkevin.airrelay.core.fitCentered
import de.merzkevin.airrelay.core.hintSlot
import de.merzkevin.airrelay.core.isError
import de.merzkevin.airrelay.core.monthlyEquivalent
import de.merzkevin.airrelay.core.offerHeadlineTrialDays
import de.merzkevin.airrelay.core.showsOffer
import de.merzkevin.airrelay.core.showsVideo
import de.merzkevin.airrelay.core.termText
import de.merzkevin.airrelay.core.wifiHintPlace
import de.merzkevin.airrelay.core.yearlySavingPercent
import de.merzkevin.airrelay.video.VideoRenderer

/**
 * The only screen. Waiting layout (name, how-to, IP, licences) or the full-screen picture,
 * decided by [de.merzkevin.airrelay.core.screenFor]. Operated with the D-pad only.
 */
class MainActivity : Activity(), ReceiverService.Listener, SurfaceHolder.Callback, Subscriptions.Listener {

    private val main = Handler(Looper.getMainLooper())
    private var service: ReceiverService? = null
    /** Kept past unbinding: surfaceDestroyed can arrive after onStop, and the decoder
     *  must let go of the surface then. The service outlives the activity. */
    private var renderer: VideoRenderer? = null
    private var screen: Screen? = null
    private var lastState = ReceiverState()
    private var stopHintVisible = false
    private var surfaceReady = false

    private lateinit var videoFrame: FrameLayout
    private lateinit var videoSurface: SurfaceView
    private lateinit var connectingText: TextView
    private lateinit var stopHint: TextView
    private lateinit var wifiHint: TextView
    private lateinit var wifiHintVideo: TextView
    /** The Wi-Fi hint is on screen (until a key, the timeout or another screen). */
    private var wifiHintActive = false
    /** Key code of the OK press that only took the Wi-Fi hint away; its repeats and its
     *  release are swallowed too. KEYCODE_UNKNOWN when none. */
    private var okTookHintAway = KeyEvent.KEYCODE_UNKNOWN
    private lateinit var waitingPanel: View
    private lateinit var statusLabel: TextView
    private lateinit var titleText: TextView
    private lateinit var stepsBlock: View
    private lateinit var step3: TextView
    private lateinit var bodyText: TextView
    private lateinit var errorActions: View
    private lateinit var retryButton: TextView
    private lateinit var networkButton: TextView
    private lateinit var ipText: TextView
    private lateinit var licensesButton: TextView
    private lateinit var licensesPanel: ScrollView
    private lateinit var licensesText: TextView
    private lateinit var heroImage: View
    private lateinit var stepRows: List<View>
    private lateinit var offerBox: View
    private lateinit var attemptText: TextView
    private lateinit var offerHeadline: TextView
    private lateinit var offerBody: TextView
    private lateinit var offerActions: View
    private lateinit var optionYearly: View
    private lateinit var optionYearlyPrice: TextView
    private lateinit var optionYearlyCompare: TextView
    private lateinit var optionMonthly: View
    private lateinit var optionMonthlyPrice: TextView
    private lateinit var restoreButton: TextView
    private lateinit var offerRetry: TextView
    private lateinit var offerMessage: TextView
    /** Access shown last; the yearly term gets the focus once each time the offer appears. */
    private var shownAccess: Access? = null
    private var resumed = false
    /** onResume skipped the Amazon calls because a session was running. */
    private var amazonPending = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as ReceiverService.LocalBinder).service
            service = s
            renderer = s.renderer
            if (surfaceReady) s.renderer.setSurface(videoSurface.holder.surface)
            s.listener = this@MainActivity
            DebugHooks.onIntent(this@MainActivity, intent, s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val hideStopHint = Runnable {
        stopHintVisible = false
        stopHint.visibility = View.GONE
    }

    private val hideWifiHint = Runnable {
        wifiHintActive = false
        wifiHint.visibility = View.GONE
        wifiHintVideo.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoFrame = findViewById(R.id.video_frame)
        videoSurface = findViewById(R.id.video_surface)
        connectingText = findViewById(R.id.connecting_text)
        stopHint = findViewById(R.id.stop_hint)
        wifiHint = findViewById(R.id.wifi_hint)
        wifiHintVideo = findViewById(R.id.wifi_hint_video)
        waitingPanel = findViewById(R.id.waiting_panel)
        statusLabel = findViewById(R.id.status_label)
        titleText = findViewById(R.id.title_text)
        stepsBlock = findViewById(R.id.steps_block)
        step3 = findViewById(R.id.step3)
        bodyText = findViewById(R.id.body_text)
        errorActions = findViewById(R.id.error_actions)
        retryButton = findViewById(R.id.retry_button)
        networkButton = findViewById(R.id.network_button)
        ipText = findViewById(R.id.ip_text)
        licensesButton = findViewById(R.id.licenses_button)
        licensesPanel = findViewById(R.id.licenses_panel)
        licensesText = findViewById(R.id.licenses_text)
        heroImage = findViewById(R.id.hero_image)
        stepRows = listOf(findViewById(R.id.step_row2), findViewById(R.id.step_row3))
        offerBox = findViewById(R.id.offer_box)
        attemptText = findViewById(R.id.attempt_text)
        offerHeadline = findViewById(R.id.offer_headline)
        offerBody = findViewById(R.id.offer_body)
        offerActions = findViewById(R.id.offer_actions)
        optionYearly = findViewById(R.id.option_yearly)
        optionYearlyPrice = findViewById(R.id.option_yearly_price)
        optionYearlyCompare = findViewById(R.id.option_yearly_compare)
        optionMonthly = findViewById(R.id.option_monthly)
        optionMonthlyPrice = findViewById(R.id.option_monthly_price)
        restoreButton = findViewById(R.id.restore_button)
        offerRetry = findViewById(R.id.offer_retry)
        offerMessage = findViewById(R.id.offer_message)

        videoSurface.holder.addCallback(this)
        licensesButton.setOnClickListener { openLicenses() }
        retryButton.setOnClickListener { service?.retry() }
        networkButton.setOnClickListener { openNetworkSettings() }
        optionYearly.setOnClickListener { Subscriptions.purchase(Skus.YEARLY) }
        optionMonthly.setOnClickListener { Subscriptions.purchase(Skus.MONTHLY) }
        restoreButton.setOnClickListener { Subscriptions.restore() }
        offerRetry.setOnClickListener { Subscriptions.retry() }
        // the yearly term is the preselected one: outlined even without focus
        optionYearly.isActivated = true
        Subscriptions.init(this)
        render(lastState)
        DebugHooks.onActivityIntent(this, intent)
    }

    override fun attachBaseContext(newBase: Context) {
        // debug builds: app language from adb (DebugHooks); release: unchanged
        val wrapped = DebugHooks.wrapLocale(newBase)
        // the system font scale counts only up to FontScale.MAX: above it the screens do not fit
        val config = wrapped.resources.configuration
        val scale = FontScale.capped(config.fontScale)
        super.attachBaseContext(
            if (scale == config.fontScale) wrapped
            else wrapped.createConfigurationContext(Configuration(config).apply { fontScale = scale }),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugHooks.onActivityIntent(this, intent)
        service?.let { DebugHooks.onIntent(this, intent, it) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        // Fire OS 8: the "open the app" alert has done its job once the app is on screen
        getSystemService(NotificationManager::class.java)?.cancel(ReceiverService.ALERT_NOTIFICATION_ID)
        ReceiverService.start(this)
        bindService(Intent(this, ReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
        Subscriptions.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // As Amazon asks: user data, prices and purchases on every resume. Not while a
        // session runs: the app was just brought up for the picture, the calls cost the
        // main thread up to ~110 ms on the Stick Lite (measured), and an admitted session
        // does not depend on the answer. It is made up when the session ends.
        if (ReceiverService.sessionRunning) amazonPending = true else Subscriptions.onResume()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        visible = false
        Subscriptions.removeListener(this)
        Subscriptions.onAppHidden()
        service?.listener = null
        service = null
        unbindService(connection)
        main.removeCallbacks(hideStopHint)
        main.removeCallbacks(hideWifiHint)
        super.onStop()
    }

    override fun onSubscriptionChanged() = render(lastState)

    override fun onSubscriptionConfirmed(restored: Boolean) {
        // Amazon shows its own purchase confirmation; only a restore gets ours
        if (restored) Toast.makeText(this, R.string.restored, Toast.LENGTH_LONG).show()
    }

    override fun onStateChanged(state: ReceiverState) = render(state)

    // ---- rendering ----

    private fun render(state: ReceiverState) {
        lastState = state
        val access = Subscriptions.access
        val s = state.screen(LicenseGate.current())
        val video = s.showsVideo()
        if (s != screen) {
            screen = s
            if (video && licensesPanel.visibility == View.VISIBLE) closeLicenses()
            if (!video) hideStopHint.run()
            if (!video && resumed && amazonPending) {
                amazonPending = false
                Subscriptions.onResume()
            }
        }

        videoFrame.visibility = if (video) View.VISIBLE else View.GONE
        waitingPanel.visibility = if (video) View.GONE else View.VISIBLE
        ipText.visibility = if (video) View.GONE else View.VISIBLE
        licensesButton.visibility = if (video) View.GONE else View.VISIBLE
        connectingText.visibility = if (s == Screen.CONNECTING) View.VISIBLE else View.GONE
        if (video) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderWifiHint(s, state, layoutVideo(state.videoSize))
            return
        }
        renderWifiHint(s, state, null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val locked = s == Screen.LOCKED
        titleText.text = state.name
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (locked) lockedTitleSizeSp(state.name) else titleSizeSp(state.name))
        step3.text = getString(R.string.step_choose, state.name)
        ipText.text = state.ip?.let { getString(R.string.ip_address, it) } ?: ""

        val error = s.isError()
        // after a refused attempt the how-to is known already; the offer needs the room
        val showSteps = s == Screen.WAITING || s == Screen.STARTING || (locked && !access.afterAttempt())
        stepsBlock.visibility = if (showSteps) View.VISIBLE else View.GONE
        bodyText.visibility = if (s == Screen.AUDIO || error) View.VISIBLE else View.GONE
        statusLabel.visibility = if (locked) View.GONE else View.VISIBLE
        heroImage.visibility = if (locked) View.GONE else View.VISIBLE
        compactWaitingLayout(locked)
        renderOffer(if (locked) access else null)
        errorActions.visibility = if (error) View.VISIBLE else View.GONE
        networkButton.visibility = if (s == Screen.NO_NETWORK) View.VISIBLE else View.GONE
        statusLabel.isActivated = error

        when (s) {
            Screen.STARTING -> statusLabel.setText(R.string.status_starting)
            Screen.WAITING -> statusLabel.setText(R.string.status_ready)
            Screen.AUDIO -> {
                statusLabel.setText(R.string.status_audio)
                bodyText.setText(R.string.audio_body)
            }
            Screen.NO_NETWORK -> {
                statusLabel.setText(R.string.status_problem)
                titleText.setText(R.string.no_network_title)
                bodyText.setText(R.string.no_network_body)
            }
            Screen.PORT_IN_USE -> {
                statusLabel.setText(R.string.status_problem)
                titleText.setText(R.string.start_failed_title)
                bodyText.setText(R.string.port_in_use_body)
            }
            Screen.START_FAILED -> {
                statusLabel.setText(R.string.status_problem)
                titleText.setText(R.string.start_failed_title)
                bodyText.setText(R.string.start_failed_body)
            }
            Screen.LOCKED -> statusLabel.setText(R.string.status_locked)
            Screen.CONNECTING, Screen.MIRRORING -> Unit
        }
        if (error) {
            titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            if (!retryButton.hasFocus() && !networkButton.hasFocus() && licensesPanel.visibility != View.VISIBLE) {
                retryButton.requestFocus()
            }
        } else if (locked) {
            if (licensesPanel.visibility != View.VISIBLE && offerBox.findFocus() == null && !licensesButton.hasFocus()) {
                (firstOfferFocus() ?: licensesButton).requestFocus()
            }
        } else if (licensesPanel.visibility != View.VISIBLE && !licensesButton.hasFocus()) {
            licensesButton.requestFocus()
        }
    }

    private fun firstOfferFocus(): View? = when {
        offerActions.visibility == View.VISIBLE -> optionYearly
        offerRetry.visibility == View.VISIBLE -> offerRetry
        else -> null
    }

    /** The subscription box; [access] null hides it (subscriber, or another screen). */
    private fun renderOffer(access: Access?) {
        if (access == null) {
            offerBox.visibility = View.GONE
            shownAccess = null
            licensesButton.nextFocusUpId = View.NO_ID
            return
        }
        val offer = Subscriptions.offer
        offerBox.visibility = View.VISIBLE
        attemptText.visibility = if (access.afterAttempt()) View.VISIBLE else View.GONE
        val showsOffer = access.showsOffer()
        offerActions.visibility = if (showsOffer) View.VISIBLE else View.GONE
        offerRetry.visibility = when (access) {
            Access.UNREACHABLE, Access.UNREACHABLE_AFTER_ATTEMPT,
            Access.CONFIRMATION_OVERDUE, Access.CONFIRMATION_OVERDUE_AFTER_ATTEMPT -> View.VISIBLE
            else -> View.GONE
        }
        when (access) {
            Access.OFFER, Access.OFFER_AFTER_ATTEMPT -> {
                val days = offerHeadlineTrialDays(offer.trials)
                offerHeadline.text = when {
                    days != null -> getString(R.string.offer_headline_trial_days, days)
                    offer.trials.isNotEmpty() -> getString(R.string.offer_headline_trial)
                    else -> getString(R.string.offer_headline)
                }
                offerBody.setText(R.string.offer_renewal)
                optionYearlyPrice.text = termLine(Skus.YEARLY, offer)
                optionMonthlyPrice.text = termLine(Skus.MONTHLY, offer)
                val yearly = offer.prices[Skus.YEARLY]
                val perMonth = monthlyEquivalent(yearly)
                val saving = yearlySavingPercent(yearly, offer.prices[Skus.MONTHLY])
                optionYearlyCompare.text = when {
                    perMonth != null && saving != null -> getString(R.string.yearly_compare_percent, perMonth, saving)
                    perMonth != null -> getString(R.string.yearly_compare, perMonth)
                    else -> ""
                }
                optionYearlyCompare.visibility = if (perMonth != null) View.VISIBLE else View.GONE
            }
            Access.CHECKING, Access.CHECKING_AFTER_ATTEMPT -> {
                offerHeadline.setText(R.string.checking_headline)
                offerBody.setText(R.string.checking_body)
            }
            Access.UNREACHABLE, Access.UNREACHABLE_AFTER_ATTEMPT -> {
                offerHeadline.setText(R.string.unreachable_headline)
                offerBody.setText(R.string.unreachable_body)
            }
            Access.CONFIRMATION_OVERDUE, Access.CONFIRMATION_OVERDUE_AFTER_ATTEMPT -> {
                offerHeadline.setText(R.string.overdue_headline)
                offerBody.setText(R.string.overdue_body)
            }
            Access.UNLOCKED -> Unit
        }
        // Up from "Licences" leads back into the box, not nowhere
        licensesButton.nextFocusUpId = when {
            showsOffer -> R.id.restore_button
            offerRetry.visibility == View.VISIBLE -> R.id.offer_retry
            else -> View.NO_ID
        }
        val message = if (showsOffer) offer.message else OfferMessage.NONE
        offerMessage.visibility = if (message == OfferMessage.NONE) View.GONE else View.VISIBLE
        offerMessage.isActivated = message != OfferMessage.WORKING
        messageText(message)?.let { offerMessage.setText(it) }
        if (access != shownAccess) {
            // a new state gets its first control focused (yearly term, or "Try again"),
            // but an offer that stays an offer keeps the focus where the user put it
            val keepFocus = showsOffer && shownAccess?.showsOffer() == true
            shownAccess = access
            if (!keepFocus && licensesPanel.visibility != View.VISIBLE) firstOfferFocus()?.requestFocus()
        }
    }

    private fun termLine(sku: String, offer: Subscriptions.Offer): String {
        val price = offer.prices[sku]
        val t = termText(price, offer.trials[sku])
        val yearly = sku == Skus.YEARLY
        return when (t.label) {
            TermLabel.TRIAL_DAYS_THEN_PRICE -> getString(
                if (yearly) R.string.price_trial_days_yearly else R.string.price_trial_days_monthly, t.trialDays, price)
            TermLabel.TRIAL_THEN_PRICE -> getString(if (yearly) R.string.price_trial_yearly else R.string.price_trial_monthly, price)
            TermLabel.PRICE_ONLY -> getString(if (yearly) R.string.price_yearly else R.string.price_monthly, price)
            TermLabel.TERM_ONLY -> getString(R.string.price_unknown)
        }
    }

    private fun messageText(m: OfferMessage): Int? = when (m) {
        OfferMessage.NONE -> null
        OfferMessage.WORKING -> R.string.msg_working
        OfferMessage.PURCHASE_CANCELLED -> R.string.msg_purchase_cancelled
        OfferMessage.PRODUCT_NOT_FOUND -> R.string.msg_product_not_found
        OfferMessage.ALREADY_SUBSCRIBED -> R.string.msg_already_subscribed
        OfferMessage.AMAZON_UNREACHABLE -> R.string.msg_amazon_unreachable
        OfferMessage.PURCHASE_PENDING -> R.string.msg_pending
        OfferMessage.NOTHING_TO_RESTORE -> R.string.msg_nothing_to_restore
    }

    /** With the offer box on top, name and how-to move closer together so all three
     *  steps stay on screen (540 dp high canvas). */
    private fun compactWaitingLayout(compact: Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        waitingPanel.setPadding(waitingPanel.paddingLeft, dp(if (compact) 22 else 32),
            waitingPanel.paddingRight, dp(if (compact) 70 else 96))
        (stepsBlock.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = dp(if (compact) 14 else 36)
        for (row in stepRows) (row.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = dp(if (compact) 8 else 18)
        stepsBlock.requestLayout()
    }

    /** Under the offer box the name must stay on one line (50 bytes at most). */
    private fun lockedTitleSizeSp(name: String): Float = if (name.length <= 28) 34f else 28f

    /** Long device names get a smaller title instead of being cut. */
    private fun titleSizeSp(name: String): Float = when {
        name.length <= 18 -> 60f
        name.length <= 28 -> 46f
        else -> 34f
    }

    /** Sizes the picture; returns where it lies in the frame, or null while unknown. */
    private fun layoutVideo(size: Size?): Rect? {
        val container = Size(videoFrame.width, videoFrame.height)
        val lp = videoSurface.layoutParams as FrameLayout.LayoutParams
        val placed = if (size != null && container.isValid) fitCentered(size, container) else null
        val wanted = if (placed == null) {
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        } else {
            FrameLayout.LayoutParams(placed.width, placed.height)
        }
        wanted.gravity = Gravity.CENTER
        if (lp.width != wanted.width || lp.height != wanted.height) {
            videoSurface.layoutParams = wanted
        }
        if (size != null && !container.isValid) {
            // first layout pass not done yet: try again once the frame has a size (the Wi-Fi
            // hint beside the picture depends on it too, so the whole picture screen again)
            videoFrame.post { if (lastState.videoSize == size) render(lastState) }
        }
        return placed
    }

    /**
     * The Wi-Fi hint (T-31b): one line on the waiting screen, or in the free strip beside a
     * portrait picture, never over the picture. Each raise of the service's counter is shown
     * once, when a place for it exists; a hint raised during a landscape picture therefore
     * appears on the waiting screen afterwards. Any key or the timeout takes it away.
     */
    private fun renderWifiHint(s: Screen, state: ReceiverState, video: Rect?) {
        val d = resources.displayMetrics.density
        val container = Size(videoFrame.width, videoFrame.height)
        val slot = if (video != null && container.isValid) hintSlot(video, container, (WifiThresholds.PILLAR_MIN_DP * d).toInt()) else null
        val place = wifiHintPlace(s, state.wifiAdvice, slot != null)
        if (place == HintPlace.NONE) {
            if (wifiHintActive) {
                main.removeCallbacks(hideWifiHint)
                hideWifiHint.run()
            }
            return
        }
        if (state.wifiHintSeq != wifiSeqSeen) {
            wifiSeqSeen = state.wifiHintSeq
            wifiHintActive = true
            main.removeCallbacks(hideWifiHint)
            main.postDelayed(hideWifiHint, WifiThresholds.HINT_SHOW_MS)
        }
        if (!wifiHintActive) return
        val text = when (state.wifiAdvice) {
            WifiAdvice.WEAK_SIGNAL -> R.string.wifi_weak
            WifiAdvice.BUSY_24GHZ -> R.string.wifi_busy_24ghz
            WifiAdvice.NONE -> return
        }
        val view = if (place == HintPlace.PILLAR) wifiHintVideo else wifiHint
        (if (place == HintPlace.PILLAR) wifiHint else wifiHintVideo).visibility = View.GONE
        view.setText(text)
        if (place == HintPlace.PILLAR && slot != null) {
            val margin = (PILLAR_MARGIN_DP * d).toInt()
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL)
            lp.leftMargin = slot.left + margin
            val old = view.layoutParams as FrameLayout.LayoutParams
            if (old.leftMargin != lp.leftMargin || old.gravity != lp.gravity) view.layoutParams = lp
            if (view.maxWidth != slot.width - 2 * margin) view.maxWidth = slot.width - 2 * margin
        }
        view.visibility = View.VISIBLE
    }

    // ---- licences ----

    private fun openLicenses() {
        licensesText.text = buildString {
            append(getString(R.string.subscription_info))
            append("\n\n")
            append(getString(R.string.licenses_intro, BuildConfig.SOURCE_URL))
            append("\n\n")
            resources.openRawResource(R.raw.gpl3).bufferedReader().use { append(it.readText()) }
        }
        licensesPanel.visibility = View.VISIBLE
        licensesPanel.scrollTo(0, 0)
        // only the text may take focus while it is open, D-pad up/down scrolls it
        licensesButton.isFocusable = false
        licensesPanel.requestFocus()
    }

    private fun closeLicenses() {
        licensesPanel.visibility = View.GONE
        // the full GPL text is ~35 KB of laid out text; do not keep it around
        licensesText.text = ""
        licensesButton.isFocusable = true
        licensesButton.requestFocus()
    }

    companion object {
        /** Whether the activity is on screen; the service skips the Fire OS 8 alert then. */
        @Volatile var visible = false
            private set

        /** Last Wi-Fi hint counter shown. Lives with the process, like the service's counter,
         *  so reopening the app does not repeat a hint that was already seen. Main thread. */
        private var wifiSeqSeen = 0

        private const val PILLAR_MARGIN_DP = 20

        /** Keys that press the focused button (View.onKeyUp's confirm keys, plus the A button
         *  of a game controller, which Fire OS maps to OK). */
        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A)
    }

    private fun openNetworkSettings() {
        for (action in listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Exception) {
            }
        }
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val swallowed = okTookHintAway
        if (swallowed != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == swallowed) {
            // the rest of the OK press that took the hint away: held (repeats) and released
            if (event.action == KeyEvent.ACTION_UP) {
                okTookHintAway = KeyEvent.KEYCODE_UNKNOWN
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
            // a new press although the release never came (focus moved away): it counts
            okTookHintAway = KeyEvent.KEYCODE_UNKNOWN
        }
        // any key of the remote takes the Wi-Fi hint away; OK does only that (T-31c, Prüfer
        // 0.1.3 Befund 3: it also pressed the focused button). Every other key still does what
        // it does, Back included.
        if (event.action == KeyEvent.ACTION_DOWN && wifiHintActive) {
            main.removeCallbacks(hideWifiHint)
            hideWifiHint.run()
            if (event.keyCode in OK_KEYS) {
                okTookHintAway = event.keyCode
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val s = screen ?: return super.onKeyDown(keyCode, event)
        when (backActionFor(s, licensesPanel.visibility == View.VISIBLE, stopHintVisible)) {
            BackAction.CLOSE_LICENSES -> closeLicenses()
            BackAction.LEAVE_APP -> finish()
            BackAction.SHOW_STOP_HINT -> {
                stopHintVisible = true
                stopHint.visibility = View.VISIBLE
                main.removeCallbacks(hideStopHint)
                main.postDelayed(hideStopHint, ReceiverConfig.STOP_HINT_MS)
            }
            BackAction.STOP_MIRRORING -> {
                hideStopHint.run()
                main.removeCallbacks(hideStopHint)
                service?.stopMirroring()
            }
        }
        return true
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        renderer?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        renderer?.clearSurface(holder.surface)
    }
}
