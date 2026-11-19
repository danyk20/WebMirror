package app.webmirror.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.webmirror.android.input.PhoneRotation
import app.webmirror.android.input.RemoteControlState
import app.webmirror.android.input.TypingState
import app.webmirror.android.net.AutoCert
import app.webmirror.android.net.Ipv4
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.VirtualIpService
import app.webmirror.android.update.Updates
import app.webmirror.android.ui.MainActions
import app.webmirror.android.ui.MainScreen
import app.webmirror.android.ui.WebMirrorTheme

class MainActivity : ComponentActivity() {

    /** Setup checklist on Home: asks for the notification permission only. Opens the app's notification settings if Android no longer shows the dialog. */
    private val notificationOnly =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }

    /** Asked first on Android 13+ when Start is tapped. The answer does not matter (the service runs without a visible notification). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askAudioThenCapture() }

    /** "In the browser" sound needs RECORD_AUDIO. Asked from the Start flow; if denied, mirroring starts without sound. */
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    /** System dialog "Start recording or casting?". Its result can be used once, for one service start. */
    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                MirrorService.start(this, result.resultCode, data)
            }
        }

    private lateinit var settings: Settings

    /** Set by a notification tap (Updates.EXTRA_OPEN_UPDATES); the UI opens Settings > Updates and clears it. */
    private var openUpdates by mutableStateOf(false)

    /** Address waiting for the system VPN consent dialog. */
    private var pendingVirtualIp: String? = null

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ip = pendingVirtualIp
            pendingVirtualIp = null
            if (result.resultCode == RESULT_OK && ip != null) {
                VirtualIpService.start(this, ip)
            } else {
                settings.virtualIpEnabled = false
            }
        }

    /** Bytes of the picked .p12, held in memory until the password was accepted (nothing is saved before). */
    private var pickedP12 by mutableStateOf<ByteArray?>(null)
    private var pickError by mutableStateOf<String?>(null)

    private val pickCertificate =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                pickedP12 = TlsStore.readPicked(this, uri)
                pickError = null
            } catch (e: Exception) {
                pickedP12 = null
                pickError = "Cannot read the file: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        RemoteControlState.allowed.value = settings.remoteControlAllowed
        TypingState.keyboard.value = settings.typingKeyboard
        TlsStore.refresh(this)
        if (!MirrorService.state.value.running) PhoneRotation.restore(this) // crash recovery
        AutoCert.onAppStart(this)
        Updates.schedule(this)
        handleIntent(intent)
        setContent {
            WebMirrorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        settings = settings,
                        actions = MainActions(
                            onStart = ::startMirror,
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= 33) notificationOnly.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onVirtualIp = ::setVirtualIp,
                            pickedP12 = pickedP12,
                            pickError = pickError,
                            onPickCertificate = { pickError = null; pickCertificate.launch(arrayOf("*/*")) },
                            onCertificateDone = { pickedP12 = null },
                            openUpdates = openUpdates,
                            onUpdatesOpened = { openUpdates = false },
                        ),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Updates.EXTRA_OPEN_UPDATES, false) == true) {
            intent.removeExtra(Updates.EXTRA_OPEN_UPDATES)
            openUpdates = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore the virtual address if it was on and the system consent is still valid. Done here, not in
        // onCreate: an activity created while the screen is off/locked may not start services (the start is also
        // guarded, and tried again on the next resume).
        if (settings.virtualIpEnabled && !VirtualIpService.state.value.active &&
            Ipv4.isValid(settings.virtualIp) && VpnService.prepare(this) == null
        ) {
            VirtualIpService.start(this, settings.virtualIp)
        }
        MirrorService.refresh(this)
    }

    private fun startMirror() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askAudioThenCapture()
        }
    }

    private fun askAudioThenCapture() {
        if (settings.audioMode == AudioMode.BROWSER &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        captureConsent.launch(CaptureRequest.createIntent(this))
    }

    /** Switch handler. The address was validated by the UI before calling with `on = true`. */
    private fun setVirtualIp(on: Boolean, ip: String) {
        settings.virtualIp = ip
        settings.virtualIpEnabled = on
        if (on) AutoCert.updateIp(this, ip)
        if (!on) {
            VirtualIpService.stop(this)
            return
        }
        val consent = VpnService.prepare(this)
        if (consent == null) {
            VirtualIpService.start(this, ip)
        } else {
            pendingVirtualIp = ip
            vpnConsent.launch(consent)
        }
    }
}

