package app.webmirror.android.encode

import app.webmirror.android.stream.StreamConfig

/**
 * Pure choices behind the H.264 encoder options (plan 2.1 `cfg` table): which MediaCodec profile/level to
 * request, given what the encoder lists. Values are the MediaCodecInfo.CodecProfileLevel constants, written
 * out so this file has no Android dependency and can be unit-tested.
 */
object EncoderOptions {

    const val AVC_PROFILE_BASELINE = 0x01
    const val AVC_PROFILE_MAIN = 0x02
    const val AVC_PROFILE_HIGH = 0x08
    const val AVC_PROFILE_CONSTRAINED_BASELINE = 0x10000
    const val HEVC_PROFILE_MAIN = 0x01
    const val VP9_PROFILE_0 = 0x01
    const val AV1_PROFILE_MAIN8 = 0x01

    /** Result of [pickProfile]: [profile] is null for "let the encoder decide". */
    data class ProfilePick(val profile: Int?, val description: String)

    /**
     * [requested] is `StreamConfig.profile`; [supported] the profile constants from the encoder's
     * `profileLevels`. "baseline" prefers Constrained Baseline (no B-frames, no reordering), then Baseline.
     * A profile the encoder does not list falls back to auto, and says so.
     */
    fun pickProfile(requested: String, supported: Set<Int>): ProfilePick = when (requested) {
        StreamConfig.PROFILE_BASELINE -> when {
            AVC_PROFILE_CONSTRAINED_BASELINE in supported ->
                ProfilePick(AVC_PROFILE_CONSTRAINED_BASELINE, "constrained baseline (requested)")
            AVC_PROFILE_BASELINE in supported -> ProfilePick(AVC_PROFILE_BASELINE, "baseline (requested)")
            else -> ProfilePick(null, "auto (baseline requested but not listed by the encoder)")
        }
        StreamConfig.PROFILE_MAIN ->
            if (AVC_PROFILE_MAIN in supported) ProfilePick(AVC_PROFILE_MAIN, "main (requested)")
            else ProfilePick(null, "auto (main requested but not listed by the encoder)")
        StreamConfig.PROFILE_HIGH ->
            if (AVC_PROFILE_HIGH in supported) ProfilePick(AVC_PROFILE_HIGH, "high (requested)")
            else ProfilePick(null, "auto (high requested but not listed by the encoder)")
        else -> ProfilePick(null, "auto (encoder default)")
    }

    /** HEVC always uses Main (8-bit 4:2:0, widely supported, hardware decodable); a missing listing leaves it to the encoder. */
    fun pickHevcProfile(supported: Set<Int>): ProfilePick =
        if (HEVC_PROFILE_MAIN in supported) ProfilePick(HEVC_PROFILE_MAIN, "main (HEVC, fixed)")
        else ProfilePick(null, "auto (HEVC main not listed by the encoder)")

    /** VP9 profile 0 / AV1 Main 8-bit: [wanted] if the encoder lists it, else the encoder decides. */
    fun pickFixedProfile(supported: Set<Int>, wanted: Int, label: String): ProfilePick =
        if (wanted in supported) ProfilePick(wanted, "$label (fixed)") else ProfilePick(null, "auto ($label not listed by the encoder)")

    // MediaCodecInfo.CodecProfileLevel.AVCLevel* in increasing order, with H.264 table A-1 limits.
    private class Level(val constant: Int, val maxFrameMbs: Int, val maxMbPerSec: Int)

    private val LEVELS = listOf(
        Level(0x1, 99, 1485), Level(0x4, 396, 3000), Level(0x8, 396, 6000), Level(0x10, 396, 11880),
        Level(0x20, 396, 11880), Level(0x40, 792, 19800), Level(0x80, 1620, 20250), Level(0x100, 1620, 40500),
        Level(0x200, 3600, 108000), Level(0x400, 5120, 216000), Level(0x800, 8192, 245760),
        Level(0x1000, 8192, 245760), Level(0x2000, 8704, 522240), Level(0x4000, 22080, 589824),
        Level(0x8000, 36864, 983040), Level(0x10000, 36864, 2073600),
    )

    /**
     * The smallest listed AVC level (MediaCodecInfo constant) that can carry [width] x [height] at [fps], or
     * null when [listedLevels] has none (then no level is requested and the encoder decides).
     */
    fun pickLevel(width: Int, height: Int, fps: Int, listedLevels: Set<Int>): Int? {
        val frameMbs = ((width + 15) / 16) * ((height + 15) / 16)
        val mbPerSec = frameMbs.toLong() * fps
        return LEVELS.firstOrNull { it.constant in listedLevels && frameMbs <= it.maxFrameMbs && mbPerSec <= it.maxMbPerSec }
            ?.constant
    }
}
