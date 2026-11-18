package app.webmirror.android

import app.webmirror.android.stream.StreamConfig
import org.junit.Assert.assertEquals
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
}
