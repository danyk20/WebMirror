package app.webmirror.android.encode

/** Pure helpers for HEVC (H.265) Annex-B data. */
object HevcUtil {

    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    /** HEVC NAL header: forbidden_zero(1) type(6) layer_id(6) temporal_id_plus1(3); [type] is bits 1..6 of byte 0. */
    private fun nalType(headerByte0: Int): Int = (headerByte0 shr 1) and 0x3F

    /** Start offset (first byte after the start code) of every NAL unit in [annexB]. */
    private fun nalStarts(annexB: ByteArray, limit: Int = annexB.size): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        val end = minOf(limit, annexB.size)
        while (i + 3 <= end) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                out.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        return out
    }

    /**
     * Removes emulation prevention bytes (`00 00 03` -> `00 00`) from [src] starting at [from], stopping once
     * [max] bytes were produced or a start code begins.
     */
    private fun unescape(src: ByteArray, from: Int, max: Int): ByteArray {
        val out = ArrayList<Byte>()
        var zeros = 0
        var i = from
        while (i < src.size && out.size < max) {
            val b = src[i].toInt() and 0xFF
            if (zeros >= 2 && b == 1) break // next start code
            if (zeros >= 2 && b == 3) {
                zeros = 0
                i++
                continue
            }
            out.add(b.toByte())
            zeros = if (b == 0) zeros + 1 else 0
            i++
        }
        return out.toByteArray()
    }

    /**
     * The WebCodecs / ISO/IEC 14496-15 Annex E codec string (`hev1.<profile_space letter><profile_idc>.<compat
     * flags, bit-reversed, hex>.<L|H><level_idc>.<constraint bytes in hex, trailing zero bytes omitted>`) from
     * the first SPS NAL unit in [annexB], or null if there is none. The fields of profile_tier_level sit at fixed
     * byte positions after the 2-byte NAL header and the one byte vps_id / max_sub_layers / nesting.
     */
    fun codecString(annexB: ByteArray): String? {
        for (start in nalStarts(annexB)) {
            if (start + 1 >= annexB.size || nalType(annexB[start].toInt() and 0xFF) != NAL_SPS) continue
            val p = unescape(annexB, start + 2, 13) // 1 + 1 + 4 + 6 + 1 bytes
            if (p.size < 13) return null
            val b1 = p[1].toInt() and 0xFF
            val profileSpace = b1 shr 6
            val high = ((b1 shr 5) and 1) == 1
            val profileIdc = b1 and 0x1F
            var compat = 0
            for (k in 0 until 4) compat = (compat shl 8) or (p[2 + k].toInt() and 0xFF)
            val level = p[12].toInt() and 0xFF
            val sb = StringBuilder("hev1.")
            if (profileSpace > 0) sb.append(('A' + profileSpace - 1))
            sb.append(profileIdc).append('.')
            sb.append(Integer.toHexString(Integer.reverse(compat))).append('.')
            sb.append(if (high) 'H' else 'L').append(level)
            var last = 5
            while (last > 0 && p[6 + last].toInt() == 0) last--
            for (k in 0..last) sb.append('.').append("%X".format(p[6 + k].toInt() and 0xFF))
            return sb.toString()
        }
        return null
    }

    /** Readable profile of a codec string from [codecString], e.g. "main", "main 10". */
    fun profileName(codec: String): String {
        val field = codec.removePrefix("hev1.").substringBefore('.')
        return when (field.trimStart('A', 'B', 'C').toIntOrNull()) {
            1 -> "main"
            2 -> "main 10"
            3 -> "main still picture"
            4 -> "range extensions"
            else -> "profile $field"
        }
    }

    /**
     * True when one of the first NAL units in [annexB] (a frame, without a leading codec config) is an IRAP
     * picture (NAL types 16..23: BLA, IDR, CRA), i.e. a point where decoding can start.
     */
    fun isKeyframe(annexB: ByteArray): Boolean {
        for (start in nalStarts(annexB, 128)) {
            if (start >= annexB.size) break
            val t = nalType(annexB[start].toInt() and 0xFF)
            if (t in 16..23) return true
            if (t < 32) return false // a non-IRAP VCL NAL (slice) comes first: a delta frame
        }
        return false
    }
}
