package app.webmirror.android.stream

/**
 * Per-viewer video send state: flow control by acknowledgement. Pure logic, no threads and no Android
 * classes; the caller (ClientSession) serialises access.
 *
 * - At most [maxInFlight] frames are sent but not yet acknowledged by the player.
 * - While blocked, newer packets replace older ones: JPEG keeps only the newest waiting frame; H.264 cannot
 *   skip deltas, so when something has waited longer than [dropAfterMs] everything waiting is dropped
 *   and the viewer needs a keyframe ([needsKeyframe]); deltas are refused until one arrives.
 * - A new H.264 keyframe makes everything waiting before it useless, so it replaces the waiting packets too.
 * - Acknowledgements carry the pts of the newest frame the player has finished with (drawn or discarded);
 *   every in-flight frame up to that pts counts as acknowledged. If no ack arrives for [ACK_STALL_MS] while
 *   frames are in flight, the accounting is reset (lost acks, stuck page) so the stream cannot deadlock.
 */
class VideoOutbox(
    maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    dropAfterMs: Int = DEFAULT_DROP_AFTER_MS,
) {

    /** Frames sent but not yet acknowledged before [next] holds back. May be changed at any time. */
    var maxInFlight: Int = maxInFlight

    /** H.264: waiting packets are dropped only when the oldest has waited longer than this. */
    var dropAfterMs: Int = dropAfterMs


    private class Entry(val packet: Packet, val atMs: Long)

    private val pending = ArrayDeque<Entry>()
    private val inFlight = ArrayDeque<Long>()
    private var progressMs = 0L

    /** True while H.264 deltas are refused because the viewer must resume at a keyframe. */
    var needsKeyframe = false
        private set

    /** Why the viewer waits for a keyframe: a deliberate [reset] (not congestion) or a congestion drop. */
    private var waitIsReset = false

    /** Frames lost to congestion (a slow viewer). This is what the bitrate controller sees. */
    var dropped = 0L
        private set

    /**
     * Frames lost because of a deliberate reset (new viewer, new hello, encoder rebuild, mode switch):
     * waiting packets thrown away by [reset] and deltas refused while the viewer waits for its first
     * keyframe. Not congestion.
     */
    var resetDropped = 0L
        private set

    val inFlightCount: Int get() = inFlight.size
    val pendingCount: Int get() = pending.size

    /** How long the oldest waiting frame has waited, 0 when nothing waits. */
    fun oldestPendingWaitMs(nowMs: Long): Long = pending.firstOrNull()?.let { nowMs - it.atMs } ?: 0L

    /** Adds a packet. Returns true when the caller should ask the encoder for a keyframe. */
    fun offer(p: Packet, nowMs: Long): Boolean {
        var wantKeyframe = false
        val head = pending.firstOrNull()
        if (p.type == Packet.TYPE_H264 &&
            ((head != null && nowMs - head.atMs > dropAfterMs) || pending.size >= MAX_PENDING)
        ) {
            dropPending(false)
            needsKeyframe = true
            waitIsReset = false
            wantKeyframe = true
        }
        if (p.type == Packet.TYPE_H264) {
            if (p.isKeyframe) {
                dropPending(false)
                needsKeyframe = false
                waitIsReset = false
            } else if (needsKeyframe) {
                if (waitIsReset) resetDropped++ else dropped++
                return true
            }
        } else {
            dropPending(false) // JPEG: newest frame wins
        }
        pending.addLast(Entry(p, nowMs))
        return wantKeyframe
    }

    /** The next packet to send, or null when nothing waits or the in-flight limit is reached. */
    fun next(nowMs: Long): Packet? {
        if (pending.isEmpty() || inFlight.size >= maxInFlight) return null
        val e = pending.removeFirst()
        if (inFlight.isEmpty()) progressMs = nowMs
        inFlight.addLast(e.packet.ptsUs)
        return e.packet
    }

    /** The player is done with every frame up to [ptsUs]. */
    fun ack(ptsUs: Long, nowMs: Long) {
        while (inFlight.isNotEmpty() && inFlight.first() <= ptsUs) inFlight.removeFirst()
        progressMs = nowMs
    }

    /** Resets the accounting if acks stopped. Returns true when the caller should request a keyframe. */
    fun checkStall(nowMs: Long): Boolean {
        if (inFlight.isEmpty() || nowMs - progressMs <= ACK_STALL_MS) return false
        inFlight.clear()
        progressMs = nowMs
        if (pending.any { it.packet.type == Packet.TYPE_H264 }) {
            dropPending(false)
            needsKeyframe = true
            waitIsReset = false
            return true
        }
        return false
    }

    /** New stream description: forget everything waiting; an H.264 viewer resumes at the next keyframe. */
    fun reset(h264: Boolean) {
        dropPending(true)
        inFlight.clear()
        needsKeyframe = h264
        waitIsReset = h264
    }

    private fun dropPending(byReset: Boolean) {
        if (byReset) resetDropped += pending.size else dropped += pending.size
        pending.clear()
    }

    companion object {
        /** Frames sent but not yet acknowledged before the server holds back. */
        const val DEFAULT_MAX_IN_FLIGHT = 6
        const val DEFAULT_DROP_AFTER_MS = 150
        const val ACK_STALL_MS = 1500L
        const val MAX_PENDING = 60
    }
}
