package app.webmirror.android.update

import android.content.Context

/** Play flavor: updates come from Google Play, so there is nothing to do. */
internal object UpdateEngine {
    fun schedule(context: Context) {}
    fun checkNow(context: Context) {}
    fun downloadAndInstall(context: Context) {}
    fun canInstallPackages(context: Context): Boolean = true
}
