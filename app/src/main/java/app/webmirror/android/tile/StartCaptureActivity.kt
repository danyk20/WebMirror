package app.webmirror.android.tile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import app.webmirror.android.AudioMode
import app.webmirror.android.Settings
import app.webmirror.android.CaptureRequest
import app.webmirror.android.MirrorService

/** Invisible activity started by the Quick Settings tile: shows the capture consent dialog, starts mirroring, finishes. */
class StartCaptureActivity : ComponentActivity() {

    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                MirrorService.start(this, result.resultCode, data)
            }
            finish()
        }

    /** "In the browser" sound needs RECORD_AUDIO; if denied, mirroring starts without sound. */
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { captureConsent.launch(CaptureRequest.createIntent(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change or process death the consent dialog may already be showing.
        if (savedInstanceState == null) {
            if (Settings(this).audioMode == AudioMode.BROWSER &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                captureConsent.launch(CaptureRequest.createIntent(this))
            }
        }
    }
}
