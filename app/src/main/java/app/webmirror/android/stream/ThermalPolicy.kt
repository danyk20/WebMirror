package app.webmirror.android.stream

/**
 * Heat protection: maps the phone's thermal status (and, on Android 11+, the thermal headroom forecast) to a limit on frame rate and
 * bitrate. Pure logic, no Android classes (status values are the `PowerManager.THERMAL_STATUS_*` integers); the caller feeds it the
 * latest readings every few hundred ms.
 *
 * - Level 0: NONE or LIGHT, and a headroom below [HEADROOM_WARN] or unavailable: no limit.
 * - Level 1: MODERATE, or a headroom of at least [HEADROOM_WARN] (early warning): at most [HOT_FPS] fps.
 * - Level 2: SEVERE: at most [HOT_FPS] fps and [SEVERE_BITRATE] bit/s.
 * - Level 3: CRITICAL and above: at most [CRITICAL_FPS] fps and [CRITICAL_BITRATE] bit/s.
 * - Hysteresis: a higher level applies immediately; a lower one only after the readings have stayed below the current level for
 *   [STEP_UP_MS] without interruption, and then it applies the highest level the readings showed in that time.
 */
class ThermalPolicy {

    /** A limit; [NONE] limits nothing. */
    data class Limit(val level: Int, val maxFps: Int, val maxBitrate: Int) {
        val active: Boolean get() = level > 0

        /** The frame rate to use when [fps] is wanted. */
        fun capFps(fps: Int): Int = minOf(fps, maxFps)

        /** The bitrate ceiling to use when [ceiling] is the existing one. */
        fun capBitrate(ceiling: Int): Int = minOf(ceiling, maxBitrate)
    }

    /** A new limit, with the reason for the log. */
    data class Change(val limit: Limit, val reason: String)

    var limit: Limit = NONE
        private set

    private var lowerSinceMs = NEVER
    private var lowerMaxLevel = 0

    /**
     * Feeds one reading at [nowMs]: [status] is `PowerManager.currentThermalStatus`, [headroom] the forecast from
     * `getThermalHeadroom(10)` (null or NaN when unavailable). Returns the new limit when it changed.
     */
    fun update(status: Int, headroom: Float?, nowMs: Long): Change? {
        val target = levelFor(status, headroom)
        val current = limit.level
        if (target >= current) {
            lowerSinceMs = NEVER
            lowerMaxLevel = 0
            if (target == current) return null
            return set(target, "status ${statusName(status)}${headroomText(headroom)}")
        }
        if (lowerSinceMs == NEVER) {
            lowerSinceMs = nowMs
            lowerMaxLevel = target
        } else {
            lowerMaxLevel = maxOf(lowerMaxLevel, target)
        }
        if (nowMs - lowerSinceMs < STEP_UP_MS) return null
        val next = lowerMaxLevel
        lowerSinceMs = NEVER
        lowerMaxLevel = 0
        return set(next, "cooled to ${statusName(status)}${headroomText(headroom)} for ${STEP_UP_MS / 1000} s")
    }

    private fun set(level: Int, why: String): Change {
        limit = limitFor(level)
        return Change(limit, "$why: ${describe(limit)}")
    }

    companion object {
        /** `PowerManager.THERMAL_STATUS_*`. */
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4

        const val HEADROOM_WARN = 0.85f
        const val HOT_FPS = 30
        const val CRITICAL_FPS = 15
        const val SEVERE_BITRATE = 2_500_000
        const val CRITICAL_BITRATE = 1_500_000
        const val STEP_UP_MS = 60_000L

        private const val NEVER = -1_000_000_000L

        val NONE = Limit(0, Int.MAX_VALUE, Int.MAX_VALUE)

        fun limitFor(level: Int): Limit = when {
            level <= 0 -> NONE
            level == 1 -> Limit(1, HOT_FPS, Int.MAX_VALUE)
            level == 2 -> Limit(2, HOT_FPS, SEVERE_BITRATE)
            else -> Limit(3, CRITICAL_FPS, CRITICAL_BITRATE)
        }

        /** The level the readings ask for, without hysteresis. */
        fun levelFor(status: Int, headroom: Float?): Int = when {
            status >= STATUS_CRITICAL -> 3
            status == STATUS_SEVERE -> 2
            status == STATUS_MODERATE -> 1
            headroom != null && !headroom.isNaN() && headroom >= HEADROOM_WARN -> 1
            else -> 0
        }

        fun statusName(status: Int): String = when (status) {
            0 -> "none"
            1 -> "light"
            2 -> "moderate"
            3 -> "severe"
            4 -> "critical"
            5 -> "emergency"
            6 -> "shutdown"
            else -> "unknown($status)"
        }

        private fun headroomText(h: Float?): String = if (h == null || h.isNaN()) "" else ", headroom ${"%.2f".format(h)}"

        private fun describe(l: Limit): String =
            if (!l.active) "no limit" else "max ${l.maxFps} fps" + if (l.maxBitrate < Int.MAX_VALUE) ", max ${l.maxBitrate / 1000} kbit/s" else ""
    }
}
