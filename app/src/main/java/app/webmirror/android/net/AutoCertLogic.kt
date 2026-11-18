package app.webmirror.android.net

import app.webmirror.android.net.acme.shouldRenew

/** Persisted state of the automatic certificate. Secrets are held decrypted only in memory ([token], [accountPkcs8]). */
data class AutoCertState(
    val enabled: Boolean = false,
    /** Bare DuckDNS name, e.g. "mycar-mirror". */
    val subdomain: String = "",
    val token: String = "",
    val accountPkcs8: ByteArray? = null,
    val accountX509: ByteArray? = null,
    val notBeforeMs: Long = 0,
    val notAfterMs: Long = 0,
    val staging: Boolean = false,
    val lastAttemptMs: Long = 0,
    val lastError: String? = null,
    /** Error from the last DuckDNS address update after the virtual address changed. */
    val ipError: String? = null,
) {
    val fqdn: String get() = "$subdomain.duckdns.org"
}

object AutoCertLogic {
    /** After a failed attempt, do not retry before this much time passed (the worker runs every 12 h). */
    const val RETRY_GAP_MS = 60L * 60 * 1000
    const val WARN_BEFORE_EXPIRY_MS = 14L * 24 * 60 * 60 * 1000

    /** Whether a renewal run should start now. [forced] is the "Renew now" button. */
    fun shouldRun(s: AutoCertState, hasCertificate: Boolean, nowMs: Long, forced: Boolean): Boolean {
        if (!s.enabled || s.subdomain.isEmpty() || s.token.isEmpty()) return false
        if (forced) return true
        if (s.lastError != null && s.lastAttemptMs > 0 && nowMs - s.lastAttemptMs < RETRY_GAP_MS) return false
        if (!hasCertificate || s.notAfterMs <= 0) return true
        return shouldRenew(s.notAfterMs, s.notBeforeMs, nowMs)
    }

    /** True when a failure deserves a notification: no certificate or less than 14 days left. */
    fun shouldNotify(s: AutoCertState, hasCertificate: Boolean, nowMs: Long): Boolean =
        !hasCertificate || s.notAfterMs - nowMs < WARN_BEFORE_EXPIRY_MS

    fun onSuccess(s: AutoCertState, notBeforeMs: Long, notAfterMs: Long, nowMs: Long, accountPkcs8: ByteArray, accountX509: ByteArray) =
        s.copy(enabled = true, notBeforeMs = notBeforeMs, notAfterMs = notAfterMs, lastAttemptMs = nowMs, lastError = null,
            accountPkcs8 = accountPkcs8, accountX509 = accountX509)

    fun onFailure(s: AutoCertState, nowMs: Long, message: String) = s.copy(lastAttemptMs = nowMs, lastError = message)

    /** Hint shown in the wizard for each kind of failure. */
    fun hint(f: app.webmirror.android.net.acme.IssueResult.Failure): String = when (f) {
        is app.webmirror.android.net.acme.IssueResult.DuckDnsRejected -> "Check the name and the token. Copy the token again from duckdns.org."
        is app.webmirror.android.net.acme.IssueResult.RateLimited -> "Let's Encrypt allows only a few certificates per week. Wait a while and try again."
        is app.webmirror.android.net.acme.IssueResult.NetworkError -> "Check that mobile data or Wi-Fi works, then try again."
        is app.webmirror.android.net.acme.IssueResult.Rejected -> f.message
        is app.webmirror.android.net.acme.IssueResult.Timeout -> "Let's Encrypt took too long. Try again in a few minutes."
        else -> ""
    }
}
