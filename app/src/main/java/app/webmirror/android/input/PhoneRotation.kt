package app.webmirror.android.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * "Rotate" button of the car page: turns the phone between portrait and landscape the way the user would by hand,
 * through the system rotation settings (needs "Modify system settings"). Auto-rotate is switched off while rotated
 * (a phone lying flat would flip back otherwise) and the user's own values are saved first and put back by [restore]
 * when mirroring stops. The saved values live in SharedPreferences so a crash cannot lose them: [restore] also runs
 * at app and service start. Nothing is touched unless the user pressed Rotate.
 */
object PhoneRotation {
    private const val TAG = "WebMirror"
    private const val PREFS = "phone_rotation"
    private const val KEY_SAVED = "saved"
    private const val KEY_AUTO = "auto"
    private const val KEY_USER = "user"

    /** Portrait (0 or 180) turns to landscape (90); anything else turns back to portrait (0). Pure. */
    fun targetRotation(current: Int): Int =
        if (current == Surface.ROTATION_0 || current == Surface.ROTATION_180) Surface.ROTATION_90 else Surface.ROTATION_0

    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /** Rotates to the other orientation. False when the permission is missing or the system refused. */
    @Synchronized
    fun toggle(context: Context): Boolean {
        if (!canWrite(context)) return false
        return try {
            val resolver = context.contentResolver
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Already rotated by us: toggle the requested rotation, not the display's. An app locked to portrait keeps
            // the display upright, and reading that would ask for landscape again and again.
            val current = if (prefs.getBoolean(KEY_SAVED, false))
                Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
            else currentRotation(context)
            if (!prefs.getBoolean(KEY_SAVED, false)) {
                // commit(): the originals must be on disk before the first change.
                prefs.edit()
                    .putInt(KEY_AUTO, Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1))
                    .putInt(KEY_USER, Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0))
                    .putBoolean(KEY_SAVED, true)
                    .commit()
            }
            val target = targetRotation(current)
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, target)
            Log.i(TAG, "rotate from the car: target rotation $target")
            true
        } catch (e: Exception) {
            Log.w(TAG, "rotate failed: $e")
            false
        }
    }

    /** Puts the user's original rotation settings back, if [toggle] changed them. Safe to call any time. */
    @Synchronized
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SAVED, false)) return
        try {
            if (canWrite(context)) {
                val resolver = context.contentResolver
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, prefs.getInt(KEY_USER, Surface.ROTATION_0))
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, prefs.getInt(KEY_AUTO, 1))
                Log.i(TAG, "rotation settings restored")
            } else {
                Log.w(TAG, "rotation settings could not be restored: permission was withdrawn")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore rotation failed: $e")
        }
        prefs.edit().clear().apply()
    }

    private fun currentRotation(context: Context): Int =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
}
