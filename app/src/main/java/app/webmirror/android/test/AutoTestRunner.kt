package app.webmirror.android.test

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.webmirror.android.MirrorService
import app.webmirror.android.ServiceState
import app.webmirror.android.Settings
import app.webmirror.android.capture.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runs the automatic test on the phone: applies every [PhaseSpec] in turn through
 * [MirrorService.applyConfig], measures it from the viewer's stats, and writes the report. State is in
 * [state] (shown by the app and by the test pattern). Lives in the app process; mirroring runs in a
 * foreground service, so the process stays alive while the app is in the background.
 */
object AutoTestRunner {
    private const val TAG = "WebMirror"
    private const val POLL_MS = 250L
    private const val TARGET_SAMPLE_MS = 500L
    private const val STATS_GAP_MS = 6000L
    private const val SOCKET_RECONNECT_DELAY_MS = 300L
    private const val NOTICE_TTL_S = 15
    private const val FINAL_NOTICE_TTL_S = 900

    enum class Status { IDLE, WAITING, RUNNING, FINISHED, ABORTED }

    data class RunState(
        val status: Status = Status.IDLE,
        val runName: String = "",
        val phaseIndex: Int = 0,
        val phaseTotal: Int = 0,
        val phaseName: String = "",
        /** "settling", "measuring" or "". */
        val stage: String = "",
        val remainingS: Int = 0,
        val totalRemainingS: Int = 0,
        /** What is missing while [status] is WAITING (e.g. "no viewer connected"). */
        val missing: List<String> = emptyList(),
        val message: String = "",
        /** File stem (without extension) of the last saved report, or null. */
        val lastReport: String? = null,
    ) {
        val active: Boolean get() = status == Status.WAITING || status == Status.RUNNING
    }

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val lock = Any()
    private var accumulator: PhaseAccumulator? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts a run (ignored while one is running). [context] should be an activity: it opens the test pattern
     * from there, which Android only allows from the foreground app.
     */
    fun start(context: Context, options: TestOptions) {
        if (isRunning) return
        val app = context.applicationContext
        _state.value = RunState(status = Status.WAITING, runName = options.safeName, message = "Starting")
        job = scope.launch {
            try {
                run(app, context, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "automatic test failed", e)
                _state.value = _state.value.copy(status = Status.ABORTED, message = "Test failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Stops the run; the owner's configuration is restored and a partial report is saved if anything was measured. */
    fun abort() {
        job?.cancel()
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    private fun preconditions(st: ServiceState): List<String> {
        val missing = ArrayList<String>()
        if (!st.running) missing.add("Mirroring is not running: tap Start mirroring")
        else if (st.paused) missing.add("Mirroring is paused (screen capture ended): tap Start mirroring (resume)")
        if (st.viewerCount < 1) missing.add("No viewer connected: open the player page in the car")
        return missing
    }

    private fun parseStats(json: String): StatsSample? = try {
        val o = JSONObject(json)
        val raw = ArrayList<Int>()
        o.optJSONArray("e2eRaw")?.let { a -> for (i in 0 until a.length()) raw.add(a.optInt(i)) }
        fun d(key: String): Double = o.optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it }
        StatsSample(
            vid = o.optInt("vid", -1),
            cfgId = o.optString("cfgId", ""),
            fps = d("fps"),
            e2eRaw = raw,
            rttMs = d("rttMs"),
            lateMs = d("lateMs"),
            netLateMs = d("netLateMs"),
            skipped = o.optLong("skipped", 0),
            replaced = o.optLong("replaced", 0),
            dropped = o.optLong("dropped", 0),
            decoderResets = o.optLong("decoderResets", 0),
            reconnects = o.optLong("reconnects", 0),
            srvDropped = o.optLong("srvDropped", 0),
        )
    } catch (e: Exception) {
        null
    }

    private fun onStats(json: String) {
        val s = parseStats(json) ?: return
        val arrived = now()
        synchronized(lock) { accumulator?.offer(arrived, s) }
    }

    private suspend fun run(app: Context, launcher: Context, options: TestOptions) {
        val settings = Settings(app)
        val originalConfig = settings.streamConfig
        val originalPreset: Preset = settings.preset
        val originalJpeg = settings.forceJpeg
        val startedAt = System.currentTimeMillis()
        val plan = TestPlan.phases(originalConfig)
        val results = ArrayList<PhaseResult>()
        val perPhaseS = options.settleSeconds + options.measureSeconds
        var finished = false
        MirrorService.statsListener = { onStats(it) }
        try {
            if (options.useTestPattern) {
                try {
                    launcher.startActivity(Intent(launcher, TestPatternActivity::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "cannot open the test pattern: $e")
                }
            }
            for ((i, spec) in plan.withIndex()) {
                val hub = MirrorService.hubRef
                val skip = if (hub == null) null else TestPlan.skipReason(spec, hub.phone, hub.viewerCaps())
                if (skip != null) {
                    Log.i(TAG, "phase ${spec.name}: $skip")
                    results.add(skippedResult(spec, skip))
                    continue
                }
                var attempt = 1
                var result: PhaseResult
                while (true) {
                    awaitPreconditions(options, i, plan.size, spec.name)
                    result = runPhase(app, settings, options, spec, i, plan.size, perPhaseS, attempt, originalPreset)
                    if (result.status == PhaseStatus.DISCONNECTED && attempt == 1) {
                        attempt++
                        Log.i(TAG, "phase ${spec.name}: viewer disconnected, repeating once")
                        continue
                    }
                    break
                }
                results.add(result)
            }
            finished = true
        } finally {
            MirrorService.statsListener = null
            synchronized(lock) { accumulator = null }
            // Restore what the owner had, even when aborted (the coroutine may be cancelled here).
            withContext(NonCancellable) {
                settings.forceJpeg = originalJpeg
                settings.preset = originalPreset
                MirrorService.applyConfig(app, originalConfig)
                var saved: String? = null
                var summary = if (finished) "Test finished" else "Test aborted"
                if (results.isNotEmpty()) {
                    val run = TestRun(
                        runName = options.safeName, startedAtMs = startedAt, options = options, preset = originalPreset.key,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}", androidSdk = Build.VERSION.SDK_INT,
                        completed = finished, phases = Scoring.rank(results),
                    )
                    saved = withContext(Dispatchers.IO) {
                        try {
                            TestReports.save(app, run)
                        } catch (e: Exception) {
                            Log.e(TAG, "cannot save the report", e)
                            null
                        }
                    }
                    summary = ReportFormat.summaryLine(run)
                }
                MirrorService.notifyViewers(summary, FINAL_NOTICE_TTL_S)
                _state.value = RunState(
                    status = if (finished) Status.FINISHED else Status.ABORTED, runName = options.safeName,
                    phaseTotal = plan.size, message = summary, lastReport = saved,
                )
                TestPatternActivity.close()
                Log.i(TAG, "automatic test ended: $summary (report $saved)")
            }
        }
    }

    private fun skippedResult(spec: PhaseSpec, reason: String) = PhaseResult(
        name = spec.name, description = spec.description, cfgId = spec.config.id, config = spec.config.summary(),
        status = PhaseStatus.SKIPPED, note = reason, attempts = 0, expectedFps = 0,
        page = PageSummary(0, null, null, null, null, null, 0, null, null, null, 0, 0, 0, 0, 0, 0),
        phone = PhoneSide(0.0, 0, null, null, "", ""),
    )

    /** Waits (without a time limit) until mirroring runs and a viewer is connected; shows what is missing. */
    private suspend fun awaitPreconditions(options: TestOptions, index: Int, total: Int, phase: String) {
        while (true) {
            val missing = preconditions(MirrorService.state.value)
            if (missing.isEmpty()) return
            _state.value = _state.value.copy(
                status = Status.WAITING, phaseIndex = index + 1, phaseTotal = total, phaseName = phase, stage = "",
                missing = missing, message = "Waiting", runName = options.safeName,
            )
            delay(500)
        }
    }

    private suspend fun runPhase(
        app: Context, settings: Settings, options: TestOptions, spec: PhaseSpec, index: Int, total: Int,
        perPhaseS: Int, attempt: Int, basePreset: Preset,
    ): PhaseResult {
        val hub = MirrorService.hubRef
        val socketChange = settings.streamConfig.diff(spec.config).socket
        settings.forceJpeg = spec.forceJpeg
        settings.preset = spec.preset ?: basePreset // applied together with the config by applyConfig below
        MirrorService.applyConfig(app, spec.config)
        if (socketChange) {
            // Socket options only apply to new connections: reconnect the viewer so the phase really uses them.
            delay(SOCKET_RECONNECT_DELAY_MS)
            hub?.disconnectAll()
        }
        val startMs = now()
        val settleEnd = startMs + options.settleSeconds * 1000L
        val endMs = settleEnd + options.measureSeconds * 1000L
        val acc = PhaseAccumulator(spec.config.id, settleEnd, endMs)
        synchronized(lock) { accumulator = acc }
        val label = "phase ${index + 1}/$total ${spec.name}"
        MirrorService.notifyViewers("Automatic test: $label", NOTICE_TTL_S)
        Log.i(TAG, "$label started (config ${spec.config.id}${if (attempt > 1) ", attempt $attempt" else ""})")

        val metrics = hub?.metrics
        var bytes0 = -1L
        var kf0 = 0L
        var frames0 = 0L
        var t0 = 0L
        var lastTargetMs = 0L
        val targets = ArrayList<Int>()
        var disconnected = false
        while (true) {
            val t = now()
            if (t >= endMs) break
            val st = MirrorService.state.value
            val settling = t < settleEnd
            if (!settling) {
                if (bytes0 < 0 && metrics != null) {
                    bytes0 = metrics.bytesSent.get()
                    kf0 = metrics.keyframeRequests.get()
                    frames0 = metrics.encodedFrames.get()
                    t0 = t
                }
                if (t - lastTargetMs >= TARGET_SAMPLE_MS) {
                    lastTargetMs = t
                    Aggregate.parseTarget(hub?.targetText)?.let { targets.add(it) }
                }
                if (preconditions(st).isNotEmpty()) disconnected = true
                if (acc.vid != null && t - acc.lastAcceptedMs > STATS_GAP_MS) disconnected = true
            }
            val remaining = ((if (settling) settleEnd else endMs) - t + 999) / 1000
            val left = (endMs - t + 999) / 1000 + (total - index - 1).toLong() * perPhaseS
            _state.value = RunState(
                status = Status.RUNNING, runName = options.safeName, phaseIndex = index + 1, phaseTotal = total,
                phaseName = spec.name, stage = if (settling) "settling" else "measuring", remainingS = remaining.toInt(),
                totalRemainingS = left.toInt(), message = spec.description, lastReport = _state.value.lastReport,
            )
            delay(POLL_MS)
        }
        val endNow = now()
        val st = MirrorService.state.value
        val page = acc.summary()
        if (page.reconnects > 0) disconnected = true
        val seconds = if (bytes0 >= 0) maxOf(0.001, (endNow - t0) / 1000.0) else 0.0
        val mbps = if (bytes0 >= 0 && metrics != null) (metrics.bytesSent.get() - bytes0) * 8.0 / 1e6 / seconds else 0.0
        val encodeFps = if (bytes0 >= 0 && metrics != null) (metrics.encodedFrames.get() - frames0) / seconds else 0.0
        val keyframes = if (bytes0 >= 0 && metrics != null) metrics.keyframeRequests.get() - kf0 else 0L
        val encoder = st.encoder.orEmpty()
        val streamText = st.stream.orEmpty()
        val jpegMode = Aggregate.isJpegStream(streamText)
        val video = st.video
        val unsupported = TestPlan.unsupportedNote(spec, encoder, jpegMode, video?.mode)
        val preset = settings.preset
        // The preset's rate counts even after an automatic fallback to 30 fps: a fallback is a poor result, not a lower target.
        val fellBack = Aggregate.isFallbackStream(streamText)
        val expectedFps = if (fellBack) preset.fps else Aggregate.parseStreamFps(streamText) ?: if (jpegMode) preset.jpegFps else preset.fps
        val status = when {
            unsupported != null -> PhaseStatus.UNSUPPORTED
            disconnected -> PhaseStatus.DISCONNECTED
            page.samples == 0 -> PhaseStatus.NO_DATA
            else -> PhaseStatus.OK
        }
        val notes = ArrayList<String>()
        if (unsupported != null) notes.add(unsupported)
        if (disconnected) notes.add(if (page.reconnects > 0) "the viewer reconnected during the measurement" else "the viewer disconnected or stopped reporting")
        if (video?.hardware == false && video.mode != "jpeg") notes.add("software encoder (${video.encoder})")
        if (fellBack) notes.add("fell back to ${Preset.FALLBACK_FPS} fps (the viewer could not keep up at ${preset.fps})")
        if (attempt > 1) notes.add("repeated after a disconnect")
        if (page.samples == 0) notes.add("no stats for this configuration arrived in the measured window")
        if (page.samples > 0 && page.e2eFrames == 0) notes.add("no e2e measurement (clock offset unknown)")
        Log.i(TAG, "$label done: $status, ${page.samples} stats, e2e p95 ${page.e2eP95}, fps ${page.fpsAvg}")
        return PhaseResult(
            name = spec.name, description = spec.description, cfgId = spec.config.id, config = spec.config.summary() +
                (if (spec.forceJpeg) " | force JPEG" else "") + (if (spec.preset != null) " | preset ${spec.preset.key}" else ""),
            status = status, note = notes.joinToString("; "), attempts = attempt, expectedFps = expectedFps,
            page = page,
            phone = PhoneSide(
                mbps = mbps, keyframes = keyframes, targetMin = targets.minOrNull(),
                targetAvg = if (targets.isEmpty()) null else targets.average(), encoderState = encoder, stream = streamText,
                mode = video?.mode.orEmpty(), codec = video?.codec.orEmpty(), encoder = video?.encoder.orEmpty(),
                hardware = video?.hardware, encodeFps = encodeFps,
            ),
        )
    }
}
