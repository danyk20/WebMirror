package app.webmirror.android.stream

/**
 * Adaptive bitrate controller (plan 2.1.1 `adaptiveBitrate`). Pure logic: the caller feeds it one
 * [ViewerSignal] per viewer every [TICK_MS] and applies the returned value. The unit of [value] is whatever
 * the caller adjusts: bit/s for H.264/HEVC, JPEG quality points for JPEG.
 *
 * - Congestion (any viewer) is only real evidence: the outbox dropped frames (congestion drops, not resets)
 *   or the page skipped late frames since the last tick, the oldest waiting frame waited longer than
 *   `dropAfterMs`, or the page's decoder queue is above [DECODE_QUEUE_LIMIT]. Lateness alone is not evidence.
 * - The target is cut only after congestion in [CUT_AFTER_TICKS] consecutive ticks; the cut multiplies it by
 *   [DECREASE_FACTOR], never below [floor].
 * - After [CALM_MS] without congestion the value rises by [INCREASE_FACTOR] (at least one unit), never above
 *   [ceiling], then waits another [CALM_MS] for the next step (800 to 4000 kbit/s takes about 16 s).
 * - Grace: for [GRACE_MS] after [configure] or [holdOff] (new encoder, config applied, new viewer) all signals
 *   are ignored (only the baselines advance): a deliberate reset is not congestion.
 * - Hysteresis: after a decrease no further decrease happens for [SETTLE_MS], and nothing changes more than
 *   once per [TICK_MS].
 */
class BitrateController(floor: Int, ceiling: Int) {

    /** What the server knows about one viewer at one moment. Totals are cumulative. */
    data class ViewerSignal(
        val id: Int,
        /** How long the oldest frame still waiting in the outbox has waited, 0 if none waits. */
        val oldestWaitMs: Long,
        val dropped: Long,
        /** Frames the page reported as skipped (cumulative, from its `stats`). */
        val skipped: Long,
        /** The page's last reported decoder queue size (0 when unknown or stale). */
        val decodeQueue: Int,
    )

    var floor: Int = minOf(floor, ceiling)
        private set
    var ceiling: Int = ceiling
        private set

    /** The current target. */
    var value: Int = ceiling
        private set

    private var calmSinceMs = 0L
    private var lastChangeMs = NEVER
    private var lastDecreaseMs = NEVER
    private var badStreak = 0
    private var holdUntilMs = NEVER
    private val baselines = HashMap<Int, LongArray>()

    /** New limits (preset, mode or config change): the target goes back to the ceiling. */
    fun configure(floor: Int, ceiling: Int, nowMs: Long = NEVER) {
        this.ceiling = ceiling
        this.floor = minOf(floor, ceiling)
        value = ceiling
        calmSinceMs = 0
        lastChangeMs = NEVER
        lastDecreaseMs = NEVER
        badStreak = 0
        baselines.clear()
        if (nowMs != NEVER) holdOff(nowMs)
    }

    /** Ignores every signal for [GRACE_MS] from [nowMs] (a reset happened: keyframe waits are no congestion). */
    fun holdOff(nowMs: Long) {
        holdUntilMs = nowMs + GRACE_MS
        calmSinceMs = nowMs
        badStreak = 0
    }

    /** True if any of [signals] shows congestion. Also advances the per-viewer baselines. */
    private fun congested(signals: List<ViewerSignal>, dropAfterMs: Int): Boolean {
        var result = false
        val seen = HashSet<Int>()
        for (s in signals) {
            seen.add(s.id)
            val base = baselines[s.id]
            if (base == null) {
                baselines[s.id] = longArrayOf(s.dropped, s.skipped) // first sight: no history to compare
            } else {
                if (s.dropped > base[0] || s.skipped > base[1]) result = true
                base[0] = s.dropped
                base[1] = s.skipped
            }
            if (s.oldestWaitMs > dropAfterMs) result = true
            if (s.decodeQueue > DECODE_QUEUE_LIMIT) result = true
        }
        baselines.keys.retainAll(seen)
        return result
    }

    /** Evaluates one tick at [nowMs]; returns the new target when it changed, else null. */
    fun tick(nowMs: Long, signals: List<ViewerSignal>, dropAfterMs: Int): Int? {
        val bad = congested(signals, dropAfterMs) && nowMs >= holdUntilMs
        if (bad) {
            badStreak++
            calmSinceMs = nowMs
        } else {
            badStreak = 0
        }
        if (nowMs - lastChangeMs < TICK_MS) return null
        if (bad) {
            if (badStreak >= CUT_AFTER_TICKS && value > floor && nowMs - lastDecreaseMs >= SETTLE_MS) {
                value = maxOf(floor, minOf(value - 1, Math.round(value * DECREASE_FACTOR).toInt()))
                lastChangeMs = nowMs
                lastDecreaseMs = nowMs
                badStreak = 0
                return value
            }
            return null
        }
        if (value < ceiling && nowMs - calmSinceMs >= CALM_MS) {
            value = minOf(ceiling, maxOf(value + 1, Math.round(value * INCREASE_FACTOR).toInt()))
            lastChangeMs = nowMs
            calmSinceMs = nowMs
            return value
        }
        return null
    }

    companion object {
        const val TICK_MS = 500L
        const val CALM_MS = 2000L
        const val SETTLE_MS = 1000L
        const val GRACE_MS = 2000L
        const val DECODE_QUEUE_LIMIT = 2
        const val CUT_AFTER_TICKS = 2
        const val DECREASE_FACTOR = 0.75
        const val INCREASE_FACTOR = 1.25

        /** JPEG quality never goes below this. */
        const val MIN_JPEG_QUALITY = 30

        private const val NEVER = -1_000_000_000L
    }
}
