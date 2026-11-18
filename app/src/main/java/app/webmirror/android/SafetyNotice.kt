package app.webmirror.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The paragraphs of the safety notice. Change them only together with [Settings.SAFETY_NOTICE_VERSION]. */
object SafetyNotice {
    val paragraphs = listOf(
        "WebMirror is for passengers. The driver must never watch or operate the mirrored screen while driving; keep your eyes on the road.",
        "Follow the traffic laws of your country. Showing video to the driver may be illegal.",
        "Remote control lets anyone with access to the page tap on your phone. Keep the pairing PIN on.",
        "The app is provided \"as is\", without warranty of any kind. The author accepts no responsibility or liability for any damage, accident, fine or loss resulting from its use. You use it at your own risk.",
    )
}

/**
 * Full-screen notice. First run (and after a version change): [onAccept] is set and the screen offers "I understand
 * and accept" and "Exit". Read again later ([onAccept] null): a single "Close" button ([onExit] then just closes it).
 */
@Composable
fun SafetyNoticeScreen(onAccept: (() -> Unit)?, onExit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Safety notice", style = MaterialTheme.typography.headlineMedium)
        for (p in SafetyNotice.paragraphs) Text(p, style = MaterialTheme.typography.bodyLarge)
        if (onAccept != null) {
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("I understand and accept") }
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Exit") }
        } else {
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
