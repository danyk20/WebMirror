package app.webmirror.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.webmirror.android.Settings
import app.webmirror.android.TypingKeyboard
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's own switch "Allow control from viewers": a live copy of the persisted `Settings.remoteControlAllowed` (default on). */
object RemoteControlState {
    val allowed = MutableStateFlow(Settings.DEFAULT_REMOTE_CONTROL)
}

/** The phone's choice "Keyboard for typing from the car": a live copy of the persisted `Settings.typingKeyboard`. */
object TypingState {
    val keyboard = MutableStateFlow(TypingKeyboard.PHONE)
}

/** Connects the hub to the running accessibility service; a no-op while the service is not connected. */
object RemoteInput : InputSink {
    /** Where the service reports text focus (the hub); set by the mirroring service. */
    @Volatile
    var kbdListener: KbdListener? = null

    override fun setTypingActive(active: Boolean) {
        RemoteInputService.instance?.post { onTypingActive(active) }
    }

    override fun typeText(text: String) {
        RemoteInputService.instance?.post { onTypeText(text) }
    }

    override fun pressKey(key: TypingKey) {
        RemoteInputService.instance?.post { onPressKey(key) }
    }

    override fun submit(ev: InputEvent) {
        RemoteInputService.instance?.post { onInput(ev) }
    }

    override fun releaseAll() {
        RemoteInputService.instance?.post { onReleaseAll() }
    }
}

/**
 * Performs the touches sent from the viewer by `dispatchGesture` (plan "Remote control"). It injects the strokes of
 * [GestureEngine] while the user's "Allow control from viewers" switch is on (the hub enforces that, see
 * `StreamHub.controlInput`). It listens to no accessibility events and reads no screen content, with one exception:
 * on Android 13+ with "Tesla keyboard" chosen and a viewer controlling, it learns WHETHER a text field has input focus
 * and its kind (see [Typing]), never the text, hint or anything else of the screen (plan 2.1.5).
 *
 * All work runs on the main thread: the engine is single-threaded and dispatchGesture callbacks arrive there.
 */
class RemoteInputService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var engine = GestureEngine()
    private var activeGeometry: DisplayGeometry? = null
    private var frameToken = 0
    private var lastInputAtMs = 0L

    /** Short descriptions of the last frames, logged when the system cancels one (debugging remote gestures). */
    private val recentFrames = ArrayDeque<String>()

    /** End point of every held (willContinue) stroke, measured the way Android's injector measures it. */
    private val exactEnds = java.util.IdentityHashMap<GestureDescription.StrokeDescription, FloatArray>()

    /** Where Android considers [path] to end: PathMeasure at full length, or the start point for a tap. */
    private fun measuredEnd(path: Path, sx: Float, sy: Float, moves: Boolean): FloatArray {
        if (!moves) return floatArrayOf(sx, sy)
        val pm = android.graphics.PathMeasure(path, false)
        val pos = FloatArray(2)
        return if (pm.getPosTan(pm.length, pos, null)) pos else floatArrayOf(sx, sy)
    }

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
        setEventTypes(false) // no events until the Tesla keyboard needs them
        _connected.value = true
        Log.i(TAG, "remote input service connected (max strokes ${GestureDescription.getMaxStrokeCount()})")
    }

    // ---- Tesla keyboard (plan 2.1.5): focus kind only, never content; nothing here is ever logged ----

    private var typingActive = false
    private var lastReported: KbdState? = null
    private var phoneKeyboardHidden = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!typingActive) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> evaluateFocus()
        }
    }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) { handler.post { evaluateFocus() } }
        override fun onFinishInput() { handler.post { evaluateFocus() } }
    }

    internal fun onTypingActive(active: Boolean) {
        val on = active && Typing.supported(Build.VERSION.SDK_INT)
        if (on == typingActive) return
        typingActive = on
        lastReported = null
        setEventTypes(on)
        if (on) evaluateFocus() else showPhoneKeyboard()
    }

    /** Listens to focus and window events only while typing is active; otherwise to nothing. */
    private fun setEventTypes(on: Boolean) {
        if (Build.VERSION.SDK_INT < Typing.MIN_SDK) return
        try {
            val info = serviceInfo ?: return
            val want = if (on) AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
            if (info.eventTypes != want) {
                info.eventTypes = want
                serviceInfo = info
            }
        } catch (e: Exception) {
            Log.w(TAG, "typing: cannot change the event types: $e")
        }
    }

    /** Reads whether an editable field has input focus and its kind, reports a change, hides/restores the phone keyboard. */
    private fun evaluateFocus() {
        if (!typingActive) return
        val state = readFocusKind()
        setPhoneKeyboardHidden(state.show)
        if (state != lastReported) {
            lastReported = state
            RemoteInput.kbdListener?.onKbd(state)
        }
    }

    /** Only isEditable, the input type, multi-line and the IME action are read; getText and everything else never. */
    @Suppress("DEPRECATION")
    private fun readFocusKind(): KbdState {
        var inputType = 0
        var imeOptions = 0
        var editable = false
        var password = false
        var multiLine = false
        if (Build.VERSION.SDK_INT >= Typing.MIN_SDK) {
            val ime = inputMethod
            if (ime != null && ime.currentInputStarted) {
                val ei = ime.currentInputEditorInfo
                if (ei != null && ei.inputType != 0) {
                    editable = true
                    inputType = ei.inputType
                    imeOptions = ei.imeOptions
                }
            }
        }
        val node = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
        if (node != null) {
            try {
                if (node.isEditable) {
                    if (!editable) inputType = node.inputType
                    editable = true
                    password = node.isPassword
                    multiLine = node.isMultiLine
                }
            } finally {
                node.recycle()
            }
        }
        return if (editable) Typing.kbdState(inputType, imeOptions, password, multiLine) else KbdState.HIDDEN
    }

    private fun setPhoneKeyboardHidden(hide: Boolean) {
        if (hide == phoneKeyboardHidden) return
        try {
            softKeyboardController.setShowMode(
                if (hide) SHOW_MODE_HIDDEN else SHOW_MODE_AUTO,
            )
            phoneKeyboardHidden = hide
        } catch (e: Exception) {
            Log.w(TAG, "typing: cannot change the keyboard show mode: $e")
        }
    }

    private fun showPhoneKeyboard() = setPhoneKeyboardHidden(false)

    internal fun onTypeText(text: String) {
        if (!typingActive || Build.VERSION.SDK_INT < Typing.MIN_SDK) return
        inputMethod?.currentInputConnection?.commitText(text, 1, null)
    }

    internal fun onPressKey(key: TypingKey) {
        if (!typingActive || Build.VERSION.SDK_INT < Typing.MIN_SDK) return
        val ime = inputMethod ?: return
        val conn = ime.currentInputConnection ?: return
        when (key) {
            // A Delete key press, like a hardware keyboard: deletes a selection too, not only the character before the cursor.
            TypingKey.BACKSPACE -> sendKey(conn, KeyEvent.KEYCODE_DEL)
            TypingKey.ENTER -> {
                val ei = ime.currentInputEditorInfo
                val action = if (ei != null) Typing.enterEditorAction(ei.imeOptions, ei.inputType) else null
                if (action != null) conn.performEditorAction(action) else sendKey(conn, key.keyCode)
            }
            else -> sendKey(conn, key.keyCode)
        }
    }

    @RequiresApi(33)
    private fun sendKey(conn: InputMethod.AccessibilityInputConnection, keyCode: Int) {
        val t = SystemClock.uptimeMillis()
        conn.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        conn.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

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
        typingActive = false
        showPhoneKeyboard()
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
        // Android cancels a gesture when a new finger joins while another one is already held. The fingers of a
        // pinch reach us a few ms apart, so the first touch waits briefly for the others and they all start together.
        if (ev.action == InputAction.DOWN && !engine.isInFlight && engine.startedCount == 0) {
            if (!grouping) {
                grouping = true
                handler.postDelayed(groupDone, GROUP_MS)
            }
            return
        }
        if (grouping) return
        pump()
    }

    private var grouping = false
    private val groupDone = Runnable {
        grouping = false
        pump()
    }

    internal fun onReleaseAll() {
        engine.releaseAll()
        activeGeometry = null
        pump()
    }

    private fun resetEngine() {
        frameToken++
        exactEnds.clear()
        handler.removeCallbacks(groupDone)
        grouping = false
        engine.cancelled()
        activeGeometry = null
    }

    /** Dispatches the next gesture frame if none is in flight. */
    private fun pump() {
        val segments = engine.next() ?: return
        val token = ++frameToken
        try {
            val builder = GestureDescription.Builder()
            val desc = StringBuilder("#").append(token).append(':')
            for (s in segments) {
                val prev = s.previous as? GestureDescription.StrokeDescription
                // A continuation must start EXACTLY where Android's injector recorded the previous stroke ending
                // (it compares floats; a mismatch cancels the whole gesture: "points mismatch"). Android computes that
                // end with PathMeasure, which can differ from the coordinates we passed by a fraction of a pixel, so
                // reuse the end point measured the same way.
                val start = prev?.let { exactEnds.remove(it) }
                val sx = start?.get(0) ?: s.fromX
                val sy = start?.get(1) ?: s.fromY
                val moves = s.toX != sx || s.toY != sy
                val path = Path().apply {
                    moveTo(sx, sy)
                    if (moves) lineTo(s.toX, s.toY)
                }
                val stroke = if (prev == null) {
                    GestureDescription.StrokeDescription(path, 0, s.durationMs, s.willContinue)
                } else {
                    prev.continueStroke(path, 0, s.durationMs, s.willContinue)
                }
                if (s.willContinue) exactEnds[stroke] = measuredEnd(path, sx, sy, moves)
                s.handle = stroke
                builder.addStroke(stroke)
                desc.append(" p").append(s.pointerId).append(if (prev == null) " new" else " cont")
                    .append(" (").append(sx).append(',').append(sy).append(")->(").append(s.toX).append(',').append(s.toY)
                    .append(") ").append(s.durationMs).append("ms").append(if (s.willContinue) " hold" else " up")
            }
            recentFrames.addLast(desc.toString())
            while (recentFrames.size > 6) recentFrames.removeFirst()
            val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (token != frameToken) return
                    engine.completed()
                    pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (token != frameToken) return
                    Log.i(TAG, "remote control: gesture cancelled by the system; last frames:\n  " + recentFrames.joinToString("\n  "))
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
        /** How long the first finger waits for further fingers before the touch starts (ms). */
        private const val GROUP_MS = 60L
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
