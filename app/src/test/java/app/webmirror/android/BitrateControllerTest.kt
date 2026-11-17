package app.webmirror.android

import app.webmirror.android.stream.BitrateController
import app.webmirror.android.stream.BitrateController.ViewerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitrateControllerTest {

    private val drop = 150

    private fun calm(id: Int = 1, dropped: Long = 0, skipped: Long = 0) = ViewerSignal(id, 0, dropped, skipped, 0)
    private fun waiting(ms: Long) = ViewerSignal(1, ms, 0, 0, 0)

    @Test
    fun startsAtTheCeilingAndStaysThereWhenCalm() {
        val c = BitrateController(800_000, 4_000_000)
        for (t in 0..10_000L step 500) assertNull(c.tick(t, listOf(calm()), drop))
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun waitingLongerThanHalfDropAfterStepsDownBy30Percent() {
        val c = BitrateController(800_000, 4_000_000)
        assertNull(c.tick(0, listOf(waiting(75)), drop)) // exactly half: not yet
        assertEquals(2_800_000, c.tick(500, listOf(waiting(76)), drop))
    }

    @Test
    fun dropsSkipsAndNetworkLatenessAreCongestion() {
        fun first(s: ViewerSignal, after: ViewerSignal): Int? {
            val c = BitrateController(800_000, 4_000_000)
            c.tick(0, listOf(s), drop) // baseline
            return c.tick(500, listOf(after), drop)
        }
        assertEquals(2_800_000, first(calm(), calm(dropped = 1)))
        assertEquals(2_800_000, first(calm(), calm(skipped = 3)))
        assertEquals(2_800_000, first(calm(), ViewerSignal(1, 0, 0, 0, 151)))
        assertNull(first(calm(), ViewerSignal(1, 0, 0, 0, 150)))
    }

    @Test
    fun oldTotalsAreNotCongestionOnlyNewOnes() {
        val c = BitrateController(800_000, 4_000_000)
        assertNull(c.tick(0, listOf(calm(dropped = 50, skipped = 9)), drop)) // first sight is the baseline
        assertNull(c.tick(500, listOf(calm(dropped = 50, skipped = 9)), drop))
    }

    @Test
    fun hysteresisNoSecondDecreaseWithinTheSettleTime() {
        val c = BitrateController(800_000, 4_000_000)
        assertEquals(2_800_000, c.tick(0, listOf(waiting(200)), drop))
        assertNull(c.tick(500, listOf(waiting(200)), drop))
        assertEquals(1_960_000, c.tick(1000, listOf(waiting(200)), drop))
    }

    @Test
    fun neverBelowTheFloor() {
        val c = BitrateController(800_000, 1_000_000)
        var t = 0L
        repeat(20) { c.tick(t, listOf(waiting(500)), drop); t += 1000 }
        assertEquals(800_000, c.value)
        assertNull(c.tick(t, listOf(waiting(500)), drop))
    }

    @Test
    fun recoversTenPercentAfterFourCalmSecondsThenWaitsAgain() {
        val c = BitrateController(800_000, 4_000_000)
        assertEquals(2_800_000, c.tick(0, listOf(waiting(200)), drop))
        for (t in 500..3500L step 500) assertNull("t=$t", c.tick(t, listOf(calm()), drop))
        assertEquals(3_080_000, c.tick(4000, listOf(calm()), drop))
        for (t in 4500..7500L step 500) assertNull("t=$t", c.tick(t, listOf(calm()), drop))
        assertEquals(3_388_000, c.tick(8000, listOf(calm()), drop))
    }

    @Test
    fun congestionRestartsTheCalmPeriod() {
        val c = BitrateController(800_000, 4_000_000)
        c.tick(0, listOf(waiting(200)), drop)
        c.tick(3000, listOf(waiting(200)), drop) // another decrease, calm restarts at 3000
        assertNull(c.tick(6500, listOf(calm()), drop))
        assertEquals(1_960_000 + 196_000, c.tick(7000, listOf(calm()), drop))
    }

    @Test
    fun neverAboveTheCeiling() {
        val c = BitrateController(800_000, 4_000_000)
        c.tick(0, listOf(waiting(200)), drop)
        var t = 500L
        repeat(60) { c.tick(t, listOf(calm()), drop); t += 4000 }
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun worstViewerDecides() {
        val c = BitrateController(800_000, 4_000_000)
        val fine = ViewerSignal(1, 0, 0, 0, 0)
        val bad = ViewerSignal(2, 500, 0, 0, 0)
        assertEquals(2_800_000, c.tick(0, listOf(fine, bad), drop))
    }

    @Test
    fun jpegQualityUsesTheSameRules() {
        val c = BitrateController(BitrateController.MIN_JPEG_QUALITY, 50)
        assertEquals(35, c.tick(0, listOf(waiting(200)), drop))
        assertEquals(30, c.tick(1000, listOf(waiting(200)), drop)) // 24 -> floor 30
        assertNull(c.tick(1500, listOf(waiting(200)), drop))
        assertEquals(33, c.tick(6000, listOf(calm()), drop))
    }

    @Test
    fun configureResetsToTheNewCeiling() {
        val c = BitrateController(800_000, 4_000_000)
        c.tick(0, listOf(waiting(200)), drop)
        c.configure(800_000, 6_000_000)
        assertEquals(6_000_000, c.value)
        assertNull(c.tick(500, listOf(calm()), drop))
    }

    @Test
    fun signalsInTheGracePeriodAfterAResetAreIgnored() {
        val c = BitrateController(800_000, 4_000_000)
        c.configure(800_000, 4_000_000, 10_000)
        c.tick(10_000, listOf(calm()), drop)
        // keyframe-wait style noise right after the reset: ignored
        assertNull(c.tick(10_500, listOf(waiting(500)), drop))
        assertNull(c.tick(11_500, listOf(calm(dropped = 11, skipped = 2)), drop))
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun aRealBacklogAfterTheGracePeriodStillLowersTheTarget() {
        val c = BitrateController(800_000, 4_000_000)
        c.configure(800_000, 4_000_000, 10_000)
        assertNull(c.tick(11_000, listOf(calm()), drop))
        assertEquals(2_800_000, c.tick(12_500, listOf(waiting(200)), drop))
    }

    @Test
    fun holdOffIgnoresSignalsForTwoSeconds() {
        val c = BitrateController(800_000, 4_000_000)
        c.holdOff(0)
        assertNull(c.tick(1_900, listOf(waiting(500)), drop))
        assertEquals(2_800_000, c.tick(2_500, listOf(waiting(500)), drop))
    }
}
