package app.webmirror.android

import android.content.Context
import android.content.SharedPreferences
import app.webmirror.android.capture.Preset
import app.webmirror.android.stream.PairingStore
import app.webmirror.android.stream.StreamConfig

/** Small SharedPreferences wrapper for the user's persistent choices. */
class Settings internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    init {
        val v = prefs.getInt(KEY_MIGRATION, 0)
        if (v < MIGRATION_VERSION) {
            val raw = prefs.getString(KEY_STREAM_CONFIG, null)
            val e = prefs.edit()
            if (raw != null) {
                val cfg = StreamConfig.fromJson(raw)
                val j = migrateJitter(v, cfg.jitterBufferMs)
                if (j != cfg.jitterBufferMs) e.putString(KEY_STREAM_CONFIG, cfg.copy(jitterBufferMs = j).toJson().toString())
            }
            e.putInt(KEY_MIGRATION, MIGRATION_VERSION).apply()
        }
    }

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

    /**
     * "Allow control from viewers" (plan 2.1.3): persisted, default ON, never reset by mirroring start/stop. It only
     * has an effect while the accessibility service is enabled.
     */
    var remoteControlAllowed: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_CONTROL, DEFAULT_REMOTE_CONTROL)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_CONTROL, value).apply()

    /** "Require pairing PIN" (plan 2.1.4): persisted, default ON, also for installs that never saw the setting. */
    var pairingRequired: Boolean
        get() = prefs.getBoolean(KEY_PAIRING_REQUIRED, DEFAULT_PAIRING_REQUIRED)
        set(value) = prefs.edit().putBoolean(KEY_PAIRING_REQUIRED, value).apply()

    /** The 6-digit pairing PIN, or null until the first one was generated. */
    var pairingPin: String?
        get() = prefs.getString(KEY_PAIRING_PIN, null)
        set(value) = prefs.edit().putString(KEY_PAIRING_PIN, value).apply()

    /** Pairing tokens of paired browsers, oldest first (hex, no separators inside). */
    var pairingTokens: List<String>
        get() = prefs.getString(KEY_PAIRING_TOKENS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_PAIRING_TOKENS, value.joinToString(",")).apply()

    /** Version of the safety notice the user accepted (0 = never); the notice is shown again when [SAFETY_NOTICE_VERSION] is higher. */
    var safetyAcceptedVersion: Int
        get() = prefs.getInt(KEY_SAFETY_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_SAFETY_VERSION, value).apply()

    /** [PairingStore] backed by these settings. */
    fun pairingStore(): PairingStore = object : PairingStore {
        override var required: Boolean
            get() = pairingRequired
            set(value) { pairingRequired = value }
        override var pin: String?
            get() = pairingPin
            set(value) { pairingPin = value }
        override var tokens: List<String>
            get() = pairingTokens
            set(value) { pairingTokens = value }
    }

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
        private const val KEY_MIGRATION = "migrationVersion"
        const val MIGRATION_VERSION = 1

        /** Migration 1: the old defaults 80 and 40 ms become the new default 20 ms; other values are the user's. */
        fun migrateJitter(fromVersion: Int, jitterMs: Int): Int =
            if (fromVersion < 1 && (jitterMs == 80 || jitterMs == 40)) StreamConfig().jitterBufferMs else jitterMs

        private const val KEY_TEST_NAME = "testRunName"
        private const val KEY_TEST_SECONDS = "testPhaseSeconds"
        private const val KEY_TEST_QUICK = "testQuick"
        private const val KEY_TEST_PATTERN = "testUsePattern"
        private const val KEY_STREAM_CONFIG = "streamConfig"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_REMOTE_CONTROL = "remoteControlAllowed"
        const val DEFAULT_REMOTE_CONTROL = true
        private const val KEY_PAIRING_REQUIRED = "pairingRequired"
        const val DEFAULT_PAIRING_REQUIRED = true
        private const val KEY_PAIRING_PIN = "pairingPin"
        private const val KEY_PAIRING_TOKENS = "pairingTokens"
        private const val KEY_SAFETY_VERSION = "safetyAcceptedVersion"

        /** Bump when the text of the safety notice changes; everybody has to accept it again. */
        const val SAFETY_NOTICE_VERSION = 1
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
