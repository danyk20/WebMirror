package app.webmirror.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import app.webmirror.android.capture.Preset
import app.webmirror.android.encode.CodecSupport
import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.input.RemoteControlState
import app.webmirror.android.input.RemoteInputService
import app.webmirror.android.net.AutoCert
import app.webmirror.android.net.Ipv4
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.acme.IssueResult
import app.webmirror.android.net.VirtualIpService
import app.webmirror.android.test.AutoTestSection
import app.webmirror.android.test.TestReportScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    /** Asked first on Android 13+. The answer does not matter (the service runs without a visible notification). */
    private val notificationPermission =
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
        TlsStore.refresh(this)
        AutoCert.onAppStart(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        settings = settings,
                        onStart = ::startMirror,
                        onVirtualIp = ::setVirtualIp,
                        pickedP12 = pickedP12,
                        pickError = pickError,
                        onPickCertificate = { pickError = null; pickCertificate.launch(arrayOf("*/*")) },
                        onCertificateDone = { pickedP12 = null },
                    )
                }
            }
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
            requestCapture()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureConsent.launch(mpm.createScreenCaptureIntent())
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

@Composable
private fun MainScreen(
    settings: Settings,
    onStart: () -> Unit,
    onVirtualIp: (Boolean, String) -> Unit,
    pickedP12: ByteArray?,
    pickError: String?,
    onPickCertificate: () -> Unit,
    onCertificateDone: () -> Unit,
) {
    val context = LocalContext.current
    val state by MirrorService.state.collectAsState()
    var openReport by remember { mutableStateOf<String?>(null) }
    var safetyAccepted by remember { mutableStateOf(settings.safetyAcceptedVersion >= Settings.SAFETY_NOTICE_VERSION) }
    var showAbout by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(false) }
    if (!safetyAccepted) {
        // Must be accepted before the app can be used; Exit closes the app.
        SafetyNoticeScreen(
            onAccept = { settings.safetyAcceptedVersion = Settings.SAFETY_NOTICE_VERSION; safetyAccepted = true },
            onExit = { (context as? android.app.Activity)?.finish() },
        )
        return
    }
    if (showAbout) {
        SafetyNoticeScreen(onAccept = null, onExit = { showAbout = false })
        return
    }
    if (showWizard) {
        CertWizardScreen(onClose = { showWizard = false })
        return
    }
    val reportStem = openReport
    if (reportStem != null) {
        TestReportScreen(reportStem) { openReport = null }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("WebMirror", style = MaterialTheme.typography.headlineMedium)

        StatusBanner(state.running, state.paused, state.stream, state.video)

        // ---- Server ----
        SectionTitle("Server")
        if (state.paused) {
            // Server and viewers are still up; a new consent resumes the capture.
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start mirroring (resume)") }
            OutlinedButton(onClick = { MirrorService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop server")
            }
        } else {
            Button(
                onClick = { if (state.running) MirrorService.stop(context) else onStart() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.running) "Stop" else "Start mirroring")
            }
        }
        state.serverError?.let { ErrorText(it) }
        state.tlsError?.let { ErrorText(it) }

        if (state.running) {
            if (state.urls.isEmpty()) {
                Text("Server running, but no network address found. Connect to Wi-Fi or enable the hotspot.")
            } else {
                val urls = state.urls
                Text("Open in a browser:", style = MaterialTheme.typography.titleMedium)
                UrlGroup("Addresses", urls.addresses)
                if (urls.names.isNotEmpty()) {
                    UrlGroup("Names (for the car test)", urls.names)
                    Text(
                        "These names point at the addresses above through the public service sslip.io. " +
                            "The device you type them into needs internet access to look them up.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                UrlGroup("https (certificate)", urls.https)
                val qr = remember(urls.addresses.firstOrNull { !it.contains('[') } ?: urls.addresses.firstOrNull() ?: urls.https.first()) { qrBitmap(urls.addresses.firstOrNull { !it.contains('[') } ?: urls.addresses.firstOrNull() ?: urls.https.first(), 512) }
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code for ${urls.addresses.firstOrNull { !it.contains('[') } ?: urls.addresses.firstOrNull() ?: urls.https.first()}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(220.dp),
                )
            }
        } else {
            Text("Start asks for permission to capture the screen, then starts the server and the capture together. There is no server while nothing is mirrored.")
        }
        Text("Viewers: ${state.viewerCount}", style = MaterialTheme.typography.titleMedium)

        HorizontalDivider()
        PairingSection(settings)

        HorizontalDivider()
        AutoTestSection(settings) { openReport = it }

        HorizontalDivider()
        QualitySection(settings) { MirrorService.applySettings(context) }

        HorizontalDivider()
        ControlSection(settings, state.running, state.viewerCount)

        HorizontalDivider()
        DisplaySection(settings) { MirrorService.applySettings(context) }

        HorizontalDivider()
        AudioSection(settings) { MirrorService.applySettings(context) }

        HorizontalDivider()
        AdvancedSection(settings, state.encoder, state.running)

        HorizontalDivider()
        VirtualAddressSection(settings, onVirtualIp)

        HorizontalDivider()
        CertificateSection(pickedP12, pickError, onPickCertificate, onCertificateDone) { AutoCert.clearRun(); showWizard = true }

        HorizontalDivider()
        OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) { Text("About & safety") }

        HorizontalDivider()
        SectionTitle("Probe result")
        Text(
            text = state.lastProbe ?: "(none yet; open /probe.html in a browser)",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        Row {
            OutlinedButton(
                enabled = state.lastProbe != null,
                onClick = { state.lastProbe?.let { shareText(context, it) } },
            ) {
                Text("Share")
            }
        }
    }
}

/** Impossible to miss: red when nothing is served, green while mirroring. */
@Composable
private fun StatusBanner(running: Boolean, paused: Boolean, stream: String?, video: StreamHub.HelloInfo?) {
    val bg = when {
        paused -> ComposeColor(0xFFB26A00)
        running -> ComposeColor(0xFF1B7F3B)
        else -> ComposeColor(0xFFC62828)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(16.dp),
    ) {
        Column {
            Text(
                when {
                    paused -> "PAUSED - server running"
                    running -> "MIRRORING - server running"
                    else -> "SERVER STOPPED"
                },
                color = ComposeColor.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Text(
                when {
                    paused -> "Screen capture ended. Viewers stay connected; tap Start to resume."
                    running -> (stream ?: "") + (video?.takeIf { it.why.isNotEmpty() }?.let {
                        "\n${it.why}" + if (it.encoder.isNotEmpty()) "\n${it.encoder} (${if (it.hardware == true) "hardware" else "software"}), ${it.codec}" else ""
                    } ?: "")
                    else -> "Nothing is being served. The Tesla browser cannot connect."
                },
                color = ComposeColor.White,
            )
        }
    }
}

@Composable
private fun QualitySection(settings: Settings, onChanged: () -> Unit) {
    var selected by remember { mutableStateOf(settings.preset) }
    SectionTitle("Quality")
    Column(Modifier.selectableGroup()) {
        for (p in Preset.entries) {
            ChoiceRow("${p.label} (longest side ${p.maxLongSide} px, ${p.fps} fps, JPEG ${p.jpegFps} fps)", selected == p, true) {
                selected = p
                settings.preset = p
                onChanged()
            }
        }
    }
}

/** Pairing PIN (plan 2.1.4): the switch, the big PIN, "New PIN", the paired browsers and "Forget paired viewers". */
@Composable
private fun PairingSection(settings: Settings) {
    val context = LocalContext.current
    var required by remember { mutableStateOf(settings.pairingRequired) }
    var pin by remember { mutableStateOf(if (settings.pairingRequired) MirrorService.pairingPin(context) else "") }
    var paired by remember { mutableStateOf(settings.pairingTokens.size) }
    // New pairings happen in the service while this screen is open.
    androidx.compose.runtime.LaunchedEffect(required) {
        while (true) {
            paired = settings.pairingTokens.size
            if (required) pin = MirrorService.pairingPin(context)
            kotlinx.coroutines.delay(1000)
        }
    }
    SectionTitle("Pairing PIN")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = required, onCheckedChange = {
            required = it
            MirrorService.setPairingRequired(context, it)
            if (it) pin = MirrorService.pairingPin(context)
        })
        Text("Require pairing PIN", modifier = Modifier.padding(start = 12.dp))
    }
    if (required) {
        Text("Enter this PIN once in each browser (the browser then stays paired):", style = MaterialTheme.typography.bodySmall)
        Text(
            pin.chunked(3).joinToString(" "),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pin = MirrorService.newPairingPin(context) }) { Text("New PIN") }
            OutlinedButton(enabled = paired > 0, onClick = { MirrorService.forgetPairedViewers(context); paired = 0 }) {
                Text("Forget paired viewers")
            }
        }
        Text("Paired viewers: $paired", style = MaterialTheme.typography.titleMedium)
        Text(
            "A new PIN does not unpair browsers that are already paired. \"Forget paired viewers\" unpairs all of them at once and disconnects them.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "Anyone on the same network can watch your screen and control the phone.",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Remote control from viewers: the switch (persisted, default on) and the accessibility service status. */
@Composable
private fun ControlSection(settings: Settings, running: Boolean, viewers: Int) {
    val context = LocalContext.current
    val allowed by RemoteControlState.allowed.collectAsState()
    val connected by RemoteInputService.connected.collectAsState()
    // Also read the system setting: it is live (observer) and refreshed on every resume, so the status is right even if
    // the connected flag was missed.
    var enabledInSettings by remember { mutableStateOf(RemoteInputService.isEnabledInSettings(context)) }
    androidx.compose.runtime.DisposableEffect(context) {
        val refresh = { enabledInSettings = RemoteInputService.isEnabledInSettings(context) }
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor(android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer,
        )
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val resumeObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle?.addObserver(resumeObserver)
        refresh()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            lifecycle?.removeObserver(resumeObserver)
        }
    }
    val ready = connected || enabledInSettings
    SectionTitle("Remote control")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = allowed, onCheckedChange = { settings.remoteControlAllowed = it; RemoteControlState.allowed.value = it })
        Text("Allow control from viewers", modifier = Modifier.padding(start = 12.dp))
    }
    Text(
        "On by default and kept until you change it. While on (and the accessibility service is enabled), anyone who opens the page on your hotspot can control this phone; one viewer at a time operates it by touching the picture. Switch it off if you do not trust the people on the network.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (allowed && running) {
        Text("Viewers can control this phone now ($viewers connected).", color = MaterialTheme.colorScheme.error)
    }
    Text(
        if (ready) "Accessibility service: active" else "Accessibility service: not enabled",
        style = MaterialTheme.typography.titleMedium,
        color = if (ready) ComposeColor(0xFF008C3C) else MaterialTheme.colorScheme.error,
    )
    Text(
        "Touches are performed by an Android accessibility service. It only injects the touches sent from a viewer; it reads no screen content. " +
            "In Accessibility settings open Installed apps (or Downloaded apps), choose WebMirror remote control and switch it on.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) { Text("Open accessibility settings") }
}

@Composable
private fun DisplaySection(settings: Settings, onChanged: () -> Unit) {
    var forceJpeg by remember { mutableStateOf(settings.forceJpeg) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    SectionTitle("Screen and video")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = keepOn, onCheckedChange = {
            keepOn = it
            settings.keepScreenOn = it
            onChanged()
        })
        Text("Keep screen on", modifier = Modifier.padding(start = 12.dp))
    }
    Text("Capture stops if the phone screen locks.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = forceJpeg, onCheckedChange = {
            forceJpeg = it
            settings.forceJpeg = it
            onChanged()
        })
        Text("Force JPEG mode", modifier = Modifier.padding(start = 12.dp))
    }
    Text("Off: H.264 is used when every viewer can decode it, JPEG otherwise.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AudioSection(settings: Settings, onChanged: () -> Unit) {
    var selected by remember { mutableStateOf(settings.audioMode) }
    SectionTitle("Audio")
    Text("Bluetooth: the phone plays the sound to the car; the page delays the picture to match (set the delay in the page).")
    Column(Modifier.selectableGroup()) {
        for (m in AudioMode.entries) {
            ChoiceRow(m.label, selected == m, m.available) {
                selected = m
                settings.audioMode = m
                onChanged()
            }
        }
    }
}

/** Every latency/smoothness option of [StreamConfig]; collapsed by default, changes apply live. */
@Composable
private fun AdvancedSection(settings: Settings, encoder: String?, running: Boolean) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var cfg by remember { mutableStateOf(settings.streamConfig) }
    var resetToken by remember { mutableStateOf(0) }
    fun change(next: StreamConfig) {
        cfg = next
        MirrorService.applyConfig(context, next)
    }
    val labels = StreamConfig.LABELS

    SectionTitle("Advanced streaming options")
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "Hide options" else "Show options (config ${cfg.id})")
    }
    if (!expanded) return

    Text("Changes apply live. Socket options affect new connections only: reload the page to use them.", style = MaterialTheme.typography.bodySmall)
    SwitchRow(labels.getValue("tcpNoDelay"), cfg.tcpNoDelay) { change(cfg.copy(tcpNoDelay = it)) }
    NumberRow("sendBufferKb", cfg.sendBufferKb, resetToken) { change(cfg.copy(sendBufferKb = it)) }
    SwitchRow(labels.getValue("lowLatencyEncoder"), cfg.lowLatencyEncoder) { change(cfg.copy(lowLatencyEncoder = it)) }
    SwitchRow(labels.getValue("allowSoftwareEncoder"), cfg.allowSoftwareEncoder) { change(cfg.copy(allowSoftwareEncoder = it)) }
    Text(labels.getValue("codec"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (c in StreamConfig.CODECS) {
            ChoiceRow(c, cfg.codec == c, true) { change(cfg.copy(codec = c)) }
        }
    }
    Text(labels.getValue("profile"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (p in StreamConfig.PROFILES) {
            ChoiceRow(p, cfg.profile == p, true) { change(cfg.copy(profile = p)) }
        }
    }
    Text(labels.getValue("bitrateMode"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (m in StreamConfig.BITRATE_MODES) {
            ChoiceRow(m, cfg.bitrateMode == m, true) { change(cfg.copy(bitrateMode = m)) }
        }
    }
    SwitchRow(labels.getValue("intraRefresh"), cfg.intraRefresh) { change(cfg.copy(intraRefresh = it)) }
    NumberRow("keyframeIntervalS", cfg.keyframeIntervalS, resetToken) { change(cfg.copy(keyframeIntervalS = it)) }
    SwitchRow(labels.getValue("fitToViewer"), cfg.fitToViewer) { change(cfg.copy(fitToViewer = it)) }
    NumberRow("maxInFlight", cfg.maxInFlight, resetToken) { change(cfg.copy(maxInFlight = it)) }
    NumberRow("dropAfterMs", cfg.dropAfterMs, resetToken) { change(cfg.copy(dropAfterMs = it)) }
    SwitchRow(labels.getValue("adaptiveBitrate"), cfg.adaptiveBitrate) { change(cfg.copy(adaptiveBitrate = it)) }
    NumberRow("minBitrateKbps", cfg.minBitrateKbps, resetToken) { change(cfg.copy(minBitrateKbps = it)) }
    NumberRow("jitterBufferMs", cfg.jitterBufferMs, resetToken) { change(cfg.copy(jitterBufferMs = it)) }
    NumberRow("skipLateFrames", cfg.skipLateFrames, resetToken) { change(cfg.copy(skipLateFrames = it)) }
    Text(labels.getValue("pacing"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (p in StreamConfig.PACINGS) {
            ChoiceRow(p, cfg.pacing == p, true) { change(cfg.copy(pacing = p)) }
        }
    }
    OutlinedButton(onClick = { resetToken++; change(StreamConfig()) }, modifier = Modifier.fillMaxWidth()) {
        Text("Reset to defaults")
    }

    Text("Codecs on this phone", style = MaterialTheme.typography.titleMedium)
    val preset = settings.preset
    val codecTable = remember(preset) {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
        val size = app.webmirror.android.capture.CaptureSize.compute(p.x, p.y, preset.maxLongSide)
        CodecSupport.describe(CodecSupport.rows(size.width, size.height, preset.fps), size.width, size.height, preset.fps)
    }
    Text(text = codecTable, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

    Text("Effective encoder state", style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (running) encoder ?: "(no encoder yet)" else "(not mirroring)",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Number field for a ranged field of [StreamConfig]; applies when the text is a valid in-range number. */
@Composable
private fun NumberRow(key: String, value: Int, resetToken: Int, onValue: (Int) -> Unit) {
    val range = StreamConfig.RANGES.getValue(key)
    var text by remember(resetToken) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { c -> c.isDigit() }.take(5)
            val n = text.toIntOrNull()
            if (n != null && n in range) onValue(n)
        },
        label = { Text(StreamConfig.LABELS.getValue(key)) },
        supportingText = { Text("${range.first} to ${range.last}") },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun UrlGroup(title: String, urls: List<String>) {
    if (urls.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge)
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (url in urls) {
                Text(url, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun VirtualAddressSection(settings: Settings, onVirtualIp: (Boolean, String) -> Unit) {
    val vip by VirtualIpService.state.collectAsState()
    var text by remember { mutableStateOf(settings.virtualIp) }
    var fieldError by remember { mutableStateOf<String?>(null) }

    SectionTitle("Virtual address")
    Text("Link-local address that the Tesla browser accepts. Devices on the phone's hotspot reach the server there.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = vip.active,
            onCheckedChange = { on ->
                val ip = text.trim()
                if (on && !Ipv4.isValid(ip)) {
                    fieldError = "Enter four numbers from 0 to 255 separated by dots, e.g. 169.254.77.1"
                } else {
                    fieldError = null
                    onVirtualIp(on, ip)
                }
            },
        )
        Text(if (vip.active) "On: ${vip.address}" else "Off")
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            fieldError = null
            settings.virtualIp = it.trim()
        },
        label = { Text("IPv4 address") },
        singleLine = true,
        enabled = !vip.active,
        isError = fieldError != null,
        supportingText = fieldError?.let { msg -> { Text(msg) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !vip.active,
            onClick = {
                text = Settings.DEFAULT_VIRTUAL_IP
                fieldError = null
                settings.virtualIp = Settings.DEFAULT_VIRTUAL_IP
            },
        ) {
            Text(Settings.DEFAULT_VIRTUAL_IP)
        }
    }
    vip.error?.let { ErrorText(it) }
}

@Composable
private fun CertificateSection(
    pickedP12: ByteArray?,
    pickError: String?,
    onPick: () -> Unit,
    onDone: () -> Unit,
    onOpenWizard: () -> Unit,
) {
    val context = LocalContext.current
    val auto by AutoCert.state.collectAsState()
    val autoRun by AutoCert.run.collectAsState()
    val tls by TlsStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    SectionTitle("HTTPS certificate")
    Text("The car shows smooth video only on a secure (https) page, served on port 8443. Get a free certificate in two minutes, or import your own.")

    val info = tls.info
    if (info != null) {
        val expiry = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.notAfterMs))
        val expired = info.notAfterMs < System.currentTimeMillis()
        Text("Subject CN: ${info.commonName ?: "(none)"}")
        Text("DNS names: ${if (info.dnsNames.isEmpty()) "(none)" else info.dnsNames.joinToString(", ")}")
        Text(if (expired) "Expired on $expiry" else "Expires on $expiry")
    } else {
        Text("No certificate installed.")
    }
    tls.error?.let { ErrorText(it) }
    pickError?.let { ErrorText(it) }
    if (auto.enabled) {
        val until = if (auto.notAfterMs > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(auto.notAfterMs)) else "?"
        Text("Automatic: ${auto.fqdn}, valid until $until, renews automatically", style = MaterialTheme.typography.titleSmall)
        auto.lastError?.let { ErrorText("Last renewal failed: $it") }
        auto.ipError?.let { ErrorText(it) }
        autoRun.lines.lastOrNull()?.takeIf { autoRun.running }?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        (autoRun.result as? IssueResult.Failure)?.takeIf { it !is IssueResult.Cancelled }?.let { ErrorText(it.message) }
        if (auto.staging) {
            ErrorText("This is a TEST certificate. Browsers and the car do NOT trust it.")
            Button(onClick = onOpenWizard, enabled = !autoRun.running, modifier = Modifier.fillMaxWidth()) {
                Text("Get a real certificate")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (autoRun.running) {
                OutlinedButton(onClick = { AutoCert.cancelRun() }) { Text("Cancel") }
            } else if (!auto.staging) {
                Button(onClick = { AutoCert.startRenewNow(context) }) { Text("Renew now") }
            }
            OutlinedButton(onClick = onOpenWizard, enabled = !autoRun.running) { Text("Change name or token") }
        }
        OutlinedButton(onClick = { AutoCert.turnOff(context, removeCertificate = true) }, enabled = !autoRun.running) {
            Text("Turn off automatic certificate")
        }
    } else {
        Button(onClick = onOpenWizard, modifier = Modifier.fillMaxWidth()) { Text("Get a free certificate") }
    }
    Text("Advanced", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importError = null; password = ""; onPick() }) {
            Text("Import certificate (.p12)")
        }
        if (!auto.enabled && (info != null || tls.error != null)) {
            OutlinedButton(onClick = { TlsStore.remove(context) }) { Text("Remove certificate") }
        }
    }
    Text("Changes apply to a running server immediately.")

    if (pickedP12 != null) {
        AlertDialog(
            onDismissRequest = { password = ""; importError = null; onDone() },
            title = { Text("Certificate password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; importError = null },
                        label = { Text("Password of the .p12 file") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = importError != null,
                    )
                    importError?.let { ErrorText(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chars = password.toCharArray()
                    scope.launch {
                        val error = withContext(Dispatchers.Default) { TlsStore.import(context, pickedP12, chars) }
                        if (error == null) {
                            AutoCert.turnOff(context, removeCertificate = false)
                            password = ""
                            importError = null
                            onDone()
                        } else {
                            importError = error
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { password = ""; importError = null; onDone() }) { Text("Cancel") }
            },
        )
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "WebMirror probe result")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share probe result"))
}

internal fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp
}
