package app.webmirror.android.encode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * JPEG mode (plan M4.1): the virtual display draws into [surface] (an RGBA_8888 [ImageReader]); every
 * accepted frame is compressed and handed to the hub. Acquire and compression both run on this encoder's
 * own HandlerThread, so the capture and the network threads never wait for JPEG work.
 */
class JpegEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val quality: Int,
    private val hub: StreamHub,
) : FrameEncoder {
    private val thread = HandlerThread("jpeg-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)

    /** Where the VirtualDisplay must draw. */
    override val surface: Surface get() = reader.surface

    private val minIntervalMs = 1000L / fps
    private val out = ReusableStream()

    // Only touched on the encoder thread (and in stop() after the thread has quit).
    private var lastSentMs = 0L
    private var pending: Image? = null
    private var pendingScheduled = false
    private var stopped = false
    private var paused = false
    private var wide: Bitmap? = null
    private var cropped: Bitmap? = null
    private var scratch: ByteBuffer? = null
    private val srcRect = Rect(0, 0, width, height)

    init {
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
    }

    /**
     * Stops delivering frames without releasing anything (the display may still draw into the old
     * surface for a moment while it is being resized and re-attached). Call [stop] afterwards.
     */
    override fun pause() {
        handler.post { paused = true }
    }

    override fun stop() {
        handler.post {
            stopped = true
            try {
                reader.setOnImageAvailableListener(null, null)
                pending?.close()
                pending = null
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "jpeg encoder stop: $e")
            }
            wide?.recycle()
            cropped?.recycle()
            wide = null
            cropped = null
            thread.quitSafely()
        }
    }

    /** Every JPEG frame is complete: nothing to do. */
    override fun requestKeyframe() {}

    private fun onImageAvailable(r: ImageReader) {
        if (stopped) return
        val image: Image
        try {
            image = r.acquireLatestImage() ?: return
        } catch (e: Exception) {
            Log.w(TAG, "acquire failed: $e")
            return
        }
        if (paused) {
            image.close()
            return
        }
        val wait = lastSentMs + minIntervalMs - SystemClock.elapsedRealtime()
        if (wait > 0) {
            // Too early. Keep the newest image and send it when the interval has passed, so the last
            // change before the screen goes static is never lost.
            pending?.close()
            pending = image
            if (!pendingScheduled) {
                pendingScheduled = true
                handler.postDelayed({ sendPending() }, wait)
            }
            return
        }
        pending?.close()
        pending = null
        process(image)
    }

    private fun sendPending() {
        pendingScheduled = false
        val image = pending ?: return
        pending = null
        if (stopped || paused) {
            image.close()
            return
        }
        process(image)
    }

    private fun process(image: Image) {
        try {
            lastSentMs = SystemClock.elapsedRealtime()
            if (image.width != width || image.height != height) return // stale size during a resize
            val bytes = compress(image) ?: return
            hub.onPacket(Packet(Packet.TYPE_JPEG, 0, image.timestamp / 1000, bytes))
        } catch (e: Exception) {
            Log.w(TAG, "jpeg frame failed: $e")
        } finally {
            image.close()
        }
    }

    private fun compress(image: Image): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // rowStride may be larger than width * 4: build the bitmap as wide as the stride, crop afterwards.
        val bmpWidth = rowStride / pixelStride
        var src = wide
        if (src == null || src.width != bmpWidth) {
            src?.recycle()
            src = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            src.setHasAlpha(false)
            wide = src
        }
        buffer.rewind()
        if (buffer.remaining() >= src.byteCount) {
            src.copyPixelsFromBuffer(buffer)
        } else {
            // The last row of the buffer can be shorter than a full stride; pad into a scratch buffer.
            var s = scratch
            if (s == null || s.capacity() < src.byteCount) {
                s = ByteBuffer.allocateDirect(src.byteCount)
                scratch = s
            }
            s.clear()
            s.put(buffer)
            s.position(0)
            src.copyPixelsFromBuffer(s)
        }
        val toCompress: Bitmap
        if (bmpWidth == width) {
            toCompress = src
        } else {
            var c = cropped
            if (c == null) {
                c = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                c.setHasAlpha(false)
                cropped = c
            }
            Canvas(c).drawBitmap(src, srcRect, srcRect, null)
            toCompress = c
        }
        out.reset()
        if (!toCompress.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return out.toByteArray()
    }

    private class ReusableStream : ByteArrayOutputStream(256 * 1024)

    companion object {
        private const val TAG = "WebMirror"
        private const val MAX_IMAGES = 3
    }
}
