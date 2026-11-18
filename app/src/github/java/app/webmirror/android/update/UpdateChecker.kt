package app.webmirror.android.update

import android.content.Context
import app.webmirror.android.BuildConfig
import app.webmirror.android.net.acme.HttpTransport
import app.webmirror.android.net.acme.UrlConnectionTransport
import java.io.IOException

sealed interface CheckResult {
    data object NoInfo : CheckResult
    data object UpToDate : CheckResult
    data class Available(val release: ReleaseInfo) : CheckResult
}

class UpdateChecker(
    private val transport: HttpTransport,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    constructor(context: Context) : this(UrlConnectionTransport(context))

    /** Blocking. Throws [IOException] on network or protocol problems. */
    @Throws(IOException::class)
    fun check(): CheckResult {
        val r = transport.request(
            "GET", "https://api.github.com/repos/$repo/releases/latest",
            mapOf("Accept" to "application/vnd.github+json"),
        )
        when {
            r.status == 404 -> return CheckResult.NoInfo
            r.status == 403 || r.status == 429 -> throw IOException("GitHub is limiting requests right now; try again later.")
            r.status != 200 -> throw IOException("GitHub answered with status ${r.status}.")
        }
        val release = try {
            ReleaseParser.parse(r.text)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable answer from GitHub.")
        } ?: return CheckResult.NoInfo
        return if (Version.isNewer(release.version, currentVersion)) CheckResult.Available(release) else CheckResult.UpToDate
    }
}
