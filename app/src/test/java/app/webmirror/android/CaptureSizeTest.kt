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
        assertEquals(Preset.SMOOTHER, Preset.fromKey("nonsense"))
    }
}
