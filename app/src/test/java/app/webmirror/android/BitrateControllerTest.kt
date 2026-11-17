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

    /** Two consecutive bad ticks at [t] and [t]+500 cut the target; returns the result of the second. */
    private fun cutAt(c: BitrateController, t: Long, bad: ViewerSignal = waiting(200)): Int? {
        assertNull(c.tick(t, listOf(bad), drop))
        return c.tick(t + 500, listOf(bad), drop)
    }

    @Test
    fun startsAtTheCeilingAndStaysThereWhenCalm() {
        val c = BitrateController(800_000, 4_000_000)
        for (t in 0..10_000L step 500) assertNull(c.tick(t, listOf(calm()), drop))
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun waitingNotLongerThanDropAfterIsNotCongestion() {
        val c = BitrateController(800_000, 4_000_000)
        for (t in 0..5_000L step 500) assertNull(c.tick(t, listOf(waiting(150)), drop)) // exactly dropAfterMs: fine
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun oneBadTickIsNotEnoughTwoConsecutiveCutByAQuarter() {
        val c = BitrateController(800_000, 4_000_000)
        assertNull(c.tick(0, listOf(waiting(151)), drop))
        assertNull(c.tick(500, listOf(calm()), drop)) // streak broken
        assertNull(c.tick(1000, listOf(waiting(151)), drop))
        assertEquals(3_000_000, c.tick(1500, listOf(waiting(151)), drop))
    }

    @Test
    fun dropsSkipsAndDecoderQueueAreCongestionLatenessIsNot() {
        fun cut(first: ViewerSignal, vararg rest: ViewerSignal): Int? {
            val c = BitrateController(800_000, 4_000_000)
            c.tick(0, listOf(first), drop) // baseline
            var r: Int? = null
            var t = 500L
            for (s in rest) { r = c.tick(t, listOf(s), drop) ?: r; t += 500 }
            return r
        }
        assertEquals(3_000_000, cut(calm(), calm(dropped = 1), calm(dropped = 2)))
        assertEquals(3_000_000, cut(calm(), calm(skipped = 3), calm(skipped = 4)))
        assertEquals(3_000_000, cut(calm(), ViewerSignal(1, 0, 0, 0, 3), ViewerSignal(1, 0, 0, 0, 3)))
        assertNull(cut(calm(), ViewerSignal(1, 0, 0, 0, 2), ViewerSignal(1, 0, 0, 0, 2)))
        // a single drop burst that is not repeated in the next tick does not count twice
        assertNull(cut(calm(), calm(dropped = 5), calm(dropped = 5)))
    }

    @Test
    fun oldTotalsAreNotCongestionOnlyNewOnes() {
        val c = BitrateController(800_000, 4_000_000)
        assertNull(c.tick(0, listOf(calm(dropped = 50, skipped = 9)), drop)) // first sight is the baseline
        assertNull(c.tick(500, listOf(calm(dropped = 50, skipped = 9)), drop))
        assertNull(c.tick(1000, listOf(calm(dropped = 50, skipped = 9)), drop))
    }

    @Test
    fun hysteresisNeedsTwoMoreBadTicksForTheNextCut() {
        val c = BitrateController(800_000, 4_000_000)
        assertEquals(3_000_000, cutAt(c, 0))
        assertNull(c.tick(1000, listOf(waiting(200)), drop))
        assertEquals(2_250_000, c.tick(1500, listOf(waiting(200)), drop))
    }

    @Test
    fun neverBelowTheFloor() {
        val c = BitrateController(800_000, 1_000_000)
        var t = 0L
        repeat(20) { c.tick(t, listOf(waiting(500)), drop); t += 500 }
        assertEquals(800_000, c.value)
        assertNull(c.tick(t, listOf(waiting(500)), drop))
    }

    @Test
    fun recoversAQuarterPerTwoCalmSeconds() {
        val c = BitrateController(800_000, 4_000_000)
        assertEquals(3_000_000, cutAt(c, 0)) // cut at 500
        for (t in 1000..2000L step 500) assertNull("t=$t", c.tick(t, listOf(calm()), drop))
        assertEquals(3_750_000, c.tick(2500, listOf(calm()), drop))
        for (t in 3000..4000L step 500) assertNull("t=$t", c.tick(t, listOf(calm()), drop))
        assertEquals(4_000_000, c.tick(4500, listOf(calm()), drop)) // capped at the ceiling
    }

    @Test
    fun fromTheFloorToTheCeilingTakesAboutFifteenSeconds() {
        val c = BitrateController(800_000, 4_000_000)
        var t = 0L
        while (c.value > 800_000) { c.tick(t, listOf(waiting(500)), drop); t += 500 }
        val start = t
        while (c.value < 4_000_000 && t < start + 60_000) { c.tick(t, listOf(calm()), drop); t += 500 }
        assertEquals(4_000_000, c.value)
        val took = t - start
        assert(took in 12_000..20_000) { "took $took ms" }
    }

    @Test
    fun congestionRestartsTheCalmPeriod() {
        val c = BitrateController(800_000, 4_000_000)
        cutAt(c, 0) // 3_000_000 at 500
        c.tick(1000, listOf(waiting(200)), drop) // a bad tick restarts the calm period at 1000
        assertNull(c.tick(2500, listOf(calm()), drop))
        assertEquals(3_750_000, c.tick(3000, listOf(calm()), drop))
    }

    @Test
    fun neverAboveTheCeiling() {
        val c = BitrateController(800_000, 4_000_000)
        cutAt(c, 0)
        var t = 1000L
        repeat(60) { c.tick(t, listOf(calm()), drop); t += 2000 }
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun worstViewerDecides() {
        val c = BitrateController(800_000, 4_000_000)
        val fine = ViewerSignal(1, 0, 0, 0, 0)
        val bad = ViewerSignal(2, 500, 0, 0, 0)
        assertNull(c.tick(0, listOf(fine, bad), drop))
        assertEquals(3_000_000, c.tick(500, listOf(fine, bad), drop))
    }

    @Test
    fun jpegQualityUsesTheSameRules() {
        val c = BitrateController(BitrateController.MIN_JPEG_QUALITY, 50)
        assertEquals(38, cutAt(c, 0)) // 50 * 0.75 = 37.5 -> 38
        assertNull(c.tick(1000, listOf(waiting(200)), drop))
        assertEquals(30, c.tick(1500, listOf(waiting(200)), drop)) // 28.5 -> floor 30
        assertNull(c.tick(2000, listOf(waiting(200)), drop))
    }

    @Test
    fun configureResetsToTheNewCeiling() {
        val c = BitrateController(800_000, 4_000_000)
        cutAt(c, 0)
        c.configure(800_000, 6_000_000)
        assertEquals(6_000_000, c.value)
        assertNull(c.tick(1000, listOf(calm()), drop))
    }

    @Test
    fun signalsInTheGracePeriodAfterAResetAreIgnored() {
        val c = BitrateController(800_000, 4_000_000)
        c.configure(800_000, 4_000_000, 10_000)
        c.tick(10_000, listOf(calm()), drop)
        assertNull(c.tick(10_500, listOf(waiting(500)), drop))
        assertNull(c.tick(11_000, listOf(waiting(500)), drop))
        assertNull(c.tick(11_500, listOf(calm(dropped = 11, skipped = 2)), drop))
        assertEquals(4_000_000, c.value)
    }

    @Test
    fun aRealBacklogAfterTheGracePeriodStillLowersTheTarget() {
        val c = BitrateController(800_000, 4_000_000)
        c.configure(800_000, 4_000_000, 10_000)
        assertNull(c.tick(11_000, listOf(calm()), drop))
        assertEquals(3_000_000, cutAt(c, 12_000))
    }

    @Test
    fun holdOffIgnoresSignalsForTwoSeconds() {
        val c = BitrateController(800_000, 4_000_000)
        c.holdOff(0)
        assertNull(c.tick(1_500, listOf(waiting(500)), drop))
        assertNull(c.tick(1_900, listOf(waiting(500)), drop))
        assertNull(c.tick(2_500, listOf(waiting(500)), drop)) // first counted bad tick
        assertEquals(3_000_000, c.tick(3_000, listOf(waiting(500)), drop))
    }
}
