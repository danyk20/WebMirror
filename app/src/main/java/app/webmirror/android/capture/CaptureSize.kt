package app.webmirror.android.capture

/** Pure size rule of plan 2.3. Free of Android classes so it can be unit-tested on the JVM. */
object CaptureSize {

    data class Size(val width: Int, val height: Int)

    /**
     * Scales the real screen size so the longest side equals [maxLongSide] (never upscales), keeps the
     * aspect ratio and rounds each side down to a multiple of 8 (at least 8).
     */
    fun compute(screenW: Int, screenH: Int, maxLongSide: Int): Size {
        require(screenW > 0 && screenH > 0 && maxLongSide > 0) { "sizes must be positive" }
        val longSide = maxOf(screenW, screenH)
        val w: Long
        val h: Long
        if (longSide <= maxLongSide) {
            w = screenW.toLong()
            h = screenH.toLong()
        } else {
            w = screenW.toLong() * maxLongSide / longSide
            h = screenH.toLong() * maxLongSide / longSide
        }
        return Size(floor8(w), floor8(h))
    }

    private fun floor8(v: Long): Int = maxOf(8L, v - v % 8).toInt()
}
