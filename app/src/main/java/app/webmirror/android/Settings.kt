package app.webmirror.android

import android.content.Context
import android.content.SharedPreferences
import app.webmirror.android.capture.Preset
import app.webmirror.android.stream.StreamConfig

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

    /** Quality preset (plan 2.3). */
    var preset: Preset
        get() = Preset.fromKey(prefs.getString(KEY_PRESET, null))
        set(value) = prefs.edit().putString(KEY_PRESET, value.key).apply()

    /** How audio reaches the viewers (plan M3.2); sent in `hello.audio`. */
    var audioMode: AudioMode
        get() = AudioMode.fromKey(prefs.getString(KEY_AUDIO, null))
        set(value) = prefs.edit().putString(KEY_AUDIO, value.key).apply()

    /** Hold a dim wake lock while mirroring, so the screen lock does not stop the capture (plan M3.5). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Always send JPEG, even when every viewer could decode H.264 (plan M4.4). */
    var forceJpeg: Boolean
        get() = prefs.getBoolean(KEY_FORCE_JPEG, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_JPEG, value).apply()

    /** All latency/smoothness tunables, stored as one JSON text (see [StreamConfig]). */
    var streamConfig: StreamConfig
        get() = StreamConfig.fromJson(prefs.getString(KEY_STREAM_CONFIG, null))
        set(value) = prefs.edit().putString(KEY_STREAM_CONFIG, value.toJson().toString()).apply()

    /** Automatic test: run name, phase length (seconds), quick mode, own test pattern. */
    var testRunName: String
        get() = prefs.getString(KEY_TEST_NAME, "parked") ?: "parked"
        set(value) = prefs.edit().putString(KEY_TEST_NAME, value).apply()

    var testPhaseSeconds: Int
        get() = prefs.getInt(KEY_TEST_SECONDS, 40)
        set(value) = prefs.edit().putInt(KEY_TEST_SECONDS, value).apply()

    var testQuick: Boolean
        get() = prefs.getBoolean(KEY_TEST_QUICK, false)
        set(value) = prefs.edit().putBoolean(KEY_TEST_QUICK, value).apply()

    var testUsePattern: Boolean
        get() = prefs.getBoolean(KEY_TEST_PATTERN, true)
        set(value) = prefs.edit().putBoolean(KEY_TEST_PATTERN, value).apply()

    companion object {
        private const val KEY_TEST_NAME = "testRunName"
        private const val KEY_TEST_SECONDS = "testPhaseSeconds"
        private const val KEY_TEST_QUICK = "testQuick"
        private const val KEY_TEST_PATTERN = "testUsePattern"
        private const val KEY_STREAM_CONFIG = "streamConfig"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_FORCE_JPEG = "forceJpeg"
        private const val FILE = "webmirror"
        private const val KEY_VIRTUAL_IP = "virtualIp"
        private const val KEY_VIRTUAL_IP_ENABLED = "virtualIpEnabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_AUDIO = "audioMode"

        /** Link-local, so Android's VPN ingress-discard rule does not apply; the Tesla browser accepts it. */
        const val DEFAULT_VIRTUAL_IP = "169.254.77.1"
    }
}

/** Audio mode (plan M3.2). [key] is the value of `hello.audio`. */
enum class AudioMode(val key: String, val label: String, val available: Boolean) {
    BLUETOOTH("bluetooth", "Bluetooth (car speakers)", true),
    BROWSER("browser", "Browser (coming later)", false),
    OFF("off", "Off", true);

    companion object {
        /** Unknown or not yet available values fall back to Bluetooth. */
        fun fromKey(key: String?): AudioMode =
            entries.firstOrNull { it.key == key && it.available } ?: BLUETOOTH
    }
}
