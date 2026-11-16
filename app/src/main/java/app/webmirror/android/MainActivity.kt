package app.webmirror.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result not needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(onStart = ::startMirror)
                }
            }
        }
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
}

@Composable
private fun MainScreen(onStart: () -> Unit) {
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

        Button(
            onClick = { if (state.running) MirrorService.stop(context) else onStart() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.running) "Stop" else "Start")
        }

        if (state.running) {
            if (state.urls.isEmpty()) {
                Text("Server running, but no network address found. Connect to Wi-Fi or enable the hotspot.")
            } else {
                Text("Open in a browser:", style = MaterialTheme.typography.titleMedium)
                for (url in state.urls) {
                    Text(url, fontFamily = FontFamily.Monospace)
                }
                val qr = remember(state.urls.first()) { qrBitmap(state.urls.first(), 512) }
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code for ${state.urls.first()}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(220.dp),
                )
            }
        } else {
            Text("Server stopped. Keep this app open on screen while testing.")
        }

        Text("Viewers: ${state.viewerCount}", style = MaterialTheme.typography.titleMedium)

        Text("Last probe result", style = MaterialTheme.typography.titleMedium)
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
