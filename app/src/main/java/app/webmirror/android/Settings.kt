package app.webmirror.android

import android.content.Context
import android.content.SharedPreferences

/** Small SharedPreferences wrapper for the user's persistent choices. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Raw text of the "Virtual address" field (may be invalid while the user is typing). */
    var virtualIp: String
        get() = prefs.getString(KEY_VIRTUAL_IP, DEFAULT_VIRTUAL_IP) ?: DEFAULT_VIRTUAL_IP
        set(value) = prefs.edit().putString(KEY_VIRTUAL_IP, value).apply()

    /** Whether the user wants the virtual address on; restored when the app opens. */
    var virtualIpEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIRTUAL_IP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VIRTUAL_IP_ENABLED, value).apply()

    companion object {
        private const val FILE = "webmirror"
        private const val KEY_VIRTUAL_IP = "virtualIp"
        private const val KEY_VIRTUAL_IP_ENABLED = "virtualIpEnabled"
        const val DEFAULT_VIRTUAL_IP = "198.51.100.1"
    }
}
