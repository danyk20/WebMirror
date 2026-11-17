package app.webmirror.android.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub

/**
 * H.264 mode (plan M2.3): the virtual display draws into the codec's input surface; every output buffer
 * becomes a packet. SPS+PPS (the codec-config buffer) are not sent on their own: they are prepended to
 * every keyframe so a decoder can start at any keyframe. No profile is requested, so the default has no
 * B-frames (low delay).
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    bitrate: Int,
    private val hub: StreamHub,
) : FrameEncoder {

    private val thread = HandlerThread("h264-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec
    private val inputSurface: Surface

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    @Volatile
    private var configBytes: ByteArray? = null

    override val surface: Surface get() = inputSurface

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val data = ByteArray(info.size)
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (stopped || paused || info.size == 0) return
                handleOutput(data, info)
            } catch (e: Exception) {
                if (!stopped) Log.w(TAG, "h264 output failed: $e")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "h264 codec error: $e")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // keyframe every 2 s
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // static screen still yields >= 10 fps
            setFloat(KEY_MAX_FPS_TO_ENCODER, fps.toFloat()) // caps 60/120 Hz screens
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.setCallback(callback, handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            try {
                c.release()
            } catch (ignored: Exception) {
            }
            thread.quitSafely()
            throw e
        }
        codec = c
    }

    private fun handleOutput(data: ByteArray, info: MediaCodec.BufferInfo) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            configBytes = data
            val codecString = AvcUtil.codecString(data)
            if (codecString == null) {
                Log.w(TAG, "no SPS in codec config")
                return
            }
            hub.onVideoConfig(codecString, width, height, fps)
            return
        }
        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val config = configBytes
        val payload = if (key && config != null) config + data else data
        hub.onPacket(Packet(Packet.TYPE_H264, if (key) Packet.FLAG_KEYFRAME else 0, info.presentationTimeUs, payload))
    }

    override fun requestKeyframe() {
        if (stopped) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "keyframe request failed: $e")
        }
    }

    override fun pause() {
        paused = true
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "h264 stop: $e")
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "h264 release: $e")
        }
        try {
            inputSurface.release()
        } catch (e: Exception) {
            Log.w(TAG, "h264 surface release: $e")
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "WebMirror"

        // MediaFormat.KEY_MAX_FPS_TO_ENCODER is not in the public SDK on API 29; the key string is stable.
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"
    }
}
