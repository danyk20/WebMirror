package app.webmirror.android

import app.webmirror.android.input.PhoneRotation
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRotationTest {

    @Test
    fun portraitTurnsToLandscape() {
        assertEquals(1, PhoneRotation.targetRotation(0))
        assertEquals(1, PhoneRotation.targetRotation(2))
    }

    @Test
    fun landscapeTurnsBackToPortrait() {
        assertEquals(0, PhoneRotation.targetRotation(1))
        assertEquals(0, PhoneRotation.targetRotation(3))
    }
}
