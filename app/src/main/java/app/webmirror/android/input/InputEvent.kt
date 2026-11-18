package app.webmirror.android.input

enum class InputAction { DOWN, MOVE, UP, CANCEL;

    companion object {
        fun parse(s: String): InputAction? = when (s) {
            "down" -> DOWN
            "move" -> MOVE
            "up" -> UP
            "cancel" -> CANCEL
            else -> null
        }
    }
}

/**
 * One validated pointer event of a viewer (plan 2.1 `input`). [x] and [y] are normalised to the picture (0..1) or NaN
 * for `up` / `cancel` messages that carry no position (the last known one is used). [tsMs] is the page's clock in ms,
 * only differences matter. [streamW] x [streamH] = the picture size the page was showing (0 = unknown).
 */
data class InputEvent(
    val action: InputAction,
    val id: Int,
    val x: Double,
    val y: Double,
    val tsMs: Double,
    val streamW: Int = 0,
    val streamH: Int = 0,
) {
    val hasPosition: Boolean get() = !x.isNaN() && !y.isNaN()

    companion object {
        const val MAX_ID = 1_000_000

        /**
         * Validates raw message fields. Returns null (ignore the message) for an unknown action, an id outside
         * 0..[MAX_ID], a non-finite timestamp, or a position outside 0..1 (down and move need a position; up and
         * cancel may omit it, but if present it must be valid).
         */
        fun validate(action: String, id: Int, x: Double, y: Double, tsMs: Double, streamW: Int = 0, streamH: Int = 0): InputEvent? {
            val a = InputAction.parse(action) ?: return null
            if (id < 0 || id > MAX_ID) return null
            if (tsMs.isNaN() || tsMs.isInfinite()) return null
            val missing = x.isNaN() && y.isNaN()
            val bad = x.isNaN() != y.isNaN() || x.isInfinite() || y.isInfinite() || x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0
            if (missing) {
                if (a == InputAction.DOWN || a == InputAction.MOVE) return null
            } else if (bad) {
                return null
            }
            return InputEvent(a, id, x, y, tsMs, streamW.coerceIn(0, 100_000), streamH.coerceIn(0, 100_000))
        }
    }
}
