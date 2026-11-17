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
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var preset = initialPreset
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
        hub.keyframeRequester = { encoder?.requestKeyframe() }
        hub.modeRequester = { m -> setMode(m) }
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
            H264Encoder(s.width, s.height, preset.fps, preset.h264Bitrate, hub)
        } else {
            JpegEncoder(s.width, s.height, preset.fps, preset.jpegQuality, hub)
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
        hub.update { it.copy(mode = StreamHub.MODE_JPEG, codec = "", width = size.width, height = size.height, fps = preset.fps) }
    }

    private fun targetSize(): CaptureSize.Size {
        val real = realScreenSize()
        return CaptureSize.compute(real.x, real.y, preset.maxLongSide)
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
    }
}
