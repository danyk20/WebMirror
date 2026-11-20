package app.webmirror.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.webmirror.android.MirrorService
import app.webmirror.android.Settings
import app.webmirror.android.input.RemoteControlState
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.VirtualIpService
import app.webmirror.android.net.carCannotReach
import app.webmirror.android.net.AutoCert
import kotlinx.coroutines.delay

/** What the Home screen needs from the activity (permission dialogs, the capture consent, the certificate wizard). */
internal class HomeActions(
    val onStart: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onOpenWizard: () -> Unit,
    val onFixVirtualIp: () -> Unit,
)

/** The Home screen: only what is used every time. */
@Composable
internal fun HomeScreen(settings: Settings, actions: HomeActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WebMirror", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
        StatusCard()
        StartStopButton(actions.onStart)
        VirtualIpWarning(settings, actions.onFixVirtualIp)
        CarCard(settings)
        PinCard(settings)
        ControlCard(settings)
        SetupChecklist(actions)
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "h264" -> "H.264"
    "hevc" -> "HEVC"
    "vp9" -> "VP9"
    "av1" -> "AV1"
    "jpeg" -> "JPEG"
    else -> mode.uppercase()
}

@Composable
private fun StatusCard() {
    val state by MirrorService.state.collectAsState()
    val (container, content) = when {
        state.paused -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        state.running -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(content))
                Text(
                    when {
                        state.paused -> "Paused"
                        state.running -> "Mirroring"
                        else -> "Not mirroring"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val detail = when {
                state.paused -> "Screen sharing ended. Tap Resume."
                state.running -> listOfNotNull(
                    state.video?.takeIf { it.codec.isNotEmpty() || it.mode.isNotEmpty() }?.let { modeLabel(it.mode) },
                    // The service's stream text ends with the mode in brackets, e.g. "(hevc)"; the label above already says it.
                    state.stream?.replace(Regex("""\s*\((h264|hevc|vp9|av1|jpeg|paused)\)$"""), ""),
                ).joinToString(" · ").ifEmpty { "Starting…" }
                else -> "Tap Start to share this screen."
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (state.running || state.paused) {
                Text(
                    if (state.viewerCount == 1) "1 viewer" else "${state.viewerCount} viewers",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.tlsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun StartStopButton(onStart: () -> Unit) {
    val context = LocalContext.current
    val state by MirrorService.state.collectAsState()
    if (state.paused) {
        // Server and viewers are still up; a new consent resumes the capture.
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Resume", style = MaterialTheme.typography.titleLarge)
        }
        TextButton(onClick = { MirrorService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text("Stop completely") }
    } else {
        Button(
            onClick = { if (state.running) MirrorService.stop(context) else onStart() },
            colors = if (state.running) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(if (state.running) "Stop" else "Start", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Warns when the car cannot reach the phone because the virtual address is off; Fix switches it on again. */
@Composable
private fun VirtualIpWarning(settings: Settings, onFix: () -> Unit) {
    val vip by VirtualIpService.state.collectAsState()
    val tls by TlsStore.state.collectAsState()
    // The flag is written before the state changes, so reading it on each recomposition is current.
    val lost = settings.virtualIpLost
    if (!carCannotReach(vip.active, lost, tls.info?.hostNames.orEmpty().isNotEmpty())) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(AppIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "The car can't reach the phone",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "The virtual address ${settings.virtualIp} is off" +
                            if (lost) " \u2014 another VPN app or Android switched it off." else ".",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onFix,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Fix") }
        }
    }
}

/** The address to type in the car: https if a certificate is installed, else the virtual (link-local) http address. */
internal fun bestUrl(urls: app.webmirror.android.net.Addresses.UrlList, virtualIp: String?): String? {
    urls.https.firstOrNull()?.let { return it }
    if (virtualIp != null) urls.addresses.firstOrNull { it.contains("//$virtualIp:") }?.let { return it }
    return urls.addresses.firstOrNull { !it.contains('[') } ?: urls.addresses.firstOrNull()
}

@Composable
private fun CarCard(settings: Settings) {
    val state by MirrorService.state.collectAsState()
    val vip by VirtualIpService.state.collectAsState()
    if (!state.running && !state.paused) return
    val urls = state.urls
    val best = bestUrl(urls, if (vip.active) vip.address else null)
    var showAll by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Open in the car", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            if (best == null) {
                Text(
                    "No network address yet. Turn on the hotspot or connect to Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }
            SelectionContainer {
                Text(
                    best,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val qr = remember(best) { qrBitmap(best, 512) }
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "QR code for $best",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(200.dp),
            )
            val others = (urls.https + urls.addresses + urls.names).distinct().filter { it != best }
            if (others.isNotEmpty()) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide other addresses" else "Show all addresses") }
                if (showAll) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (u in others) Text(u, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                            if (urls.names.isNotEmpty()) {
                                Text(
                                    "Addresses with sslip.io are looked up on the internet by the device you type them into.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinCard(settings: Settings) {
    val context = LocalContext.current
    var required by remember { mutableStateOf(settings.pairingRequired) }
    var pin by remember { mutableStateOf(if (settings.pairingRequired) MirrorService.pairingPin(context) else "") }
    // The PIN and the protection can change in Settings or by a new pairing while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            required = settings.pairingRequired
            pin = if (required) MirrorService.pairingPin(context) else ""
            delay(1000)
        }
    }
    if (!required) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(AppIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Viewer protection is off. Anyone on your Wi-Fi can watch.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PIN for the car", style = MaterialTheme.typography.titleMedium)
            Text(
                pin.chunked(if (pin.length == 4) 2 else 3).joinToString(" "),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Enter it once in the car browser.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pin = MirrorService.newPairingPin(context) }) { Text("New PIN") }
            }
        }
    }
}

@Composable
private fun ControlCard(settings: Settings) {
    val context = LocalContext.current
    val allowed by RemoteControlState.allowed.collectAsState()
    val ready = rememberAccessibilityReady()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow control from the car", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !allowed -> "Off. The car can only watch."
                            ready -> "Ready"
                            else -> "Needs permission"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (allowed && !ready) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowed, onCheckedChange = { settings.remoteControlAllowed = it; RemoteControlState.allowed.value = it })
            }
            if (allowed && !ready) {
                TextButton(onClick = { openAccessibilitySettings(context) }) { Text("Open Accessibility settings") }
            }
        }
    }
}

/** Shown only while something is missing; every item is tappable. */
@Composable
private fun SetupChecklist(actions: HomeActions) {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    val allowed by RemoteControlState.allowed.collectAsState()
    val ready = rememberAccessibilityReady()
    val tls by TlsStore.state.collectAsState()
    val auto by AutoCert.state.collectAsState()
    val notificationsMissing = remember(resumes) {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
    val info = tls.info
    val certMissing = info == null || info.notAfterMs < System.currentTimeMillis() || auto.staging
    val controlMissing = allowed && !ready
    if (!notificationsMissing && !controlMissing && !certMissing) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Setup checklist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (notificationsMissing) {
                ChecklistItem("Allow notifications", "Android needs it to keep sharing running.", actions.onRequestNotifications)
            }
            if (controlMissing) {
                ChecklistItem("Allow remote control", "Turn on WebMirror in Accessibility settings.") { openAccessibilitySettings(context) }
            }
            if (certMissing) {
                ChecklistItem(
                    if (auto.staging) "Replace the test certificate" else "Get a secure address",
                    "A free certificate makes the video smooth in the car.",
                    actions.onOpenWizard,
                )
            }
        }
    }
}

@Composable
private fun ChecklistItem(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
