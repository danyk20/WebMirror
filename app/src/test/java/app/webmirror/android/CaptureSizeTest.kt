package app.webmirror.android

import app.webmirror.android.capture.CaptureSize
import app.webmirror.android.capture.Preset
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSizeTest {

    private fun check(w: Int, h: Int, max: Int, expW: Int, expH: Int) {
        assertEquals(CaptureSize.Size(expW, expH), CaptureSize.compute(w, h, max))
    }

    @Test fun portraitSharper() = check(1080, 2400, 1920, 864, 1920)

    @Test fun portraitSmoother() = check(1080, 2400, 1280, 576, 1280)

    @Test fun landscapeSmoother() = check(2400, 1080, 1280, 1280, 576)

    @Test fun neverUpscales() = check(720, 1280, 1920, 720, 1280)

    @Test fun roundsDownToMultipleOfEightWithoutScaling() = check(1081, 1283, 1920, 1080, 1280)

    @Test fun tinySideStaysAtLeastEight() = check(4, 4000, 1280, 8, 1280)

    @Test fun presetsMatchPlan() {
        assertEquals(1920, Preset.SHARPER.maxLongSide)
        assertEquals(15, Preset.SHARPER.fps)
        assertEquals(1280, Preset.SMOOTHER.maxLongSide)
        assertEquals(30, Preset.SMOOTHER.fps)
        assertEquals(1280, Preset.SMOOTHEST.maxLongSide)
        assertEquals(60, Preset.SMOOTHEST.fps)
        assertEquals(6_000_000, Preset.SMOOTHEST.h264Bitrate)
        assertEquals(20, Preset.SMOOTHEST.jpegFps)
        assertEquals("smoothest", Preset.SMOOTHEST.key)
        assertEquals("Smoothest (60 fps)", Preset.SMOOTHEST.label)
        assertEquals(Preset.SMOOTHEST, Preset.fromKey("smoothest"))
        assertEquals(Preset.SMOOTHEST, Preset.fromKey("nonsense"))
        assertEquals(Preset.SMOOTHEST, Preset.fromKey(null))
    }

    private fun fit(w: Int, h: Int, max: Int, bw: Int, bh: Int, expW: Int, expH: Int) {
        assertEquals(CaptureSize.Size(expW, expH), CaptureSize.fit(w, h, max, bw, bh))
    }

    @Test
    fun fitLimitedByBoxHeight() {
        // 1080x2400 phone, 1920 cap, landscape window 1920x1080: height limits -> 486x1080 -> 480x1072
        fit(1080, 2400, 1920, 1920, 1080, 480, 1072)
    }

    @Test
    fun fitCappedByPresetLongSide() {
        // huge window: the preset cap 1280 wins -> 576x1280
        fit(1080, 2400, 1280, 4000, 4000, 576, 1280)
    }

    @Test
    fun fitNeverUpscales() {
        fit(720, 1280, 1920, 4000, 4000, 720, 1280)
    }

    @Test
    fun fitLimitedByBoxWidth() {
        // landscape screen 2400x1080 in a 600x2000 window: width limits -> 600x270 -> 592x256
        fit(2400, 1080, 1920, 600, 2000, 592, 256)
    }

    @Test
    fun fitSidesAreMultiplesOf16AndAtLeast16() {
        val s = CaptureSize.fit(1080, 2400, 1920, 100, 100)
        assertEquals(0, s.width % 16)
        assertEquals(0, s.height % 16)
        assertEquals(true, s.width >= 16 && s.height >= 16)
        assertEquals(CaptureSize.Size(16, 16), CaptureSize.fit(1080, 2400, 1920, 1, 1))
    }

    @Test
    fun keepIfCloseHoldsSizeWithinTenPercent() {
        val cur = CaptureSize.Size(576, 1280)
        assertEquals(cur, CaptureSize.keepIfClose(cur, CaptureSize.Size(544, 1216)))   // -5.6%, -5%
        assertEquals(CaptureSize.Size(512, 1136), CaptureSize.keepIfClose(cur, CaptureSize.Size(512, 1136))) // -11%
        assertEquals(CaptureSize.Size(1280, 576), CaptureSize.keepIfClose(cur, CaptureSize.Size(1280, 576))) // rotation
        assertEquals(CaptureSize.Size(576, 1280), CaptureSize.keepIfClose(null, CaptureSize.Size(576, 1280)))
    }
}
