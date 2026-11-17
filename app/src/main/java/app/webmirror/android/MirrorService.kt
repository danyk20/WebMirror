package app.webmirror.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.webmirror.android.capture.ScreenCapture
import app.webmirror.android.net.Addresses
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.VirtualIpService
import app.webmirror.android.server.WebServer
import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.stream.StreamHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class ServiceState(
    val running: Boolean = false,
    val urls: Addresses.UrlList = Addresses.UrlList.EMPTY,
    /** True while the HTTPS server on [MirrorService.TLS_PORT] is up. */
    val httpsRunning: Boolean = false,
    /** Why HTTPS could not start (HTTP is unaffected), or null. */
    val tlsError: String? = null,
    /** Why the HTTP server could not start, or null. */
    val serverError: String? = null,
    val viewerCount: Int = 0,
    /** True while the server runs but screen capture has ended (screen lock, status-bar chip, ...). */
    val paused: Boolean = false,
    val lastStats: String? = null,
    val lastProbe: String? = null,
    /** Size of the picture being sent, e.g. "576x1280 @30 fps", or null when not mirroring. */
    val stream: String? = null,
    /** The latest stream description (mode, codec string, encoder, hardware, why), or null when not mirroring. */
    val video: StreamHub.HelloInfo? = null,
    /** What the encoder actually accepted (profile, low latency, ...), multi-line, or null when not mirroring. */
    val encoder: String? = null,
)

/**
 * Foreground service that owns everything while mirroring: the screen-capture projection, the capture and
 * encoder, the web server, and the wake/Wi-Fi locks. The server outlives the capture: when the projection
 * ends (screen lock, status-bar chip) the service goes to a "paused" state in which the server, the virtual
 * address and the viewers stay up (hello `mode:"paused"`) until the user taps Start again (new consent) or
 * Stop. Start order is fixed (plan M2.1): startForeground with the mediaProjection type first, then
 * getMediaProjection, then registerCallback, then the VirtualDisplay.
 *
 * Foreground type: mediaProjection while capturing; specialUse while paused (a mediaProjection service
 * without a projection is not a documented state, so the type is dropped when the projection ends).
 */
class MirrorService : Service() {

    private val hub = StreamHub()
    private var server: WebServer? = null
    private var tlsServer: WebServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var sweeper: ScheduledExecutorService? = null

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenLock: PowerManager.WakeLock? = null

    /** The server is up (capturing or paused). */
    private var active = false

    /** The configuration currently applied (what [applyConfig] diffs against). */
    private var appliedConfig = StreamConfig()

    private val projectionCallback = object : MediaProjection.Callback() {
        /** Capture ended (screen lock, status-bar chip, system): keep the server, show "paused". */
        override fun onStop() {
            Log.i(TAG, "projection stopped by the system or the user")
            pauseCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        hubRef = hub
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMirroring(intent)
            ACTION_STOP -> stopAll(null)
            ACTION_REFRESH -> if (active) refreshUrls() else stopSelf()
            ACTION_APPLY_SETTINGS -> if (active) applySettings() else stopSelf()
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (active) stopAll(null)
        hubRef = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startMirroring(intent: Intent) {
        // startForegroundService() promises startForeground() within seconds, so do it before anything
        // else, and with the mediaProjection type (required on Android 14+ before getMediaProjection).
        val resuming = active
        if (resuming && capture != null) return // duplicate start while mirroring
        try {
            startForeground(
                NOTIFICATION_ID, buildNotification(false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            failStart("Cannot start the foreground service: ${e.message ?: e.javaClass.simpleName}", resuming)
            return
        }
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            failStart("Screen capture was not allowed.", resuming)
            return
        }
        val settings = Settings(this)
        try {
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                ?: throw IllegalStateException("the system returned no projection")
            projection = p
            // Mandatory on Android 14+ before the display is created.
            p.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            if (!resuming) {
                hub.reset()
                bindEvents()
            }
            hub.update { it.copy(audio = settings.audioMode.key) }
            hub.forceJpeg = settings.forceJpeg
            val cfg = settings.streamConfig
            appliedConfig = cfg
            hub.applyConfig(cfg)
            val cap = ScreenCapture(this, p, hub, settings.preset, cfg) { report ->
                _state.update { it.copy(encoder = report) }
            }
            capture = cap
            cap.start()
        } catch (e: Exception) {
            Log.e(TAG, "cannot start capture", e)
            val message = "Cannot start screen capture: ${e.message ?: e.javaClass.simpleName}"
            if (resuming) {
                endCapture()
                enterPausedForeground()
                _state.update { it.copy(serverError = message) }
            } else {
                active = true
                stopAll(message)
            }
            return
        }
        if (!resuming) {
            val error = startServer()
            if (error != null) {
                active = true
                stopAll(error)
                return
            }
            active = true
            acquireLocks()
        }
        acquireScreenLock()
        _state.update { it.copy(paused = false, serverError = null) }
        Log.i(TAG, if (resuming) "mirroring resumed" else "mirroring started")
    }

    /**
     * Could not even begin: show why. A first start leaves foreground state; a resume goes back to the
     * paused state so the server and viewers survive a refused consent.
     */
    private fun failStart(message: String, resuming: Boolean) {
        if (resuming) {
            enterPausedForeground()
            _state.update { it.copy(serverError = message) }
            return
        }
        _state.update { it.copy(running = false, serverError = message) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Tears down capture and projection only; the server, locks and viewers stay. */
    private fun endCapture() {
        capture?.stop()
        capture = null
        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "projection stop: $e")
            }
        }
        projection = null
        releaseScreenLock()
    }

    /** Capture ended but the server keeps running: tell the viewers and change the notification. */
    private fun pauseCapture() {
        if (!active || capture == null && projection == null) return
        endCapture()
        hub.update { it.copy(mode = StreamHub.MODE_PAUSED) }
        _state.update { it.copy(paused = true, stream = null, video = null, encoder = null) }
        enterPausedForeground()
        Log.i(TAG, "capture paused, server still running")
    }

    /** Foreground type specialUse (no projection any more) with the "Paused" notification text. */
    private fun enterPausedForeground() {
        val n = buildNotification(true)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot enter paused foreground state: $e")
        }
        _state.update { it.copy(paused = true, stream = null, video = null) }
    }

    /**
     * The one entry point for the stream tunables: applies only what changed. Encoder fields rebuild the
     * encoder (new encoder, `setSurface` on the same VirtualDisplay); flow-control and socket fields are read
     * live by sessions and the server (socket options only affect new connections); page fields go to every
     * viewer in a new `hello.cfg`.
     */
    private fun applyConfig(config: StreamConfig) {
        val diff = appliedConfig.diff(config)
        appliedConfig = config
        if (!diff.any) return
        Log.i(TAG, "config ${config.id} applied (changed: $diff): ${config.summary()}")
        hub.applyConfig(config)
        capture?.applyConfig(config) // always: a new config resets the adaptive target
    }

    /** Applies preset and audio mode from [Settings] to the running stream. */
    private fun applySettings() {
        val settings = Settings(this)
        hub.forceJpeg = settings.forceJpeg
        capture?.setPreset(settings.preset)
        hub.update { it.copy(audio = settings.audioMode.key) }
        applyConfig(settings.streamConfig)
        if (capture != null) {
            if (settings.keepScreenOn) acquireScreenLock() else releaseScreenLock()
        }
    }

    /** Full stop: capture, projection, server, locks, notification. [error] is shown in the UI. */
    private fun stopAll(error: String?) {
        if (!active) {
            stopSelf()
            return
        }
        active = false
        endCapture()
        releaseLocks()
        stopServer()
        hub.reset()
        _state.update { it.copy(running = false, paused = false, serverError = error, stream = null, video = null, encoder = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "mirroring stopped")
    }

    @Suppress("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebMirror:mirroring").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: $e")
        }
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WebMirror:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock failed: $e")
        }
    }

    /** Keeps the screen on while capturing (plan M3.5): Android stops the projection when the screen locks. */
    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun acquireScreenLock() {
        if (!Settings(this).keepScreenOn || screenLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "WebMirror:screen").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "screen wake lock failed: $e")
        }
    }

    private fun releaseScreenLock() {
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "screen wake lock release: $e")
        }
        screenLock = null
    }

    private fun releaseLocks() {
        releaseScreenLock()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release: $e")
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock release: $e")
        }
        wifiLock = null
    }

    private fun bindEvents() {
        hub.events = object : StreamHub.Events {
            override fun onViewerCountChanged(count: Int) {
                _state.update { it.copy(viewerCount = count) }
            }

            override fun onStats(json: String) {
                try {
                    statsListener?.invoke(json)
                } catch (e: Exception) {
                    Log.w(TAG, "stats listener failed: $e")
                }
                // The per-frame e2e list is for the automatic test only; keep the state and log readable.
                val shown = try {
                    JSONObject(json).apply { remove("e2eRaw") }.toString()
                } catch (e: Exception) {
                    json
                }
                _state.update { it.copy(lastStats = shown) }
            }

            override fun onProbe(json: String) {
                _state.update { it.copy(lastProbe = json) }
            }

            override fun onStreamChanged(info: StreamHub.HelloInfo) {
                _state.update { it.copy(stream = "${info.width}x${info.height} @${info.fps} fps (${info.mode})", video = info) }
            }
        }
    }

    /** Starts the web server and its helpers. Returns an error message, or null on success. */
    private fun startServer(): String? {
        val s = WebServer(PORT, applicationContext.assets, hub)
        try {
            s.start(0, true) // timeout 0: idle WebSockets must not be killed by the default 5 s read timeout
        } catch (e: IOException) {
            Log.e(TAG, "cannot start server on port $PORT", e)
            return "Cannot start the server on port $PORT: ${e.message}"
        }
        server = s
        sweeper = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "idle-sweeper").apply { isDaemon = true }
        }.also { ex ->
            ex.scheduleWithFixedDelay({
                try {
                    hub.sweepIdle(IDLE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "sweep failed: $e")
                }
            }, 2, 2, TimeUnit.SECONDS)
            ex.scheduleWithFixedDelay({
                try {
                    hub.pingAll()
                } catch (e: Exception) {
                    Log.w(TAG, "ping failed: $e")
                }
            }, 1, 1, TimeUnit.SECONDS)
            // Also catches changes that send no network callback, e.g. the hotspot being toggled.
            ex.scheduleWithFixedDelay({
                try {
                    refreshUrls()
                } catch (e: Exception) {
                    Log.w(TAG, "url refresh failed: $e")
                }
            }, URL_POLL_SECONDS, URL_POLL_SECONDS, TimeUnit.SECONDS)
        }
        _state.update { it.copy(running = true, urls = Addresses.UrlList.EMPTY, viewerCount = 0, serverError = null) }
        registerNetworkCallback()
        TlsStore.refresh(this)
        scope.launch { TlsStore.state.collect { applyTls(it.info) } }
        scope.launch { VirtualIpService.state.collect { refreshUrls() } }
        refreshUrls()
        Log.i(TAG, "server started on port $PORT: ${_state.value.urls}")
        return null
    }

    /** (Re)starts or stops the HTTPS server to match the stored certificate. HTTP is never affected. */
    private fun applyTls(info: app.webmirror.android.net.TlsInfo?) {
        stopTls()
        server?.httpsRedirectHost = null
        if (info != null) {
            try {
                val ctx = TlsStore.sslContext(this)
                val t = WebServer(TLS_PORT, applicationContext.assets, hub)
                t.enableTls(ctx)
                t.start(0, true)
                tlsServer = t
                server?.httpsRedirectHost = info.hostNames.firstOrNull()
                server?.httpsPort = TLS_PORT
                _state.update { it.copy(httpsRunning = true, tlsError = null) }
                Log.i(TAG, "https started on port $TLS_PORT")
            } catch (e: Exception) {
                Log.w(TAG, "cannot start https on port $TLS_PORT", e)
                _state.update { it.copy(httpsRunning = false, tlsError = "HTTPS not started: ${e.message ?: e.javaClass.simpleName}") }
            }
        } else {
            _state.update { it.copy(httpsRunning = false, tlsError = null) }
        }
        refreshUrls()
    }

    private fun stopTls() {
        val t = tlsServer
        tlsServer = null
        if (t != null) {
            try {
                t.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop https failed: $e")
            }
        }
    }

    /** Recomputes the URL list from the current addresses, virtual address and certificate. Thread-safe. */
    private fun refreshUrls() {
        val virtual = VirtualIpService.state.value.let { if (it.active) it.address else null }
        val httpsNames = if (_state.value.httpsRunning) TlsStore.state.value.info?.hostNames.orEmpty() else emptyList()
        val urls = Addresses.buildUrls(Addresses.localIpv4().map { it.ip }, virtual, PORT, httpsNames, TLS_PORT, Addresses.localIpv6().map { it.ip })
        _state.update { if (it.running) it.copy(urls = urls) else it }
    }

    private fun scheduleRefresh() {
        // Debounce: the interface address may lag behind the callback, and callbacks come in bursts.
        scope.launch {
            refreshJob?.cancel()
            refreshJob = launch {
                delay(500)
                refreshUrls()
            }
        }
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRefresh()
            override fun onLost(network: Network) = scheduleRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleRefresh()
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "cannot register network callback: $e")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "cannot unregister network callback: $e")
        }
    }

    private fun stopServer() {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        unregisterNetworkCallback()
        stopTls()
        sweeper?.shutdownNow()
        sweeper = null
        val s = server
        server = null
        if (s != null) {
            try {
                s.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: $e")
            }
        }
        hub.events = null
        _state.update { it.copy(running = false, paused = false, urls = Addresses.UrlList.EMPTY, viewerCount = 0, httpsRunning = false, tlsError = null) }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.notification_paused else R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.notification_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "WebMirror"
        const val PORT = 8080
        const val TLS_PORT = 8443
        private const val URL_POLL_SECONDS = 5L
        private const val ACTION_REFRESH = "app.webmirror.android.REFRESH"
        private const val IDLE_TIMEOUT_MS = 10_000L
        private const val ACTION_STOP = "app.webmirror.android.STOP"
        private const val ACTION_START = "app.webmirror.android.START"
        private const val ACTION_APPLY_SETTINGS = "app.webmirror.android.APPLY_SETTINGS"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "webmirror"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(ServiceState())
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        /** The running service's hub (null when no service instance exists); used by the automatic test. */
        @Volatile
        var hubRef: StreamHub? = null
            private set

        /** Receives every viewer `stats` message (JSON text, with `vid` and the raw `e2eRaw` list) on a session thread. */
        @Volatile
        var statsListener: ((String) -> Unit)? = null

        /** Shows [text] on every viewer's screen for [ttlS] seconds (plan 2.1 `notice`). */
        fun notifyViewers(text: String, ttlS: Int) {
            hubRef?.notice(text, ttlS)
        }

        /**
         * Starts mirroring with the result of the screen-capture consent dialog. The result can be used
         * once; never keep it for a later session.
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        /** Asks a running service to recompute its URL list (e.g. when the app comes to the foreground). */
        fun refresh(context: Context) {
            if (_state.value.running) {
                context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_REFRESH))
            }
        }

        /**
         * Stores [config] as the current one and applies it live (only what changed). Safe to call while
         * nothing is mirroring: it is then used at the next start.
         */
        fun applyConfig(context: Context, config: StreamConfig) {
            Settings(context).streamConfig = config
            applySettings(context)
        }

        /** Tells a running service that the preset or audio mode changed. */
        fun applySettings(context: Context) {
            if (_state.value.running) {
                context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_APPLY_SETTINGS))
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
