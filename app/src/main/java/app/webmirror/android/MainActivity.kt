package app.webmirror.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
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
import app.webmirror.android.net.Ipv4
import app.webmirror.android.net.TlsStore
import app.webmirror.android.net.VirtualIpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result not needed */ }

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
        TlsStore.refresh(this)
        // Restore the virtual address if it was on and the system consent is still valid.
        if (settings.virtualIpEnabled && !VirtualIpService.state.value.active &&
            Ipv4.isValid(settings.virtualIp) && VpnService.prepare(this) == null
        ) {
            VirtualIpService.start(this, settings.virtualIp)
        }
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
        MirrorService.refresh(this)
    }

    private fun startMirror() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        MirrorService.start(this)
    }

    /** Switch handler. The address was validated by the UI before calling with `on = true`. */
    private fun setVirtualIp(on: Boolean, ip: String) {
        settings.virtualIp = ip
        settings.virtualIpEnabled = on
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("WebMirror", style = MaterialTheme.typography.headlineMedium)

        // ---- Server ----
        SectionTitle("Server")
        Button(
            onClick = { if (state.running) MirrorService.stop(context) else onStart() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.running) "Stop" else "Start")
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
            Text("Server stopped. Keep this app open on screen while testing.")
        }
        Text("Viewers: ${state.viewerCount}", style = MaterialTheme.typography.titleMedium)

        HorizontalDivider()
        VirtualAddressSection(settings, onVirtualIp)

        HorizontalDivider()
        CertificateSection(pickedP12, pickError, onPickCertificate, onCertificateDone)

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
    Text("Gives the phone an extra IPv4 address (a local VPN interface; no traffic is tunnelled). Devices on the phone's hotspot may reach the server there.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = vip.active,
            onCheckedChange = { on ->
                val ip = text.trim()
                if (on && !Ipv4.isValid(ip)) {
                    fieldError = "Enter four numbers from 0 to 255 separated by dots, e.g. 198.51.100.1"
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
        for (preset in listOf("198.51.100.1", "3.3.3.3")) {
            OutlinedButton(
                enabled = !vip.active,
                onClick = {
                    text = preset
                    fieldError = null
                    settings.virtualIp = preset
                },
            ) {
                Text(preset)
            }
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
) {
    val context = LocalContext.current
    val tls by TlsStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    SectionTitle("HTTPS certificate")
    Text("Optional. Import your own .p12 to also serve https on port 8443. The file stays on this phone only.")

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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importError = null; password = ""; onPick() }) {
            Text("Import certificate (.p12)")
        }
        if (info != null || tls.error != null) {
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

private fun qrBitmap(text: String, size: Int): Bitmap {
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
