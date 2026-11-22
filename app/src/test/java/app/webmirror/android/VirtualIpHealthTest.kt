package app.webmirror.android

import app.webmirror.android.net.carCannotReach
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualIpHealthTest {
    @Test
    fun notMirroringIsNeverAProblem() {
        assertFalse(carCannotReach(mirroring = false, active = false, lost = true, httpsNameConfigured = true))
        assertFalse(carCannotReach(mirroring = false, active = false, lost = false, httpsNameConfigured = true))
    }

    @Test
    fun activeAddressIsAlwaysReachable() {
        assertFalse(carCannotReach(mirroring = true, active = true, lost = true, httpsNameConfigured = true))
        assertFalse(carCannotReach(mirroring = true, active = true, lost = false, httpsNameConfigured = true))
    }

    @Test
    fun lostAddressIsUnreachable() {
        assertTrue(carCannotReach(mirroring = true, active = false, lost = true, httpsNameConfigured = false))
    }

    @Test
    fun secureNamePointsToTheVirtualAddress() {
        assertTrue(carCannotReach(mirroring = true, active = false, lost = false, httpsNameConfigured = true))
    }

    @Test
    fun offWithoutNameIsFine() {
        assertFalse(carCannotReach(mirroring = true, active = false, lost = false, httpsNameConfigured = false))
    }
}
