package app.webmirror.android

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** The one place that builds the screen-capture consent intent (Home Start button and the Quick Settings tile). */
object CaptureRequest {
    /**
     * Android 14+ (API 34): the app chooses what the consent dialog offers, with `MediaProjectionConfig`:
     * the entire screen only (default) or the user's choice between one app and the entire screen.
     * Below 34 the system always captures the entire screen.
     */
    fun createIntent(context: Context): Intent {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) {
            val config = when (Settings(context).captureSource) {
                CaptureSource.ENTIRE_SCREEN -> MediaProjectionConfig.createConfigForDefaultDisplay()
                CaptureSource.USER_CHOICE -> MediaProjectionConfig.createConfigForUserChoice()
            }
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
    }
}
