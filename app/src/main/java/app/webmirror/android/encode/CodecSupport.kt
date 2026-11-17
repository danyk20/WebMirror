package app.webmirror.android.encode

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import app.webmirror.android.stream.CodecChooser

/**
 * What the phone's encoders can do, per codec: the hardware encoder (if any), the software encoder (if any), and
 * whether the current picture size and frame rate fit. The pure parts ([isHardwareByName], [pick], [describe])
 * are unit-tested; [detect] reads MediaCodecList.
 */
object CodecSupport {

    private const val TAG = "WebMirror"

    /** Codec id to MIME type, in display order. */
    val MIMES: Map<String, String> = linkedMapOf(
        CodecChooser.H264 to "video/avc",
        CodecChooser.HEVC to "video/hevc",
        CodecChooser.VP9 to "video/x-vnd.on2.vp9",
        CodecChooser.AV1 to "video/av01",
    )

    val LABELS: Map<String, String> = mapOf(
        CodecChooser.H264 to "H.264", CodecChooser.HEVC to "HEVC", CodecChooser.VP9 to "VP9", CodecChooser.AV1 to "AV1",
    )

    /** One encoder: [name] and whether it is hardware. */
    data class Cand(val name: String, val hardware: Boolean)

    /** Android 10 has no isHardwareAccelerated for codecs in the SDK we may rely on here, so older data uses the name. */
    fun isHardwareByName(name: String): Boolean {
        val n = name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw"))
    }

    /** First hardware and first software encoder of [cands] (in the order MediaCodecList lists them). */
    fun pick(cands: List<Cand>): Pair<String?, String?> =
        cands.firstOrNull { it.hardware }?.name to cands.firstOrNull { !it.hardware }?.name

    /** One row of the table shown in the app and logged. */
    data class Row(val codec: String, val hw: String?, val sw: String?, val hwFits: Boolean, val swFits: Boolean)

    fun describe(rows: List<Row>, width: Int, height: Int, fps: Int): String = buildString {
        append("Encoders for ${width}x$height @$fps fps\n")
        for (r in rows) {
            append(CodecSupport.LABELS[r.codec] ?: r.codec).append(": ")
            append("hardware ").append(r.hw?.let { it + if (r.hwFits) "" else " (size/rate NOT supported)" } ?: "none")
            append("; software ").append(r.sw?.let { it + if (r.swFits) "" else " (size/rate NOT supported)" } ?: "none")
            append('\n')
        }
    }.trimEnd()

    @Volatile
    private var cache: Map<String, List<Pair<Cand, MediaCodecInfo>>>? = null
    private var logged = false

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly else isHardwareByName(info.name)

    private fun enumerate(): Map<String, List<Pair<Cand, MediaCodecInfo>>> {
        cache?.let { return it }
        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        } catch (e: Exception) {
            Log.w(TAG, "cannot list codecs: $e")
            emptyList()
        }
        val out = LinkedHashMap<String, List<Pair<Cand, MediaCodecInfo>>>()
        for ((codec, mime) in MIMES) {
            out[codec] = infos.filter { i -> i.supportedTypes.any { it.equals(mime, true) } }
                .map { Cand(it.name, isHardware(it)) to it }
        }
        cache = out
        return out
    }

    private fun fits(info: MediaCodecInfo, mime: String, w: Int, h: Int, fps: Int): Boolean = try {
        info.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) == true
    } catch (e: Exception) {
        false
    }

    /** The table for a picture size and rate. */
    fun rows(width: Int, height: Int, fps: Int): List<Row> = enumerate().map { (codec, list) ->
        val mime = MIMES.getValue(codec)
        val (hw, sw) = pick(list.map { it.first })
        fun ok(name: String?) = name == null || list.first { it.first.name == name }.let { fits(it.second, mime, width, height, fps) }
        Row(codec, hw, sw, ok(hw), ok(sw))
    }

    fun phoneCaps(width: Int, height: Int, fps: Int): CodecChooser.PhoneCaps = CodecChooser.PhoneCaps(
        rows(width, height, fps).associate { it.codec to CodecChooser.PhoneCodec(it.hw, it.sw, it.hwFits, it.swFits) },
    )

    /** Logs the table once per process. */
    @Synchronized
    fun logOnce(width: Int, height: Int, fps: Int) {
        if (logged) return
        logged = true
        Log.i(TAG, "codecs on this phone:\n" + describe(rows(width, height, fps), width, height, fps))
    }
}
