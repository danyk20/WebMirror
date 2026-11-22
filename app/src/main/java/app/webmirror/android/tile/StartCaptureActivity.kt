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
import app.webmirror.android.net.Ipv4
import android.net.VpnService

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

    /** VPN consent for the virtual address. Denied: mirror without it and let Home warn. */
    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) Settings(this).virtualIpLost = true
            askAudioThenCapture()
        }

    private fun askAudioThenCapture() {
        if (Settings(this).audioMode == AudioMode.BROWSER &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            captureConsent.launch(CaptureRequest.createIntent(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change or process death the consent dialog may already be showing.
        if (savedInstanceState == null) {
            val settings = Settings(this)
            val consent = if (settings.virtualIpEnabled && Ipv4.isValid(settings.virtualIp)) VpnService.prepare(this) else null
            if (consent != null) vpnConsent.launch(consent) else askAudioThenCapture()
        }
    }
}
