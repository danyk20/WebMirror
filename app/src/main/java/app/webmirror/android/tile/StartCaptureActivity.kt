package app.webmirror.android.tile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change or process death the consent dialog may already be showing.
        if (savedInstanceState == null) captureConsent.launch(CaptureRequest.createIntent(this))
    }
}
