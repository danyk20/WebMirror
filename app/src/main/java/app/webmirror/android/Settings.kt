package app.webmirror.android

import android.content.Context
import android.content.SharedPreferences
import app.webmirror.android.capture.Preset
import app.webmirror.android.stream.AudioProfile
import app.webmirror.android.stream.Pairing
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
            // Migration 2: the default PIN length is 4 digits. Installs that never chose a length and still hold the old
            // 6-digit PIN get a new 4-digit PIN (generated at first use, by [Pairing]).
            if (migratePairingPin(v, prefs.contains(KEY_PAIRING_PIN_LENGTH), prefs.getString(KEY_PAIRING_PIN, null))) {
                e.remove(KEY_PAIRING_PIN)
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

    /** Set when another VPN app or Android switched the virtual address off; cleared when it is active again or the user switches it off. */
    var virtualIpLost: Boolean
        get() = prefs.getBoolean(KEY_VIRTUAL_IP_LOST, false)
        set(value) = prefs.edit().putBoolean(KEY_VIRTUAL_IP_LOST, value).apply()

    /** Quality preset (plan 2.3). */
    var preset: Preset
        get() = Preset.fromKey(prefs.getString(KEY_PRESET, null))
        set(value) = prefs.edit().putString(KEY_PRESET, value.key).apply()

    /** How audio reaches the viewers (plan M3.2); sent in `hello.audio`. */
    var audioMode: AudioMode
        get() = AudioMode.fromKey(prefs.getString(KEY_AUDIO, null))
        set(value) = prefs.edit().putString(KEY_AUDIO, value.key).apply()

    /** Profile of the "In the browser" audio mode (Fast / High quality); sent in `hello.audio.profile`. */
    var audioProfile: AudioProfile
        get() = AudioProfile.fromKey(prefs.getString(KEY_AUDIO_PROFILE, null))
        set(value) = prefs.edit().putString(KEY_AUDIO_PROFILE, value.key).apply()

    /**
     * "Allow control from viewers" (plan 2.1.3): persisted, default ON, never reset by mirroring start/stop. It only
     * has an effect while the accessibility service is enabled.
     */
    var remoteControlAllowed: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_CONTROL, DEFAULT_REMOTE_CONTROL)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_CONTROL, value).apply()

    /** Which keyboard types for the car (plan 2.1.5): the phone's (default) or the car browser's own. Applies live. */
    var typingKeyboard: TypingKeyboard
        get() = TypingKeyboard.fromKey(prefs.getString(KEY_TYPING_KEYBOARD, null))
        set(value) = prefs.edit().putString(KEY_TYPING_KEYBOARD, value.key).apply()

    /** "Require pairing PIN" (plan 2.1.4): persisted, default ON, also for installs that never saw the setting. */
    var pairingRequired: Boolean
        get() = prefs.getBoolean(KEY_PAIRING_REQUIRED, DEFAULT_PAIRING_REQUIRED)
        set(value) = prefs.edit().putBoolean(KEY_PAIRING_REQUIRED, value).apply()

    /** Digits of the pairing PIN (4 or 6). Default 4; the user chooses under Settings > Security. */
    var pairingPinLength: Int
        get() = prefs.getInt(KEY_PAIRING_PIN_LENGTH, Pairing.DEFAULT_PIN_DIGITS).let {
            if (it == Pairing.PIN_DIGITS_LONG) it else Pairing.PIN_DIGITS_SHORT
        }
        set(value) = prefs.edit().putInt(KEY_PAIRING_PIN_LENGTH, value).apply()

    /** What the system asks to share when the user taps Start (Android 14+; older versions always share everything). */
    var captureSource: CaptureSource
        get() = CaptureSource.fromKey(prefs.getString(KEY_CAPTURE_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_CAPTURE_SOURCE, value.key).apply()

    /** The pairing PIN, or null until the first one was generated. */
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
        override var pinLength: Int
            get() = pairingPinLength
            set(value) { pairingPinLength = value }
    }

    /** Hold a dim wake lock while mirroring, so the screen lock does not stop the capture (plan M3.5). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Minutes a paused session may idle before it stops itself (frees the VPN); 0 = never. */
    var pausedAutoStopMinutes: Int
        get() = prefs.getInt(KEY_PAUSED_AUTO_STOP, 120)
        set(value) = prefs.edit().putInt(KEY_PAUSED_AUTO_STOP, value).apply()

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
        const val MIGRATION_VERSION = 2

        /** Migration 1: the old defaults 80 and 40 ms become the new default 20 ms; other values are the user's. */
        fun migrateJitter(fromVersion: Int, jitterMs: Int): Int =
            if (fromVersion < 1 && (jitterMs == 80 || jitterMs == 40)) StreamConfig().jitterBufferMs else jitterMs

        /**
         * Migration 2: true when the stored PIN must be dropped (so a 4-digit one is generated): the user never chose a
         * length ([lengthChosen] false) and the stored PIN is the old 6-digit one.
         */
        fun migratePairingPin(fromVersion: Int, lengthChosen: Boolean, storedPin: String?): Boolean =
            fromVersion < 2 && !lengthChosen && storedPin != null && storedPin.length == Pairing.PIN_DIGITS_LONG

        private const val KEY_PAIRING_PIN_LENGTH = "pairingPinLength"
        private const val KEY_CAPTURE_SOURCE = "captureSource"
        private const val KEY_TEST_NAME = "testRunName"
        private const val KEY_TEST_SECONDS = "testPhaseSeconds"
        private const val KEY_TEST_QUICK = "testQuick"
        private const val KEY_TEST_PATTERN = "testUsePattern"
        private const val KEY_STREAM_CONFIG = "streamConfig"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_PAUSED_AUTO_STOP = "paused_auto_stop_minutes"
        private const val KEY_REMOTE_CONTROL = "remoteControlAllowed"
        private const val KEY_TYPING_KEYBOARD = "typingKeyboard"
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
        private const val KEY_VIRTUAL_IP_LOST = "virtualIpLost"
        private const val KEY_PRESET = "preset"
        private const val KEY_AUDIO = "audioMode"
        private const val KEY_AUDIO_PROFILE = "audioProfile"

        /** Link-local, so Android's VPN ingress-discard rule does not apply; the Tesla browser accepts it. */
        const val DEFAULT_VIRTUAL_IP = "169.254.77.1"
    }
}

/** Audio mode (plan M3.2, 2.4.1). [key] is the value of `hello.audio.mode`; [label] is the short name (Settings list). */
enum class AudioMode(val key: String, val label: String, val optionText: String, val explanation: String) {
    BLUETOOTH(
        "bluetooth", "Car speakers via Bluetooth", "Car speakers via Bluetooth (adjust delay in the car)",
        "The phone plays the sound to the car over Bluetooth. The page delays the picture to match; set the delay in the page.",
    ),
    BROWSER(
        "browser", "In the browser", "In the browser (sound plays in the car browser, in sync)",
        "The phone sends its sound to the page; the page plays it and keeps picture and sound in sync.",
    ),
    OFF("off", "Off", "Off", "No sound handling: no sound in the browser and no delay hint.");

    companion object {
        /** Unknown values fall back to Bluetooth. */
        fun fromKey(key: String?): AudioMode = entries.firstOrNull { it.key == key } ?: BLUETOOTH
    }
}

/** What the user wants to share when tapping Start (Android 14+ only). */
enum class CaptureSource(val key: String, val label: String) {
    ENTIRE_SCREEN("entire", "Entire screen"),
    USER_CHOICE("choose", "Let me choose each time");

    companion object {
        fun fromKey(key: String?): CaptureSource = entries.firstOrNull { it.key == key } ?: ENTIRE_SCREEN
    }
}

/** Keyboard for typing from the car (plan 2.1.5). [key] is stored and is the value of `hello.control.kbd`. */
enum class TypingKeyboard(val key: String, val label: String, val explanation: String) {
    PHONE("phone", "Phone keyboard", "The phone's keyboard appears in the mirrored picture and you tap its keys on the car screen."),
    TESLA("tesla", "Tesla keyboard", "The car browser's own keyboard opens when you tap a text field. The phone's keyboard stays hidden.");

    companion object {
        /** Unknown values fall back to the phone keyboard. */
        fun fromKey(key: String?): TypingKeyboard = entries.firstOrNull { it.key == key } ?: PHONE
    }
}
