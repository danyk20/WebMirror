package app.webmirror.android

import app.webmirror.android.stream.CodecChooser
import app.webmirror.android.stream.CodecChooser.PhoneCaps
import app.webmirror.android.stream.CodecChooser.PhoneCodec
import app.webmirror.android.stream.CodecChooser.Support
import app.webmirror.android.stream.CodecChooser.ViewerCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecChooserTest {

    // The owner's phone: hardware H.264 and HEVC, software VP9 and AV1.
    private val s22 = PhoneCaps(
        mapOf(
            "h264" to PhoneCodec(hw = "c2.qti.avc.encoder", sw = "c2.android.avc.encoder"),
            "hevc" to PhoneCodec(hw = "c2.qti.hevc.encoder", sw = "c2.android.hevc.encoder"),
            "vp9" to PhoneCodec(sw = "c2.android.vp9.encoder"),
            "av1" to PhoneCodec(sw = "c2.android.av1.encoder"),
        ),
    )

    private fun viewer(h264: Support, hevc: Support = Support(), vp9: Support = Support(), av1: Support = Support()) =
        ViewerCaps(mapOf("h264" to h264, "hevc" to hevc, "vp9" to vp9, "av1" to av1))

    private val hw = Support(sw = true, hw = true)
    private val swOnly = Support(sw = true)

    // The Tesla: hardware H.264, HEVC and VP9; AV1 only in software.
    private val tesla = viewer(hw, hw, hw, swOnly)

    @Test
    fun autoPicksHevcWhenPhoneAndViewerHaveHardware() {
        val c = CodecChooser.choose("auto", false, s22, listOf(tesla))
        assertEquals("hevc", c.mode)
        assertEquals("c2.qti.hevc.encoder", c.encoder)
        assertTrue(c.hardware)
        assertEquals("auto: hevc hw on phone and all viewers", c.why)
    }

    @Test
    fun oneH264OnlyViewerPullsEveryoneToH264() {
        val old = viewer(swOnly)
        assertEquals("h264", CodecChooser.choose("auto", false, s22, listOf(tesla, old)).mode)
        assertEquals("h264", CodecChooser.choose("auto", false, s22, listOf(viewer(hw))).mode)
    }

    @Test
    fun softwareDecodeIsAcceptedForH264Only() {
        val c = CodecChooser.choose("auto", false, s22, listOf(viewer(swOnly, swOnly, swOnly, swOnly)))
        assertEquals("h264", c.mode)
        assertTrue(c.why.contains("software decode"))
        // a viewer that only decodes VP9 in software cannot get video at all
        val noH264 = viewer(Support(), vp9 = swOnly)
        assertEquals("jpeg", CodecChooser.choose("auto", false, s22, listOf(noH264)).mode)
        assertNull(CodecChooser.choose("auto", false, s22, listOf(noH264)).encoder)
    }

    @Test
    fun phoneWithHardwareVp9AndAv1PrefersAv1ThenVp9() {
        val pixel = PhoneCaps(
            mapOf("h264" to PhoneCodec("h", "s"), "vp9" to PhoneCodec(hw = "vp9hw"), "av1" to PhoneCodec(hw = "av1hw")),
        )
        assertEquals("av1", CodecChooser.choose("auto", false, pixel, listOf(viewer(hw, vp9 = hw, av1 = hw))).mode)
        assertEquals("vp9", CodecChooser.choose("auto", false, pixel, listOf(tesla)).mode) // Tesla: AV1 only software
        assertEquals("h264", CodecChooser.choose("auto", false, pixel, listOf(viewer(hw))).mode)
    }

    @Test
    fun softwareEncodersOnlyWhenAllowedAndAfterHardwareOnes() {
        val allV = viewer(hw, hw, hw, hw)
        val phone = PhoneCaps(mapOf("h264" to PhoneCodec(hw = "h"), "av1" to PhoneCodec(sw = "av1sw")))
        assertEquals("h264", CodecChooser.choose("auto", false, phone, listOf(allV)).mode)
        // hardware H.264 wins over a software AV1 even though AV1 comes first in the preference list
        assertEquals("h264", CodecChooser.choose("auto", true, phone, listOf(allV)).mode)
        val swOnlyPhone = PhoneCaps(mapOf("h264" to PhoneCodec(hw = null, sw = "h264sw"), "av1" to PhoneCodec(sw = "av1sw")))
        val c = CodecChooser.choose("auto", true, swOnlyPhone, listOf(allV))
        assertEquals("av1", c.mode)
        assertEquals(false, c.hardware)
    }

    @Test
    fun fixedChoiceIsHonouredWhenEveryoneSupportsIt() {
        val c = CodecChooser.choose("vp9", true, s22, listOf(tesla))
        assertEquals("vp9", c.mode)
        assertEquals("c2.android.vp9.encoder", c.encoder)
        assertEquals(false, c.hardware)
        assertEquals("h264", CodecChooser.choose("h264", false, s22, listOf(tesla)).mode)
        assertEquals("hevc", CodecChooser.choose("hevc", false, s22, listOf(tesla)).mode)
    }

    @Test
    fun fixedChoiceThatIsNotUsableFallsBackToTheAutoResultWithAReason() {
        // VP9 on the S22 is software only and software is not allowed
        val c = CodecChooser.choose("vp9", false, s22, listOf(tesla))
        assertEquals("hevc", c.mode)
        assertTrue(c.why, c.why.startsWith("fixed vp9 not usable (phone has only a software encoder); auto: hevc"))
        // AV1: the Tesla only decodes it in software
        val d = CodecChooser.choose("av1", true, s22, listOf(tesla))
        assertEquals("hevc", d.mode)
        assertTrue(d.why.contains("a viewer lacks hardware decode"))
    }

    @Test
    fun sizeThatTheEncoderCannotDoDisqualifiesIt() {
        val phone = PhoneCaps(
            mapOf("h264" to PhoneCodec(hw = "h"), "hevc" to PhoneCodec(hw = "e", hwFits = false)),
        )
        assertEquals("h264", CodecChooser.choose("auto", false, phone, listOf(tesla)).mode)
    }

    @Test
    fun h264IsTheLastResortEvenInSoftwareAndEvenIfTheSizeIsNotClaimed() {
        val soft = PhoneCaps(mapOf("h264" to PhoneCodec(sw = "c2.android.avc.encoder")))
        val c = CodecChooser.choose("auto", false, soft, listOf(tesla))
        assertEquals("h264", c.mode)
        assertEquals(false, c.hardware)
        val unclaimed = PhoneCaps(mapOf("h264" to PhoneCodec(hw = "h", hwFits = false)))
        assertEquals("h", CodecChooser.choose("auto", false, unclaimed, listOf(tesla)).encoder)
        assertEquals("jpeg", CodecChooser.choose("auto", false, PhoneCaps(), listOf(tesla)).mode)
    }

    @Test
    fun legacyHelloBooleansAreUnderstood() {
        val v = ViewerCaps.legacy(h264 = true, hevc = true)
        assertEquals("hevc", CodecChooser.choose("auto", false, s22, listOf(v)).mode)
        assertEquals("h264", CodecChooser.choose("auto", false, s22, listOf(ViewerCaps.legacy(true, false))).mode)
        assertEquals("jpeg", CodecChooser.choose("auto", false, s22, listOf(ViewerCaps.legacy(false, false))).mode)
    }

    @Test
    fun preferenceOrderIsAConstantList() {
        assertEquals(listOf("hevc", "av1", "vp9", "h264"), CodecChooser.PREFERENCE)
    }
}
