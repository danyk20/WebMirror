package app.webmirror.android.capture

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import app.webmirror.android.encode.FrameEncoder
import app.webmirror.android.encode.H264Encoder
import app.webmirror.android.encode.JpegEncoder
import app.webmirror.android.stream.BitrateController
import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.stream.StreamHub

/**
 * Owns the one VirtualDisplay of a MediaProjection (Android 14+ allows only one per projection, so it is
 * created once and later only resized and re-targeted with setSurface) and the encoder drawing from it.
 * Rotation and preset changes go through [reconfigure].
 *
 * The projection itself (consent result, callback, stop) belongs to the service; this class only needs a
 * projection that already has its callback registered.
 */
class ScreenCapture(
    context: Context,
    private val projection: MediaProjection,
    private val hub: StreamHub,
    initialPreset: Preset,
    initialConfig: StreamConfig = StreamConfig(),
    /** Called with a multi-line description of the encoder state whenever it is (re)built or learns more. */
    private val onEncoderReport: (String?) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)

    /** Adaptive bitrate (H.264) / quality (JPEG): evaluated every [BitrateController.TICK_MS] on [handler]. */
    private val controller = BitrateController(0, 0)
    private val reportLock = Any()
    private var baseReport: String? = null
    private var lastTargetLogMs = 0L

    private var preset = initialPreset
    private var config = initialConfig
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: FrameEncoder? = null
    private var mode = StreamHub.MODE_JPEG
    private var size = CaptureSize.Size(0, 0)
    private var released = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) reconfigure()
        }
    }

    /** Creates the display and the first encoder. Call once, after `projection.registerCallback`. */
    @Synchronized
    fun start() {
        check(virtualDisplay == null && !released) { "already started" }
        size = targetSize()
        mode = hub.wantedMode()
        val enc = newEncoderOrFallback(size)
        encoder = enc
        virtualDisplay = projection.createVirtualDisplay(
            "WebMirror", size.width, size.height, densityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.surface, null, handler,
        )
        publish()
        handler.postDelayed(adaptiveTick, BitrateController.TICK_MS)
        hub.keyframeRequester = { encoder?.requestKeyframe() }
        hub.modeRequester = { m -> setMode(m) }
        hub.viewListener = { if (config.fitToViewer) reconfigure() }
        displayManager.registerDisplayListener(displayListener, handler)
        Log.i(TAG, "capture started ${size.width}x${size.height} ${preset.label}")
    }

    /** Applies a new preset to the running capture (resize + setSurface on the same display). */
    fun setPreset(p: Preset) {
        handler.post {
            synchronized(this) {
                if (preset == p) return@synchronized
                preset = p
                applyChange(forceEncoder = true)
            }
        }
    }

    /**
     * Applies a new configuration live: a changed encoder option rebuilds the encoder (H.264 mode only, JPEG
     * ignores them); a changed "fit to viewer" only re-evaluates the size. Same display, new surface.
     */
    fun applyConfig(c: StreamConfig) {
        handler.post {
            synchronized(this) {
                val old = config
                config = c
                resetController() // any applied config: the target goes back to the ceiling, with a grace period
                encoder?.setTarget(controller.value)
                val rebuild = old.encoderKey != c.encoderKey && mode == StreamHub.MODE_H264
                if (rebuild || old.fitToViewer != c.fitToViewer) applyChange(forceEncoder = rebuild)
            }
        }
    }

    /** Switches between jpeg and h264 on the same display (plan 2.2). Safe to call from any thread. */
    fun setMode(m: String) {
        handler.post {
            synchronized(this) {
                if (mode == m) return@synchronized
                mode = m
                applyChange(forceEncoder = true)
            }
        }
    }

    /** Recomputes the size (rotation) and reconfigures if it changed. Safe to call from any thread. */
    fun reconfigure() {
        handler.post { synchronized(this) { applyChange(forceEncoder = false) } }
    }

    @Synchronized
    fun stop() {
        if (released) return
        released = true
        hub.modeRequester = null
        hub.keyframeRequester = null
        hub.viewListener = null
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister listener: $e")
        }
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release display: $e")
        }
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun applyChange(forceEncoder: Boolean) {
        val vd = virtualDisplay
        if (released || vd == null) return
        val newSize = targetSize()
        val sizeChanged = newSize != size
        if (!sizeChanged && !forceEncoder) return
        try {
            // Build the new encoder first, then move the one display over, then retire the old encoder.
            val old = encoder
            val enc = newEncoderOrFallback(newSize)
            old?.pause()
            if (sizeChanged) vd.resize(newSize.width, newSize.height, densityDpi())
            vd.setSurface(enc.surface)
            encoder = enc
            size = newSize
            old?.stop()
            publish()
            Log.i(TAG, "capture reconfigured ${size.width}x${size.height} ${preset.label}")
        } catch (e: Exception) {
            Log.w(TAG, "reconfigure failed: $e")
        }
    }

    private fun newEncoder(s: CaptureSize.Size): FrameEncoder =
        if (mode == StreamHub.MODE_H264) {
            resetController()
            H264Encoder(s.width, s.height, preset.fps, controller.value, hub, config) { setBaseReport(it) }
        } else {
            resetController()
            setBaseReport("JPEG ${s.width}x${s.height} @${preset.jpegFps} fps, quality ${preset.jpegQuality}\n(H.264 encoder options do not apply in JPEG mode)")
            JpegEncoder(s.width, s.height, preset.jpegFps, controller.value, hub)
        }

    /** Floor and ceiling of the adaptive target for the current mode: bit/s for H.264, quality for JPEG. */
    private fun limits(): Pair<Int, Int> =
        if (mode == StreamHub.MODE_H264) {
            Pair(config.minBitrateKbps * 1000, preset.h264Bitrate)
        } else {
            Pair(BitrateController.MIN_JPEG_QUALITY, preset.jpegQuality)
        }

    /** The target goes back to the preset's value (new encoder, new limits, adaptive switched). */
    private fun resetController() {
        val (floor, ceiling) = limits()
        controller.configure(floor, ceiling, System.nanoTime() / 1_000_000)
        publishTarget()
    }

    private fun targetText(): String {
        val unit = if (mode == StreamHub.MODE_H264) "kbps" else "quality"
        val div = if (mode == StreamHub.MODE_H264) 1000 else 1
        val text = "${controller.value / div}/${controller.ceiling / div} $unit"
        return if (config.adaptiveBitrate) text else "$text fixed"
    }

    /** Makes the current target visible: to viewers (ping), and in the app's encoder state. */
    private fun publishTarget() {
        hub.targetText = targetText()
        emitReport()
    }

    private fun setBaseReport(text: String) {
        synchronized(reportLock) { baseReport = text }
        emitReport()
    }

    private fun emitReport() {
        val text = synchronized(reportLock) {
            val base = baseReport ?: return
            "$base\ntarget: ${hub.targetText}" + if (config.adaptiveBitrate) " (adaptive, floor ${controller.floor / (if (mode == StreamHub.MODE_H264) 1000 else 1)})" else ""
        }
        onEncoderReport(text)
    }

    private var lastViewers = 0

    private val adaptiveTick = object : Runnable {
        override fun run() {
            if (released) return
            try {
                synchronized(this@ScreenCapture) {
                    val enc = encoder
                    if (config.adaptiveBitrate && enc != null && !released) {
                        val now = System.nanoTime() / 1_000_000
                        val viewers = hub.viewerCount
                        if (viewers > 0 && lastViewers == 0) { // the first viewer connects: start at the ceiling
                            resetController()
                            enc.setTarget(controller.value)
                        }
                        lastViewers = viewers
                        val n = controller.tick(now, hub.congestionSignals(now), config.dropAfterMs)
                        if (n != null) {
                            enc.setTarget(n)
                            publishTarget()
                            if (now - lastTargetLogMs >= TARGET_LOG_MS) {
                                lastTargetLogMs = now
                                Log.i(TAG, "adaptive target now ${hub.targetText}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "adaptive tick failed: $e")
            }
            handler.postDelayed(this, BitrateController.TICK_MS)
        }
    }

    /** A device without a usable H.264 encoder falls back to JPEG instead of failing the capture. */
    private fun newEncoderOrFallback(s: CaptureSize.Size): FrameEncoder =
        try {
            newEncoder(s)
        } catch (e: Exception) {
            if (mode != StreamHub.MODE_H264) throw e
            Log.w(TAG, "h264 encoder unavailable, using jpeg: $e")
            mode = StreamHub.MODE_JPEG
            newEncoder(s)
        }

    /**
     * Tells the hub (and so every viewer, in a new hello) what the stream looks like now. In h264 mode the
     * encoder does it itself once it knows its codec string (hub.onVideoConfig).
     */
    private fun publish() {
        if (mode == StreamHub.MODE_H264) return
        hub.update { it.copy(mode = StreamHub.MODE_JPEG, codec = "", width = size.width, height = size.height, fps = preset.jpegFps) }
    }

    private fun targetSize(): CaptureSize.Size {
        val real = realScreenSize()
        val box = if (config.fitToViewer) hub.viewerBox() else null
        if (box == null) return CaptureSize.compute(real.x, real.y, preset.maxLongSide)
        val fit = CaptureSize.fit(real.x, real.y, preset.maxLongSide, box.width, box.height)
        // Only re-encode when the viewer's window changed the size by more than 10%.
        return CaptureSize.keepIfClose(size.takeIf { it.width > 0 }, fit)
    }

    /**
     * Real size of the default display in its current rotation. Plan M2.5 suggests maximumWindowMetrics on
     * API 30+, but that is resolved through a (non-visual) service context whose rotation is not
     * guaranteed to be current; the display itself always is.
     */
    @Suppress("DEPRECATION")
    private fun realScreenSize(): Point {
        val p = Point()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    private fun densityDpi(): Int = appContext.resources.displayMetrics.densityDpi

    companion object {
        private const val TAG = "WebMirror"
        private const val TARGET_LOG_MS = 2000L
    }
}
