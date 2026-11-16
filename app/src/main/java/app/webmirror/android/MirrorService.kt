package app.webmirror.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.util.Log
import app.webmirror.android.net.Addresses
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.VirtualIpService
import app.webmirror.android.server.WebServer
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
    val lastStats: String? = null,
    val lastProbe: String? = null,
)

/**
 * M0: a plain started service that only starts and stops [WebServer].
 * It deliberately does NOT call startForeground yet: the mediaProjection service type may only be used
 * after screen-capture consent (M2.1). Until then keep the app open on screen.
 * The notification is built and posted already, so M2 only has to switch to startForeground(...).
 */
class MirrorService : Service() {

    private val hub = StreamHub()
    private var server: WebServer? = null
    private var tlsServer: WebServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var sweeper: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            if (server != null) refreshUrls()
            return START_NOT_STICKY
        }
        if (server == null) startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    private fun startServer() {
        hub.events = object : StreamHub.Events {
            override fun onViewerCountChanged(count: Int) {
                _state.update { it.copy(viewerCount = count) }
            }

            override fun onStats(json: String) {
                _state.update { it.copy(lastStats = json) }
            }

            override fun onProbe(json: String) {
                _state.update { it.copy(lastProbe = json) }
            }
        }
        val s = WebServer(PORT, applicationContext.assets, hub)
        try {
            s.start(0, true) // timeout 0: idle WebSockets must not be killed by the default 5 s read timeout
        } catch (e: IOException) {
            Log.e(TAG, "cannot start server on port $PORT", e)
            _state.update { it.copy(running = false, serverError = "Cannot start the server on port $PORT: ${e.message}") }
            stopSelf()
            return
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
        postNotification()
        registerNetworkCallback()
        TlsStore.refresh(this)
        scope.launch { TlsStore.state.collect { applyTls(it.info) } }
        scope.launch { VirtualIpService.state.collect { refreshUrls() } }
        refreshUrls()
        Log.i(TAG, "server started on port $PORT: ${_state.value.urls}")
    }

    /** (Re)starts or stops the HTTPS server to match the stored certificate. HTTP is never affected. */
    private fun applyTls(info: app.webmirror.android.net.TlsInfo?) {
        stopTls()
        if (info != null) {
            try {
                val ctx = TlsStore.sslContext(this)
                val t = WebServer(TLS_PORT, applicationContext.assets, hub)
                t.makeSecure(ctx.serverSocketFactory, null)
                t.start(0, true)
                tlsServer = t
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
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        _state.update { it.copy(running = false, urls = Addresses.UrlList.EMPTY, viewerCount = 0, httpsRunning = false, tlsError = null) }
    }

    private fun postNotification() {
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
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
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
        // No-op if POST_NOTIFICATIONS was not granted. M2 replaces this with startForeground(...).
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "WebMirror"
        const val PORT = 8080
        const val TLS_PORT = 8443
        private const val URL_POLL_SECONDS = 5L
        private const val ACTION_REFRESH = "app.webmirror.android.REFRESH"
        private const val IDLE_TIMEOUT_MS = 10_000L
        private const val ACTION_STOP = "app.webmirror.android.STOP"
        private const val CHANNEL_ID = "webmirror"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(ServiceState())
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        fun start(context: Context) {
            context.startService(Intent(context, MirrorService::class.java))
        }

        /** Asks a running service to recompute its URL list (e.g. when the app comes to the foreground). */
        fun refresh(context: Context) {
            if (_state.value.running) {
                context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_REFRESH))
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
