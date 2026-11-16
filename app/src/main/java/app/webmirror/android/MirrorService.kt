package app.webmirror.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import app.webmirror.android.net.Addresses
import app.webmirror.android.server.WebServer
import app.webmirror.android.stream.StreamHub
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
    val urls: List<String> = emptyList(),
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
    private var sweeper: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
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
        }
        val urls = Addresses.localIpv4().map { Addresses.url(it.ip, PORT) }
        _state.update { it.copy(running = true, urls = urls, viewerCount = 0) }
        postNotification()
        Log.i(TAG, "server started on port $PORT: $urls")
    }

    private fun stopServer() {
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
        _state.update { it.copy(running = false, urls = emptyList(), viewerCount = 0) }
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
        private const val IDLE_TIMEOUT_MS = 10_000L
        private const val ACTION_STOP = "app.webmirror.android.STOP"
        private const val CHANNEL_ID = "webmirror"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(ServiceState())
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        fun start(context: Context) {
            context.startService(Intent(context, MirrorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
