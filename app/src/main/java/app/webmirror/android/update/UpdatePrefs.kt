package app.webmirror.android.update

import android.content.Context
import android.content.SharedPreferences
import app.webmirror.android.BuildConfig

class UpdatePrefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Default ON for the GitHub build (once a day). */
    var autoCheck: Boolean
        get() = sp.getBoolean("auto_check", BuildConfig.SELF_UPDATE)
        set(v) = sp.edit().putBoolean("auto_check", v).apply()

    /** Default OFF. */
    var autoInstall: Boolean
        get() = sp.getBoolean("auto_install", false)
        set(v) = sp.edit().putBoolean("auto_install", v).apply()

    var lastCheckMs: Long
        get() = sp.getLong("last_check", 0L)
        set(v) = sp.edit().putLong("last_check", v).apply()

    var lastSeenVersion: String?
        get() = sp.getString("last_seen_version", null)
        set(v) = sp.edit().putString("last_seen_version", v).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    /** Version we already posted an "available" notification for. */
    var lastNotifiedVersion: String?
        get() = sp.getString("last_notified", null)
        set(v) = sp.edit().putString("last_notified", v).apply()
}
