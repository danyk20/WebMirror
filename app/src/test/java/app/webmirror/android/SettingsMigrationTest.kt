package app.webmirror.android

import app.webmirror.android.stream.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun oldDefaultsBecomeTwenty() {
        assertEquals(20, Settings.migrateJitter(0, 80))
        assertEquals(20, Settings.migrateJitter(0, 40))
    }

    @Test
    fun otherValuesAreKept() {
        for (v in listOf(0, 10, 20, 30, 150)) assertEquals(v, Settings.migrateJitter(0, v))
    }

    @Test
    fun doesNotRunAgainOnceMigrated() {
        assertEquals(40, Settings.migrateJitter(Settings.MIGRATION_VERSION, 40))
    }

    @Test
    fun newDefaultIsTwenty() {
        assertEquals(20, StreamConfig().jitterBufferMs)
        assertEquals(20, StreamConfig.fromJson(null as String?).jitterBufferMs)
    }

    @Test
    fun oldSixDigitPinIsDroppedWhenNoLengthWasChosen() {
        assertTrue(Settings.migratePairingPin(1, false, "123456"))
        assertTrue(Settings.migratePairingPin(0, false, "000042"))
    }

    @Test
    fun chosenLengthOrOtherPinsAreKept() {
        assertFalse(Settings.migratePairingPin(1, true, "123456"))
        assertFalse(Settings.migratePairingPin(1, false, "1234"))
        assertFalse(Settings.migratePairingPin(1, false, null))
        assertFalse(Settings.migratePairingPin(2, false, "123456"))
    }
}
