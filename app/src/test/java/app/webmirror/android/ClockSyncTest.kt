package app.webmirror.android

import app.webmirror.android.stream.ClockSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClockSyncTest {

    @Test
    fun offsetAssumesTheAnswerWasMadeHalfARoundTripAfterSending() {
        val c = ClockSync()
        // Phone sent at 1_000_000 us, got the pong at 1_020_000 us (rtt 20 ms). The page clock then read 5000 ms.
        // Page time at the phone's 1_010_000 us was 5000 ms, so page - phone = 5_000_000 - 1_010_000.
        val s = c.add(0, 1_000_000, 1_020_000, 5000.0)!!
        assertEquals(20_000, s.rttUs)
        assertEquals(5_000_000L - 1_010_000L, s.offsetUs)
        assertEquals(20_000, c.lastRttUs)
    }

    @Test
    fun bestIsTheSampleWithTheSmallestRoundTrip() {
        val c = ClockSync()
        c.add(0, 0, 40_000, 100.0)
        val small = c.add(1_000, 1_000_000, 1_005_000, 1_100.0)!!
        c.add(2_000, 2_000_000, 2_060_000, 2_100.0)
        assertEquals(small, c.best(2_500))
        assertEquals(60_000, c.lastRttUs) // the last one, not the best
    }

    @Test
    fun theWindowForgetsOldSamples() {
        val c = ClockSync(windowMs = 30_000)
        c.add(0, 0, 2_000, 10.0) // rtt 2 ms, very good, but old
        val later = c.add(40_000, 40_000_000, 40_020_000, 40_100.0)!!
        assertEquals(later, c.best(40_000))
        assertNull(ClockSync().best(0))
    }

    @Test
    fun equalRoundTripsPreferTheNewest() {
        val c = ClockSync()
        c.add(0, 0, 10_000, 1.0)
        val newer = c.add(1_000, 1_000_000, 1_010_000, 1_001.0)!!
        assertEquals(newer, c.best(1_000))
    }

    @Test
    fun impossibleSamplesAreIgnored() {
        val c = ClockSync()
        assertNull(c.add(0, 100, 50, 1.0)) // negative round trip
        assertNull(c.add(0, 0, 20_000_000, 1.0)) // 20 s
        assertNull(c.add(0, 0, 1000, Double.NaN))
        assertNull(c.best(0))
        assertNotNull(c.add(0, 0, 1000, 1.0))
    }
}
