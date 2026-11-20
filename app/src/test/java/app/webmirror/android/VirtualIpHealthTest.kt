package app.webmirror.android

import app.webmirror.android.net.carCannotReach
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualIpHealthTest {
    @Test
    fun activeAddressIsAlwaysReachable() {
        assertFalse(carCannotReach(active = true, lost = true, httpsNameConfigured = true))
        assertFalse(carCannotReach(active = true, lost = false, httpsNameConfigured = true))
    }

    @Test
    fun lostAddressIsUnreachable() {
        assertTrue(carCannotReach(active = false, lost = true, httpsNameConfigured = false))
    }

    @Test
    fun secureNamePointsToTheVirtualAddress() {
        assertTrue(carCannotReach(active = false, lost = false, httpsNameConfigured = true))
    }

    @Test
    fun offOnPurposeWithoutNameIsFine() {
        assertFalse(carCannotReach(active = false, lost = false, httpsNameConfigured = false))
    }
}
