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

    /**
     * "Fit to viewer": the largest stream that fits inside the viewer's physical pixels ([boxW] x [boxH])
     * keeping the screen's aspect ratio, never larger than the screen itself, with the longest side capped at
     * [maxLongSide], each side rounded down to a multiple of 16 (at least 16).
     */
    fun fit(screenW: Int, screenH: Int, maxLongSide: Int, boxW: Int, boxH: Int): Size {
        require(screenW > 0 && screenH > 0 && maxLongSide > 0 && boxW > 0 && boxH > 0) { "sizes must be positive" }
        val longSide = maxOf(screenW, screenH)
        // scale = min(box/screen per axis, cap/longSide, 1), compared as fractions to stay in integers
        var w = screenW.toLong()
        var h = screenH.toLong()
        fun shrink(num: Long, den: Long) {
            if (num < den) {
                w = w * num / den
                h = h * num / den
            }
        }
        shrink(maxLongSide.toLong(), longSide.toLong())
        // box limit: the more restrictive axis, measured on the already capped size
        val capW = w
        val capH = h
        if (boxW.toLong() * capH < boxH.toLong() * capW) shrink(boxW.toLong(), capW) else shrink(boxH.toLong(), capH)
        return Size(floor(w, 16), floor(h, 16))
    }

    /**
     * Avoids re-encoding for small viewer-window changes: keeps [current] when [target] differs from it by at
     * most [tolerance] (fraction) in both width and height.
     */
    fun keepIfClose(current: Size?, target: Size, tolerance: Double = 0.10): Size {
        if (current == null || current.width <= 0 || current.height <= 0) return target
        val dw = Math.abs(target.width - current.width).toDouble() / current.width
        val dh = Math.abs(target.height - current.height).toDouble() / current.height
        return if (dw <= tolerance && dh <= tolerance) current else target
    }

    private fun floor(v: Long, m: Int): Int = maxOf(m.toLong(), v - v % m).toInt()

    private fun floor8(v: Long): Int = maxOf(8L, v - v % 8).toInt()
}
