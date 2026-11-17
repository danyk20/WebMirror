package app.webmirror.android

import app.webmirror.android.encode.Av1Util
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Av1UtilTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // Real output of aomenc (libaom, 584x1280, 8-bit 4:2:0): temporal delimiter, sequence header, start of the frame OBU.
    private val td = bytes(0x12, 0x00)
    private val seq = bytes(0x0a, 0x0b, 0x00, 0x00, 0x00, 0x2c, 0xd4, 0x8f, 0x3f, 0xdd, 0xaf, 0x98, 0x04)
    private val frameKey = bytes(0x32, 0x03, 0x14, 0x00, 0x24)      // OBU_FRAME, show_existing 0, frame_type KEY
    private val frameDelta = bytes(0x32, 0x03, 0x34, 0x00, 0x24)    // frame_type INTER

    /** Writes bits MSB first, for building sequence headers that real encoders do not emit here. */
    private class Bits {
        val bits = ArrayList<Int>()
        fun put(v: Long, n: Int): Bits { for (i in n - 1 downTo 0) bits.add(((v shr i) and 1).toInt()); return this }
        fun obu(): ByteArray {
            val padded = ArrayList(bits)
            padded.add(1) // trailing bit
            while (padded.size % 8 != 0) padded.add(0)
            val payload = ByteArray(padded.size / 8) { i -> (0 until 8).fold(0) { a, k -> (a shl 1) or padded[i * 8 + k] }.toByte() }
            return byteArrayOf(0x0a, payload.size.toByte()) + payload
        }
    }

    @Test
    fun codecStringFromARealSequenceHeader() {
        val info = Av1Util.parseSequenceHeader(seq)!!
        assertEquals(Av1Util.SeqInfo(profile = 0, levelIdx = 5, tier = 0, bitDepth = 8), info)
        assertEquals("av01.0.05M.08", Av1Util.codecString(seq))
    }

    @Test
    fun tenBitHighTierWithTimingAndDecoderModelInfo() {
        val b = Bits().put(0, 3).put(0, 1).put(0, 1)          // profile 0, not still, not reduced
            .put(1, 1)                                         // timing_info_present
            .put(1000, 32).put(30000, 32).put(1, 1)            // ticks, time scale, equal_picture_interval
            .put(1, 1)                                         // uvlc 0 = a single '1' bit
            .put(1, 1)                                         // decoder_model_info_present
            .put(9, 5).put(1, 32).put(9, 5).put(9, 5)          // buffer_delay_length_minus_1 = 9 ...
            .put(0, 1)                                         // initial_display_delay_present
            .put(1, 5)                                         // 2 operating points
            .put(0, 12).put(13, 5).put(1, 1).put(1, 1).put(0, 10).put(0, 10).put(0, 1) // op 0: level 13, tier H, decoder model
            .put(0, 12).put(8, 5).put(0, 1).put(0, 1)          // op 1: level 8, tier L, no decoder model
            .put(9, 4).put(9, 4).put(1279, 10).put(719, 10)    // frame size bits and max size
            .put(0, 1)                                         // frame_id_numbers_present
            .put(0, 3)                                         // sb128, filter intra, intra edge
            .put(0, 4).put(1, 1).put(0, 2)                     // interintra.., enable_order_hint + jnt/ref mvs
            .put(1, 1)                                         // seq_choose_screen_content_tools
            .put(1, 1)                                         // seq_choose_integer_mv
            .put(5, 3)                                         // order_hint_bits_minus_1
            .put(0, 3)                                         // superres, cdef, restoration
            .put(1, 1)                                         // high_bitdepth
        val info = Av1Util.parseSequenceHeader(b.obu())!!
        assertEquals(Av1Util.SeqInfo(0, 13, 1, 10), info)
        assertEquals("av01.0.13H.10", Av1Util.codecString(info))
    }

    @Test
    fun twelveBitProfile2AndReducedStillPicture() {
        val p2 = Bits().put(2, 3).put(0, 1).put(0, 1).put(0, 1).put(0, 1).put(0, 5).put(0, 12).put(4, 5)
            .put(9, 4).put(9, 4).put(99, 10).put(99, 10).put(0, 1).put(0, 3).put(0, 4).put(0, 1)
            .put(0, 1).put(0, 1).put(0, 3).put(1, 1).put(1, 1)
        assertEquals("av01.2.04M.12", Av1Util.codecString(p2.obu()))
        val reduced = Bits().put(0, 3).put(1, 1).put(1, 1).put(8, 5)
            .put(9, 4).put(9, 4).put(99, 10).put(99, 10).put(0, 3).put(0, 3).put(0, 1)
        assertEquals("av01.0.08M.08", Av1Util.codecString(reduced.obu()))
    }

    @Test
    fun truncatedOrWrongInputGivesNull() {
        assertNull(Av1Util.parseSequenceHeader(bytes(0x0a, 0x02, 0x00, 0x00)))
        assertNull(Av1Util.parseSequenceHeader(td))
        assertNull(Av1Util.codecString(ByteArray(0)))
    }

    @Test
    fun sequenceHeaderFromCsdBareOrAv1cRecord() {
        assertArrayEquals(seq, Av1Util.sequenceHeader(seq))
        val av1c = bytes(0x81, 0x05, 0x0c, 0x00) + seq
        assertArrayEquals(seq, Av1Util.sequenceHeader(av1c))
        assertNull(Av1Util.sequenceHeader(frameKey))
    }

    @Test
    fun sequenceHeaderGoesAfterTheTemporalDelimiterAndIsNotDuplicated() {
        val tu = td + frameKey
        val out = Av1Util.withSequenceHeader(tu, seq)
        assertArrayEquals(td + seq + frameKey, out)
        assertArrayEquals(seq + frameKey, Av1Util.withSequenceHeader(frameKey, seq))
        val already = td + seq + frameKey
        assertArrayEquals(already, Av1Util.withSequenceHeader(already, seq))
        assertTrue(Av1Util.hasSequenceHeader(already))
        assertFalse(Av1Util.hasSequenceHeader(tu))
    }

    @Test
    fun keyFrameDetection() {
        assertTrue(Av1Util.isKeyframe(td + seq + frameKey))
        assertFalse(Av1Util.isKeyframe(td + frameDelta))
        assertFalse(Av1Util.isKeyframe(td))
    }

    @Test
    fun obuWalkingHandlesMultiByteSizesAndMalformedData() {
        val big = ByteArray(200) { 7 }
        val obu = bytes(0x32, 0xc8, 0x01) + big          // size 200 as leb128
        val parsed = Av1Util.parseObus(td + obu)
        assertEquals(2, parsed.size)
        assertEquals(Av1Util.OBU_FRAME, parsed[1].type)
        assertEquals(td.size + 3, parsed[1].payloadStart)
        assertNotNull(parsed)
        assertEquals(1, Av1Util.parseObus(td + bytes(0x32, 0x7f, 0x00)).size) // second OBU claims more bytes than exist
    }
}
