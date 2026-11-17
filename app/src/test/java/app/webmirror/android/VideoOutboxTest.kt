package app.webmirror.android

import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.VideoOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutboxTest {

    private fun jpeg(pts: Long) = Packet(Packet.TYPE_JPEG, 0, pts, ByteArray(1))
    private fun key(pts: Long) = Packet(Packet.TYPE_H264, Packet.FLAG_KEYFRAME, pts, ByteArray(1))
    private fun delta(pts: Long) = Packet(Packet.TYPE_H264, 0, pts, ByteArray(1))

    @Test
    fun sendsUpToTheLimitThenHoldsBack() {
        val o = VideoOutbox(3)
        // JPEG replaces older waiting frames, so feed one at a time and send it.
        for (i in 1..3L) {
            o.offer(jpeg(i), 0)
            assertEquals(i, o.next(0)!!.ptsUs)
        }
        assertEquals(3, o.inFlightCount)
        o.offer(jpeg(4), 0)
        assertNull(o.next(0))
        o.ack(1, 10)
        assertEquals(2, o.inFlightCount)
        assertEquals(4L, o.next(10)!!.ptsUs)
    }

    @Test
    fun jpegKeepsOnlyTheNewestWaitingFrame() {
        val o = VideoOutbox(1)
        o.offer(jpeg(1), 0)
        o.next(0)
        o.offer(jpeg(2), 1)
        o.offer(jpeg(3), 2)
        o.offer(jpeg(4), 3)
        assertEquals(1, o.pendingCount)
        o.ack(1, 5)
        assertEquals(4L, o.next(5)!!.ptsUs)
    }

    @Test
    fun ackIsCumulativeAndIgnoresOldOnes() {
        val o = VideoOutbox(3)
        for (i in 1..3L) { o.offer(jpeg(i), 0); o.next(0) }
        o.ack(0, 1)
        assertEquals(3, o.inFlightCount)
        o.ack(3, 2)
        assertEquals(0, o.inFlightCount)
        o.ack(99, 3)
        assertEquals(0, o.inFlightCount)
    }

    @Test
    fun h264BlockedLongerThanDropAfterDropsEverythingAndWaitsForKeyframe() {
        val o = VideoOutbox(1, 33)
        assertFalse(o.offer(key(1), 0))
        o.next(0)                         // in flight, limit reached
        assertFalse(o.offer(delta(2), 10))
        assertFalse(o.offer(delta(3), 30)) // head waited 20 ms: still fine
        assertEquals(2, o.pendingCount)
        assertTrue(o.offer(delta(4), 50))  // head waited 40 ms > 33: drop all
        assertEquals(0, o.pendingCount)
        assertTrue(o.needsKeyframe)
        // deltas are refused until a keyframe arrives
        assertTrue(o.offer(delta(5), 60))
        assertEquals(0, o.pendingCount)
        assertFalse(o.offer(key(6), 70))
        assertFalse(o.needsKeyframe)
        assertEquals(1, o.pendingCount)
        o.ack(1, 80)
        assertEquals(6L, o.next(80)!!.ptsUs)
        // and deltas flow again
        o.offer(delta(7), 80)
        assertEquals(1, o.pendingCount)
    }

    @Test
    fun newKeyframeReplacesWaitingH264Packets() {
        val o = VideoOutbox(1)
        o.offer(key(1), 0)
        o.next(0)
        o.offer(delta(2), 1)
        o.offer(key(3), 2)
        assertEquals(1, o.pendingCount)
        o.ack(1, 3)
        assertEquals(3L, o.next(3)!!.ptsUs)
    }

    @Test
    fun missingAcksResetTheAccountingAfterTheStallTime() {
        val o = VideoOutbox(2)
        o.offer(jpeg(1), 0); o.next(0)
        o.offer(jpeg(2), 0); o.next(0)
        assertFalse(o.checkStall(VideoOutbox.ACK_STALL_MS))
        assertEquals(2, o.inFlightCount)
        assertFalse(o.checkStall(VideoOutbox.ACK_STALL_MS + 1)) // JPEG: no keyframe needed
        assertEquals(0, o.inFlightCount)
    }

    @Test
    fun stallWithWaitingH264NeedsKeyframe() {
        val o = VideoOutbox(1)
        o.offer(key(1), 0); o.next(0)
        o.offer(delta(2), 1)
        assertTrue(o.checkStall(VideoOutbox.ACK_STALL_MS + 5))
        assertTrue(o.needsKeyframe)
        assertEquals(0, o.pendingCount)
    }

    @Test
    fun resetForNewStream() {
        val o = VideoOutbox(1)
        o.offer(key(1), 0); o.next(0)
        o.offer(delta(2), 1)
        o.reset(true)
        assertEquals(0, o.inFlightCount)
        assertEquals(0, o.pendingCount)
        assertTrue(o.needsKeyframe)
        o.reset(false)
        assertFalse(o.needsKeyframe)
    }

    @Test
    fun keyframeWaitAfterResetCountsAsResetDropsNotCongestion() {
        val o = VideoOutbox(1)
        o.offer(key(1), 0); o.next(0)
        o.offer(delta(2), 1)
        o.reset(true)
        for (i in 3..13L) o.offer(delta(i), i)
        assertEquals(0L, o.dropped)
        assertEquals(12L, o.resetDropped) // 1 waiting + 11 refused
        o.offer(key(14), 20)
        assertFalse(o.needsKeyframe)
    }

    @Test
    fun backlogDropsStillCountAsCongestion() {
        val o = VideoOutbox(1, 150)
        o.offer(key(1), 0); o.next(0)
        o.offer(delta(2), 1)
        o.offer(delta(3), 400) // oldest waited > dropAfter: dropped, keyframe needed
        assertEquals(2L, o.dropped) // the waiting delta and the refused one
        o.offer(delta(4), 401)
        assertEquals(3L, o.dropped)
        assertEquals(0L, o.resetDropped)
    }

    @Test
    fun defaultsAreSixInFlightAnd150Ms() {
        val o = VideoOutbox()
        assertEquals(6, o.maxInFlight)
        assertEquals(150, o.dropAfterMs)
    }

    @Test
    fun jitteryNetworkWithin150MsDoesNotCauseAKeyframeStorm() {
        val o = VideoOutbox(1) // default dropAfterMs 150
        o.offer(key(1), 0); o.next(0)
        for (i in 2..5L) assertFalse(o.offer(delta(i), i * 30)) // head waited up to 120 ms
        assertEquals(4, o.pendingCount)
        assertFalse(o.needsKeyframe)
        assertTrue(o.offer(delta(6), 215)) // head waited 155 ms > 150
        assertTrue(o.needsKeyframe)
    }

    @Test
    fun dropAfterMsCanBeChangedLive() {
        val o = VideoOutbox(1, 500)
        o.offer(key(1), 0); o.next(0)
        o.offer(delta(2), 10)
        assertFalse(o.offer(delta(3), 300))
        o.dropAfterMs = 100
        assertTrue(o.offer(delta(4), 320))
        assertEquals(0, o.pendingCount)
    }

    @Test
    fun maxInFlightCanBeChangedLive() {
        val o = VideoOutbox(1)
        o.offer(jpeg(1), 0); o.next(0)
        o.offer(jpeg(2), 1)
        assertNull(o.next(1))
        o.maxInFlight = 2
        assertEquals(2L, o.next(1)!!.ptsUs)
        o.offer(jpeg(3), 2)
        assertNull(o.next(2))
    }
}
