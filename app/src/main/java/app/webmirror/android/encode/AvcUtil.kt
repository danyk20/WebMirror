package app.webmirror.android.encode

/** Pure helpers for H.264 Annex-B data. */
object AvcUtil {

    /**
     * Returns the RFC 6381 codec string (`avc1.PPCCLL`) from the first SPS NAL unit in [annexB], or null
     * if there is none. Profile, constraint flags and level are the three bytes after the NAL header.
     */
    fun codecString(annexB: ByteArray): String? {
        var i = 0
        while (i + 3 < annexB.size) {
            val startCode3 = annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1
            if (startCode3) {
                val nal = i + 3
                if (nal + 3 < annexB.size && (annexB[nal].toInt() and 0x1F) == 7) {
                    val sb = StringBuilder("avc1.")
                    for (k in 1..3) sb.append("%02X".format(annexB[nal + k].toInt() and 0xFF))
                    return sb.toString()
                }
                i = nal
            } else {
                i++
            }
        }
        return null
    }
}
