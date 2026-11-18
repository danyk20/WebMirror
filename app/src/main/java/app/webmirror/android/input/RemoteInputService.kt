package app.webmirror.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's own switch "Allow control from viewers". In memory only: off after every app start and mirroring session. */
object RemoteControlState {
    val allowed = MutableStateFlow(false)
}

/** Connects the hub to the running accessibility service; a no-op while the service is not connected. */
object RemoteInput : InputSink {
    override fun submit(ev: InputEvent) {
        RemoteInputService.instance?.post { onInput(ev) }
    }

    override fun releaseAll() {
        RemoteInputService.instance?.post { onReleaseAll() }
    }
}

/**
 * Performs the touches sent from the viewer by `dispatchGesture` (plan "Remote control"). It listens to no
 * accessibility events and reads no screen content; it only injects the strokes of [GestureEngine] while the user's
 * "Allow control from viewers" switch is on (the hub enforces that, see `StreamHub.controlInput`).
 *
 * All work runs on the main thread: the engine is single-threaded and dispatchGesture callbacks arrive there.
 */
class RemoteInputService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var engine = GestureEngine()
    private var activeGeometry: DisplayGeometry? = null
    private var frameToken = 0
    private var lastInputAtMs = 0L

    private val idleCheck = object : Runnable {
        override fun run() {
            if (engine.hasActive) {
                if (now() - lastInputAtMs > IDLE_RELEASE_MS) {
                    Log.i(TAG, "remote control: touch held for ${IDLE_RELEASE_MS / 1000} s without input, lifting it")
                    onReleaseAll()
                }
                handler.postDelayed(this, IDLE_CHECK_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = GestureEngine(minOf(MAX_POINTERS, GestureDescription.getMaxStrokeCount()))
        instance = this
        _connected.value = true
        Log.i(TAG, "remote input service connected (max strokes ${GestureDescription.getMaxStrokeCount()})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        handler.post { resetEngine() }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        _connected.value = false
        handler.removeCallbacksAndMessages(null)
        frameToken++
        engine.cancelled()
    }

    fun post(block: RemoteInputService.() -> Unit) {
        handler.post { block() }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    @Suppress("DEPRECATION")
    private fun currentGeometry(): DisplayGeometry? {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val p = Point()
        d.getRealSize(p)
        if (p.x <= 0 || p.y <= 0) return null
        return DisplayGeometry(p.x, p.y, d.rotation)
    }

    internal fun onInput(ev: InputEvent) {
        val geo = currentGeometry() ?: return
        if (!geo.matchesStream(ev.streamW, ev.streamH)) return // picture of the old orientation
        val active = activeGeometry
        if (engine.hasActive && active != null && active != geo) {
            // Rotation changed mid-gesture: the touches cannot continue; lift them where they are.
            Log.i(TAG, "remote control: display rotated during a gesture, lifting the touches")
            engine.releaseAll()
            activeGeometry = null
            pump()
            return
        }
        lastInputAtMs = now()
        val x = if (ev.hasPosition) geo.mapX(ev.x) else null
        val y = if (ev.hasPosition) geo.mapY(ev.y) else null
        when (ev.action) {
            InputAction.DOWN -> {
                if (!engine.hasActive) activeGeometry = geo
                if (x != null && y != null) engine.down(ev.id, x, y, ev.tsMs)
            }
            InputAction.MOVE -> if (x != null && y != null) engine.move(ev.id, x, y, ev.tsMs)
            InputAction.UP, InputAction.CANCEL -> engine.up(ev.id, x, y, ev.tsMs)
        }
        if (engine.hasActive) {
            handler.removeCallbacks(idleCheck)
            handler.postDelayed(idleCheck, IDLE_CHECK_MS)
        }
        pump()
    }

    internal fun onReleaseAll() {
        engine.releaseAll()
        activeGeometry = null
        pump()
    }

    private fun resetEngine() {
        frameToken++
        engine.cancelled()
        activeGeometry = null
    }

    /** Dispatches the next gesture frame if none is in flight. */
    private fun pump() {
        val segments = engine.next() ?: return
        val token = ++frameToken
        try {
            val builder = GestureDescription.Builder()
            for (s in segments) {
                val path = Path().apply {
                    moveTo(s.fromX, s.fromY)
                    if (s.moves) lineTo(s.toX, s.toY)
                }
                val prev = s.previous as? GestureDescription.StrokeDescription
                val stroke = if (prev == null) {
                    GestureDescription.StrokeDescription(path, 0, s.durationMs, s.willContinue)
                } else {
                    prev.continueStroke(path, 0, s.durationMs, s.willContinue)
                }
                s.handle = stroke
                builder.addStroke(stroke)
            }
            val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (token != frameToken) return
                    engine.completed()
                    pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (token != frameToken) return
                    Log.i(TAG, "remote control: gesture cancelled by the system")
                    resetEngine()
                }
            }, handler)
            if (!accepted) {
                Log.w(TAG, "remote control: dispatchGesture refused the gesture")
                resetEngine()
            }
        } catch (e: Exception) {
            Log.w(TAG, "remote control: cannot build or dispatch the gesture: $e")
            resetEngine()
        }
    }

    companion object {
        private const val TAG = "WebMirror"
        private const val MAX_POINTERS = 10
        private const val IDLE_CHECK_MS = 5_000L
        private const val IDLE_RELEASE_MS = 15_000L

        /** True when the user enabled this service in Settings > Accessibility (it is listed in ENABLED_ACCESSIBILITY_SERVICES). */
        fun isEnabledInSettings(context: android.content.Context): Boolean {
            val list = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = android.content.ComponentName(context, RemoteInputService::class.java)
            return list.split(':').any { entry ->
                val c = android.content.ComponentName.unflattenFromString(entry)
                c != null && c == me
            }
        }

        @Volatile
        var instance: RemoteInputService? = null
            private set

        private val _connected = MutableStateFlow(false)

        /** True while the system has the service bound (the user switched it on in Accessibility settings). */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
