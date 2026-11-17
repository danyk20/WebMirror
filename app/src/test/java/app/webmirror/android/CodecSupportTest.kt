package app.webmirror.android

import app.webmirror.android.encode.CodecSupport
import app.webmirror.android.encode.CodecSupport.Cand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecSupportTest {

    @Test
    fun softwareNamesAreRecognised() {
        assertFalse(CodecSupport.isHardwareByName("c2.android.vp9.encoder"))
        assertFalse(CodecSupport.isHardwareByName("OMX.google.h264.encoder"))
        assertFalse(CodecSupport.isHardwareByName("OMX.SEC.avc.sw.enc"))
        assertTrue(CodecSupport.isHardwareByName("c2.qti.hevc.encoder"))
        assertTrue(CodecSupport.isHardwareByName("OMX.qcom.video.encoder.avc"))
    }

    @Test
    fun pickTakesTheFirstHardwareAndTheFirstSoftwareEncoder() {
        val list = listOf(Cand("c2.android.avc.encoder", false), Cand("c2.qti.avc.encoder", true), Cand("c2.other.avc.encoder", true))
        assertEquals("c2.qti.avc.encoder" to "c2.android.avc.encoder", CodecSupport.pick(list))
        assertEquals(null to null, CodecSupport.pick(emptyList()))
        assertNull(CodecSupport.pick(listOf(Cand("x", false))).first)
    }

    @Test
    fun tableTextShowsHardwareSoftwareAndSizeProblems() {
        val text = CodecSupport.describe(
            listOf(
                CodecSupport.Row("h264", "c2.qti.avc.encoder", "c2.android.avc.encoder", true, true),
                CodecSupport.Row("vp9", null, "c2.android.vp9.encoder", true, false),
            ),
            584, 1280, 30,
        )
        assertTrue(text.startsWith("Encoders for 584x1280 @30 fps"))
        assertTrue(text.contains("H.264: hardware c2.qti.avc.encoder; software c2.android.avc.encoder"))
        assertTrue(text.contains("VP9: hardware none; software c2.android.vp9.encoder (size/rate NOT supported)"))
    }
}
