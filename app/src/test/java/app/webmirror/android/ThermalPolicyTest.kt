package app.webmirror.android

import app.webmirror.android.capture.Preset
import app.webmirror.android.stream.ThermalPolicy
import app.webmirror.android.stream.ThermalPolicy.Companion.STATUS_CRITICAL
import app.webmirror.android.stream.ThermalPolicy.Companion.STATUS_LIGHT
import app.webmirror.android.stream.ThermalPolicy.Companion.STATUS_MODERATE
import app.webmirror.android.stream.ThermalPolicy.Companion.STATUS_NONE
import app.webmirror.android.stream.ThermalPolicy.Companion.STATUS_SEVERE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    private val step = ThermalPolicy.STEP_UP_MS

    @Test
    fun noLimitWhenCoolOrUnavailable() {
        val p = ThermalPolicy()
        assertNull(p.update(STATUS_NONE, null, 0))
        assertNull(p.update(STATUS_LIGHT, 0.84f, 1000))
        assertNull(p.update(STATUS_NONE, Float.NaN, 2000))
        assertFalse(p.limit.active)
        assertEquals(60, p.limit.capFps(60))
        assertEquals(6_000_000, p.limit.capBitrate(6_000_000))
    }

    @Test
    fun moderateAndHeadroomCapAt30Fps() {
        val p = ThermalPolicy()
        assertNotNull(p.update(STATUS_MODERATE, null, 0))
        assertEquals(30, p.limit.maxFps)
        assertEquals(Int.MAX_VALUE, p.limit.maxBitrate)
        val q = ThermalPolicy()
        assertNotNull(q.update(STATUS_LIGHT, 0.85f, 0))
        assertEquals(30, q.limit.capFps(60))
        assertEquals(15, q.limit.capFps(15)) // never raises
    }

    @Test
    fun severeAndCriticalLimits() {
        val p = ThermalPolicy()
        p.update(STATUS_SEVERE, null, 0)
        assertEquals(30, p.limit.maxFps)
        assertEquals(2_500_000, p.limit.maxBitrate)
        p.update(STATUS_CRITICAL, null, 10)
        assertEquals(15, p.limit.maxFps)
        assertEquals(1_500_000, p.limit.maxBitrate)
        p.update(6, null, 20) // shutdown: same as critical
        assertEquals(3, p.limit.level)
    }

    @Test
    fun stepsDownImmediatelyAndUpOnlyAfterAMinute() {
        val p = ThermalPolicy()
        p.update(STATUS_SEVERE, null, 0)
        assertNull(p.update(STATUS_NONE, null, 1000))
        assertNull(p.update(STATUS_NONE, null, 1000 + step - 1))
        assertEquals(2, p.limit.level)
        val c = p.update(STATUS_NONE, null, 1000 + step)
        assertNotNull(c)
        assertFalse(p.limit.active)
    }

    @Test
    fun hotAgainRestartsTheWait() {
        val p = ThermalPolicy()
        p.update(STATUS_SEVERE, null, 0)
        p.update(STATUS_NONE, null, 1000)
        p.update(STATUS_SEVERE, null, 50_000) // back at the level: the wait restarts
        assertNull(p.update(STATUS_NONE, null, 51_000))
        assertNull(p.update(STATUS_NONE, null, 51_000 + step - 1))
        assertNotNull(p.update(STATUS_NONE, null, 51_000 + step))
    }

    @Test
    fun stepUpGoesToTheHighestLevelSeenDuringTheWait() {
        val p = ThermalPolicy()
        p.update(STATUS_CRITICAL, null, 0)
        p.update(STATUS_NONE, null, 1000)
        p.update(STATUS_MODERATE, null, 30_000)
        p.update(STATUS_NONE, null, 50_000)
        p.update(STATUS_NONE, null, 1000 + step)
        assertEquals(1, p.limit.level)
        assertEquals(30, p.limit.maxFps)
    }

    @Test
    fun headroomAloneKeepsTheCapAndLiftsAfterAMinute() {
        val p = ThermalPolicy()
        p.update(STATUS_NONE, 0.9f, 0)
        assertEquals(1, p.limit.level)
        assertNull(p.update(STATUS_NONE, 0.5f, 5000))
        assertNotNull(p.update(STATUS_NONE, 0.5f, 5000 + step))
        assertFalse(p.limit.active)
    }

    @Test
    fun combinesWithGovernorFpsAndPresetBitrate() {
        val smoothest = Preset.SMOOTHEST
        val p = ThermalPolicy()
        p.update(STATUS_SEVERE, null, 0)
        // governor at 60: thermal 30 fps, ceiling min(bitrateAt(30) = 4 Mbit/s, 2.5)
        val fps = p.limit.capFps(60)
        assertEquals(30, fps)
        assertEquals(2_500_000, p.limit.capBitrate(smoothest.bitrateAt(fps)))
        // governor already at 30: unchanged rate
        assertEquals(30, p.limit.capFps(30))
        // moderate only: the bitrate follows the capped rate (4 Mbit/s), not the preset's 6
        val m = ThermalPolicy()
        m.update(STATUS_MODERATE, null, 0)
        assertEquals(4_000_000, m.limit.capBitrate(smoothest.bitrateAt(m.limit.capFps(60))))
        // critical on Sharper (15 fps preset): the rate stays, the ceiling falls
        val c = ThermalPolicy()
        c.update(STATUS_CRITICAL, null, 0)
        assertEquals(15, c.limit.capFps(Preset.SHARPER.fps))
        assertEquals(1_500_000, c.limit.capBitrate(Preset.SHARPER.bitrateAt(15)))
        assertTrue(c.limit.active)
    }
}
