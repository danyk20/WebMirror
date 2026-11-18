package app.webmirror.android.capture

/** Quality presets (plan 2.3). Pure data so it can be used from JVM tests. */
enum class Preset(
    val key: String,
    val label: String,
    val maxLongSide: Int,
    val fps: Int,
    val h264Bitrate: Int,
    val jpegQuality: Int,
    val jpegFps: Int,
) {
    SHARPER("sharper", "Sharper", 1920, 15, 6_000_000, 70, 15),
    SMOOTHER("smoother", "Smoother", 1280, 30, 4_000_000, 50, 20),
    SMOOTHEST("smoothest", "Smoothest (60 fps)", 1280, 60, 6_000_000, 50, 20);

    /**
     * The H.264/HEVC/VP9/AV1 bitrate ceiling when the picture is limited to [atFps] (the automatic fallback of
     * `FpsGovernor`): two thirds of the preset's value below the preset's own rate (6 -> 4 Mbit/s, the Smoother value).
     */
    fun bitrateAt(atFps: Int): Int = if (atFps >= fps) h264Bitrate else h264Bitrate / 3 * 2

    /** True if the automatic fallback to [FALLBACK_FPS] applies to this preset (only 60 fps). */
    val canFallBack: Boolean get() = fps > FALLBACK_FPS

    companion object {
        /** The rate `FpsGovernor` drops to when the viewer cannot keep up with a higher one. */
        const val FALLBACK_FPS = 30

        fun fromKey(key: String?): Preset = entries.firstOrNull { it.key == key } ?: SMOOTHER
    }
}
