package app.webmirror.android

import app.webmirror.android.input.GestureEngine
import app.webmirror.android.input.GestureEngine.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    /** Plays the service's role: gives every segment a stroke handle, as the real one does after building the stroke. */
    private fun GestureEngine.frame(): List<Segment> {
        val f = next()!!
        f.forEach { it.handle = Any() }
        return f
    }

    @Test
    fun downStartsAHeldZeroLengthStrokeImmediately() {
        val e = GestureEngine()
        e.down(1, 100f, 200f, 0.0)
        val f = e.frame()
        assertEquals(1, f.size)
        val s = f[0]
        assertFalse(s.moves)
        assertTrue(s.willContinue)
        assertNull(s.previous)
        assertTrue(s.durationMs > 0)
        assertNull("one frame at a time", e.next())
    }

    @Test
    fun continuationStartsWhereThePreviousEndedAndKeepsThePreviousHandle() {
        val e = GestureEngine()
        e.down(1, 100f, 200f, 0.0)
        val first = e.frame()[0]
        e.completed()
        e.move(1, 140f, 260f, 16.0)
        val second = e.frame()[0]
        assertEquals(100f, second.fromX, 0f); assertEquals(200f, second.fromY, 0f)
        assertEquals(140f, second.toX, 0f); assertEquals(260f, second.toY, 0f)
        assertSame(first.handle, second.previous)
        assertTrue(second.willContinue)
        assertEquals(16L, second.durationMs)
        e.completed()
        e.move(1, 150f, 270f, 32.0)
        val third = e.frame()[0]
        assertEquals(140f, third.fromX, 0f) // continues from where the second ended
        assertSame(second.handle, third.previous)
    }

    @Test
    fun movesDuringAFlightAreCoalescedToTheNewestAndPlayedSlightlyFaster() {
        val e = GestureEngine()
        e.down(1, 0f, 0f, 0.0)
        e.frame()
        e.move(1, 10f, 0f, 16.0)   // frame in flight: nothing to dispatch yet
        e.move(1, 20f, 0f, 32.0)
        e.move(1, 30f, 0f, 48.0)
        assertNull(e.next())
        e.completed()
        val s = e.frame()[0]
        assertEquals(30f, s.toX, 0f)
        assertEquals(36L, s.durationMs) // 48 ms of finger time * 3/4
        assertNull(e.next()) // nothing new
    }

    @Test
    fun tapIsDownThenUp() {
        val e = GestureEngine()
        e.down(7, 50f, 60f, 1000.0)
        val d = e.frame()[0]
        e.up(7, 50f, 60f, 1080.0) // arrives during the flight
        e.completed()
        val u = e.frame()[0]
        assertFalse(u.willContinue)
        assertSame(d.handle, u.previous)
        assertFalse(u.moves)
        assertEquals(GestureEngine.MIN_SEGMENT_MS, u.durationMs) // the real hold time passes between the frames
        e.completed()
        assertFalse(e.hasActive)
    }

    @Test
    fun downAndUpWithinOneFlightBecomeOneStrokeWithTheHoldTime() {
        val e = GestureEngine()
        e.down(1, 0f, 0f, 0.0)
        e.frame()
        e.completed()
        e.down(2, 300f, 400f, 1000.0) // no frame in flight now: dispatched at once
        e.frame()
        // Simulate a busy phone: a second tap while in flight
        e.down(3, 10f, 10f, 1100.0)
        e.up(3, 10f, 10f, 1180.0)
        e.completed()
        val f = e.frame()
        val tap = f.first { it.pointerId == 3 }
        assertFalse(tap.willContinue)
        assertNull(tap.previous)
        assertEquals(80L, tap.durationMs)
    }

    @Test
    fun longPressKeepsTheStrokeDownWithoutFurtherFrames() {
        val e = GestureEngine()
        e.down(1, 5f, 5f, 0.0)
        e.frame()
        e.completed()
        assertNull(e.next()) // finger stays still: the system keeps the stroke down, no traffic
        assertTrue(e.hasActive)
    }

    @Test
    fun twoFingersAreOneGestureWithTwoStrokesAndAHeldFingerIsContinued() {
        val e = GestureEngine()
        e.down(1, 100f, 100f, 0.0)
        val a0 = e.frame()[0]
        e.completed()
        e.down(2, 200f, 100f, 10.0)
        val f1 = e.frame()
        // finger 1 did not change but must be part of the frame, as a continuation
        val a1 = f1.first { it.pointerId == 1 }
        assertSame(a0.handle, a1.previous)
        assertTrue(a1.willContinue); assertFalse(a1.moves)
        assertEquals(2, f1.size)
        e.completed()
        e.move(1, 90f, 100f, 26.0)
        e.move(2, 210f, 100f, 26.0)
        val f2 = e.frame()
        assertEquals(setOf(1, 2), f2.map { it.pointerId }.toSet())
        assertTrue(f2.all { it.moves && it.previous != null })
    }

    @Test
    fun pinchEndsBothStrokes() {
        val e = GestureEngine()
        e.down(1, 100f, 100f, 0.0); e.down(2, 200f, 100f, 0.0)
        e.frame(); e.completed()
        e.up(1, 120f, 100f, 40.0); e.up(2, 180f, 100f, 40.0)
        val f = e.frame()
        assertTrue(f.none { it.willContinue })
        e.completed()
        assertFalse(e.hasActive)
    }

    @Test
    fun aNewTouchWaitsOneFrameWhenAnotherLiftsAtTheSameTime() {
        val e = GestureEngine()
        e.down(1, 0f, 0f, 0.0)
        e.frame()
        e.down(9, 50f, 50f, 5.0) // arrives in flight
        e.up(1, 0f, 0f, 6.0)
        e.completed()
        val f = e.frame()
        assertEquals(listOf(1), f.map { it.pointerId }) // 1 lifts alone
        e.completed()
        val g = e.frame()
        assertEquals(listOf(9), g.map { it.pointerId })
        assertTrue(g[0].willContinue)
    }

    @Test
    fun theSameIdCanBeReusedImmediatelyForADoubleTap() {
        val e = GestureEngine()
        e.down(1, 5f, 5f, 0.0)
        e.frame()
        e.up(1, 5f, 5f, 40.0)
        e.down(1, 5f, 5f, 90.0) // second tap with the same id (mouse) while the first lift waits
        e.up(1, 5f, 5f, 130.0)
        e.completed()
        val lift = e.frame()
        assertEquals(1, lift.size)
        assertFalse(lift[0].willContinue)
        e.completed()
        val second = e.frame()
        assertEquals(1, second.size)
        assertNull(second[0].previous) // a fresh stroke
    }

    @Test
    fun releaseAllLiftsStartedFingersAndDropsUnstartedOnes() {
        val e = GestureEngine()
        e.down(1, 10f, 10f, 0.0)
        e.frame()
        e.down(2, 20f, 20f, 1.0) // never dispatched
        e.releaseAll()
        e.completed()
        val f = e.frame()
        assertEquals(listOf(1), f.map { it.pointerId })
        assertFalse(f[0].willContinue)
        e.completed()
        assertFalse(e.hasActive)
    }

    @Test
    fun cancelForgetsEverythingAndLaterMovesAreIgnoredUntilANewDown() {
        val e = GestureEngine()
        e.down(1, 10f, 10f, 0.0)
        e.frame()
        e.cancelled()
        assertFalse(e.hasActive)
        e.move(1, 20f, 20f, 16.0)
        assertNull(e.next())
        e.down(1, 30f, 30f, 32.0)
        assertNotNull(e.next())
    }

    @Test
    fun noMoreFingersThanTheSystemAllows() {
        val e = GestureEngine(maxPointers = 2)
        assertTrue(e.down(1, 0f, 0f, 0.0))
        assertTrue(e.down(2, 0f, 0f, 0.0))
        assertFalse(e.down(3, 0f, 0f, 0.0))
        assertEquals(2, e.frame().size)
    }

    @Test
    fun segmentDurationIsClamped() {
        assertEquals(GestureEngine.MIN_SEGMENT_MS, GestureEngine.clampDuration(-5.0, GestureEngine.MIN_SEGMENT_MS, 100))
        assertEquals(100L, GestureEngine.clampDuration(5000.0, GestureEngine.MIN_SEGMENT_MS, 100))
        assertEquals(17L, GestureEngine.clampDuration(16.7, GestureEngine.MIN_SEGMENT_MS, 100))
        assertEquals(GestureEngine.MIN_SEGMENT_MS, GestureEngine.clampDuration(Double.NaN, GestureEngine.MIN_SEGMENT_MS, 100))
    }
}
