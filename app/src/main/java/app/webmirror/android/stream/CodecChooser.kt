package app.webmirror.android.stream

/**
 * Picks the video codec from what the phone can encode and what every connected viewer can decode (plan 2.2).
 * Pure Kotlin, JVM-tested.
 */
object CodecChooser {

    const val H264 = "h264"
    const val HEVC = "hevc"
    const val VP9 = "vp9"
    const val AV1 = "av1"
    const val JPEG = "jpeg"

    /** Codec ids that are video modes, in no particular order. */
    val VIDEO_CODECS = listOf(H264, HEVC, VP9, AV1)

    /**
     * Auto-mode preference, most efficient first. Easy to change: only [H264] has been measured as the reference
     * in the car (car tests 2 and 3); HEVC, AV1 and VP9 are verified in the car only after their `codec-*` test
     * phases ran there. H.264 is always the last resort.
     */
    val PREFERENCE = listOf(HEVC, AV1, VP9, H264)

    /** Decode support of one codec in a browser: [sw] = decodable at all, [hw] = decodable in hardware. */
    data class Support(val sw: Boolean = false, val hw: Boolean = false) {
        val any: Boolean get() = sw || hw
    }

    /** What one viewer said in its `hello`. */
    data class ViewerCaps(val codecs: Map<String, Support>) {
        fun support(codec: String): Support = codecs[codec] ?: Support()
        val anyVideo: Boolean get() = codecs.values.any { it.any }

        companion object {
            /** From the old `h264` / `hevc` booleans: `hevc` was checked with prefer-hardware, `h264` as decodable at all. */
            fun legacy(h264: Boolean, hevc: Boolean) =
                ViewerCaps(mapOf(H264 to Support(sw = h264), HEVC to Support(sw = hevc, hw = hevc)))
        }
    }

    /** Encoders of the phone for one codec: [hw] / [sw] are MediaCodec names or null; the flags say they fit the current size. */
    data class PhoneCodec(val hw: String? = null, val sw: String? = null, val hwFits: Boolean = true, val swFits: Boolean = true) {
        val usableHw: String? get() = hw.takeIf { hwFits }
        val usableSw: String? get() = sw.takeIf { swFits }
    }

    data class PhoneCaps(val codecs: Map<String, PhoneCodec> = emptyMap()) {
        fun codec(c: String): PhoneCodec = codecs[c] ?: PhoneCodec()
    }

    /** [mode] is a video codec id or [JPEG]; [encoder] is the MediaCodec name to create (null for jpeg). */
    data class Choice(val mode: String, val encoder: String?, val hardware: Boolean, val why: String)

    /** The encoder for [codec]: hardware if usable, else software if [allowSoftware] (or [lastResort]). */
    fun encoderFor(codec: String, phone: PhoneCaps, allowSoftware: Boolean, lastResort: Boolean = false): Pair<String, Boolean>? {
        val p = phone.codec(codec)
        p.usableHw?.let { return it to true }
        if (allowSoftware || lastResort) p.usableSw?.let { return it to false }
        return null
    }

    /**
     * The encoder to create for [codec] (see [encoderFor]). H.264 is the last resort of the whole system: its
     * software encoder is accepted even when software is not allowed, and a size the encoder does not claim to
     * support is tried anyway (that is what the app did before the codec choice existed).
     */
    fun encoderForMode(codec: String, phone: PhoneCaps, allowSoftware: Boolean): Pair<String, Boolean>? {
        encoderFor(codec, phone, allowSoftware, lastResort = codec == H264)?.let { return it }
        if (codec != H264) return null
        val p = phone.codec(H264)
        return p.hw?.let { it to true } ?: p.sw?.let { it to false }
    }

    /** [setting] = "auto" or a codec id. [viewers] = every viewer that said hello (not empty). */
    fun choose(setting: String, allowSoftware: Boolean, phone: PhoneCaps, viewers: List<ViewerCaps>): Choice {
        fun viewersHw(c: String) = viewers.all { it.support(c).hw }
        fun hwOk(c: String) = viewersHw(c)
        val note: String
        if (setting in VIDEO_CODECS) {
            val enc = encoderForMode(setting, phone, allowSoftware)
            // Software decode is accepted for H.264 only, as in auto mode.
            val viewersOk = hwOk(setting) || (setting == H264 && viewers.all { it.support(H264).any })
            if (enc != null && viewersOk) {
                return Choice(setting, enc.first, enc.second, "fixed $setting: ${hwText(enc.second)} on phone and all viewers")
            }
            note = "fixed $setting not usable (" + when {
                enc == null && phone.codec(setting).sw != null && !allowSoftware && phone.codec(setting).hw == null ->
                    "phone has only a software encoder"
                enc == null -> "no usable encoder on the phone"
                else -> "a viewer lacks hardware decode"
            } + "); "
        } else {
            note = ""
        }
        // Hardware encoders first (in preference order), then, if allowed, software encoders.
        for (c in PREFERENCE) {
            val p = phone.codec(c)
            if (p.usableHw != null && hwOk(c)) return Choice(c, p.usableHw, true, "${note}auto: $c hw on phone and all viewers")
        }
        if (allowSoftware) {
            for (c in PREFERENCE) {
                val p = phone.codec(c)
                if (p.usableSw != null && hwOk(c)) return Choice(c, p.usableSw, false, "${note}auto: $c software encoder (allowed), hw decode on all viewers")
            }
        }
        // Software decode is accepted for H.264 only; software H.264 encoding is the last resort on the phone.
        val h = encoderForMode(H264, phone, allowSoftware)
        if (h != null && viewers.all { it.support(H264).any }) {
            return Choice(H264, h.first, h.second, "${note}auto: h264, no codec with hardware on phone and all viewers; viewer software decode accepted")
        }
        return Choice(JPEG, null, false, "${note}jpeg: " + if (h == null) "no usable H.264 encoder on the phone" else "a viewer cannot decode H.264")
    }

    private fun hwText(hw: Boolean) = if (hw) "hw" else "software encoder"
}
