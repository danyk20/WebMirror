package app.webmirror.android.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.stream.StreamHub

/**
 * H.264 / HEVC mode (plan M2.3): the virtual display draws into the codec's input surface; every output buffer
 * becomes a packet. The parameter sets (the codec-config buffer: SPS+PPS, for HEVC VPS+SPS+PPS) are not sent on
 * their own: they are prepended to every keyframe so a decoder can start at any keyframe. [hevc] selects
 * `video/hevc` (profile Main, otherwise the same options); the file keeps its H.264 vocabulary in the option names.
 *
 * The encoder options of [StreamConfig] (profile, low latency, bitrate mode, intra refresh, keyframe
 * interval) are only requested when the encoder's capabilities say it supports them; what was requested and
 * what the SPS finally says is described by [H264Report] and handed to [onReport]. If the encoder refuses
 * the full option set, it is opened again with the plain format.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val hub: StreamHub,
    private val config: StreamConfig = StreamConfig(),
    private val hevc: Boolean = false,
    private val onReport: (String) -> Unit = {},
) : FrameEncoder {

    /** What was asked of the encoder and what came back; see [describe]. */
    data class H264Report(
        val profile: String = "auto (encoder default)",
        val level: String = "auto",
        val lowLatency: String = "off",
        val intraRefresh: String = "off",
        val bitrateMode: String = "encoder default",
        val keyframes: String = "",
        val actual: String? = null,
        val fallback: String? = null,
    ) {
        fun describe(width: Int, height: Int, fps: Int, name: String = "H.264"): String = buildString {
            append("$name ${width}x$height @$fps fps\n")
            append("profile: $profile, level $level\n")
            if (actual != null) append("SPS says: $actual\n")
            append("low latency: $lowLatency\n")
            append("intra refresh: $intraRefresh\n")
            append("bitrate mode: $bitrateMode\n")
            append("keyframes: $keyframes")
            if (fallback != null) append("\nFALLBACK: $fallback")
        }
    }

    private class Opened(val codec: MediaCodec, val surface: Surface, val report: H264Report)

    @Volatile
    private var report = H264Report()

    private val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    private val thread = HandlerThread("video-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec
    private val inputSurface: Surface

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    private var reportedActual = false

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
                if (!stopped) Log.w(TAG, "video output failed: $e")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "video codec error: $e")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    init {
        val opened = try {
            open(full = true)
        } catch (e: Exception) {
            Log.w(TAG, "video encoder refused the option set, retrying with the plain format: $e")
            try {
                val o = open(full = false)
                Opened(o.codec, o.surface, o.report.copy(fallback = "encoder refused the options (${e.message}); plain format used"))
            } catch (e2: Exception) {
                thread.quitSafely()
                throw e2
            }
        }
        codec = opened.codec
        inputSurface = opened.surface
        report = opened.report
        emitReport()
    }

    private fun emitReport() {
        val text = report.describe(width, height, fps, if (hevc) "HEVC" else "H.264")
        Log.i(TAG, "encoder state:\n$text")
        onReport(text)
    }

    /** Creates, configures and starts a codec. [full] = request the options of [config] where supported. */
    private fun open(full: Boolean): Opened {
        val c = MediaCodec.createEncoderByType(mime)
        try {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalS)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // static screen still yields >= 10 fps
                setFloat(KEY_MAX_FPS_TO_ENCODER, fps.toFloat()) // caps 60/120 Hz screens
            }
            var r = H264Report(keyframes = "every ${config.keyframeIntervalS} s")
            if (full) r = applyOptions(c, format, r)
            c.setCallback(callback, handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            try {
                Log.i(TAG, "encoder input format: ${c.inputFormat}")
            } catch (e: Exception) {
                // informational only
            }
            return Opened(c, surface, r)
        } catch (e: Exception) {
            try {
                c.release()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    /** Adds the optional keys to [format], each only if the encoder's capabilities allow it. */
    private fun applyOptions(c: MediaCodec, format: MediaFormat, start: H264Report): H264Report {
        var r = start
        val caps: CodecCapabilities? = try {
            c.codecInfo.getCapabilitiesForType(mime)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read encoder capabilities: $e")
            null
        }
        // Never any B-frames: they add reordering delay.
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

        // Profile and level, only if the encoder lists them.
        val levels = caps?.profileLevels.orEmpty()
        val pick = if (hevc) {
            EncoderOptions.pickHevcProfile(levels.map { it.profile }.toSet())
        } else {
            EncoderOptions.pickProfile(config.profile, levels.map { it.profile }.toSet())
        }
        val profile = pick.profile
        var levelText = "auto"
        if (profile != null) {
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            val listed = levels.filter { it.profile == profile }.map { it.level }.toSet()
            // HEVC: no level is requested (the encoder picks one that fits; the SPS tells which).
            val level = if (hevc) null else EncoderOptions.pickLevel(width, height, fps, listed)
            if (level != null) {
                format.setInteger(MediaFormat.KEY_LEVEL, level)
                levelText = "0x${Integer.toHexString(level)} (smallest listed that fits)"
            }
        }
        if (profile == null && (hevc || config.profile != StreamConfig.PROFILE_AUTO)) {
            Log.i(TAG, "profile ${config.profile}: ${pick.description}")
        }
        r = r.copy(profile = pick.description, level = levelText)

        // Low latency.
        val lowLatency = when {
            !config.lowLatencyEncoder -> "off"
            Build.VERSION.SDK_INT < 30 -> "not available (needs Android 11)"
            else -> {
                format.setInteger(MediaFormat.KEY_LATENCY, 1)
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = real-time
                val feature = try {
                    caps?.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) == true
                } catch (e: Exception) {
                    false
                }
                if (feature) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    "on (latency 1, priority 0, low-latency feature supported)"
                } else {
                    "partly (latency 1, priority 0 set; low-latency feature NOT supported by this encoder)"
                }
            }
        }
        r = r.copy(lowLatency = lowLatency)

        // Bitrate mode.
        val wantedMode = if (config.bitrateMode == StreamConfig.BITRATE_VBR) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        }
        val modeName = config.bitrateMode.uppercase()
        val modeOk = try {
            caps?.encoderCapabilities?.isBitrateModeSupported(wantedMode) == true
        } catch (e: Exception) {
            false
        }
        if (modeOk) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, wantedMode)
            r = r.copy(bitrateMode = "$modeName (supported)")
        } else {
            r = r.copy(bitrateMode = "$modeName requested but NOT supported; encoder default")
        }

        // Intra refresh.
        if (config.intraRefresh) {
            val ok = try {
                caps?.isFeatureSupported(CodecCapabilities.FEATURE_IntraRefresh) == true
            } catch (e: Exception) {
                false
            }
            if (ok) {
                format.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, fps) // one full refresh per second
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, INTRA_REFRESH_KEYFRAME_S)
                r = r.copy(
                    intraRefresh = "on (period $fps frames = 1 s)",
                    keyframes = "every $INTRA_REFRESH_KEYFRAME_S s and on request",
                )
            } else {
                Log.i(TAG, "intra refresh requested but the encoder does not support it; ignored")
                r = r.copy(intraRefresh = "requested but NOT supported; periodic keyframes used")
            }
        }
        return r
    }

    private fun handleOutput(data: ByteArray, info: MediaCodec.BufferInfo) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            configBytes = data
            val codecString = if (hevc) HevcUtil.codecString(data) else AvcUtil.codecString(data)
            if (codecString == null) {
                Log.w(TAG, "no SPS in codec config")
                return
            }
            val profileName = if (hevc) HevcUtil.profileName(codecString) else AvcUtil.profileName(codecString)
            report = report.copy(actual = "$codecString ($profileName)")
            if (!reportedActual) {
                reportedActual = true
                emitReport()
            }
            hub.onVideoConfig(if (hevc) StreamHub.MODE_HEVC else StreamHub.MODE_H264, codecString, width, height, fps)
            return
        }
        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || (hevc && HevcUtil.isKeyframe(data))
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

    override fun setTarget(value: Int) {
        if (stopped) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value) })
        } catch (e: Exception) {
            Log.w(TAG, "bitrate change failed: $e")
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
            Log.w(TAG, "video stop: $e")
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "video release: $e")
        }
        try {
            inputSurface.release()
        } catch (e: Exception) {
            Log.w(TAG, "video surface release: $e")
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "WebMirror"
        private const val INTRA_REFRESH_KEYFRAME_S = 10

        // MediaFormat.KEY_MAX_FPS_TO_ENCODER is not in the public SDK on API 29; the key string is stable.
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"
    }
}
