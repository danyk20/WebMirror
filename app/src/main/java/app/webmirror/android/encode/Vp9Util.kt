package app.webmirror.android.encode

/**
 * Pure helpers for VP9 frames as MediaCodec produces them (one frame, or a superframe, per buffer, no start codes).
 * Sources: VP9 Bitstream & Decoding Process Specification (uncompressed_header, Annex A levels) and the
 * "VP Codec ISO Media File Format Binding" codec string `vp09.PP.LL.DD` (as referenced by the W3C WebCodecs VP9
 * registration, which says chunk data is one VP9 frame and `description` is not used).
 */
object Vp9Util {

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun bit(): Int {
            val i = pos shr 3
            if (i >= b.size) return 0
            val v = (b[i].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }
    }

    data class Header(val profile: Int, val bitDepth: Int, val keyframe: Boolean)

    /** Parses the start of the uncompressed header; null if it is not a VP9 frame. Bit depth is only known on key frames (else 0). */
    fun parseHeader(frame: ByteArray): Header? {
        if (frame.size < 2) return null
        val r = Reader(frame)
        if (r.bits(2) != 2) return null // frame_marker
        val low = r.bit()
        val high = r.bit()
        val profile = (high shl 1) + low
        if (profile == 3) r.bit() // reserved_zero
        if (r.bit() == 1) return Header(profile, 0, false) // show_existing_frame
        val frameType = r.bit() // 0 = KEY_FRAME
        r.bit() // show_frame
        r.bit() // error_resilient_mode
        if (frameType != 0) return Header(profile, 0, false)
        if (r.bits(8) != 0x49 || r.bits(8) != 0x83 || r.bits(8) != 0x42) return null // frame_sync_code
        val depth = if (profile >= 2) (if (r.bit() == 1) 12 else 10) else 8
        return Header(profile, depth, true)
    }

    fun isKeyframe(frame: ByteArray): Boolean = parseHeader(frame)?.keyframe == true

    private class Level(val code: Int, val maxLumaPs: Long, val maxLumaSr: Long)

    // Annex A of the VP9 specification: level, MaxLumaPictureSize, MaxLumaSampleRate.
    private val LEVELS = listOf(
        Level(10, 36_864, 829_440), Level(11, 73_728, 2_764_800), Level(20, 122_880, 4_608_000),
        Level(21, 245_760, 9_216_000), Level(30, 552_960, 20_736_000), Level(31, 983_040, 36_864_000),
        Level(40, 2_228_224, 83_558_400), Level(41, 2_228_224, 160_432_128), Level(50, 8_912_896, 311_951_360),
        Level(51, 8_912_896, 588_251_136), Level(52, 8_912_896, 1_176_502_272), Level(60, 35_651_584, 1_176_502_272),
        Level(61, 35_651_584, 2_353_004_544), Level(62, 35_651_584, 4_706_009_088),
    )

    /** Smallest VP9 level (as the two digits of the codec string, 31 = 3.1) that carries [width] x [height] at [fps]. */
    fun level(width: Int, height: Int, fps: Int): Int {
        val ps = width.toLong() * height
        val sr = ps * fps
        val longSide = maxOf(width, height)
        return (LEVELS.firstOrNull { ps <= it.maxLumaPs && sr <= it.maxLumaSr && longSide * longSide <= 8 * it.maxLumaPs }
            ?: LEVELS.last()).code
    }

    /** `vp09.PP.LL.DD`; profile and depth from a key frame (profile 0, 8-bit if it cannot be parsed). */
    fun codecString(keyframe: ByteArray, width: Int, height: Int, fps: Int): String {
        val h = parseHeader(keyframe)
        val profile = h?.profile ?: 0
        val depth = h?.bitDepth?.takeIf { it > 0 } ?: 8
        return "vp09.%02d.%02d.%02d".format(profile, level(width, height, fps), depth)
    }
}
