package app.webmirror.android.test

import app.webmirror.android.capture.Preset
import app.webmirror.android.stream.CodecChooser
import app.webmirror.android.stream.JsonText
import app.webmirror.android.stream.StreamConfig
import java.util.Locale

/*
 * Automatic test: the plan (which configurations, in which order), the aggregation of the page's stats
 * per phase, the score, and the report text (JSON and CSV). Pure Kotlin, no Android classes, so it is
 * unit-tested on the JVM. The Android glue (applying configurations, timing, files) is in AutoTestRunner.
 */

/** One phase of the test: a complete configuration, plus the one switch that is not part of [StreamConfig]. */
data class PhaseSpec(
    val name: String,
    val description: String,
    val config: StreamConfig,
    /** "Force JPEG" (an app setting, not a [StreamConfig] field). */
    val forceJpeg: Boolean = false,
    /** The quality preset for this phase (an app setting, not a [StreamConfig] field); null = the owner's preset. */
    val preset: Preset? = null,
)

/** What the user chose on the start screen. */
data class TestOptions(
    val runName: String = "test",
    val phaseSeconds: Int = DEFAULT_PHASE_SECONDS,
    val settleSeconds: Int = SETTLE_SECONDS,
    val quick: Boolean = false,
    val useTestPattern: Boolean = true,
) {
    /** Length of the measured part of every phase. */
    val measureSeconds: Int get() = if (quick) QUICK_PHASE_SECONDS else phaseSeconds.coerceIn(MIN_PHASE_SECONDS, MAX_PHASE_SECONDS)

    /** The run name as used in file names. */
    val safeName: String get() = sanitizeName(runName)

    companion object {
        const val DEFAULT_PHASE_SECONDS = 40
        const val QUICK_PHASE_SECONDS = 20
        const val SETTLE_SECONDS = 5
        const val MIN_PHASE_SECONDS = 5
        const val MAX_PHASE_SECONDS = 600

        /** Letters, digits, '-' and '_' only (spaces become '-'); never empty. */
        fun sanitizeName(s: String): String {
            val t = s.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")
                .trim('-').take(40)
            return t.ifEmpty { "test" }
        }
    }
}

/** The list of phases. Edit [phases] to change what the test does: each entry is a full [StreamConfig]. */
object TestPlan {

    /** Passing a config through the same clamping the app does when it stores it keeps the config id stable. */
    private fun norm(c: StreamConfig): StreamConfig = StreamConfig.fromMap(c.toMap())

    fun phases(base: StreamConfig = StreamConfig()): List<PhaseSpec> {
        fun p(name: String, description: String, c: StreamConfig, preset: Preset? = null) = PhaseSpec(name, description, norm(c), preset = preset)
        // The other options (legacy, low-latency, profile, vbr, intra refresh, fit to viewer, in-flight, drop-after,
        // vsync pacing, jitter 150 ms, JPEG) were measured in the car on 2026-11-18 and made no measurable difference
        // (docs/development/car-test-results.md); they are not repeated. The options themselves still exist in StreamConfig.
        return listOf(
            p("default", "Your current settings (baseline)", base),
            p("jitter-40ms", "Player jitter buffer 40 ms", base.copy(jitterBufferMs = 40)),
            p("codec-h264", "Forced H.264", base.copy(codec = StreamConfig.CODEC_H264)),
            p("codec-hevc", "Forced HEVC", base.copy(codec = StreamConfig.CODEC_HEVC)),
            p("codec-vp9", "Forced VP9 (software encoder allowed)", base.copy(codec = StreamConfig.CODEC_VP9, allowSoftwareEncoder = true)),
            p("codec-av1", "Forced AV1 (software encoder allowed)", base.copy(codec = StreamConfig.CODEC_AV1, allowSoftwareEncoder = true)),
            p("fps-60", "Smoothest preset: 1280 px at 60 fps (falls back to 30 fps if the viewer cannot keep up)", base, Preset.SMOOTHEST),
        )
    }

    /**
     * Why a forced-codec phase cannot run (null: it can, or it is not a forced-codec phase). Uses the same
     * decision as the live stream ([CodecChooser.choose]): when the forced codec would not be chosen, the phone
     * would measure another codec, so the phase is skipped instead.
     */
    fun skipReason(spec: PhaseSpec, phone: CodecChooser.PhoneCaps, viewers: List<CodecChooser.ViewerCaps>): String? {
        val codec = spec.config.codec
        if (codec !in CodecChooser.VIDEO_CODECS || viewers.isEmpty()) return null
        val label = mapOf("h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1").getValue(codec)
        if (CodecChooser.choose(codec, spec.config.allowSoftwareEncoder, phone, viewers).mode == codec) return null
        return when {
            CodecChooser.encoderForMode(codec, phone, spec.config.allowSoftwareEncoder) == null ->
                "skipped: no usable $label encoder on this phone"
            else -> "skipped: a viewer cannot decode $label in hardware"
        }
    }

    /** Which encoder-state lines mean "the phone could not do what this phase asked". */
    private val UNSUPPORTED_MARKERS = listOf("NOT supported", "not listed by the encoder", "FALLBACK", "not available (needs")

    /**
     * A note when the encoder text shows that the option of a phase did not apply (the phase then measures the
     * same thing as `default`), else null. [jpegMode]: the stream is JPEG, so H.264 options do not apply.
     */
    fun unsupportedNote(spec: PhaseSpec, encoderText: String?, jpegMode: Boolean, actualMode: String? = null): String? {
        if (spec.forceJpeg) return null
        if (jpegMode) return "the stream was JPEG (a viewer cannot decode H.264), so H.264 options did not apply"
        val text = encoderText ?: return null
        val fixed = spec.config.codec
        if (fixed != StreamConfig.CODEC_AUTO) {
            val label = mapOf("h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1").getValue(fixed)
            val used = if (actualMode != null) actualMode == fixed else text.startsWith(label)
            if (!used) {
                return "$label was not used (the phone has no usable encoder for it, a viewer cannot decode it in hardware, or the encoder refused it): ${actualMode ?: "another codec"} was measured"
            }
        }
        val lines = text.lines().filter { l -> UNSUPPORTED_MARKERS.any { l.contains(it) } }
        // Only report what this phase asked for: the default phase has the same lines for the same reasons.
        val relevant = lines.filter { l ->
            when (spec.name) {
                else -> l.startsWith("FALLBACK")
            }
        }
        return if (relevant.isEmpty()) null else relevant.joinToString("; ").trim()
    }
}

/** One viewer `stats` message as the test needs it (parsed by the runner). */
data class StatsSample(
    val vid: Int,
    val cfgId: String,
    val fps: Double,
    val e2eRaw: List<Int> = emptyList(),
    val rttMs: Double = 0.0,
    val lateMs: Double = 0.0,
    val netLateMs: Double = 0.0,
    /** Cumulative counters of the page (and, for [srvDropped], of the phone's outbox for this viewer). */
    val skipped: Long = 0,
    val replaced: Long = 0,
    val dropped: Long = 0,
    val decoderResets: Long = 0,
    val reconnects: Long = 0,
    val srvDropped: Long = 0,
)

/** Plain statistics helpers. */
object Aggregate {
    fun avg(v: List<Double>): Double? = if (v.isEmpty()) null else v.sum() / v.size

    fun min(v: List<Double>): Double? = v.minOrNull()

    fun max(v: List<Double>): Double? = v.maxOrNull()

    /** Nearest-rank percentile ([p] in 0..100): the smallest value that at least p % of the values do not exceed. */
    fun percentile(v: List<Double>, p: Double): Double? {
        if (v.isEmpty()) return null
        val sorted = v.sorted()
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun p95(v: List<Double>): Double? = percentile(v, 95.0)

    /** Leading integer of the adaptive target text ("3200/4000 kbps", "40/50 quality"), or null. */
    fun parseTarget(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = Regex("^\\s*(\\d+)").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** The "@N fps" of the stream text ("584x1280 @30 fps (h264)"), or null. */
    fun parseStreamFps(text: String?): Int? =
        text?.let { Regex("@(\\d+) fps").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** True when the stream text says the rate is the automatic fallback ("584x1280 @30 fps (fallback) (h264)"). */
    fun isFallbackStream(text: String?): Boolean = text?.contains("(fallback)") == true

    fun isJpegStream(text: String?): Boolean = text?.contains("(jpeg)") == true
}

/** The page-side numbers of one phase. */
data class PageSummary(
    val samples: Int,
    val fpsAvg: Double?,
    val fpsMin: Double?,
    val e2eAvg: Double?,
    val e2eP95: Double?,
    val e2eMax: Double?,
    val e2eFrames: Int,
    val rttAvg: Double?,
    val lateMs: Double?,
    val netLateMs: Double?,
    val skipped: Long,
    val replaced: Long,
    val dropped: Long,
    val decoderResets: Long,
    val reconnects: Long,
    val srvDropped: Long,
)

/**
 * Collects the stats of one phase. Only a message counts that (1) carries this phase's `cfgId`, (2) covers a
 * time after the settle period (stats arrive every [statsIntervalMs] and cover the interval before their
 * arrival, so the first [statsIntervalMs] after the settle period are skipped too), (3) arrived before the
 * phase ended, and (4) comes from the same viewer as the first accepted one. Cumulative counters are counted
 * from the first accepted message to the last.
 */
class PhaseAccumulator(
    val cfgId: String,
    val settleEndMs: Long,
    val endMs: Long,
    private val statsIntervalMs: Long = STATS_INTERVAL_MS,
) {
    private val samples = ArrayList<StatsSample>()

    /** The viewer whose stats count (the first one accepted), or null before any. */
    var vid: Int? = null
        private set

    /** When the last accepted message arrived, or 0. */
    var lastAcceptedMs: Long = 0
        private set

    val sampleCount: Int get() = samples.size

    /** Offers one message that arrived at [arrivedMs]; true if it was accepted. */
    @Synchronized
    fun offer(arrivedMs: Long, s: StatsSample): Boolean {
        if (s.cfgId != cfgId) return false
        if (arrivedMs - statsIntervalMs < settleEndMs) return false
        if (arrivedMs > endMs) return false
        val v = vid
        if (v != null && v != s.vid) return false
        vid = s.vid
        samples.add(s)
        lastAcceptedMs = arrivedMs
        return true
    }

    @Synchronized
    fun summary(): PageSummary {
        val e2e = samples.flatMap { it.e2eRaw }.map { it.toDouble() }
        fun delta(f: (StatsSample) -> Long): Long {
            if (samples.size < 2) return 0
            val first = f(samples.first())
            val last = f(samples.last())
            return if (last < first) last else last - first // a smaller value: the counter restarted (page reloaded)
        }
        return PageSummary(
            samples = samples.size,
            fpsAvg = Aggregate.avg(samples.map { it.fps }),
            fpsMin = Aggregate.min(samples.map { it.fps }),
            e2eAvg = Aggregate.avg(e2e),
            e2eP95 = Aggregate.p95(e2e),
            e2eMax = Aggregate.max(e2e),
            e2eFrames = e2e.size,
            rttAvg = Aggregate.avg(samples.map { it.rttMs }.filter { it > 0 }),
            lateMs = Aggregate.avg(samples.map { it.lateMs }),
            netLateMs = Aggregate.avg(samples.map { it.netLateMs }),
            skipped = delta { it.skipped },
            replaced = delta { it.replaced },
            dropped = delta { it.dropped },
            decoderResets = delta { it.decoderResets },
            reconnects = delta { it.reconnects },
            srvDropped = delta { it.srvDropped },
        )
    }

    companion object {
        const val STATS_INTERVAL_MS = 2000L
    }
}

/** What only the phone knows about one phase. */
data class PhoneSide(
    val mbps: Double,
    val keyframes: Long,
    val targetMin: Int?,
    val targetAvg: Double?,
    val encoderState: String,
    val stream: String,
    /** The mode actually used ("h264", "hevc", "vp9", "av1", "jpeg"), its codec string, the MediaCodec name and whether it is hardware. */
    val mode: String = "",
    val codec: String = "",
    val encoder: String = "",
    val hardware: Boolean? = null,
    /** Frames out of the encoder per second during the measured window (the phone side of the frame rate). */
    val encodeFps: Double = 0.0,
)

object PhaseStatus {
    const val OK = "ok"
    const val DISCONNECTED = "disconnected"
    const val NO_DATA = "no-data"
    const val UNSUPPORTED = "unsupported"

    /** Not run: the phone or a viewer cannot do what the phase asks (see [TestPlan.skipReason]). */
    const val SKIPPED = "skipped"
}

/** The measured result of one phase, after scoring. */
data class PhaseResult(
    val name: String,
    val description: String,
    val cfgId: String,
    val config: String,
    val status: String,
    val note: String,
    val attempts: Int,
    val expectedFps: Int,
    val page: PageSummary,
    val phone: PhoneSide,
    val score: Double? = null,
    val rank: Int? = null,
) {
    /** Only old reports (before 2026-11-18) have a `legacy` reference phase. */
    val reference: Boolean get() = name == "legacy"
}

/** Ranking of the phases: lower score is better. */
object Scoring {
    const val FPS_FRACTION = 0.8
    const val FPS_PENALTY_MAX_MS = 1000.0
    const val RECONNECT_PENALTY_MS = 2000.0
    const val DECODER_RESET_PENALTY_MS = 250.0
    const val DISCONNECT_PENALTY_MS = 5000.0

    /** A phase without any e2e measurement sorts after every phase that has one. */
    const val NO_E2E_BASE_MS = 1_000_000.0

    /** A phase without any page stats sorts last. */
    const val NO_DATA_SCORE = 1e9

    val FORMULA: String =
        "score = e2e P95 (ms) + fps penalty + 2000 per reconnect + 250 per decoder reset + 5000 if the viewer " +
            "disconnected. fps penalty = max(0, 0.8 x preset fps - average fps) / (0.8 x preset fps) x 1000. " +
            "Lower is better. Phases that were skipped or whose option the encoder could not apply are listed last without a rank."

    fun score(r: PhaseResult): Double {
        val p = r.page
        if (p.samples == 0) return NO_DATA_SCORE
        val base = p.e2eP95 ?: (NO_E2E_BASE_MS + (p.lateMs ?: 0.0))
        val wanted = FPS_FRACTION * r.expectedFps
        val fpsPenalty = if (wanted > 0) maxOf(0.0, wanted - (p.fpsAvg ?: 0.0)) / wanted * FPS_PENALTY_MAX_MS else 0.0
        val stall = p.reconnects * RECONNECT_PENALTY_MS + p.decoderResets * DECODER_RESET_PENALTY_MS +
            (if (r.status == PhaseStatus.DISCONNECTED) DISCONNECT_PENALTY_MS else 0.0)
        return base + fpsPenalty + stall
    }

    /** Scores every result and sorts them best first; ranks are 1.. for phases that applied (not "unsupported"). */
    fun rank(results: List<PhaseResult>): List<PhaseResult> {
        val scored = results.map { it.copy(score = score(it)) }
        val (applied, unsupported) = scored.partition { it.status != PhaseStatus.UNSUPPORTED && it.status != PhaseStatus.SKIPPED }
        val sorted = applied.sortedWith(compareBy({ it.score }, { it.page.e2eP95 ?: Double.MAX_VALUE }, { it.name }))
        val ranked = sorted.mapIndexed { i, r -> r.copy(rank = i + 1) }
        return ranked + unsupported.sortedBy { it.score }
    }
}

/** The whole run, for the report. */
data class TestRun(
    val runName: String,
    val startedAtMs: Long,
    val options: TestOptions,
    val preset: String,
    val device: String,
    val androidSdk: Int,
    val completed: Boolean,
    val phases: List<PhaseResult>,
)

/** JSON and CSV text of a [TestRun] (phases in the order given: best first). */
object ReportFormat {
    private fun num(d: Double?): String =
        if (d == null || d.isNaN() || d.isInfinite()) "null" else String.format(Locale.US, "%.2f", d)

    private fun csvNum(d: Double?): String =
        if (d == null || d.isNaN() || d.isInfinite()) "" else String.format(Locale.US, "%.2f", d)

    fun toJson(run: TestRun): String = buildString {
        append("{\n")
        append("  \"app\": \"WebMirror\",\n  \"report\": 1,\n")
        append("  \"runName\": ${JsonText.quote(run.runName)},\n")
        append("  \"startedAtMs\": ${run.startedAtMs},\n")
        append("  \"completed\": ${run.completed},\n")
        append("  \"phaseSeconds\": ${run.options.measureSeconds},\n")
        append("  \"settleSeconds\": ${run.options.settleSeconds},\n")
        append("  \"quick\": ${run.options.quick},\n")
        append("  \"useTestPattern\": ${run.options.useTestPattern},\n")
        append("  \"preset\": ${JsonText.quote(run.preset)},\n")
        append("  \"device\": ${JsonText.quote(run.device)},\n")
        append("  \"androidSdk\": ${run.androidSdk},\n")
        append("  \"scoreFormula\": ${JsonText.quote(Scoring.FORMULA)},\n")
        append("  \"phases\": [\n")
        run.phases.forEachIndexed { i, r ->
            val p = r.page
            val h = r.phone
            append("    {")
            append("\"rank\": ${r.rank ?: "null"}, \"name\": ${JsonText.quote(r.name)}, ")
            append("\"description\": ${JsonText.quote(r.description)}, \"cfgId\": ${JsonText.quote(r.cfgId)}, ")
            append("\"config\": ${JsonText.quote(r.config)}, \"status\": ${JsonText.quote(r.status)}, ")
            append("\"note\": ${JsonText.quote(r.note)}, \"attempts\": ${r.attempts}, \"expectedFps\": ${r.expectedFps}, ")
            append("\"score\": ${num(r.score)}, \"samples\": ${p.samples}, ")
            append("\"fpsAvg\": ${num(p.fpsAvg)}, \"fpsMin\": ${num(p.fpsMin)}, ")
            append("\"e2eAvg\": ${num(p.e2eAvg)}, \"e2eP95\": ${num(p.e2eP95)}, \"e2eMax\": ${num(p.e2eMax)}, \"e2eFrames\": ${p.e2eFrames}, ")
            append("\"rttAvg\": ${num(p.rttAvg)}, \"lateMs\": ${num(p.lateMs)}, \"netLateMs\": ${num(p.netLateMs)}, ")
            append("\"skipped\": ${p.skipped}, \"replaced\": ${p.replaced}, \"dropped\": ${p.dropped}, ")
            append("\"decoderResets\": ${p.decoderResets}, \"reconnects\": ${p.reconnects}, ")
            append("\"mbps\": ${num(h.mbps)}, \"srvDropped\": ${p.srvDropped}, \"keyframes\": ${h.keyframes}, ")
            append("\"targetMin\": ${h.targetMin ?: "null"}, \"targetAvg\": ${num(h.targetAvg)}, ")
            append("\"mode\": ${JsonText.quote(h.mode)}, \"codec\": ${JsonText.quote(h.codec)}, \"encoder\": ${JsonText.quote(h.encoder)}, ")
            append("\"hardware\": ${h.hardware ?: "null"}, \"encodeFps\": ${num(h.encodeFps)}, ")
            append("\"stream\": ${JsonText.quote(h.stream)}, \"encoderState\": ${JsonText.quote(h.encoderState)}")
            append("}")
            append(if (i < run.phases.size - 1) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    val CSV_HEADER = listOf(
        "rank", "phase", "score", "status", "e2eAvgMs", "e2eP95Ms", "e2eMaxMs", "fpsAvg", "fpsMin", "rttAvgMs",
        "lateMs", "netLateMs", "skipped", "replaced", "dropped", "decoderResets", "reconnects", "mbps",
        "srvDropped", "keyframes", "targetMin", "targetAvg", "samples", "attempts", "expectedFps", "mode", "codec", "encoder",
        "hardware", "encodeFps", "note",
    )

    private fun csvText(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"" else s

    fun toCsv(run: TestRun): String = buildString {
        append("# WebMirror automatic test, run ${csvText(run.runName)}, started ${run.startedAtMs}, preset ${run.preset}, ")
        append("completed ${run.completed}\n")
        append("# ${Scoring.FORMULA.replace("\n", " ")}\n")
        append(CSV_HEADER.joinToString(",")).append('\n')
        for (r in run.phases) {
            val p = r.page
            val h = r.phone
            append(
                listOf(
                    r.rank?.toString() ?: "", csvText(r.name), csvNum(r.score), r.status, csvNum(p.e2eAvg), csvNum(p.e2eP95),
                    csvNum(p.e2eMax), csvNum(p.fpsAvg), csvNum(p.fpsMin), csvNum(p.rttAvg), csvNum(p.lateMs),
                    csvNum(p.netLateMs), p.skipped.toString(), p.replaced.toString(), p.dropped.toString(),
                    p.decoderResets.toString(), p.reconnects.toString(), csvNum(h.mbps), p.srvDropped.toString(),
                    h.keyframes.toString(), h.targetMin?.toString() ?: "", csvNum(h.targetAvg), p.samples.toString(),
                    r.attempts.toString(), r.expectedFps.toString(), h.mode, csvText(h.codec), csvText(h.encoder),
                    h.hardware?.toString() ?: "", csvNum(h.encodeFps), csvText(r.note),
                ).joinToString(","),
            ).append('\n')
        }
    }

    /** File name stem: `20261117-143005-parked`. */
    fun fileStem(startedAtMs: Long, runName: String): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getDefault(), Locale.US)
        c.timeInMillis = startedAtMs
        return String.format(
            Locale.US, "%04d%02d%02d-%02d%02d%02d-%s",
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND),
            TestOptions.sanitizeName(runName),
        )
    }

    /** One line for the page: "Test finished — best: <phase>", or what happened if no phase has a rank. */
    fun summaryLine(run: TestRun): String {
        val best = run.phases.firstOrNull { it.rank == 1 }
        val head = if (run.completed) "Test finished" else "Test aborted"
        if (best == null) return "$head — no usable result"
        val p95 = best.page.e2eP95?.let { " (e2e P95 ${Math.round(it)} ms)" } ?: ""
        return "$head — best: ${best.name}$p95"
    }
}
