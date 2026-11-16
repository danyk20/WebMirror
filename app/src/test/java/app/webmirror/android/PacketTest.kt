package app.webmirror.android

import app.webmirror.android.stream.Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {

    @Test
    fun ptsIsEncodedBigEndianAcrossHiAndLo() {
        val bytes = Packet(1, 0, 0x1_0000_0002L, ByteArray(0)).toBytes()
        val expected = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2)
        assertArrayEquals(expected, bytes.copyOfRange(4, 12))
    }

    @Test
    fun headerFieldsAndPayload() {
        val payload = byteArrayOf(9, 8, 7)
        val bytes = Packet(Packet.TYPE_H264, Packet.FLAG_KEYFRAME, 5L, payload).toBytes()
        assertEquals(Packet.HEADER_SIZE + 3, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(1, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
        assertArrayEquals(payload, bytes.copyOfRange(12, 15))
    }

    @Test
    fun highBitsAreNotSignExtended() {
        val bytes = Packet(2, 0, 0xFFFF_FFFFL, ByteArray(0)).toBytes()
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, -1, -1, -1, -1), bytes.copyOfRange(4, 12))
    }

    @Test
    fun keyframeFlag() {
        assertTrue(Packet(1, 1, 0, ByteArray(0)).isKeyframe)
        assertTrue(!Packet(1, 0, 0, ByteArray(0)).isKeyframe)
    }
}
