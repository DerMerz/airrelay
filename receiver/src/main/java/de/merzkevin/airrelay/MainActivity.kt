package de.merzkevin.airrelay

import android.app.Activity
import android.content.ComponentName
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
import de.merzkevin.airrelay.core.BackAction
import de.merzkevin.airrelay.core.LicenseGate
import de.merzkevin.airrelay.core.ReceiverConfig
import de.merzkevin.airrelay.core.ReceiverState
import de.merzkevin.airrelay.core.Screen
import de.merzkevin.airrelay.core.Size
import de.merzkevin.airrelay.core.backActionFor
import de.merzkevin.airrelay.core.fitCentered
import de.merzkevin.airrelay.core.isError
import de.merzkevin.airrelay.core.showsVideo
import de.merzkevin.airrelay.video.VideoRenderer

/**
 * The only screen. Waiting layout (name, how-to, IP, licences) or the full-screen picture,
 * decided by [de.merzkevin.airrelay.core.screenFor]. Operated with the D-pad only.
 */
class MainActivity : Activity(), ReceiverService.Listener, SurfaceHolder.Callback {

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as ReceiverService.LocalBinder).service
            service = s
            renderer = s.renderer
            if (surfaceReady) s.renderer.setSurface(videoSurface.holder.surface)
            s.listener = this@MainActivity
            DebugHooks.onIntent(this@MainActivity, intent, s)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val hideStopHint = Runnable {
        stopHintVisible = false
        stopHint.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoFrame = findViewById(R.id.video_frame)
        videoSurface = findViewById(R.id.video_surface)
        connectingText = findViewById(R.id.connecting_text)
        stopHint = findViewById(R.id.stop_hint)
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

        videoSurface.holder.addCallback(this)
        licensesButton.setOnClickListener { openLicenses() }
        retryButton.setOnClickListener { service?.retry() }
        networkButton.setOnClickListener { openNetworkSettings() }
        render(lastState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        service?.let { DebugHooks.onIntent(this, intent, it) }
    }

    override fun onStart() {
        super.onStart()
        ReceiverService.start(this)
        bindService(Intent(this, ReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.listener = null
        service = null
        unbindService(connection)
        main.removeCallbacks(hideStopHint)
        super.onStop()
    }

    override fun onStateChanged(state: ReceiverState) = render(state)

    // ---- rendering ----

    private fun render(state: ReceiverState) {
        lastState = state
        val s = state.screen(LicenseGate.current())
        val video = s.showsVideo()
        if (s != screen) {
            screen = s
            if (video && licensesPanel.visibility == View.VISIBLE) closeLicenses()
            if (!video) hideStopHint.run()
        }

        videoFrame.visibility = if (video) View.VISIBLE else View.GONE
        waitingPanel.visibility = if (video) View.GONE else View.VISIBLE
        ipText.visibility = if (video) View.GONE else View.VISIBLE
        licensesButton.visibility = if (video) View.GONE else View.VISIBLE
        connectingText.visibility = if (s == Screen.CONNECTING) View.VISIBLE else View.GONE
        if (video) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            layoutVideo(state.videoSize)
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        titleText.text = state.name
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSizeSp(state.name))
        step3.text = getString(R.string.step_choose, state.name)
        ipText.text = state.ip?.let { getString(R.string.ip_address, it) } ?: ""

        val error = s.isError()
        stepsBlock.visibility = if (s == Screen.WAITING || s == Screen.STARTING) View.VISIBLE else View.GONE
        bodyText.visibility = if (s == Screen.AUDIO || error || s == Screen.LOCKED) View.VISIBLE else View.GONE
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
            Screen.LOCKED -> {
                statusLabel.setText(R.string.status_locked)
                bodyText.setText(R.string.locked_body)
            }
            Screen.CONNECTING, Screen.MIRRORING -> Unit
        }
        if (error) {
            titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            if (!retryButton.hasFocus() && !networkButton.hasFocus() && licensesPanel.visibility != View.VISIBLE) {
                retryButton.requestFocus()
            }
        } else if (licensesPanel.visibility != View.VISIBLE && !licensesButton.hasFocus()) {
            licensesButton.requestFocus()
        }
    }

    /** Long device names get a smaller title instead of being cut. */
    private fun titleSizeSp(name: String): Float = when {
        name.length <= 18 -> 60f
        name.length <= 28 -> 46f
        else -> 34f
    }

    private fun layoutVideo(size: Size?) {
        val container = Size(videoFrame.width, videoFrame.height)
        val lp = videoSurface.layoutParams as FrameLayout.LayoutParams
        val wanted = if (size == null || !container.isValid) {
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        } else {
            val r = fitCentered(size, container)
            FrameLayout.LayoutParams(r.width, r.height)
        }
        wanted.gravity = Gravity.CENTER
        if (lp.width != wanted.width || lp.height != wanted.height) {
            videoSurface.layoutParams = wanted
        }
        if (size != null && !container.isValid) {
            // first layout pass not done yet: try again once the frame has a size
            videoFrame.post { if (lastState.videoSize == size) layoutVideo(size) }
        }
    }

    // ---- licences ----

    private fun openLicenses() {
        licensesText.text = buildString {
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
