package app.webmirror.android.update

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Receives the PackageInstaller session status (declared, not exported, in the github manifest). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val release = UpdateEngine.currentRelease()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                if (isForeground()) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (_: Exception) { UpdateNotifications.readyToInstall(context, confirm) }
                } else {
                    UpdateNotifications.readyToInstall(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateNotifications.clearReady(context) // the app is replaced and restarted by the system
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateNotifications.clearReady(context)
                Updates.setState(if (release != null) UpdateState.Available(release) else UpdateState.Idle)
            }
            else -> {
                UpdateNotifications.clearReady(context)
                Updates.setState(UpdateState.Failed(message?.takeIf { it.isNotBlank() } ?: "Android could not install the update (code $status)."))
            }
        }
    }

    private fun isForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
