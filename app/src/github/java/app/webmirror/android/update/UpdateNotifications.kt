package app.webmirror.android.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal object UpdateNotifications {
    private const val CHANNEL = "updates"
    private const val ID_AVAILABLE = 9101
    private const val ID_READY = 9102

    private fun canPost(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun post(ctx: Context, id: Int, title: String, text: String, pi: PendingIntent?) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** "WebMirror X.Y available"; tapping opens the app with [Updates.EXTRA_OPEN_UPDATES]. */
    fun available(ctx: Context, release: ReleaseInfo) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            putExtra(Updates.EXTRA_OPEN_UPDATES, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = launch?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        post(ctx, ID_AVAILABLE, "WebMirror ${release.version} available", "Tap to see what's new and update.", pi)
    }

    /** "Update ready, tap to install": [confirm] is the system's install confirmation intent. */
    fun readyToInstall(ctx: Context, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        post(ctx, ID_READY, "Update ready", "Tap to install.", pi)
    }

    fun clearReady(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_READY)
}
