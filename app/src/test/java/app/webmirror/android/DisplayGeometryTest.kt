package app.webmirror.android

import app.webmirror.android.input.DisplayGeometry
import app.webmirror.android.input.InputAction
import app.webmirror.android.input.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayGeometryTest {

    /** The system reports the natural 1080x2400 panel as 1080x2400 / 2400x1080 / 1080x2400 / 2400x1080 for rotation 0..3. */
    private fun geo(rotation: Int) =
        if (rotation % 2 == 0) DisplayGeometry(1080, 2400, rotation) else DisplayGeometry(2400, 1080, rotation)

    @Test
    fun cornersAndCentreMapToTheDisplayInAllFourRotations() {
        for (r in 0..3) {
            val g = geo(r)
            assertEquals(0f, g.mapX(0.0), 0f)
            assertEquals(0f, g.mapY(0.0), 0f)
            assertEquals((g.width - 1).toFloat(), g.mapX(1.0), 0f)
            assertEquals((g.height - 1).toFloat(), g.mapY(1.0), 0f)
            assertEquals((g.width - 1) / 2f, g.mapX(0.5), 0.5f)
            assertEquals((g.height - 1) / 2f, g.mapY(0.5), 0.5f)
        }
    }

    @Test
    fun sameNormalisedPointLandsAtTheSameRelativePlaceWhateverTheRotation() {
        // A point 25% from the left and 75% from the top of the picture is there on the screen in every rotation.
        for (r in 0..3) {
            val g = geo(r)
            assertEquals(0.25, g.mapX(0.25) / (g.width - 1.0), 0.001)
            assertEquals(0.75, g.mapY(0.75) / (g.height - 1.0), 0.001)
        }
    }

    @Test
    fun outOfRangeInputIsClamped() {
        val g = geo(0)
        assertEquals(0f, g.mapX(-3.0), 0f)
        assertEquals(1079f, g.mapX(7.0), 0f)
    }

    @Test
    fun streamOrientationMustMatchTheDisplay() {
        assertTrue(geo(0).matchesStream(576, 1280))
        assertFalse(geo(0).matchesStream(1280, 576)) // picture of the old landscape orientation
        assertTrue(geo(1).matchesStream(1280, 576))
        assertFalse(geo(3).matchesStream(576, 1280))
        assertTrue(geo(0).matchesStream(0, 0)) // unknown
    }

    @Test
    fun rotationChangeMakesTheGeometryDifferentEvenAtTheSameSize() {
        assertTrue(geo(0) != geo(2))
        assertTrue(geo(0) != geo(1))
    }

    @Test
    fun validEventsAreAccepted() {
        val e = InputEvent.validate("down", 3, 0.5, 0.25, 1234.5, 576, 1280)
        assertNotNull(e)
        assertEquals(InputAction.DOWN, e!!.action)
        assertTrue(e.hasPosition)
        val up = InputEvent.validate("up", 3, Double.NaN, Double.NaN, 1300.0)
        assertNotNull(up)
        assertFalse(up!!.hasPosition)
    }

    @Test
    fun invalidEventsAreRejected() {
        assertNull(InputEvent.validate("down", 1, 1.01, 0.5, 0.0))
        assertNull(InputEvent.validate("move", 1, -0.01, 0.5, 0.0))
        assertNull(InputEvent.validate("move", 1, Double.NaN, Double.NaN, 0.0)) // move needs a position
        assertNull(InputEvent.validate("down", 1, 0.5, Double.NaN, 0.0))
        assertNull(InputEvent.validate("up", 1, 0.5, Double.POSITIVE_INFINITY, 0.0))
        assertNull(InputEvent.validate("tap", 1, 0.5, 0.5, 0.0))
        assertNull(InputEvent.validate("down", -1, 0.5, 0.5, 0.0))
        assertNull(InputEvent.validate("down", 1, 0.5, 0.5, Double.NaN))
        assertNull(InputEvent.validate("down", InputEvent.MAX_ID + 1, 0.5, 0.5, 0.0))
    }
}
