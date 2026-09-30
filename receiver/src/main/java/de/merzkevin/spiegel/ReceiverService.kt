package de.merzkevin.spiegel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import de.merzkevin.spiegel.bridge.NativeBridge
import de.merzkevin.spiegel.bridge.NativeCallbacks
import de.merzkevin.spiegel.core.Entitlement
import de.merzkevin.spiegel.core.LicenseGate
import de.merzkevin.spiegel.core.ReceiverConfig
import de.merzkevin.spiegel.core.ReceiverState
import de.merzkevin.spiegel.core.ServerStatus
import de.merzkevin.spiegel.core.Size
import de.merzkevin.spiegel.core.advertisedSize
import de.merzkevin.spiegel.core.airplayVolumeToGainQ15
import de.merzkevin.spiegel.core.deviceIdFor
import de.merzkevin.spiegel.core.pickIpv4
import de.merzkevin.spiegel.core.receiverName
import de.merzkevin.spiegel.core.startOutcome
import de.merzkevin.spiegel.video.VideoRenderer
import java.net.NetworkInterface

/**
 * Foreground service that owns the receiver: native server, dns-sd announcement, video
 * decoder and the state the activity renders. Runs without the activity, starts at boot
 * and brings the activity to the front when a sender starts mirroring.
 *
 * Idle cost: no timers, no polling. Work happens only on events (sender connects, network
 * or device name changes). The "server" thread only runs start/stop/restart.
 */
class ReceiverService : Service(), NativeCallbacks, VideoRenderer.Listener {

    interface Listener {
        fun onStateChanged(state: ReceiverState)
    }

    inner class LocalBinder : Binder() {
        val service: ReceiverService get() = this@ReceiverService
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var serverThread: HandlerThread
    private lateinit var server: Handler
    private val binder = LocalBinder()

    private val stateLock = Any()
    @Volatile var state = ReceiverState()
        private set
    private var notifyPending = false
    var listener: Listener? = null
        set(value) {
            field = value
            value?.onStateChanged(state)
        }

    lateinit var renderer: VideoRenderer
        private set

    // server thread only
    private var handle = 0L
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdRegistrations = ArrayList<NsdManager.RegistrationListener>()
    private var startedName: String? = null
    private var startedIp: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var senderDb = 0f

    private val nsd by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        serverThread = HandlerThread("server").apply { start() }
        server = Handler(serverThread.looper)
        renderer = VideoRenderer(this, codecSize())
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spiegel:session")
            .apply { setReferenceCounted(false) }
        server.post { startServer() }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(DEVICE_NAME_SETTING), false, nameObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // debug builds: a test stream can be fed while the app is in the background
        DebugHooks.onIntent(this, intent, this)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(nameObserver)
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        server.post { stopServer() }
        serverThread.quitSafely()
        try { serverThread.join(SERVER_JOIN_MS) } catch (_: InterruptedException) {}
        renderer.stopSession()
        wakeLock?.release()
        super.onDestroy()
    }

    // ---- actions from the activity ----

    /** Retry after an error screen. */
    fun retry() {
        server.post { restartServer("retry") }
    }

    /** Back pressed twice during mirroring. */
    fun stopMirroring() {
        Log.i(TAG, "stop requested on the TV")
        server.post { if (handle != 0L) NativeBridge.nativeDisconnect(handle) }
        renderer.stopSession()
        update { it.copy(mirroring = false, firstFrameShown = false, videoSize = null) }
    }

    /** Debug builds only (DebugHooks): show a server state without breaking the network.
     *  Unused in release, R8 removes it. */
    fun debugShowStatus(status: ServerStatus) = update { it.copy(server = status) }

    // ---- server lifecycle (server thread) ----

    private fun startServer() {
        if (handle != 0L) return
        val name = receiverName(Settings.Global.getString(contentResolver, DEVICE_NAME_SETTING))
        val ip = currentIpv4()
        update { ReceiverState(server = ServerStatus.STARTING, name = name, ip = ip) }
        if (ip == null) {
            finishStart(startOutcome(null, false, null), name, null, 0)
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        NativeBridge.nativeSetDefaultStreamValues(
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
        )
        val size = advertisedSize(displaySize(), null)
        val deviceId = deviceIdFor(Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
        handle = NativeBridge.nativeInit(this, deviceId, name, size.width, size.height,
            ReceiverConfig.MAX_FPS, ReceiverConfig.ADVERTISE_ALAC)
        if (handle == 0L) {
            finishStart(startOutcome(ip, false, null), name, ip, 0)
            return
        }
        var port: Int? = null
        for (p in ReceiverConfig.PORTS_TO_TRY) {
            val bound = NativeBridge.nativeStart(handle, p)
            if (bound > 0) { port = bound; break }
        }
        if (port == null) {
            NativeBridge.nativeDestroy(handle)
            handle = 0L
            finishStart(startOutcome(ip, true, null), name, ip, 0)
            return
        }
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("spiegel:mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        register("_airplay._tcp", name, port, NativeBridge.nativeTxtRecords(handle, false))
        register("_raop._tcp", NativeBridge.nativeRaopServiceName(handle) ?: name, port,
            NativeBridge.nativeTxtRecords(handle, true))
        startedName = name
        startedIp = ip
        Log.i(TAG, "receiver '$name' running on $ip:$port, ${size.width}x${size.height}@${ReceiverConfig.MAX_FPS}")
        finishStart(startOutcome(ip, true, port), name, ip, port)
    }

    private fun finishStart(status: ServerStatus, name: String, ip: String?, port: Int) {
        if (status != ServerStatus.RUNNING) Log.w(TAG, "receiver not started: $status")
        update { it.copy(server = status, name = name, ip = ip, port = port) }
    }

    private fun stopServer() {
        nsdRegistrations.forEach { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        nsdRegistrations.clear()
        multicastLock?.release()
        multicastLock = null
        if (handle != 0L) {
            NativeBridge.nativeStop(handle)
            NativeBridge.nativeDestroy(handle)
            handle = 0L
        }
        startedName = null
        startedIp = null
        renderer.stopSession()
        update { it.withoutSession() }
    }

    private fun restartServer(reason: String) {
        Log.i(TAG, "restarting receiver: $reason")
        stopServer()
        startServer()
    }

    private fun register(type: String, serviceName: String, port: Int, txt: Array<String>?) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = type
            this.port = port
            txt?.let { for (i in 0 until it.size / 2) setAttribute(it[2 * i], it[2 * i + 1]) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "$type registered as '${info.serviceName}'")
                // dns-sd renames on a clash ("Name (2)"); show what the iPhone will list
                if (type == "_airplay._tcp") update { it.copy(name = info.serviceName) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "$type registration failed: $code")
                // without the announcement no iPhone can find the receiver: say so
                if (type == "_airplay._tcp") update { it.copy(server = ServerStatus.START_FAILED) }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsdRegistrations.add(l)
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    // delivered on the system's connectivity thread; all work moves to the server thread
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { server.post { onNetworkEvent() } }
        override fun onLost(network: Network) { server.post { onNetworkEvent() } }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { server.post { onNetworkEvent() } }
    }

    // server thread
    private fun onNetworkEvent() {
        val ip = currentIpv4()
        val s = state.server
        when {
            ip == startedIp && s == ServerStatus.RUNNING -> Unit
            ip == null && handle == 0L -> update { it.copy(server = ServerStatus.NO_NETWORK, ip = null) }
            else -> restartServer("network $startedIp -> $ip")
        }
    }

    private val nameObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            val name = receiverName(Settings.Global.getString(contentResolver, DEVICE_NAME_SETTING))
            server.post { if (startedName != null && name != startedName) restartServer("device name changed") }
        }
    }

    private fun currentIpv4(): String? = try {
        pickIpv4(NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .map { nif -> nif.name to nif.inetAddresses.toList().mapNotNull { a -> a.hostAddress?.takeIf { '.' in it } } })
    } catch (e: Exception) {
        Log.w(TAG, "network interfaces not readable", e)
        null
    }

    private fun displaySize(): Size {
        val mode = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        return if (mode != null) Size(mode.physicalWidth, mode.physicalHeight)
        else resources.displayMetrics.let { Size(it.widthPixels, it.heightPixels) }
    }

    private fun codecSize(): Size = advertisedSize(displaySize(), null)

    // ---- state ----

    private fun update(change: (ReceiverState) -> ReceiverState) {
        val wakeChange: Boolean
        synchronized(stateLock) {
            val old = state
            val new = change(old)
            if (new == old) return
            state = new
            wakeChange = old.needsWakeLock != new.needsWakeLock
            if (notifyPending) return@synchronized
            notifyPending = true
            main.post {
                synchronized(stateLock) { notifyPending = false }
                listener?.onStateChanged(state)
            }
        }
        if (wakeChange) {
            if (state.needsWakeLock) wakeLock?.acquire(WAKE_LOCK_MAX_MS) else wakeLock?.release()
        }
    }

    // ---- NativeCallbacks (UxPlay threads) ----

    override fun onConnectionInit() {
        update { it.copy(connections = it.connections + 1) }
        // measure.sh reads this line
        Log.i(TAG, "Client connected (${state.connections}) at ${SystemClock.elapsedRealtime()} ms")
    }

    override fun onConnectionDestroy() {
        var last = false
        update {
            val n = (it.connections - 1).coerceAtLeast(0)
            last = n == 0
            if (last) it.withoutSession() else it.copy(connections = n)
        }
        Log.i(TAG, "Client disconnected (${state.connections})")
        if (last) {
            server.post { if (handle != 0L) NativeBridge.nativeAudioStop(handle) }
            renderer.stopSession()
        }
    }

    override fun onMirrorRunning(running: Boolean) {
        Log.i(TAG, "Mirroring ${if (running) "started" else "stopped"} at ${SystemClock.elapsedRealtime()} ms")
        if (running) {
            renderer.startSession()
            update { it.copy(mirroring = true, firstFrameShown = false, videoSize = null) }
            main.post { bringToFront() }
        } else {
            renderer.stopSession()
            update { it.copy(mirroring = false, firstFrameShown = false, videoSize = null) }
        }
    }

    override fun onVideoData(data: ByteArray, ptsNs: Long) = renderer.onFrame(data, ptsNs)

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        val h = handle
        if (h != 0L) {
            NativeBridge.nativeAudioStart(h)
            NativeBridge.nativeAudioFormat(h, ct, spf)
            NativeBridge.nativeSetGain(h, airplayVolumeToGainQ15(senderDb))
        }
        Log.i(TAG, "audio ct=$ct spf=$spf screen=$usingScreen")
        if (!usingScreen) update { it.copy(audioSession = true) }
    }

    override fun onAudioTeardown() {
        val h = handle
        if (h != 0L) NativeBridge.nativeAudioStop(h)
        update { it.copy(audioSession = false) }
    }

    override fun onVolume(db: Float) {
        senderDb = db
        val h = handle
        if (h != 0L) NativeBridge.nativeSetGain(h, airplayVolumeToGainQ15(db))
    }

    override fun onClientVolume(): Float = senderDb

    // ---- VideoRenderer.Listener (video thread) ----

    override fun onVideoSize(size: Size) = update { it.copy(videoSize = size) }

    override fun onFirstFrameRendered() = update { it.copy(firstFrameShown = true) }

    // ---- helpers ----

    private fun bringToFront() {
        if (LicenseGate.current() != Entitlement.UNLOCKED) return
        if (!state.mirroring) return
        // Allowed from a foreground service up to Android 9 (Fire OS 7). Android 10+
        // blocks background activity starts, see README "Fire OS 8".
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            Log.w(TAG, "could not bring the app to the front", e)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SpiegelService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val SERVER_JOIN_MS = 2000L
        /** Safety net only; released as soon as the session ends. */
        private const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L
        /** Settings.Global.DEVICE_NAME (constant is API 25, the key exists on Fire OS 6 too). */
        const val DEVICE_NAME_SETTING = "device_name"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
