package app.webmirror.android.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.webmirror.android.AudioMode
import app.webmirror.android.BuildConfig
import app.webmirror.android.CaptureSource
import app.webmirror.android.MirrorService
import app.webmirror.android.SafetyNotice
import app.webmirror.android.Settings
import app.webmirror.android.capture.CaptureSize
import app.webmirror.android.capture.Preset
import app.webmirror.android.encode.CodecSupport
import app.webmirror.android.input.RemoteControlState
import app.webmirror.android.net.AutoCert
import app.webmirror.android.net.Ipv4
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.VirtualIpService
import app.webmirror.android.net.acme.IssueResult
import app.webmirror.android.stream.Pairing
import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.test.AutoTestRunner
import app.webmirror.android.test.AutoTestSection
import app.webmirror.android.update.UpdateState
import app.webmirror.android.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The categories of the Settings list. */
internal enum class SettingsPage(val title: String, val icon: ImageVector?) {
    VIDEO("Video & quality", AppIcons.Play),
    CAPTURE("Screen capture", AppIcons.Phone),
    AUDIO("Audio", null),
    CONTROL("Remote control", AppIcons.Person),
    SECURITY("Security", AppIcons.Lock),
    HTTPS("Secure connection (HTTPS)", AppIcons.CheckCircle),
    NETWORK("Network", AppIcons.Place),
    UPDATES("Updates", AppIcons.Refresh),
    ADVANCED("Advanced streaming", AppIcons.Settings),
    TESTING("Testing", AppIcons.Search),
    DEBUGGING("Debugging", AppIcons.Build),
    ABOUT("About & safety", AppIcons.Info),
}

/** What the settings pages need from the activity. */
internal class SettingsActions(
    val onVirtualIp: (Boolean, String) -> Unit,
    val pickedP12: ByteArray?,
    val pickError: String?,
    val onPickCertificate: () -> Unit,
    val onCertificateDone: () -> Unit,
    val onOpenWizard: () -> Unit,
    val onOpenReport: (String) -> Unit,
    val onOpenHelp: () -> Unit,
)

private val dateFormat: DateFormat get() = DateFormat.getDateInstance(DateFormat.MEDIUM)

// ---------------------------------------------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun SettingsHome(settings: Settings, onOpen: (SettingsPage) -> Unit) {
    val tls by TlsStore.state.collectAsState()
    val vip by VirtualIpService.state.collectAsState()
    val test by AutoTestRunner.state.collectAsState()
    val controlAllowed by RemoteControlState.allowed.collectAsState()
    val updateState by Updates.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
        for (page in SettingsPage.entries) {
            val summary = when (page) {
                SettingsPage.VIDEO -> settings.preset.label
                SettingsPage.CAPTURE -> if (Build.VERSION.SDK_INT >= 34) settings.captureSource.label else CaptureSource.ENTIRE_SCREEN.label
                SettingsPage.AUDIO -> settings.audioMode.label
                SettingsPage.CONTROL -> if (controlAllowed) "On" else "Off"
                SettingsPage.SECURITY -> protectionLabel(settings)
                SettingsPage.HTTPS -> tls.info?.let { info ->
                    val name = info.dnsNames.firstOrNull() ?: info.commonName ?: "Certificate"
                    if (info.notAfterMs < System.currentTimeMillis()) "$name (expired)" else "$name, until ${dateFormat.format(Date(info.notAfterMs))}"
                } ?: "No certificate"
                SettingsPage.NETWORK -> if (vip.active) "Virtual address on: ${vip.address}" else "Virtual address off"
                SettingsPage.UPDATES -> if (updateState is UpdateState.Available) "New version available" else "Version ${BuildConfig.VERSION_NAME}"
                SettingsPage.ADVANCED -> "Latency and smoothness options"
                SettingsPage.TESTING -> if (test.active) "Test running" else "Automatic test and results"
                SettingsPage.DEBUGGING -> "Probe result, encoder, statistics"
                SettingsPage.ABOUT -> "Version ${BuildConfig.VERSION_NAME}, MIT license"
            }
            ListItem(
                headlineContent = { Text(page.title) },
                supportingContent = { Text(summary, maxLines = 1) },
                leadingContent = { Box(Modifier.size(24.dp)) { page.icon?.let { Icon(it, contentDescription = null) } } },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page == SettingsPage.UPDATES && updateState is UpdateState.Available) androidx.compose.material3.Badge()
                        Icon(AppIcons.ChevronRight, contentDescription = null)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(page) },
            )
        }
    }
}

private fun protectionLabel(settings: Settings): String =
    if (!settings.pairingRequired) "No protection" else "${settings.pairingPinLength}-digit PIN"

// ---------------------------------------------------------------------------------------------------------------
// One sub-page
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun SettingsSubPage(page: SettingsPage, settings: Settings, actions: SettingsActions, onBack: () -> Unit) {
    SubPage(page.title, onBack) {
        when (page) {
            SettingsPage.VIDEO -> VideoSettings(settings)
            SettingsPage.CAPTURE -> CaptureSettings(settings)
            SettingsPage.AUDIO -> AudioSettings(settings)
            SettingsPage.CONTROL -> ControlSettings(settings)
            SettingsPage.SECURITY -> SecuritySettings(settings)
            SettingsPage.HTTPS -> HttpsSettings(actions)
            SettingsPage.NETWORK -> NetworkSettings(settings, actions.onVirtualIp)
            SettingsPage.UPDATES -> UpdatesSettingsPage()
            SettingsPage.ADVANCED -> AdvancedSettings(settings)
            SettingsPage.TESTING -> AutoTestSection(settings, actions.onOpenReport)
            SettingsPage.DEBUGGING -> DebuggingSettings(settings)
            SettingsPage.ABOUT -> AboutSettings(actions.onOpenHelp)
        }
    }
}

@Composable
private fun VideoSettings(settings: Settings) {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(settings.preset) }
    var forceJpeg by remember { mutableStateOf(settings.forceJpeg) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    val apply = { MirrorService.applySettings(context) }
    GroupTitle("Quality")
    Column(Modifier.selectableGroup()) {
        for (p in Preset.entries) {
            ChoiceRow(
                p.label,
                preset == p,
                supporting = if (p == Preset.SHARPER) "Sharper text, up to ${p.maxLongSide} px and ${p.fps} frames per second."
                else "Smoother motion, up to ${p.maxLongSide} px and ${p.fps} frames per second.",
            ) {
                preset = p
                settings.preset = p
                apply()
            }
        }
    }
    GroupTitle("Screen and video")
    SwitchRow("Keep screen on", keepOn, supporting = "Sharing stops when the phone screen locks.") {
        keepOn = it
        settings.keepScreenOn = it
        apply()
    }
    SwitchRow("Force JPEG mode", forceJpeg, supporting = "Off: video is used when every viewer can play it, pictures (JPEG) otherwise.") {
        forceJpeg = it
        settings.forceJpeg = it
        apply()
    }
}

@Composable
private fun CaptureSettings(settings: Settings) {
    var source by remember { mutableStateOf(settings.captureSource) }
    val supported = Build.VERSION.SDK_INT >= 34
    GroupTitle("What to share when you tap Start")
    Column(Modifier.selectableGroup()) {
        ChoiceRow(
            CaptureSource.ENTIRE_SCREEN.label,
            !supported || source == CaptureSource.ENTIRE_SCREEN,
            enabled = supported,
            supporting = "Everything on the phone screen, whatever app you use.",
        ) { source = CaptureSource.ENTIRE_SCREEN; settings.captureSource = source }
        ChoiceRow(
            CaptureSource.USER_CHOICE.label,
            supported && source == CaptureSource.USER_CHOICE,
            enabled = supported,
            supporting = "Android asks whether to share one app or the entire screen.",
        ) { source = CaptureSource.USER_CHOICE; settings.captureSource = source }
    }
    if (!supported) Note("Your Android version always shares the entire screen.")
    if (Build.VERSION.SDK_INT >= 33) {
        val context = LocalContext.current
        var added by remember { mutableStateOf(false) }
        GroupTitle("Quick Settings tile")
        Note("Start and stop mirroring from the Quick Settings panel without opening the app.")
        OutlinedButton(
            enabled = !added,
            onClick = {
                val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
                sbm.requestAddTileService(
                    android.content.ComponentName(context, app.webmirror.android.tile.MirrorTileService::class.java),
                    "WebMirror",
                    android.graphics.drawable.Icon.createWithResource(context, app.webmirror.android.R.drawable.ic_tile),
                    context.mainExecutor,
                ) { result ->
                    if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    ) added = true
                }
            },
        ) { Text(if (added) "WebMirror tile added" else "Add WebMirror to Quick Settings") }
    }
}

@Composable
private fun AudioSettings(settings: Settings) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(settings.audioMode) }
    Note("WebMirror does not send sound to the browser yet. In the car, sound comes from the phone over Bluetooth.")
    Column(Modifier.selectableGroup()) {
        for (m in AudioMode.entries) {
            ChoiceRow(
                m.label,
                selected == m,
                enabled = m.available,
                supporting = when (m) {
                    AudioMode.BLUETOOTH -> "The phone plays the sound to the car. The page delays the picture to match; set the delay in the page."
                    AudioMode.BROWSER -> "Sound through the browser. Not available yet."
                    AudioMode.OFF -> "No sound hint on the page."
                },
            ) {
                selected = m
                settings.audioMode = m
                MirrorService.applySettings(context)
            }
        }
    }
}

@Composable
private fun ControlSettings(settings: Settings) {
    val context = LocalContext.current
    val state by MirrorService.state.collectAsState()
    val allowed by RemoteControlState.allowed.collectAsState()
    val ready = rememberAccessibilityReady()
    SwitchRow("Allow control from the car", allowed) { settings.remoteControlAllowed = it; RemoteControlState.allowed.value = it }
    Note(
        "On by default and kept until you change it. While on (and the accessibility service is enabled), anyone who opens the page on your hotspot " +
            "can control this phone; one viewer at a time operates it by touching the picture. Switch it off if you do not trust the people on the network.",
    )
    if (allowed && state.running) ErrorText("Viewers can control this phone now (${state.viewerCount} connected).")
    Text(
        if (ready) "Accessibility service: active" else "Accessibility service: not enabled",
        style = MaterialTheme.typography.titleMedium,
        color = if (ready) Color(0xFF008C3C) else MaterialTheme.colorScheme.error,
    )
    Note(
        "Touches are performed by an Android accessibility service. It only injects the touches sent from a viewer; it reads no screen content. " +
            "In Accessibility settings open Installed apps (or Downloaded apps), choose WebMirror remote control and switch it on.",
    )
    OutlinedButton(onClick = { openAccessibilitySettings(context) }) { Text("Open accessibility settings") }
}

@Composable
private fun SecuritySettings(settings: Settings) {
    val context = LocalContext.current
    var required by remember { mutableStateOf(settings.pairingRequired) }
    var length by remember { mutableStateOf(settings.pairingPinLength) }
    var pin by remember { mutableStateOf(if (settings.pairingRequired) MirrorService.pairingPin(context) else "") }
    var paired by remember { mutableStateOf(settings.pairingTokens.size) }
    // New pairings happen in the service while this screen is open.
    LaunchedEffect(required) {
        while (true) {
            paired = settings.pairingTokens.size
            if (required) pin = MirrorService.pairingPin(context)
            delay(1000)
        }
    }
    fun choose(none: Boolean, digits: Int) {
        if (none) {
            required = false
            MirrorService.setPairingRequired(context, false)
            return
        }
        if (digits != length) {
            length = digits
            pin = MirrorService.setPairingPinLength(context, digits)
        }
        if (!required) {
            required = true
            MirrorService.setPairingRequired(context, true)
            pin = MirrorService.pairingPin(context)
        }
    }
    GroupTitle("Viewer protection")
    Column(Modifier.selectableGroup()) {
        ChoiceRow("4-digit PIN", required && length == 4, supporting = "Recommended. Quick to type in the car.") { choose(false, 4) }
        ChoiceRow("6-digit PIN", required && length == 6, supporting = "Harder to guess.") { choose(false, 6) }
        ChoiceRow("No protection", !required) { choose(true, length) }
    }
    if (!required) {
        ErrorText(
            "Anyone connected to the same Wi-Fi or hotspot can watch your screen and control the phone. " +
                "Use only on your own password-protected hotspot.",
        )
    } else {
        Note("Enter the PIN once in each browser. The browser then stays paired.")
        Text(
            pin.chunked(if (pin.length == 4) 2 else 3).joinToString(" "),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pin = MirrorService.newPairingPin(context) }) { Text("New PIN") }
            OutlinedButton(enabled = paired > 0, onClick = { MirrorService.forgetPairedViewers(context); paired = 0 }) {
                Text("Forget paired viewers")
            }
        }
        Text("Paired viewers: $paired", style = MaterialTheme.typography.titleMedium)
        Note("A new PIN does not unpair browsers that are already paired. \"Forget paired viewers\" unpairs all of them at once and disconnects them.")
    }
}

@Composable
private fun NetworkSettings(settings: Settings, onVirtualIp: (Boolean, String) -> Unit) {
    val vip by VirtualIpService.state.collectAsState()
    var text by remember { mutableStateOf(settings.virtualIp) }
    var fieldError by remember { mutableStateOf<String?>(null) }

    GroupTitle("Virtual address")
    Note("A link-local address that the Tesla browser accepts. Devices on the phone's hotspot reach the server there.")
    SwitchRow(if (vip.active) "On: ${vip.address}" else "Off", vip.active) { on ->
        val ip = text.trim()
        if (on && !Ipv4.isValid(ip)) {
            fieldError = "Enter four numbers from 0 to 255 separated by dots, e.g. 169.254.77.1"
        } else {
            fieldError = null
            onVirtualIp(on, ip)
        }
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
    OutlinedButton(
        enabled = !vip.active,
        onClick = {
            text = Settings.DEFAULT_VIRTUAL_IP
            fieldError = null
            settings.virtualIp = Settings.DEFAULT_VIRTUAL_IP
        },
    ) { Text("Use ${Settings.DEFAULT_VIRTUAL_IP}") }
    vip.error?.let { ErrorText(it) }
}

@Composable
private fun HttpsSettings(actions: SettingsActions) {
    val context = LocalContext.current
    val auto by AutoCert.state.collectAsState()
    val autoRun by AutoCert.run.collectAsState()
    val tls by TlsStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    val pickedP12 = actions.pickedP12
    val onOpenWizard = actions.onOpenWizard

    Note("The car shows smooth video only on a secure (https) page, served on port 8443. Get a free certificate in two minutes, or import your own.")

    val info = tls.info
    if (info != null) {
        val expiry = dateFormat.format(Date(info.notAfterMs))
        val expired = info.notAfterMs < System.currentTimeMillis()
        Text("Subject CN: ${info.commonName ?: "(none)"}")
        Text("DNS names: ${if (info.dnsNames.isEmpty()) "(none)" else info.dnsNames.joinToString(", ")}")
        Text(if (expired) "Expired on $expiry" else "Expires on $expiry")
    } else {
        Text("No certificate installed.")
    }
    tls.error?.let { ErrorText(it) }
    actions.pickError?.let { ErrorText(it) }
    if (auto.enabled) {
        val until = if (auto.notAfterMs > 0) dateFormat.format(Date(auto.notAfterMs)) else "?"
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
    HorizontalDivider()
    GroupTitle("Advanced")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importError = null; password = ""; actions.onPickCertificate() }) {
            Text("Import certificate (.p12)")
        }
        if (!auto.enabled && (info != null || tls.error != null)) {
            OutlinedButton(onClick = { TlsStore.remove(context) }) { Text("Remove certificate") }
        }
    }
    Note("Changes apply to a running server immediately.")

    if (pickedP12 != null) {
        AlertDialog(
            onDismissRequest = { password = ""; importError = null; actions.onCertificateDone() },
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
                            actions.onCertificateDone()
                        } else {
                            importError = error
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { password = ""; importError = null; actions.onCertificateDone() }) { Text("Cancel") }
            },
        )
    }
}

/** Every latency/smoothness option of [StreamConfig]; changes apply live. */
@Composable
private fun AdvancedSettings(settings: Settings) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(settings.streamConfig) }
    var resetToken by remember { mutableIntStateOf(0) }
    fun change(next: StreamConfig) {
        cfg = next
        MirrorService.applyConfig(context, next)
    }
    val labels = StreamConfig.LABELS

    Note("Configuration ${cfg.id}. Changes apply live. Socket options affect new connections only: reload the page to use them.")
    SwitchRow(labels.getValue("tcpNoDelay"), cfg.tcpNoDelay) { change(cfg.copy(tcpNoDelay = it)) }
    NumberRow("sendBufferKb", cfg.sendBufferKb, resetToken) { change(cfg.copy(sendBufferKb = it)) }
    SwitchRow(labels.getValue("lowLatencyEncoder"), cfg.lowLatencyEncoder) { change(cfg.copy(lowLatencyEncoder = it)) }
    SwitchRow(labels.getValue("allowSoftwareEncoder"), cfg.allowSoftwareEncoder) { change(cfg.copy(allowSoftwareEncoder = it)) }
    Text(labels.getValue("codec"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (c in StreamConfig.CODECS) ChoiceRow(c, cfg.codec == c) { change(cfg.copy(codec = c)) }
    }
    Text(labels.getValue("profile"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (p in StreamConfig.PROFILES) ChoiceRow(p, cfg.profile == p) { change(cfg.copy(profile = p)) }
    }
    Text(labels.getValue("bitrateMode"), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (m in StreamConfig.BITRATE_MODES) ChoiceRow(m, cfg.bitrateMode == m) { change(cfg.copy(bitrateMode = m)) }
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
        for (p in StreamConfig.PACINGS) ChoiceRow(p, cfg.pacing == p) { change(cfg.copy(pacing = p)) }
    }
    OutlinedButton(onClick = { resetToken++; change(StreamConfig()) }, modifier = Modifier.fillMaxWidth()) {
        Text("Reset to defaults")
    }
}

@Composable
private fun DebuggingSettings(settings: Settings) {
    val context = LocalContext.current
    val state by MirrorService.state.collectAsState()

    GroupTitle("Probe result")
    Text(
        text = state.lastProbe ?: "(none yet; open /probe.html in a browser)",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(enabled = state.lastProbe != null, onClick = { state.lastProbe?.let { shareText(context, "WebMirror probe result", it) } }) {
        Text("Share")
    }
    HorizontalDivider()

    GroupTitle("Current stream")
    val v = state.video
    val streamText = if (state.running && v != null) {
        buildString {
            append("mode ${v.mode}, ${v.codec}\n")
            if (v.encoder.isNotEmpty()) append("${v.encoder} (${if (v.hardware == true) "hardware" else "software"})\n")
            if (v.why.isNotEmpty()) append(v.why)
        }.trim()
    } else "(not mirroring)"
    Text(streamText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    HorizontalDivider()

    GroupTitle("Stream statistics")
    Text(state.lastStats ?: "(none yet)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    HorizontalDivider()

    GroupTitle("Effective encoder state")
    Text(
        text = if (state.running) state.encoder ?: "(no encoder yet)" else "(not mirroring)",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
    HorizontalDivider()

    GroupTitle("Codecs on this phone")
    val preset = settings.preset
    val codecTable = remember(preset) {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
        val size = CaptureSize.compute(p.x, p.y, preset.maxLongSide)
        CodecSupport.describe(CodecSupport.rows(size.width, size.height, preset.fps), size.width, size.height, preset.fps)
    }
    Text(text = codecTable, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

@Composable
private fun AboutSettings(onOpenHelp: () -> Unit) {
    Text("WebMirror", style = MaterialTheme.typography.titleLarge)
    Note("Version ${BuildConfig.VERSION_NAME}")
    Note("License: MIT")
    TextButton(onClick = onOpenHelp) { Text("Help") }
    HorizontalDivider()
    GroupTitle("Safety notice")
    for (p in SafetyNotice.paragraphs) Text(p, style = MaterialTheme.typography.bodyLarge)
}
