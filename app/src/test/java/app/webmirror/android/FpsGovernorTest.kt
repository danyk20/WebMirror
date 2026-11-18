package app.webmirror.android

import app.webmirror.android.capture.Preset
import app.webmirror.android.stream.FpsGovernor
import app.webmirror.android.stream.FpsGovernor.Viewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsGovernorTest {

    /** Drives windows of [FpsGovernor.WINDOW_MS] with a simple world: the encoder delivers [encFps] frames/s. */
    private class Rig(val g: FpsGovernor = FpsGovernor()) {
        var now = 0L
        var encoded = 0L
        var lost = 0L

        fun window(
            encFps: Double = 60.0,
            drawn: Double = 60.0,
            queue: Int = 0,
            lostInWindow: Long = 0,
            viewerIds: List<Int> = listOf(1),
            fresh: Boolean = true,
        ): FpsGovernor.Change? {
            now += FpsGovernor.WINDOW_MS
            encoded += (encFps * FpsGovernor.WINDOW_MS / 1000).toLong()
            lost += lostInWindow
            return g.onWindow(now, encoded, viewerIds.map { Viewer(it, drawn, queue, lost, fresh) })
        }

        /** [n] healthy windows; asserts nothing changed. */
        fun healthy(n: Int) = repeat(n) { assertNull(window()) }

        /** Bad windows (the viewer draws 30 fps) until the governor reacts; returns the change. */
        fun badUntilChange(max: Int = 10): FpsGovernor.Change {
            repeat(max) {
                val c = window(drawn = 30.0)
                if (c != null) return c
            }
            throw AssertionError("no change after $max bad windows")
        }
    }

    private fun started(): Rig = Rig().also {
        it.g.configure(60, 0)
        it.healthy(4) // grace period and a first look
    }

    @Test
    fun staysAt60WhenHealthy() {
        val r = started()
        r.healthy(100)
        assertEquals(60, r.g.effectiveFps)
        assertFalse(r.g.inFallback)
    }

    @Test
    fun fallsBackAfterThreeBadWindowsNotBefore() {
        val r = started()
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        val c = r.window(drawn = 30.0)
        assertNotNull(c)
        assertEquals(30, c!!.fps)
        assertEquals(30, r.g.effectiveFps)
        assertTrue(r.g.inFallback)
    }

    @Test
    fun aGoodWindowBreaksTheStreak() {
        val r = started()
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window()) // healthy again
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        assertEquals(60, r.g.effectiveFps)
        assertNotNull(r.window(drawn = 30.0))
    }

    @Test
    fun decodeQueueOfFourIsBadThreeIsNot() {
        val r = started()
        repeat(10) { assertNull(r.window(queue = 3)) }
        val c = r.badUntilChangeWith { r.window(queue = 4) }
        assertEquals(30, c.fps)
    }

    @Test
    fun fivePercentLossIsBadFourIsNot() {
        val r = started()
        // 60 fps x 2 s = 120 drawn; 6 lost of 126 = 4.8 % -> fine; 7 of 127 = 5.5 % -> bad.
        repeat(10) { assertNull(r.window(lostInWindow = 6)) }
        val c = r.badUntilChangeWith { r.window(lostInWindow = 7) }
        assertEquals(30, c.fps)
    }

    @Test
    fun theSlowestViewerDecides() {
        val g = FpsGovernor()
        g.configure(60, 0)
        var now = 0L
        var encoded = 0L
        fun step(slow: Double): FpsGovernor.Change? {
            now += FpsGovernor.WINDOW_MS
            encoded += 120
            return g.onWindow(now, encoded, listOf(Viewer(1, 60.0, 0, 0, true), Viewer(2, slow, 0, 0, true)))
        }
        repeat(4) { assertNull(step(60.0)) }
        assertNull(step(40.0))
        assertNull(step(40.0))
        assertEquals(30, step(40.0)!!.fps)
    }

    @Test
    fun staleStatsAreNotJudged() {
        val r = started()
        repeat(10) { assertNull(r.window(drawn = 10.0, fresh = false)) }
        assertEquals(60, r.g.effectiveFps)
    }

    @Test
    fun aStaticScreenIsIgnoredAndBreaksTheStreak() {
        val r = started()
        // The encoder only repeats frames (10 fps) and the viewer draws 10 fps: no verdict, however long.
        repeat(50) { assertNull(r.window(encFps = 10.0, drawn = 10.0)) }
        assertEquals(60, r.g.effectiveFps)
        // Two bad windows, then a static one, then two bad ones: still no fallback.
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(encFps = 10.0, drawn = 10.0))
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        assertEquals(60, r.g.effectiveFps)
    }

    @Test
    fun retriesAfterAMinuteAndStaysIfHealthy() {
        val r = started()
        val c = r.badUntilChange()
        assertEquals(30, c.fps)
        val failedAt = r.now
        assertEquals(FpsGovernor.RETRY_BASE_MS, r.g.currentWaitMs)
        assertEquals(failedAt + 60_000, r.g.nextRetryAtMs)
        // 29 windows (58 s) later still at 30; the 30th (60 s) retries.
        repeat(29) { assertNull(r.window(encFps = 30.0, drawn = 30.0)) }
        assertEquals(30, r.g.effectiveFps)
        val retry = r.window(encFps = 30.0, drawn = 30.0)
        assertEquals(60, retry!!.fps)
        assertFalse(r.g.inFallback)
        r.healthy(80) // well past the proof time
        assertEquals(60, r.g.effectiveFps)
        // The next failure starts again with the base wait.
        r.badUntilChange()
        assertEquals(FpsGovernor.RETRY_BASE_MS, r.g.currentWaitMs)
    }

    @Test
    fun aFailedRetryDoublesTheWaitUpToTheCap() {
        val r = started()
        r.badUntilChange()
        var expected = FpsGovernor.RETRY_BASE_MS
        val waits = ArrayList<Long>()
        repeat(6) {
            // Wait for the retry, then fail again soon after it.
            var retried = false
            repeat(400) { if (!retried) retried = r.window(encFps = 30.0, drawn = 30.0)?.fps == 60 }
            assertTrue(retried)
            r.badUntilChange()
            waits.add(r.g.currentWaitMs)
        }
        expected = FpsGovernor.RETRY_BASE_MS
        val want = ArrayList<Long>()
        repeat(6) { expected = minOf(expected * 2, FpsGovernor.RETRY_MAX_MS); want.add(expected) }
        assertEquals(want, waits)
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), waits)
    }

    @Test
    fun aDifferentSetOfViewersResetsEverything() {
        val r = started()
        r.badUntilChange()
        r.window(encFps = 30.0, drawn = 30.0, viewerIds = listOf(1))
        val c = r.window(encFps = 30.0, drawn = 30.0, viewerIds = listOf(1, 2))
        assertEquals(60, c!!.fps)
        assertEquals(60, r.g.effectiveFps)
        assertEquals(FpsGovernor.RETRY_BASE_MS, r.g.currentWaitMs)
        // Bad windows right after the change are in the grace period and do not count.
        assertNull(r.window(drawn = 30.0, viewerIds = listOf(1, 2)))
        assertNull(r.window(drawn = 30.0, viewerIds = listOf(1, 2)))
        assertEquals(60, r.g.effectiveFps)
    }

    @Test
    fun aViewerChangeInNormalOperationOnlyRestartsTheStreak() {
        val r = started()
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(viewerIds = listOf(1, 2))) // new viewer: grace period, streak cleared
        assertEquals(60, r.g.effectiveFps)
    }

    @Test
    fun presetChangeResetsAndOnlyThe60FpsPresetIsActive() {
        val r = started()
        r.badUntilChange()
        r.g.configure(60, r.now) // preset chosen again (or another preset and back)
        assertEquals(60, r.g.effectiveFps)
        assertFalse(r.g.inFallback)
        assertEquals(FpsGovernor.RETRY_BASE_MS, r.g.currentWaitMs)

        val smoother = Rig()
        smoother.g.configure(30, 0)
        assertFalse(smoother.g.enabled)
        assertEquals(30, smoother.g.effectiveFps)
        repeat(20) { assertNull(smoother.window(encFps = 30.0, drawn = 5.0)) }
        assertEquals(30, smoother.g.effectiveFps)
    }

    @Test
    fun holdOffSkipsTheWindowsAfterAnEncoderRebuild() {
        val r = started()
        assertNull(r.window(drawn = 30.0))
        assertNull(r.window(drawn = 30.0))
        r.g.holdOff(r.now)
        repeat(2) { assertNull(r.window(drawn = 30.0)) } // 4 s of grace
        assertEquals(60, r.g.effectiveFps)
    }

    @Test
    fun noViewersNoVerdict() {
        val r = Rig()
        r.g.configure(60, 0)
        repeat(20) { assertNull(r.window(drawn = 0.0, viewerIds = emptyList())) }
        assertEquals(60, r.g.effectiveFps)
    }

    @Test
    fun presetBitrateAtFallbackRateIsTheSmootherOne() {
        assertEquals(6_000_000, Preset.SMOOTHEST.bitrateAt(60))
        assertEquals(4_000_000, Preset.SMOOTHEST.bitrateAt(30))
        assertEquals(4_000_000, Preset.SMOOTHER.bitrateAt(30))
        assertTrue(Preset.SMOOTHEST.canFallBack)
        assertFalse(Preset.SMOOTHER.canFallBack)
        assertFalse(Preset.SHARPER.canFallBack)
    }

    /** Runs [step] (one bad window each) until it returns a change. */
    private fun Rig.badUntilChangeWith(step: () -> FpsGovernor.Change?): FpsGovernor.Change {
        repeat(3) {
            val c = step()
            if (c != null) return c
        }
        throw AssertionError("no change after 3 bad windows")
    }
}
