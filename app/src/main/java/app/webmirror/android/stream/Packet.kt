package app.webmirror.android.stream

/**
 * One binary WebSocket message: 12-byte big-endian header + payload (wire protocol v1, plan section 2.1).
 * Free of Android classes so it can be unit-tested on the JVM.
 */
class Packet(
    val type: Int,
    val flags: Int,
    val ptsUs: Long,
    val payload: ByteArray,
) {
    val isKeyframe: Boolean get() = (flags and FLAG_KEYFRAME) != 0

    fun toBytes(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        out[1] = flags.toByte()
        // bytes 2..3 reserved = 0
        val hi = (ptsUs ushr 32).toInt()
        val lo = ptsUs.toInt()
        putInt(out, 4, hi)
        putInt(out, 8, lo)
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    private fun putInt(dst: ByteArray, offset: Int, v: Int) {
        dst[offset] = (v ushr 24).toByte()
        dst[offset + 1] = (v ushr 16).toByte()
        dst[offset + 2] = (v ushr 8).toByte()
        dst[offset + 3] = v.toByte()
    }

    companion object {
        const val HEADER_SIZE = 12
        const val TYPE_H264 = 1
        const val TYPE_JPEG = 2
        const val TYPE_PCM = 3

        /** Not part of the stream protocol: dummy data for the M0 throughput test ("blast"). */
        const val TYPE_BLAST = 0
        const val FLAG_KEYFRAME = 1
    }
}
