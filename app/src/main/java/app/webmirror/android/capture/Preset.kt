package app.webmirror.android.capture

/** Quality presets (plan 2.3). Pure data so it can be used from JVM tests. */
enum class Preset(
    val key: String,
    val label: String,
    val maxLongSide: Int,
    val fps: Int,
    val h264Bitrate: Int,
    val jpegQuality: Int,
) {
    SHARPER("sharper", "Sharper", 1920, 15, 6_000_000, 80),
    SMOOTHER("smoother", "Smoother", 1280, 30, 4_000_000, 65);

    companion object {
        fun fromKey(key: String?): Preset = entries.firstOrNull { it.key == key } ?: SMOOTHER
    }
}
