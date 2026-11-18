package app.webmirror.android.stream

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/** Where [Pairing] keeps what must survive a restart (the phone's settings in the app, memory in tests). */
interface PairingStore {
    var required: Boolean
    var pin: String?
    var tokens: List<String>

    /** Number of digits of the PIN: [Pairing.PIN_DIGITS_SHORT] (default) or [Pairing.PIN_DIGITS_LONG]. */
    var pinLength: Int
}

/** [PairingStore] in memory (tests, and the default of a hub that has no pairing). */
class MemoryPairingStore(
    override var required: Boolean = false,
    override var pin: String? = null,
    override var tokens: List<String> = emptyList(),
    override var pinLength: Int = Pairing.DEFAULT_PIN_DIGITS,
) : PairingStore

/**
 * The pairing PIN, the pairing tokens and the global PIN rate limit (plan 2.1.4). Pure and thread-safe; the clock
 * (a monotonic millisecond clock) and the random source are injectable for tests.
 *
 * Rate limit: [MAX_WRONG] wrong PINs within [WINDOW_MS] lock every PIN attempt for [LOCK_MS]. It is global, not per
 * address (addresses on a LAN can be spoofed). PINs and tokens are compared in constant time.
 */
class Pairing(
    private val store: PairingStore,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: Random = SecureRandom(),
) {

    sealed interface Attempt {
        /** Correct PIN: [token] is the new pairing token for the viewer. */
        class Ok(val token: String) : Attempt

        /** Wrong PIN. [lockedForMs] > 0 when this attempt started the lockout. */
        class Wrong(val lockedForMs: Long) : Attempt

        /** Refused without looking at the PIN: locked out. */
        class Locked(val retryInMs: Long) : Attempt
    }

    private val lock = Any()
    private val wrongAt = ArrayDeque<Long>()
    private var lockedUntilMs = Long.MIN_VALUE

    /** Whether viewers must pair (the phone's "Require pairing PIN" switch). */
    var required: Boolean
        get() = synchronized(lock) { store.required }
        set(value) = synchronized(lock) { store.required = value }

    /**
     * Number of digits of the PIN (4 or 6). Changing it generates a new PIN of that length at once; existing pairing
     * tokens stay valid. Other values are ignored.
     */
    var pinLength: Int
        get() = synchronized(lock) { store.pinLength }
        set(value) = synchronized(lock) {
            if (value != PIN_DIGITS_SHORT && value != PIN_DIGITS_LONG) return
            if (value != store.pinLength) {
                store.pinLength = value
                generatePin()
            }
        }

    /** The current PIN, created on first use. */
    val pin: String
        get() = synchronized(lock) { currentPin() }

    /** A new PIN. Existing pairing tokens stay valid. */
    fun newPin(): String = synchronized(lock) { generatePin() }

    val tokenCount: Int get() = synchronized(lock) { store.tokens.size }

    /** Milliseconds until PIN attempts are accepted again, 0 when not locked. */
    fun lockedForMs(): Long = synchronized(lock) { lockRemaining(nowMs()) }

    /** True if [token] is a known pairing token. Compares against every token without stopping early. */
    fun isValidToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val candidate = token.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            var ok = false
            for (t in store.tokens) {
                if (MessageDigest.isEqual(candidate, t.toByteArray(Charsets.UTF_8))) ok = true
            }
            return ok
        }
    }

    /** A viewer entered [pin]. */
    fun attempt(pin: String?): Attempt = synchronized(lock) {
        val now = nowMs()
        val left = lockRemaining(now)
        if (left > 0) return Attempt.Locked(left)
        val expected = currentPin().toByteArray(Charsets.UTF_8)
        if (MessageDigest.isEqual((pin ?: "").toByteArray(Charsets.UTF_8), expected)) {
            wrongAt.clear()
            return Attempt.Ok(newToken())
        }
        while (wrongAt.isNotEmpty() && now - wrongAt.first() >= WINDOW_MS) wrongAt.removeFirst()
        wrongAt.addLast(now)
        if (wrongAt.size >= MAX_WRONG) {
            wrongAt.clear()
            lockedUntilMs = now + LOCK_MS
            return Attempt.Wrong(LOCK_MS)
        }
        Attempt.Wrong(0)
    }

    /** Forgets every pairing token (the caller disconnects the viewers that used one). */
    fun forgetAll() = synchronized(lock) { store.tokens = emptyList() }

    private fun lockRemaining(now: Long): Long = if (lockedUntilMs == Long.MIN_VALUE) 0 else maxOf(0L, lockedUntilMs - now)

    private fun currentPin(): String {
        val p = store.pin
        return if (p != null && p.length == store.pinLength && p.all { it in '0'..'9' }) p else generatePin()
    }

    private fun generatePin(): String {
        val digits = store.pinLength
        val bound = if (digits == PIN_DIGITS_LONG) 1_000_000 else 10_000
        val p = random.nextInt(bound).toString().padStart(digits, '0')
        store.pin = p
        return p
    }

    private fun newToken(): String {
        val b = ByteArray(TOKEN_BYTES)
        random.nextBytes(b)
        val t = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        store.tokens = (store.tokens + t).takeLast(MAX_TOKENS)
        return t
    }

    companion object {
        const val PIN_DIGITS_SHORT = 4
        const val PIN_DIGITS_LONG = 6
        const val DEFAULT_PIN_DIGITS = PIN_DIGITS_SHORT
        const val TOKEN_BYTES = 24 // 192 bits
        const val MAX_TOKENS = 20
        const val MAX_WRONG = 5
        const val WINDOW_MS = 60_000L
        const val LOCK_MS = 60_000L
    }
}
