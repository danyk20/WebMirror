package app.webmirror.android.stream

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Reads the phone's thermal state for [ThermalPolicy]: the status through a listener (API 29), the headroom forecast
 * (`getThermalHeadroom(10)`, API 30+, polled at most every [HEADROOM_POLL_MS]; it is rate limited by the system).
 */
class ThermalMonitor(context: Context) {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)

    @Volatile
    var status: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    @Volatile
    var headroom: Float? = null
        private set

    private var lastPollMs = Long.MIN_VALUE / 2
    private var registered = false
    private val listener = PowerManager.OnThermalStatusChangedListener { status = it }

    fun start() {
        try {
            status = power.currentThermalStatus
            power.addThermalStatusListener(listener)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "thermal: no status available: $e")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            power.removeThermalStatusListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "thermal: remove listener: $e")
        }
    }

    /** Refreshes [headroom] when due. */
    fun poll(nowMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || nowMs - lastPollMs < HEADROOM_POLL_MS) return
        lastPollMs = nowMs
        headroom = try {
            power.getThermalHeadroom(HEADROOM_FORECAST_S).takeIf { !it.isNaN() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "WebMirror"
        const val HEADROOM_FORECAST_S = 10
        const val HEADROOM_POLL_MS = 5_000L
    }
}
