package app.webmirror.android

import android.content.SharedPreferences
import app.webmirror.android.input.RemoteControlState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRemoteControlTest {

    /** Minimal in-memory SharedPreferences (the methods Settings uses). */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(k: String, d: String?) = map[k] as String? ?: d
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
        override fun contains(k: String) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = also { map[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = also { map[k] = v }
            override fun putLong(k: String, v: Long) = also { map[k] = v }
            override fun putFloat(k: String, v: Float) = also { map[k] = v }
            override fun putBoolean(k: String, v: Boolean) = also { map[k] = v }
            override fun remove(k: String) = also { map.remove(k) }
            override fun clear() = also { map.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }

    @Test
    fun remoteControlIsOnByDefault() {
        assertTrue(Settings.DEFAULT_REMOTE_CONTROL)
        assertTrue(Settings(FakePrefs()).remoteControlAllowed)
        assertTrue(RemoteControlState.allowed.value)
    }

    @Test
    fun switchingOffPersistsUntilSwitchedOnAgain() {
        val prefs = FakePrefs()
        Settings(prefs).remoteControlAllowed = false
        assertFalse(Settings(prefs).remoteControlAllowed) // a later app/session start reads the stored value
        Settings(prefs).remoteControlAllowed = true
        assertEquals(true, Settings(prefs).remoteControlAllowed)
    }
}
