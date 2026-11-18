package app.webmirror.android.stream

/**
 * Automatic fallback from 60 to 30 fps (preset "Smoothest") when the viewer cannot keep up. Pure logic, no threads and no
 * Android classes; the caller (`ScreenCapture`) calls [onWindow] about every [WINDOW_MS] on one thread.
 *
 * - Only active when [configure] got a wanted rate above [FALLBACK_FPS] (the Smoothest preset) and at least one viewer is connected.
 * - A window counts only while the screen is actually changing: the encoder delivered at least [ACTIVE_MIN_FPS] frames per second in it
 *   (a static screen yields about 10 fps whatever the viewer does, so it says nothing; such a window also breaks the streak).
 * - A window is bad when, for any viewer with fresh stats, it drew fewer than [MIN_DRAWN_FPS] frames per second, or its decoder queue is
 *   at least [DECODE_QUEUE_BAD], or at least [LOSS_FRACTION] of its frames were dropped or skipped (page and outbox congestion drops).
 *   [BAD_WINDOWS] bad windows in a row (about 6 s) make the rate [FALLBACK_FPS].
 * - After a fallback the rate is retried after [RETRY_BASE_MS]. If the retry fails again within [PROVE_MS] of the retry, the wait doubles
 *   (up to [RETRY_MAX_MS]); a retry that lasted longer than that resets the wait.
 * - A different set of viewers, a preset change ([configure]) and an encoder rebuild ([holdOff]) reset the state or give a short
 *   grace period in which windows are not judged (new encoder, new keyframe, new connection).
 */
class FpsGovernor {

    /** What the server knows about one viewer at the time of a window. [lostTotal] is cumulative (page drops and skips plus outbox drops). */
    data class Viewer(
        val id: Int,
        /** The page's last reported rate of frames drawn per second; only meaningful when [fresh]. */
        val drawnFps: Double,
        val decodeQueue: Int,
        val lostTotal: Long,
        /** The viewer's last stats are recent enough to judge. */
        val fresh: Boolean,
    )

    /** A change of the effective rate, with the reason for the log. */
    data class Change(val fps: Int, val reason: String)

    private var wanted = 0
    private var fallbackActive = false
    private var lastWindowMs = NEVER
    private var lastEncoded = 0L
    private var lastIds: Set<Int> = emptySet()
    private val lostBase = HashMap<Int, Long>()
    private var badWindows = 0
    private var holdUntilMs = NEVER
    private var retryAtMs = NEVER
    private var retriedAtMs = NEVER
    private var waitMs = RETRY_BASE_MS

    /** True when [configure] set a rate that can fall back. */
    val enabled: Boolean get() = wanted > FALLBACK_FPS

    /** True while the rate is capped at [FALLBACK_FPS] because the viewer could not keep up. */
    val inFallback: Boolean get() = fallbackActive

    /** The rate the encoder should run at now. 0 before [configure]. */
    val effectiveFps: Int get() = if (fallbackActive) FALLBACK_FPS else wanted

    /** The wait before the next retry (visible for logs and tests). */
    val currentWaitMs: Long get() = waitMs

    /** When the 60 fps retry is due, or -1 when not in fallback. */
    val nextRetryAtMs: Long get() = if (fallbackActive) retryAtMs else -1L

    /** A new wanted rate (preset change or start): everything is forgotten, no fallback. */
    fun configure(wantedFps: Int, nowMs: Long) {
        wanted = wantedFps
        resetAll(nowMs)
    }

    /** The encoder was rebuilt or the mode switched: no window is judged for [GRACE_MS]. */
    fun holdOff(nowMs: Long) {
        holdUntilMs = nowMs + GRACE_MS
        badWindows = 0
    }

    private fun resetAll(nowMs: Long) {
        fallbackActive = false
        badWindows = 0
        retryAtMs = NEVER
        retriedAtMs = NEVER
        waitMs = RETRY_BASE_MS
        lostBase.clear()
        lastIds = emptySet()
        lastWindowMs = NEVER
        holdUntilMs = nowMs + GRACE_MS
    }

    /**
     * One evaluation window ending at [nowMs]. [encodedTotal] is the cumulative number of frames the encoder delivered; [viewers] every
     * connected viewer. Returns the new effective rate when it changed.
     */
    fun onWindow(nowMs: Long, encodedTotal: Long, viewers: List<Viewer>): Change? {
        if (!enabled) return null
        val windowMs = if (lastWindowMs == NEVER) 0L else nowMs - lastWindowMs
        val encodedFps = if (windowMs > 0) (encodedTotal - lastEncoded) * 1000.0 / windowMs else 0.0
        val firstWindow = lastWindowMs == NEVER
        lastWindowMs = nowMs
        lastEncoded = encodedTotal

        // A different set of viewers is a new situation: start over (a viewer that could not keep up may be gone).
        val ids = viewers.map { it.id }.toSet()
        if (ids != lastIds) {
            val wasFallback = fallbackActive
            resetAll(nowMs)
            lastIds = ids
            lastWindowMs = nowMs
            for (v in viewers) lostBase[v.id] = v.lostTotal
            return if (wasFallback) Change(wanted, "the set of viewers changed") else null
        }
        // Loss since the last window, per viewer; the baselines advance every window.
        val lostNow = HashMap<Int, Long>()
        for (v in viewers) {
            lostNow[v.id] = maxOf(0L, v.lostTotal - (lostBase[v.id] ?: v.lostTotal))
            lostBase[v.id] = v.lostTotal
        }
        if (viewers.isEmpty() || firstWindow) return null

        if (fallbackActive) {
            if (nowMs < retryAtMs) return null
            fallbackActive = false
            retriedAtMs = nowMs
            badWindows = 0
            holdUntilMs = nowMs + GRACE_MS
            return Change(wanted, "retrying $wanted fps after ${waitMs / 1000} s")
        }
        if (nowMs < holdUntilMs) return null
        if (encodedFps < ACTIVE_MIN_FPS) { // static screen (or nothing drawn): not a verdict
            badWindows = 0
            return null
        }
        val fresh = viewers.filter { it.fresh }
        if (fresh.isEmpty()) return null // no data: neither better nor worse

        val windowS = windowMs / 1000.0
        var why: String? = null
        for (v in fresh) {
            val lost = lostNow.getValue(v.id)
            val frames = v.drawnFps * windowS + lost
            when {
                v.drawnFps < MIN_DRAWN_FPS -> why = "viewer ${v.id} draws ${"%.1f".format(v.drawnFps)} fps"
                v.decodeQueue >= DECODE_QUEUE_BAD -> why = "viewer ${v.id} decode queue ${v.decodeQueue}"
                frames > 0 && lost / frames >= LOSS_FRACTION ->
                    why = "viewer ${v.id} lost $lost of ${frames.toLong()} frames"
            }
            if (why != null) break
        }
        if (why == null) {
            badWindows = 0
            return null
        }
        badWindows++
        if (badWindows < BAD_WINDOWS) return null

        // Fall back. A retry that failed soon after it started doubles the wait.
        waitMs = if (retriedAtMs != NEVER && nowMs - retriedAtMs < PROVE_MS) minOf(waitMs * 2, RETRY_MAX_MS) else RETRY_BASE_MS
        fallbackActive = true
        retryAtMs = nowMs + waitMs
        badWindows = 0
        return Change(FALLBACK_FPS, "$why for $BAD_WINDOWS windows (encoder ${"%.0f".format(encodedFps)} fps); retry $wanted fps in ${waitMs / 1000} s")
    }

    companion object {
        /** The rate used after a fallback. */
        const val FALLBACK_FPS = 30

        /** How often the caller evaluates a window. */
        const val WINDOW_MS = 2000L

        /** The encoder must deliver at least this many frames per second in a window for it to count (screen is changing). */
        const val ACTIVE_MIN_FPS = 45.0

        /** The slowest viewer must draw at least this many frames per second. */
        const val MIN_DRAWN_FPS = 45.0

        /** A decoder queue of at least this many chunks is bad. */
        const val DECODE_QUEUE_BAD = 4

        /** Fraction of dropped or skipped frames in a window that is bad. */
        const val LOSS_FRACTION = 0.05

        /** Consecutive bad windows (about 6 s) before falling back. */
        const val BAD_WINDOWS = 3

        const val RETRY_BASE_MS = 60_000L
        const val RETRY_MAX_MS = 600_000L

        /** A retry that holds this long without a new fallback counts as a success: the wait goes back to [RETRY_BASE_MS]. */
        const val PROVE_MS = 60_000L

        /** After a start, a reset, a viewer change, a retry or an encoder rebuild, windows are not judged for this long. */
        const val GRACE_MS = 4000L

        /** A viewer's stats older than this are not judged. */
        const val STATS_FRESH_MS = 3500L

        private const val NEVER = -1_000_000_000L
    }
}
