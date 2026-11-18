package app.webmirror.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.webmirror.android.net.AutoCert
import app.webmirror.android.net.AutoCertLogic
import app.webmirror.android.net.acme.CertInput
import app.webmirror.android.net.acme.IssueResult

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

private fun clipboardText(context: Context): String? =
    try {
        context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (_: Exception) {
        null
    }

/** Full-screen wizard "Free HTTPS certificate". The issuance itself runs in [AutoCert], not in this composable. */
@Composable
internal fun CertWizardScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val run by AutoCert.run.collectAsState()
    val saved = remember { AutoCert.state.value }
    val hasSavedToken = saved.enabled && saved.token.isNotEmpty()
    var name by rememberSaveable { mutableStateOf(if (saved.enabled) saved.subdomain else "") }
    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var staging by rememberSaveable { mutableStateOf(false) }
    var showDev by rememberSaveable { mutableStateOf(false) }

    val sub = CertInput.normalizeSubdomain(name)
    val tokenOk = CertInput.isValidToken(token) || (token.isEmpty() && hasSavedToken)
    val success = run.result as? IssueResult.Success

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Free HTTPS certificate", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { AutoCert.clearRun(); onClose() }) { Text("Close") }
        }
        Text(
            "The car shows smooth video only on a secure (https) page. This sets up a free secure address for your " +
                "phone in about two minutes. You need a free DuckDNS account.",
        )

        if (success != null) {
            val url = AutoCert.carUrl(success.fqdn)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Done. In the car open:", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, fontSize = 18.sp) }
                    val qr = remember(url) { qrBitmap(url, 512) }
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QR code for $url",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(220.dp),
                    )
                    Text("In the Tesla: Wi-Fi → your phone's hotspot → turn on 'Remain connected in Drive'.")
                    Text("The certificate is renewed automatically while you use the app.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { AutoCert.clearRun(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
            return@Column
        }

        HorizontalDivider()
        Text("1. Create your free address", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sign in at duckdns.org (Google, GitHub, …). In \"sub domain\" type a name, for example mycar-mirror, " +
                "and tap \"add domain\". You don't need to change the \"current ip\": the app sets it.",
        )
        Text("Your address will be <name>.duckdns.org.")
        OutlinedButton(onClick = { openUrl(context, "https://www.duckdns.org") }) { Text("Open duckdns.org") }

        HorizontalDivider()
        Text("2. Copy your token", style = MaterialTheme.typography.titleMedium)
        Text("The token is shown at the top of the DuckDNS page after you sign in. Tap it to copy it, then come back here.")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your DuckDNS name") },
            singleLine = true,
            isError = name.isNotBlank() && sub == null,
            supportingText = {
                when {
                    name.isBlank() -> Text("For example mycar-mirror")
                    sub == null -> Text("Use letters, digits and dashes only, like mycar-mirror.")
                    else -> Text("$sub.duckdns.org")
                }
            },
            enabled = !run.running,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = token.isNotBlank() && !tokenOk,
            supportingText = {
                if (token.isNotBlank() && !tokenOk) Text("The token has 36 characters, like 123e4567-e89b-12d3-a456-426614174000.")
                else if (token.isEmpty() && hasSavedToken) Text("Leave empty to keep the saved token")
            },
            enabled = !run.running,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { clipboardText(context)?.let { token = it.trim() } }, enabled = !run.running) { Text("Paste") }
            OutlinedButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
        }

        HorizontalDivider()
        Text("3. Agree to Let's Encrypt terms", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it }, enabled = !run.running)
            Text("I agree to the Let's Encrypt Subscriber Agreement")
        }
        TextButton(onClick = { openUrl(context, "https://letsencrypt.org/repository/") }) { Text("Read the agreement") }
        Text(
            "Let's Encrypt is a free, non-profit certificate authority. The certificate is renewed automatically while you use the app.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("4. Get the certificate", style = MaterialTheme.typography.titleMedium)
        Text("This takes about one to two minutes. Keep the app open.")
        if (run.running) {
            OutlinedButton(onClick = { AutoCert.cancelRun() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Button(
                onClick = { AutoCert.startSetup(context, sub!!, token, staging) },
                enabled = sub != null && tokenOk && agreed,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (BuildConfig.DEBUG && staging) "Get TEST certificate (not trusted)" else "Get certificate") }
        }
        if (run.lines.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (l in run.lines) Text(l, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        (run.result as? IssueResult.Failure)?.let { f ->
            if (f !is IssueResult.Cancelled) {
                Text(f.message, color = MaterialTheme.colorScheme.error)
                val hint = AutoCertLogic.hint(f)
                if (hint.isNotEmpty() && hint != f.message) Text(hint)
            } else {
                Text("Cancelled.")
            }
        }

        HorizontalDivider()
        Text(
            "Your name and token are stored only on this phone. The app talks to duckdns.org, Let's Encrypt and " +
                "Cloudflare DNS (to check the record) only while getting or renewing the certificate.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (BuildConfig.DEBUG) {
            HorizontalDivider()
            Text("For app developers only", style = MaterialTheme.typography.titleMedium)
            if (!showDev) {
                TextButton(onClick = { showDev = true }) { Text("Show developer options") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = staging, onCheckedChange = { staging = it }, enabled = !run.running)
                    Text("Use Let's Encrypt test server (staging)", modifier = Modifier.padding(start = 12.dp))
                }
                Text(
                    "Test certificates are NOT trusted by browsers or the car. Leave this off.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
