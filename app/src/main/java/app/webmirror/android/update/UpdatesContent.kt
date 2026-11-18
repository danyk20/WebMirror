package app.webmirror.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.webmirror.android.BuildConfig
import java.text.DateFormat
import java.util.Date

/** The Updates page body (no navigation chrome). */
@Composable
fun UpdatesContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        if (BuildConfig.SELF_UPDATE) GithubUpdates(context) else PlayUpdates(context)
    }
}

@Composable
private fun PlayUpdates(context: Context) {
    Text("Updates come from Google Play.")
    Button(onClick = {
        try { context.startActivity(Updates.playIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) {
            try { context.startActivity(Updates.playWebIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
        }
    }) { Text("Open in Google Play") }
}

@Composable
private fun GithubUpdates(context: Context) {
    val prefs = remember { UpdatePrefs(context) }
    val state by Updates.state.collectAsState()
    var autoCheck by remember { mutableStateOf(prefs.autoCheck) }
    var autoInstall by remember { mutableStateOf(prefs.autoInstall) }
    // Re-evaluated on every state change; the permission is granted in system settings, then the user comes back.
    val canInstall = Updates.canInstallPackages(context)

    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Updates.checkNow(context) }, enabled = !busy) { Text("Check now") }
    }
    Text(statusText(state, prefs))
    (state as? UpdateState.Downloading)?.let { d ->
        if (d.progress >= 0f) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Postponed -> s.release
        is UpdateState.NeedsInstallPermission -> s.release
        else -> null
    }
    if (release != null) {
        Text(release.name, style = MaterialTheme.typography.titleSmall)
        if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { Updates.downloadAndInstall(context) }, enabled = !busy) { Text("Download and install") }
    }
    if (!canInstall) {
        Text("To install updates, allow WebMirror to install unknown apps.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            context.startActivity(Updates.unknownSourcesIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open settings") }
    }

    SwitchRow(
        "Check for updates automatically", "Looks for a new version once a day.",
        autoCheck,
    ) { autoCheck = it; prefs.autoCheck = it; Updates.onAutoCheckChanged(context) }
    SwitchRow(
        "Install updates automatically", "Android may ask you to confirm the first update. Waits while mirroring is running.",
        autoInstall,
    ) { autoInstall = it; prefs.autoInstall = it }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun statusText(s: UpdateState, prefs: UpdatePrefs): String = when (s) {
    UpdateState.Idle -> prefs.lastCheckMs.takeIf { it > 0 }?.let { "Last checked ${DateFormat.getDateTimeInstance().format(Date(it))}." } ?: "Not checked yet."
    UpdateState.Checking -> "Checking for updates..."
    UpdateState.UpToDate -> "WebMirror is up to date."
    UpdateState.NoInfo -> "No update information available."
    is UpdateState.Available -> "Version ${s.release.version} is available."
    is UpdateState.Downloading -> "Downloading version ${s.release.version}..."
    is UpdateState.ReadyToInstall -> "Ready to install."
    is UpdateState.NeedsInstallPermission -> "Allow installing unknown apps, then try again."
    is UpdateState.Installing -> "Installing... confirm if Android asks."
    is UpdateState.Postponed -> "Version ${s.release.version} will install when mirroring stops."
    is UpdateState.Failed -> "Update failed: ${s.message}"
}
