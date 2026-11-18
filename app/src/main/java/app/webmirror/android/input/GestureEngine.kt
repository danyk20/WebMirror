package app.webmirror.android.input

/**
 * Turns the live pointer events of a viewer into a chain of gesture frames for `AccessibilityService.dispatchGesture`.
 * Pure (no Android classes), single-threaded: the caller serialises all calls.
 *
 * Android rules this is built around (GestureDescription docs, API 26+):
 *  - A stroke is a path with exactly one contour, non-negative bounds, a positive duration; a path with only a
 *    `moveTo` is a touch that does not move.
 *  - `willContinue = true` keeps the pointer down when the gesture ends; the next gesture must continue that stroke with
 *    `previous.continueStroke(path, startTime, duration, willContinue)` whose path STARTS at the end point of the
 *    previous one. `startTime` is relative to the start of the new gesture.
 *  - One gesture holds at most `GestureDescription.getMaxStrokeCount()` strokes ([maxPointers]); dispatching a gesture
 *    cancels any gesture in progress, so a new frame is only dispatched after `onCompleted` of the previous one.
 *  - A held (continued) stroke that is left out of the next gesture is lost, so every active pointer is part of
 *    every frame (a pointer that did not move is a zero-length continuation).
 *
 * Events arriving while a frame is in flight are coalesced: only the newest position of a pointer is kept, so the
 * remote touch follows the viewer's finger with at most one frame of lag.
 */
class GestureEngine(private val maxPointers: Int = 10) {

    /** One stroke of the next gesture. The caller stores the platform stroke object it builds in [handle]. */
    class Segment(
        val pointerId: Int,
        val fromX: Float, val fromY: Float,
        val toX: Float, val toY: Float,
        val durationMs: Long,
        val willContinue: Boolean,
        /** `handle` of this pointer's previous segment (the stroke to call `continueStroke` on), null for a new stroke. */
        val previous: Any?,
    ) {
        var handle: Any? = null
        val moves: Boolean get() = fromX != toX || fromY != toY
    }

    private class Pointer(val id: Int, x: Float, y: Float, ts: Double) {
        var lastX = x; var lastY = y; var lastTs = ts       // end of the last dispatched segment (start for a new one)
        var targetX = x; var targetY = y; var targetTs = ts // newest position received
        var started = false                                 // first segment dispatched
        var ending = false                                  // `up` received
        var dirty = true
        var arrivedBusy = false                             // newest event arrived while a frame was in flight
        var lastSegment: Segment? = null
        var restart = false                                 // lifted only so it can start again together with a new finger
    }

    private val pointers = ArrayList<Pointer>()
    private var inFlight = false

    /** True while any touch is down or waiting to be lifted. */
    val hasActive: Boolean get() = pointers.isNotEmpty()
    val isInFlight: Boolean get() = inFlight
    val activeCount: Int get() = pointers.size
    /** Fingers whose touch has already been dispatched (held on the phone). */
    val startedCount: Int get() = pointers.count { it.started && !it.ending }

    private fun find(id: Int): Pointer? = pointers.lastOrNull { it.id == id && !it.ending }

    /** A finger went down at ([x], [y]) in display pixels. Returns false if it was ignored (too many fingers). */
    fun down(id: Int, x: Float, y: Float, ts: Double): Boolean {
        if (find(id) != null) return false
        if (pointers.count { !it.ending } >= maxPointers) return false
        pointers.add(Pointer(id, x, y, ts).also { it.arrivedBusy = inFlight })
        return true
    }

    fun move(id: Int, x: Float, y: Float, ts: Double) {
        val p = find(id) ?: return
        if (x == p.targetX && y == p.targetY) return
        p.targetX = x; p.targetY = y; p.targetTs = maxOf(ts, p.lastTs)
        p.dirty = true
        p.arrivedBusy = inFlight
    }

    /** Finger lifted at ([x], [y]) (null = where it was last). */
    fun up(id: Int, x: Float?, y: Float?, ts: Double) {
        val p = find(id) ?: return
        if (x != null && y != null) { p.targetX = x; p.targetY = y }
        p.targetTs = maxOf(ts, p.lastTs)
        p.ending = true
        p.dirty = true
        p.arrivedBusy = inFlight
    }

    /** Lift every finger where it is (viewer left, control switched off, rotation). Never-started touches are dropped. */
    fun releaseAll() {
        val it = pointers.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (!p.started) { it.remove(); continue }
            if (!p.ending) { p.ending = true; p.dirty = true }
        }
    }

    /**
     * The strokes of the next gesture, or null if nothing is to be sent or a frame is still in flight. Marks a frame
     * in flight; the caller reports [completed] or [cancelled].
     */
    fun next(): List<Segment>? {
        if (inFlight || pointers.none { it.dirty }) return null
        // Android cancels a gesture when a new finger joins while others are held. So a late finger first makes every
        // held finger lift where it is; the next frame then puts them all down again together, as new strokes.
        if (pointers.any { !it.started && !it.ending } && pointers.any { it.started && !it.ending }) {
            val out = ArrayList<Segment>()
            for (p in pointers) {
                if (!p.started) continue
                val seg = Segment(p.id, p.lastX, p.lastY, p.targetX, p.targetY, MIN_SEGMENT_MS, false, p.lastSegment?.handle)
                p.lastSegment = seg
                p.lastX = p.targetX; p.lastY = p.targetY
                if (p.targetTs > p.lastTs) p.lastTs = p.targetTs
                if (!p.ending) p.restart = true
                p.dirty = false
                p.arrivedBusy = false
                out.add(seg)
            }
            inFlight = true
            return out
        }
        // A touch that starts must not begin in the same instant another one lifts (it would look like two fingers):
        // new touches wait one frame when a started one ends now.
        val deferNew = pointers.any { it.started && it.ending }
        val frame = ArrayList<Pointer>()
        for (p in pointers) {
            if (!p.started && (deferNew || pointers.any { q -> q !== p && q.id == p.id && q.started && q.ending })) continue
            frame.add(p)
        }
        if (frame.none { it.dirty }) return null
        var longest = MIN_SEGMENT_MS
        val out = ArrayList<Segment>()
        for (p in frame) {
            val seg: Segment
            if (!p.started) {
                if (p.ending) {
                    // Whole tap / long press in one stroke: from the down point to the up point, as long as it lasted.
                    val d = clampDuration(p.targetTs - p.lastTs, MIN_SEGMENT_MS, MAX_TAP_MS)
                    seg = Segment(p.id, p.lastX, p.lastY, p.targetX, p.targetY, d, false, null)
                } else {
                    seg = Segment(p.id, p.lastX, p.lastY, p.lastX, p.lastY, MIN_SEGMENT_MS, true, null)
                }
            } else {
                val moves = p.targetX != p.lastX || p.targetY != p.lastY
                val d = if (!moves) MIN_SEGMENT_MS else catchUp(clampDuration(p.targetTs - p.lastTs, MIN_SEGMENT_MS, MAX_SEGMENT_MS), p.arrivedBusy)
                seg = Segment(p.id, p.lastX, p.lastY, p.targetX, p.targetY, d, !p.ending, p.lastSegment?.handle)
            }
            if (seg.durationMs > longest) longest = seg.durationMs
            out.add(seg)
        }
        // Every stroke that stays down lasts exactly as long as the frame. Android samples the gesture over time; a
        // continued stroke that ends earlier than the others drops out of the later samples, which the injector treats
        // as that finger lifting, and the next frame's continuation of it is then rejected (gesture cancelled). This is
        // what broke two-finger gestures such as pinch zoom.
        val result = ArrayList<Segment>(out.size)
        for ((i, p) in frame.withIndex()) {
            var s = out[i]
            if (s.willContinue && s.durationMs != longest) {
                s = Segment(s.pointerId, s.fromX, s.fromY, s.toX, s.toY, longest, true, s.previous)
            }
            p.lastSegment = s
            p.lastX = p.targetX; p.lastY = p.targetY
            if (p.targetTs > p.lastTs) p.lastTs = p.targetTs
            p.started = true
            p.dirty = false
            p.arrivedBusy = false
            result.add(s)
        }
        inFlight = true
        return result
    }

    /** The frame returned by [next] finished: lifted fingers are forgotten. */
    fun completed() {
        inFlight = false
        pointers.removeAll { it.started && it.ending && !it.dirty }
        for (p in pointers) {
            if (!p.restart) continue
            p.restart = false
            p.started = false
            p.lastSegment = null
            p.dirty = true
        }
    }

    /** The frame was cancelled or could not be dispatched: all touches are gone as far as we can tell. */
    fun cancelled() {
        inFlight = false
        pointers.clear()
    }

    companion object {
        const val MIN_SEGMENT_MS = 5L
        const val MAX_SEGMENT_MS = 100L
        const val MAX_TAP_MS = 1000L

        /** Remote replay must not fall behind the finger: segments of events that had to wait are played a quarter faster. */
        private fun catchUp(ms: Long, backlog: Boolean): Long =
            if (backlog) maxOf(MIN_SEGMENT_MS, ms * 3 / 4) else ms

        /** Duration of a segment from the page's time difference [dtMs]. */
        fun clampDuration(dtMs: Double, min: Long, max: Long): Long =
            if (dtMs.isNaN()) min else Math.round(dtMs).coerceIn(min, max)
    }
}
