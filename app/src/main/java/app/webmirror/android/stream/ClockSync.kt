package app.webmirror.android.stream

/**
 * Clock offset between the phone and one page (NTP style, plan 2.1 `ping.ts` / `pong` / `ping.off`).
 * Pure logic. All phone times are microseconds of the phone's monotonic clock (`System.nanoTime() / 1000`,
 * the clock of the packet pts); page times are `performance.now()` in milliseconds.
 *
 * A sample is one ping/pong round trip: the phone sent at [Sample] `sendUs`, got the pong at `recvUs`; the page
 * read its clock `pageMs` when it answered. Assuming the answer was made half a round trip after sending, the
 * page clock minus the phone clock is `pageMs * 1000 - (sendUs + rtt / 2)`. The sample with the smallest
 * round trip in the last [windowMs] is the least disturbed by queueing, so it supplies the offset.
 */
class ClockSync(private val windowMs: Long = WINDOW_MS) {

    data class Sample(val atMs: Long, val rttUs: Long, val offsetUs: Long)

    private val samples = ArrayList<Sample>()
    private var lastRtt = -1L

    /** Round trip of the most recent sample in microseconds, or -1 when there was none. */
    @get:Synchronized
    val lastRttUs: Long get() = lastRtt

    /**
     * Adds one measurement taken at [nowMs] (any monotonic millisecond clock). Returns the sample, or null
     * when the numbers are impossible (negative or absurdly long round trip).
     */
    @Synchronized
    fun add(nowMs: Long, sendUs: Long, recvUs: Long, pageMs: Double): Sample? {
        val rtt = recvUs - sendUs
        if (rtt < 0 || rtt > MAX_RTT_US || pageMs.isNaN() || pageMs.isInfinite()) return null
        val offset = Math.round(pageMs * 1000.0) - (sendUs + rtt / 2)
        val s = Sample(nowMs, rtt, offset)
        samples.add(s)
        lastRtt = rtt
        prune(nowMs)
        return s
    }

    /** The sample with the smallest round trip within the window (the newest one among equals), or null. */
    @Synchronized
    fun best(nowMs: Long): Sample? {
        prune(nowMs)
        var best: Sample? = null
        for (s in samples) {
            val b = best
            if (b == null || s.rttUs <= b.rttUs) best = s
        }
        return best
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples[0].atMs > windowMs) samples.removeAt(0)
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val MAX_RTT_US = 10_000_000L
    }
}
