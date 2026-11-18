package app.webmirror.android.input

/**
 * The real display (incl. system bars) in its CURRENT rotation, as `Display.getRealSize` and `Display.getRotation`
 * report it: [width] x [height] already swapped for the two landscape rotations. The stream is a scaled copy of exactly
 * this rectangle, so a viewer's normalised picture coordinates (0..1) map linearly onto it. Pure; no Android classes.
 */
data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int) {

    init {
        require(width > 0 && height > 0) { "display size must be positive" }
    }

    /** Pixel x for a normalised x (clamped to 0..1); the last column is `width - 1`. */
    fun mapX(nx: Double): Float = (nx.coerceIn(0.0, 1.0) * (width - 1)).toFloat()

    fun mapY(ny: Double): Float = (ny.coerceIn(0.0, 1.0) * (height - 1)).toFloat()

    /**
     * Whether a picture of [streamW] x [streamH] (the viewer's last `hello`) has the same orientation as the display now.
     * After a 90 degree rotation the viewer may still send coordinates for the old picture; those are ignored.
     * Unknown size (0) is accepted.
     */
    fun matchesStream(streamW: Int, streamH: Int): Boolean =
        streamW <= 0 || streamH <= 0 || (streamW > streamH) == (width > height)
}
