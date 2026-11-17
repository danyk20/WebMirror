package app.webmirror.android

import app.webmirror.android.encode.AvcUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvcUtilTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun codecStringFromFourByteStartCode() {
        val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1F, 0x8D, 0x68)
        assertEquals("avc1.42C01F", AvcUtil.codecString(sps))
    }

    @Test
    fun codecStringFromThreeByteStartCodeAndPpsAfterSps() {
        val data = bytes(0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC, 0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80)
        assertEquals("avc1.640028", AvcUtil.codecString(data))
    }

    @Test
    fun skipsNonSpsNalUnitsBeforeTheSps() {
        val data = bytes(0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80, 0, 0, 0, 1, 0x67, 0x4D, 0x40, 0x1F, 0x00)
        assertEquals("avc1.4D401F", AvcUtil.codecString(data))
    }

    @Test
    fun noSpsGivesNull() {
        assertNull(AvcUtil.codecString(bytes(0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80)))
        assertNull(AvcUtil.codecString(ByteArray(0)))
        assertNull(AvcUtil.codecString(bytes(0, 0, 0, 1, 0x67, 0x42)))
    }
}
