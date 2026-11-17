package app.webmirror.android.encode

/**
 * Pure helpers for AV1 in the low-overhead bitstream format (OBUs with size fields), as MediaCodec produces it.
 * Sources: AV1 Bitstream & Decoding Process Specification (5.3 OBU syntax, 5.5 sequence header) and the codec
 * string `av01.P.LLT.DD` of the AV1 Codec ISO Media File Format Binding. The W3C WebCodecs AV1 registration says:
 * `description` is not used; a chunk is one temporal unit (the leading temporal delimiter may be omitted); a key
 * chunk must contain a sequence header OBU before the first frame header. So the encoder's codec-config sequence
 * header is prepended to every keyframe and nothing is sent in `hello` besides the codec string.
 */
object Av1Util {

    const val OBU_SEQUENCE_HEADER = 1
    const val OBU_TEMPORAL_DELIMITER = 2
    const val OBU_FRAME_HEADER = 3
    const val OBU_FRAME = 6

    /** One OBU: [start] of its header byte, [end] exclusive, [type], [payloadStart]. */
    data class Obu(val start: Int, val end: Int, val type: Int, val payloadStart: Int)

    private fun leb128(b: ByteArray, pos: Int): Pair<Long, Int>? {
        var value = 0L
        for (i in 0 until 8) {
            if (pos + i >= b.size) return null
            val v = b[pos + i].toInt() and 0xFF
            value = value or ((v and 0x7F).toLong() shl (7 * i))
            if (v and 0x80 == 0) return value to (i + 1)
        }
        return null
    }

    /** Splits [data] into OBUs; stops at the first malformed one. An OBU without size field extends to the end. */
    fun parseObus(data: ByteArray): List<Obu> {
        val out = ArrayList<Obu>()
        var p = 0
        while (p < data.size) {
            val h = data[p].toInt() and 0xFF
            if (h and 0x80 != 0) break // forbidden bit
            val type = (h shr 3) and 0xF
            val ext = (h shr 2) and 1
            val hasSize = (h shr 1) and 1
            var q = p + 1 + ext
            if (q > data.size) break
            val end: Int
            if (hasSize == 1) {
                val (size, n) = leb128(data, q) ?: break
                q += n
                end = (q + size).toInt()
                if (size < 0 || end > data.size || end < q) break
            } else {
                end = data.size
            }
            out.add(Obu(p, end, type, q))
            p = end
        }
        return out
    }

    /**
     * The sequence header OBU (with header and size field) out of the encoder's codec-config bytes: either bare OBUs
     * or an `av1C` record (first byte 0x81, 4 fixed bytes, then the config OBUs). Null if there is none.
     */
    fun sequenceHeader(csd: ByteArray): ByteArray? {
        val body = if (csd.isNotEmpty() && (csd[0].toInt() and 0xFF) == 0x81 && csd.size > 4) csd.copyOfRange(4, csd.size) else csd
        val o = parseObus(body).firstOrNull { it.type == OBU_SEQUENCE_HEADER } ?: return null
        if ((body[o.start].toInt() shr 1) and 1 == 0) return null // no size field: cannot be put in front of other OBUs
        return body.copyOfRange(o.start, o.end)
    }

    fun hasSequenceHeader(tu: ByteArray): Boolean = parseObus(tu).any { it.type == OBU_SEQUENCE_HEADER }

    /** [tu] with [seq] inserted after the temporal delimiter (if any), unless it already has a sequence header. */
    fun withSequenceHeader(tu: ByteArray, seq: ByteArray): ByteArray {
        val obus = parseObus(tu)
        if (obus.any { it.type == OBU_SEQUENCE_HEADER }) return tu
        val at = obus.firstOrNull()?.takeIf { it.type == OBU_TEMPORAL_DELIMITER }?.end ?: 0
        return tu.copyOfRange(0, at) + seq + tu.copyOfRange(at, tu.size)
    }

    /** True if the first frame header in [tu] is a shown KEY_FRAME (assumes reduced_still_picture_header = 0). */
    fun isKeyframe(tu: ByteArray): Boolean {
        val o = parseObus(tu).firstOrNull { it.type == OBU_FRAME || it.type == OBU_FRAME_HEADER } ?: return false
        if (o.payloadStart >= tu.size) return false
        val b = tu[o.payloadStart].toInt() and 0xFF
        return (b shr 7) == 0 && ((b shr 5) and 3) == 0 // show_existing_frame = 0, frame_type = KEY_FRAME
    }

    private class Reader(val b: ByteArray, var pos: Int) {
        fun bit(): Int {
            val i = pos shr 3
            if (i >= b.size) throw IndexOutOfBoundsException()
            val v = (b[i].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun bits(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or bit().toLong() }
            return v
        }
        fun skip(n: Int) { repeat(n) { bit() } }
    }

    data class SeqInfo(val profile: Int, val levelIdx: Int, val tier: Int, val bitDepth: Int)

    /** Parses profile, level 0, tier 0 and bit depth from a sequence header OBU ([obu] = header byte + size + payload). */
    fun parseSequenceHeader(obu: ByteArray): SeqInfo? = try {
        val o = parseObus(obu).firstOrNull { it.type == OBU_SEQUENCE_HEADER } ?: return null
        val r = Reader(obu, o.payloadStart * 8)
        val profile = r.bits(3).toInt()
        r.bit() // still_picture
        val reduced = r.bit() == 1
        var level = 0
        var tier = 0
        var decoderModel = false
        var bufDelayLen = 0
        if (reduced) {
            level = r.bits(5).toInt()
        } else {
            if (r.bit() == 1) { // timing_info_present_flag
                r.skip(32); r.skip(32)
                if (r.bit() == 1) { // equal_picture_interval: uvlc num_ticks_per_picture_minus_1
                    var zeros = 0
                    while (r.bit() == 0 && zeros < 32) zeros++
                    if (zeros < 32) r.skip(zeros)
                }
                decoderModel = r.bit() == 1
                if (decoderModel) {
                    bufDelayLen = r.bits(5).toInt() + 1
                    r.skip(32)
                    r.skip(5); r.skip(5)
                }
            }
            val initialDisplayDelay = r.bit() == 1
            val cnt = r.bits(5).toInt() + 1
            for (i in 0 until cnt) {
                r.skip(12)
                val lvl = r.bits(5).toInt()
                val t = if (lvl > 7) r.bit() else 0
                if (i == 0) { level = lvl; tier = t }
                if (decoderModel && r.bit() == 1) { r.skip(bufDelayLen); r.skip(bufDelayLen); r.bit() }
                if (initialDisplayDelay && r.bit() == 1) r.skip(4)
            }
        }
        val wBits = r.bits(4).toInt() + 1
        val hBits = r.bits(4).toInt() + 1
        r.skip(wBits); r.skip(hBits)
        if (!reduced && r.bit() == 1) { r.skip(4); r.skip(3) } // frame ids
        r.skip(3) // 128x128 superblock, filter intra, intra edge filter
        if (!reduced) {
            r.skip(4) // interintra, masked, warped, dual filter
            val orderHint = r.bit() == 1
            if (orderHint) r.skip(2)
            val chooseSct = r.bit() == 1
            val forceSct = if (chooseSct) 2 else r.bit()
            if (forceSct > 0) { if (r.bit() == 0) r.bit() }
            if (orderHint) r.skip(3)
        }
        r.skip(3) // superres, cdef, restoration
        val high = r.bit() == 1
        val depth = if (profile == 2 && high) (if (r.bit() == 1) 12 else 10) else if (high) 10 else 8
        SeqInfo(profile, level, tier, depth)
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** `av01.P.LLT.DD`, e.g. `av01.0.08M.08`. */
    fun codecString(seq: SeqInfo): String =
        "av01.%d.%02d%s.%02d".format(seq.profile, seq.levelIdx, if (seq.tier == 1) "H" else "M", seq.bitDepth)

    fun codecString(sequenceHeaderObu: ByteArray): String? = parseSequenceHeader(sequenceHeaderObu)?.let { codecString(it) }
}
