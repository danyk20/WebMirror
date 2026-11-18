package app.webmirror.android.input

/**
 * Who may control the phone. The phone's switch ([allowed]) and the accessibility service ([ready]) must both be on;
 * then exactly one viewer (the first that asks) owns the control until it turns it off, disconnects, or the switch /
 * service goes away. Pure and thread-safe.
 */
class ControlArbiter {

    /** Answer to a `control` request: a `control` message, or `controlDenied` with [reason]. */
    data class Reply(val denied: Boolean, val on: Boolean, val owner: Boolean, val reason: String?) {
        fun toJson(): String =
            if (denied) """{"t":"controlDenied","reason":"$reason"}"""
            else """{"t":"control","on":$on,"owner":$owner${if (reason != null) ""","reason":"$reason"""" else ""}}"""
    }

    private val lock = Any()
    private var allowed = false
    private var ready = false
    private var owner: Int? = null

    val ownerId: Int? get() = synchronized(lock) { owner }

    /** `{t:"control", on}` from viewer [viewerId]. */
    fun request(viewerId: Int, on: Boolean): Reply = synchronized(lock) {
        if (!on) {
            if (owner == viewerId) owner = null
            return Reply(denied = false, on = false, owner = false, reason = null)
        }
        when {
            !allowed -> Reply(true, false, false, REASON_NOT_ALLOWED)
            !ready -> Reply(true, false, false, REASON_NOT_READY)
            owner != null && owner != viewerId -> Reply(true, false, false, REASON_BUSY)
            else -> {
                owner = viewerId
                Reply(denied = false, on = true, owner = true, reason = null)
            }
        }
    }

    /** True while [viewerId] may inject input. */
    fun canInput(viewerId: Int): Boolean = synchronized(lock) { allowed && ready && owner == viewerId }

    /** The viewer is gone. Returns true if it was the owner (the caller must release the touches). */
    fun release(viewerId: Int): Boolean = synchronized(lock) {
        if (owner == viewerId) { owner = null; true } else false
    }

    /**
     * The phone's switch or the service state changed. Returns the dropped owner and the reason when control became
     * unavailable while somebody owned it, else null.
     */
    fun setState(allowed: Boolean, ready: Boolean): Pair<Int, String>? = synchronized(lock) {
        this.allowed = allowed
        this.ready = ready
        val o = owner
        if (o != null && (!allowed || !ready)) {
            owner = null
            o to (if (!allowed) REASON_NOT_ALLOWED else REASON_NOT_READY)
        } else null
    }

    /** Forgets the owner (mirroring stopped); the allowed/ready state is kept. */
    fun clearOwner() = synchronized(lock) { owner = null }

    companion object {
        const val REASON_NOT_ALLOWED = "notAllowed"
        const val REASON_NOT_READY = "notReady"
        const val REASON_BUSY = "busy"
    }
}

/**
 * At most [maxPerSecond] messages per fixed one-second window per viewer. [essential] messages (`up`, `cancel`) are
 * never refused, only counted: a lost `up` would leave a finger stuck on the phone's screen.
 */
class RateLimiter(private val maxPerSecond: Int = DEFAULT_MAX_PER_SECOND) {
    private var windowStartMs = Long.MIN_VALUE
    private var count = 0

    @Synchronized
    fun allow(nowMs: Long, essential: Boolean = false): Boolean {
        if (windowStartMs == Long.MIN_VALUE || nowMs - windowStartMs >= 1000 || nowMs < windowStartMs) {
            windowStartMs = nowMs
            count = 0
        }
        if (essential) { count++; return true }
        if (count >= maxPerSecond) return false
        count++
        return true
    }

    companion object {
        const val DEFAULT_MAX_PER_SECOND = 120
    }
}
