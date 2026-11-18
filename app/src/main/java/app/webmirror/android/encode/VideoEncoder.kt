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
 * H.264 / HEVC / VP9 / AV1 mode (plan M2.3, 2.2): the virtual display draws into the codec's input surface; every output buffer
 * becomes a packet. The parameter sets (the codec-config buffer: SPS+PPS, for HEVC VPS+SPS+PPS) are not sent on
 * their own: they are prepended to every keyframe so a decoder can start at any keyframe. [mode] (a
 * `CodecChooser` codec id) selects the MIME type and [encoderName] the exact MediaCodec (hardware or software, chosen
 * by the hub). HEVC: profile Main; VP9: profile 0; AV1: Main 8-bit; the same low-latency / CBR / intra-refresh /
 * keyframe keys where supported, no B-frame key for VP9/AV1. AV1 sends the codec-config sequence header OBU in front
 * of every keyframe (WebCodecs AV1: no `description`); VP9 has no config and its codec string comes from the first
 * keyframe. The file keeps its H.264 vocabulary in the option names.
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
    private val mode: String = StreamHub.MODE_H264,
    private val encoderName: String,
    private val hardware: Boolean,
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
        fun describe(width: Int, height: Int, fps: Int, name: String = "H.264", encoder: String = ""): String = buildString {
            append("$name ${width}x$height @$fps fps\n")
            if (encoder.isNotEmpty()) append("encoder: $encoder\n")
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

    private val hevc = mode == StreamHub.MODE_HEVC
    private val vp9 = mode == StreamHub.MODE_VP9
    private val av1 = mode == StreamHub.MODE_AV1
    private val mime = CodecSupport.MIMES.getValue(mode)
    private val label = CodecSupport.LABELS.getValue(mode)
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

    /** AV1: the sequence header OBU to put in front of keyframes. */
    private var seqHeader: ByteArray? = null

    /** True once `hub.onVideoConfig` was called (H.264/HEVC/AV1 at the config buffer or first keyframe, VP9 at the first keyframe). */
    private var announced = false

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
        val text = report.describe(width, height, fps, label, "$encoderName (${if (hardware) "hardware" else "software"})")
        Log.i(TAG, "encoder state:\n$text")
        onReport(text)
    }

    /** Creates, configures and starts a codec. [full] = request the options of [config] where supported. */
    private fun open(full: Boolean): Opened {
        val c = MediaCodec.createByCodecName(encoderName)
        try {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalS)
                // A static screen still yields >= 10 fps (100 ms is 6 frame intervals at 60 fps, 3 at 30: a repeat never replaces a real frame).
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                setFloat(KEY_MAX_FPS_TO_ENCODER, fps.toFloat()) // caps 60/120 Hz screens to the encoder rate (60 or 30 here)
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
        // Never any B-frames: they add reordering delay (VP9 and AV1 as used here have no such key).
        if (!vp9 && !av1) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

        // Profile and level, only if the encoder lists them.
        val levels = caps?.profileLevels.orEmpty()
        val listedProfiles = levels.map { it.profile }.toSet()
        val pick = if (hevc) {
            EncoderOptions.pickHevcProfile(listedProfiles)
        } else if (vp9) {
            EncoderOptions.pickFixedProfile(listedProfiles, EncoderOptions.VP9_PROFILE_0, "VP9 profile 0")
        } else if (av1) {
            EncoderOptions.pickFixedProfile(listedProfiles, EncoderOptions.AV1_PROFILE_MAIN8, "AV1 Main 8-bit")
        } else {
            EncoderOptions.pickProfile(config.profile, levels.map { it.profile }.toSet())
        }
        val profile = pick.profile
        var levelText = "auto"
        if (profile != null) {
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            val listed = levels.filter { it.profile == profile }.map { it.level }.toSet()
            // HEVC/VP9/AV1: no level is requested (the encoder picks one that fits; the stream tells which).
            val level = if (hevc || vp9 || av1) null else EncoderOptions.pickLevel(width, height, fps, listed)
            if (level != null) {
                format.setInteger(MediaFormat.KEY_LEVEL, level)
                levelText = "0x${Integer.toHexString(level)} (smallest listed that fits)"
            }
        }
        if (profile == null && (mode != StreamHub.MODE_H264 || config.profile != StreamConfig.PROFILE_AUTO)) {
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

    private fun announce(codecString: String) {
        announced = true
        val profileName = when {
            hevc -> HevcUtil.profileName(codecString)
            vp9 || av1 -> label
            else -> AvcUtil.profileName(codecString)
        }
        report = report.copy(actual = "$codecString ($profileName)")
        if (!reportedActual) {
            reportedActual = true
            emitReport()
        }
        hub.onVideoConfig(mode, codecString, width, height, fps, encoderName, hardware)
    }

    private fun handleOutput(data: ByteArray, info: MediaCodec.BufferInfo) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (vp9) return // VP9 has no codec config; any such buffer is ignored
            if (av1) {
                val seq = Av1Util.sequenceHeader(data)
                val cs = seq?.let { Av1Util.codecString(it) }
                if (seq == null || cs == null) {
                    Log.w(TAG, "no usable sequence header in AV1 codec config (will try the first keyframe)")
                    return
                }
                seqHeader = seq
                announce(cs)
                return
            }
            configBytes = data
            val codecString = if (hevc) HevcUtil.codecString(data) else AvcUtil.codecString(data)
            if (codecString == null) {
                Log.w(TAG, "no SPS in codec config")
                return
            }
            announce(codecString)
            return
        }
        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
            (hevc && HevcUtil.isKeyframe(data)) || (vp9 && Vp9Util.isKeyframe(data)) || (av1 && Av1Util.isKeyframe(data))
        if (vp9 && !announced) {
            if (!key) return // nothing can be shown before the first keyframe, and the page has no hello yet
            announce(Vp9Util.codecString(data, width, height, fps))
        }
        if (av1) {
            if (seqHeader == null && key) {
                // No (usable) codec-config buffer: take the sequence header from the keyframe itself.
                val seq = Av1Util.sequenceHeader(data)
                if (seq != null) {
                    seqHeader = seq
                    Av1Util.codecString(seq)?.let { announce(it) }
                }
            }
            if (!announced) return
        }
        val config = configBytes
        val seq = seqHeader
        val payload = when {
            key && config != null -> config + data
            av1 && key && seq != null -> Av1Util.withSequenceHeader(data, seq)
            else -> data
        }
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
