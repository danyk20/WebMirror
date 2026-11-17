package app.webmirror.android.stream

import org.json.JSONObject
import java.util.zip.CRC32

/**
 * Every latency/smoothness tunable in one object (plan 2.1 `cfg`). The whole object is persisted,
 * applied live by `MirrorService.applyConfig`, and can be replaced at once (e.g. by an automatic test).
 * Pure Kotlin plus org.json, so it is unit-testable.
 */
data class StreamConfig(
    // ---- socket (new connections) ----
    val tcpNoDelay: Boolean = true,
    /** Socket send buffer in KB; 0 = system default. */
    val sendBufferKb: Int = 64,
    // ---- encoder (rebuild on change) ----
    val lowLatencyEncoder: Boolean = true,
    /** "baseline", "main", "high" or "auto". */
    val profile: String = PROFILE_BASELINE,
    /** "cbr" or "vbr". */
    val bitrateMode: String = BITRATE_CBR,
    val intraRefresh: Boolean = false,
    val keyframeIntervalS: Int = 2,
    // ---- picture size ----
    val fitToViewer: Boolean = false,
    // ---- server flow control (live) ----
    val maxInFlight: Int = 6,
    val dropAfterMs: Int = 150,
    // ---- adaptive bitrate (live) ----
    /** Lower the H.264 bitrate (JPEG: quality) when a viewer falls behind, raise it again when it recovers. */
    val adaptiveBitrate: Boolean = true,
    /** Lowest H.264 bitrate the controller may choose, kbit/s (JPEG: quality never goes below 30). */
    val minBitrateKbps: Int = 800,
    // ---- page (sent in hello.cfg) ----
    val jitterBufferMs: Int = 80,
    /** The player skips frames later than this many frame intervals; 0 = never. */
    val skipLateFrames: Int = 2,
    /** "immediate" = draw each decoded frame at once; "vsync" = draw the newest decoded frame once per screen refresh. */
    val pacing: String = PACING_IMMEDIATE,
) {

    /** The fields whose change needs a new encoder. */
    data class EncoderKey(
        val lowLatencyEncoder: Boolean,
        val profile: String,
        val bitrateMode: String,
        val intraRefresh: Boolean,
        val keyframeIntervalS: Int,
    )

    val encoderKey: EncoderKey
        get() = EncoderKey(lowLatencyEncoder, profile, bitrateMode, intraRefresh, keyframeIntervalS)

    /** Which parts of the running system a change touches. */
    data class Diff(
        val encoder: Boolean,
        val fit: Boolean,
        val socket: Boolean,
        val outbox: Boolean,
        val page: Boolean,
        val adaptive: Boolean = false,
    ) {
        val any: Boolean get() = encoder || fit || socket || outbox || page || adaptive
    }

    fun diff(newer: StreamConfig): Diff = Diff(
        encoder = encoderKey != newer.encoderKey,
        fit = fitToViewer != newer.fitToViewer,
        socket = tcpNoDelay != newer.tcpNoDelay || sendBufferKb != newer.sendBufferKb,
        outbox = maxInFlight != newer.maxInFlight || dropAfterMs != newer.dropAfterMs,
        page = jitterBufferMs != newer.jitterBufferMs || skipLateFrames != newer.skipLateFrames || pacing != newer.pacing,
        adaptive = adaptiveBitrate != newer.adaptiveBitrate || minBitrateKbps != newer.minBitrateKbps,
    )

    /** Field name to value, in display order. The JSON form and the id are derived from it. */
    fun toMap(): Map<String, Any> = linkedMapOf(
        "tcpNoDelay" to tcpNoDelay,
        "sendBufferKb" to sendBufferKb,
        "lowLatencyEncoder" to lowLatencyEncoder,
        "profile" to profile,
        "bitrateMode" to bitrateMode,
        "intraRefresh" to intraRefresh,
        "keyframeIntervalS" to keyframeIntervalS,
        "fitToViewer" to fitToViewer,
        "maxInFlight" to maxInFlight,
        "dropAfterMs" to dropAfterMs,
        "adaptiveBitrate" to adaptiveBitrate,
        "minBitrateKbps" to minBitrateKbps,
        "jitterBufferMs" to jitterBufferMs,
        "skipLateFrames" to skipLateFrames,
        "pacing" to pacing,
    )

    fun toJson(): JSONObject = JSONObject(toMap())

    /** Short stable identifier of the whole configuration (8 hex digits), for attributing stats. */
    val id: String
        get() {
            val crc = CRC32()
            crc.update(toMap().entries.joinToString(",") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8))
            return "%08x".format(crc.value)
        }

    /** One compact line for overlays and logs. */
    fun summary(): String = buildString {
        append(profile).append(' ').append(bitrateMode)
        if (lowLatencyEncoder) append(" lowlat")
        if (intraRefresh) append(" intra-refresh") else append(" kf").append(keyframeIntervalS).append('s')
        if (fitToViewer) append(" fit")
        append(" | ").append(if (tcpNoDelay) "nodelay" else "nagle")
        append(" buf").append(if (sendBufferKb == 0) "sys" else "${sendBufferKb}K")
        append(" inflight").append(maxInFlight)
        append(" drop").append(dropAfterMs).append("ms")
        if (adaptiveBitrate) append(" adapt>=").append(minBitrateKbps).append('k') else append(" fixed-rate")
        append(" | jitter").append(jitterBufferMs).append("ms")
        append(" skip").append(skipLateFrames)
        append(' ').append(pacing)
    }

    /** What the page needs, as the `cfg` object of `hello` (the summary contains no characters needing escapes). */
    fun pageJson(): String =
        """{"id":"$id","summary":"${summary()}","jitterBufferMs":$jitterBufferMs,"skipLateFrames":$skipLateFrames,"pacing":"$pacing"}"""

    companion object {
        const val PROFILE_BASELINE = "baseline"
        const val PROFILE_MAIN = "main"
        const val PROFILE_HIGH = "high"
        const val PROFILE_AUTO = "auto"
        const val BITRATE_CBR = "cbr"
        const val BITRATE_VBR = "vbr"

        const val PACING_IMMEDIATE = "immediate"
        const val PACING_VSYNC = "vsync"

        val PACINGS = listOf(PACING_IMMEDIATE, PACING_VSYNC)
        val PROFILES = listOf(PROFILE_BASELINE, PROFILE_MAIN, PROFILE_HIGH, PROFILE_AUTO)
        val BITRATE_MODES = listOf(BITRATE_CBR, BITRATE_VBR)

        /** Short human label per field, keyed by the JSON field name (display order). */
        val LABELS: Map<String, String> = linkedMapOf(
            "tcpNoDelay" to "TCP no-delay (new connections)",
            "sendBufferKb" to "Socket send buffer, KB (0 = system default; new connections)",
            "lowLatencyEncoder" to "Low-latency encoder mode (Android 11+)",
            "profile" to "H.264 profile",
            "bitrateMode" to "Bitrate mode",
            "intraRefresh" to "Intra refresh instead of keyframes",
            "keyframeIntervalS" to "Keyframe interval, seconds",
            "fitToViewer" to "Fit stream size to the viewer window",
            "maxInFlight" to "Frames in flight before the phone waits",
            "dropAfterMs" to "H.264: drop waiting frames after, ms",
            "adaptiveBitrate" to "Adaptive bitrate (lower it when the viewer falls behind)",
            "minBitrateKbps" to "Adaptive bitrate floor, kbit/s",
            "jitterBufferMs" to "Player jitter buffer, ms",
            "skipLateFrames" to "Player skips frames late by N intervals (0 = never)",
            "pacing" to "Player frame pacing",
        )

        /** Allowed range of each numeric field. */
        val RANGES: Map<String, IntRange> = mapOf(
            "sendBufferKb" to 0..1024,
            "keyframeIntervalS" to 1..10,
            "maxInFlight" to 1..30,
            "dropAfterMs" to 10..2000,
            "minBitrateKbps" to 100..20000,
            "jitterBufferMs" to 0..1000,
            "skipLateFrames" to 0..10,
        )

        /** Missing or invalid fields fall back to the defaults, numbers are clamped into their range. */
        fun fromMap(m: Map<String, Any?>): StreamConfig {
            val d = StreamConfig()
            fun num(key: String, def: Int): Int {
                val r = RANGES.getValue(key)
                val v = (m[key] as? Number)?.toInt() ?: return def
                return v.coerceIn(r.first, r.last)
            }
            fun bool(key: String, def: Boolean): Boolean = m[key] as? Boolean ?: def
            val profile = (m["profile"] as? String)?.takeIf { it in PROFILES } ?: d.profile
            val pacing = (m["pacing"] as? String)?.takeIf { it in PACINGS } ?: d.pacing
            val mode = (m["bitrateMode"] as? String)?.takeIf { it in BITRATE_MODES } ?: d.bitrateMode
            return StreamConfig(
                tcpNoDelay = bool("tcpNoDelay", d.tcpNoDelay),
                sendBufferKb = num("sendBufferKb", d.sendBufferKb),
                lowLatencyEncoder = bool("lowLatencyEncoder", d.lowLatencyEncoder),
                profile = profile,
                bitrateMode = mode,
                intraRefresh = bool("intraRefresh", d.intraRefresh),
                keyframeIntervalS = num("keyframeIntervalS", d.keyframeIntervalS),
                fitToViewer = bool("fitToViewer", d.fitToViewer),
                maxInFlight = num("maxInFlight", d.maxInFlight),
                dropAfterMs = num("dropAfterMs", d.dropAfterMs),
                adaptiveBitrate = bool("adaptiveBitrate", d.adaptiveBitrate),
                minBitrateKbps = num("minBitrateKbps", d.minBitrateKbps),
                jitterBufferMs = num("jitterBufferMs", d.jitterBufferMs),
                skipLateFrames = num("skipLateFrames", d.skipLateFrames),
                pacing = pacing,
            )
        }

        fun fromJson(o: JSONObject?): StreamConfig {
            if (o == null) return StreamConfig()
            val m = HashMap<String, Any?>()
            for (k in o.keys()) m[k] = o.opt(k)
            return fromMap(m)
        }

        fun fromJson(text: String?): StreamConfig = try {
            if (text.isNullOrBlank()) StreamConfig() else fromJson(JSONObject(text))
        } catch (e: Exception) {
            StreamConfig()
        }
    }
}
