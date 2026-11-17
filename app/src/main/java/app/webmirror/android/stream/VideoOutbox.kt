package app.webmirror.android.stream

/**
 * Per-viewer video send state: flow control by acknowledgement. Pure logic, no threads and no Android
 * classes; the caller (ClientSession) serialises access.
 *
 * - At most [maxInFlight] frames are sent but not yet acknowledged by the player.
 * - While blocked, newer packets replace older ones: JPEG keeps only the newest waiting frame; H.264 cannot
 *   skip deltas, so when something has waited longer than one frame interval everything waiting is dropped
 *   and the viewer needs a keyframe ([needsKeyframe]); deltas are refused until one arrives.
 * - A new H.264 keyframe makes everything waiting before it useless, so it replaces the waiting packets too.
 * - Acknowledgements carry the pts of the newest frame the player has finished with (drawn or discarded);
 *   every in-flight frame up to that pts counts as acknowledged. If no ack arrives for [ACK_STALL_MS] while
 *   frames are in flight, the accounting is reset (lost acks, stuck page) so the stream cannot deadlock.
 */
class VideoOutbox(private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT) {

    private class Entry(val packet: Packet, val atMs: Long)

    private val pending = ArrayDeque<Entry>()
    private val inFlight = ArrayDeque<Long>()
    private var progressMs = 0L

    /** True while H.264 deltas are refused because the viewer must resume at a keyframe. */
    var needsKeyframe = false
        private set

    var dropped = 0L
        private set

    val inFlightCount: Int get() = inFlight.size
    val pendingCount: Int get() = pending.size

    /** Adds a packet. Returns true when the caller should ask the encoder for a keyframe. */
    fun offer(p: Packet, nowMs: Long, frameIntervalMs: Long): Boolean {
        var wantKeyframe = false
        val head = pending.firstOrNull()
        if (p.type == Packet.TYPE_H264 &&
            ((head != null && nowMs - head.atMs > frameIntervalMs) || pending.size >= MAX_PENDING)
        ) {
            dropPending()
            needsKeyframe = true
            wantKeyframe = true
        }
        if (p.type == Packet.TYPE_H264) {
            if (p.isKeyframe) {
                dropPending()
                needsKeyframe = false
            } else if (needsKeyframe) {
                dropped++
                return true
            }
        } else {
            dropPending() // JPEG: newest frame wins
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
            dropPending()
            needsKeyframe = true
            return true
        }
        return false
    }

    /** New stream description: forget everything waiting; an H.264 viewer resumes at the next keyframe. */
    fun reset(h264: Boolean) {
        dropPending()
        inFlight.clear()
        needsKeyframe = h264
    }

    private fun dropPending() {
        dropped += pending.size
        pending.clear()
    }

    companion object {
        /** Frames sent but not yet acknowledged before the server holds back. */
        const val DEFAULT_MAX_IN_FLIGHT = 3
        const val ACK_STALL_MS = 1500L
        const val MAX_PENDING = 60
    }
}
