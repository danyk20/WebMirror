package app.webmirror.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.webmirror.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Facade used by the app and the UI. On the play flavor every call is a no-op. */
object Updates {
    /** Extra put on the launch intent of "update available" notifications: the UI should open the Updates page. */
    const val EXTRA_OPEN_UPDATES = "app.webmirror.android.OPEN_UPDATES"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    internal fun setState(s: UpdateState) { _state.value = s }

    val selfUpdate: Boolean get() = BuildConfig.SELF_UPDATE

    /** Call once at app start (Application/Activity onCreate). Schedules the daily check if enabled. */
    fun schedule(context: Context) = UpdateEngine.schedule(context.applicationContext)

    /** Re-applies the schedule after the auto-check switch changed. */
    fun onAutoCheckChanged(context: Context) = UpdateEngine.schedule(context.applicationContext)

    /** Asynchronous; progress is reported through [state]. */
    fun checkNow(context: Context) = UpdateEngine.checkNow(context.applicationContext)

    /** Downloads, verifies and installs the available release. Asynchronous; see [state]. */
    fun downloadAndInstall(context: Context) = UpdateEngine.downloadAndInstall(context.applicationContext)

    /** Whether Android lets us install packages (always true on play, where nothing is installed). */
    fun canInstallPackages(context: Context): Boolean = UpdateEngine.canInstallPackages(context)

    /** Intent for the "install unknown apps" settings page of this app. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Google Play listing intent (market://, with web fallback via [playWebIntent]). */
    fun playIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))

    fun playWebIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
}
