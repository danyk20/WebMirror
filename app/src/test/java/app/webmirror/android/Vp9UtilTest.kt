package app.webmirror.android

import app.webmirror.android.encode.Vp9Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vp9UtilTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // Starts of real key frames written by libvpx (ffmpeg -c:v libvpx-vp9), 584x1280.
    private val profile0Key = bytes(0x82, 0x49, 0x83, 0x42, 0x00, 0x24, 0x70, 0x4f)
    private val profile1Key = bytes(0xa2, 0x49, 0x83, 0x42, 0xe0, 0x24, 0x70, 0x4f)
    private val profile2Key = bytes(0x92, 0x49, 0x83, 0x42, 0x00, 0x12, 0x38, 0x27) // 10-bit

    @Test
    fun parsesProfileAndBitDepthOfKeyFrames() {
        assertEquals(Vp9Util.Header(0, 8, true), Vp9Util.parseHeader(profile0Key))
        assertEquals(Vp9Util.Header(1, 8, true), Vp9Util.parseHeader(profile1Key))
        assertEquals(Vp9Util.Header(2, 10, true), Vp9Util.parseHeader(profile2Key))
    }

    @Test
    fun deltaFramesAndGarbageAreNotKeyFrames() {
        // frame_marker 2, profile 0, show_existing 0, frame_type 1 (non-key), show_frame 1, error_res 0
        val delta = bytes(0x86, 0x00, 0x00)
        assertFalse(Vp9Util.isKeyframe(delta))
        assertNotNull(Vp9Util.parseHeader(delta))
        assertTrue(Vp9Util.isKeyframe(profile0Key))
        assertNull(Vp9Util.parseHeader(bytes(0x00, 0x00, 0x00)))
        assertNull(Vp9Util.parseHeader(bytes(0x82)))
        // wrong sync code
        assertNull(Vp9Util.parseHeader(bytes(0x82, 0x49, 0x83, 0x43, 0x00)))
    }

    @Test
    fun levelFollowsTheAnnexATable() {
        assertEquals(31, Vp9Util.level(584, 1280, 30))   // the Smoother preset: same as the probe's vp09.00.31.08
        assertEquals(40, Vp9Util.level(864, 1920, 15))   // the Sharper preset
        assertEquals(40, Vp9Util.level(584, 1280, 60))   // the Smoothest preset: 44.9 Msamples/s is above level 3.1 (36.9) and fits 4.0 (83.6)
        assertEquals(40, Vp9Util.level(1280, 720, 60))
        assertEquals(10, Vp9Util.level(176, 144, 15))
        assertEquals(41, Vp9Util.level(1920, 1080, 60))
        assertEquals(62, Vp9Util.level(7680, 4320, 120))
    }

    @Test
    fun codecStringHasProfileLevelDepth() {
        assertEquals("vp09.00.31.08", Vp9Util.codecString(profile0Key, 584, 1280, 30))
        assertEquals("vp09.02.31.10", Vp9Util.codecString(profile2Key, 584, 1280, 30))
        assertEquals("vp09.00.31.08", Vp9Util.codecString(bytes(1, 2, 3), 584, 1280, 30)) // unparsable: profile 0, 8-bit
    }
}
