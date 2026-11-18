package app.webmirror.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------------------------
// The text of the Help page. Edit it here: one HelpSection per heading, blocks in the order they are shown.
// ---------------------------------------------------------------------------------------------------------------

internal sealed interface HelpBlock {
    /** A short paragraph. */
    data class Paragraph(val text: String) : HelpBlock

    /** A small heading inside a section. */
    data class Heading(val text: String) : HelpBlock

    /** Numbered steps. */
    data class Steps(val items: List<String>) : HelpBlock

    /** A bullet list. */
    data class Bullets(val items: List<String>) : HelpBlock
}

internal data class HelpSection(val title: String, val blocks: List<HelpBlock>)

private fun p(text: String) = HelpBlock.Paragraph(text)
private fun h(text: String) = HelpBlock.Heading(text)
private fun steps(vararg items: String) = HelpBlock.Steps(items.toList())
private fun bullets(vararg items: String) = HelpBlock.Bullets(items.toList())

/** Index of the section that is open when the page is shown. */
private const val FIRST_OPEN = 1

internal val HELP_SECTIONS: List<HelpSection> = listOf(
    HelpSection(
        "What WebMirror does",
        listOf(
            p("WebMirror shows your phone screen in a web browser, for example the browser of a Tesla."),
            p("The phone shares its screen over its own hotspot. Nothing needs to be installed in the car."),
        ),
    ),
    HelpSection(
        "Getting started in the car",
        listOf(
            steps(
                "Turn on the hotspot on your phone.",
                "In the Tesla, join the phone's hotspot and turn on \"Remain connected in Drive\".",
                "In WebMirror, tap Start and allow screen sharing.",
                "Open the address shown on Home in the car browser. You can also scan the QR code.",
                "Enter the PIN shown on Home. You only need to do this once per browser.",
            ),
            p("For passengers only. The driver must never watch the screen while driving."),
            p("Tip: add the WebMirror tile to Quick Settings (swipe down twice, tap the pencil/edit button) to start and stop mirroring without opening the app."),
        ),
    ),
    HelpSection(
        "Secure address (https)",
        listOf(
            p("The car browser plays smooth video only on a secure address that starts with https://."),
            p("A plain http address still works, but the picture is less smooth."),
            h("How to get a free one"),
            p("Tap \"Get a free certificate\" on Home (in the Setup checklist) or in Settings > Secure connection (HTTPS). A short guide takes you through it. It needs a free DuckDNS account and takes about two minutes."),
            p("The certificate renews by itself."),
        ),
    ),
    HelpSection(
        "Remote control",
        listOf(
            p("Remote control lets the person in the car tap on the mirrored picture to operate your phone."),
            p("It needs one permission: the WebMirror remote control accessibility service. It only performs the touches sent from the car. It does not read your screen."),
            h("Turn it on"),
            p("Switch on \"Allow control from the car\" on Home. If it says \"Needs permission\", open Accessibility settings, choose WebMirror remote control and switch it on."),
            h("Turn it off"),
            p("Switch off \"Allow control from the car\". To remove the permission completely, switch the service off in Android's Accessibility settings."),
        ),
    ),
    HelpSection(
        "Security and the PIN",
        listOf(
            p("The PIN stops other people on your hotspot from watching your screen or controlling your phone. Settings > Security > Viewer protection has three choices:"),
            bullets(
                "4-digit PIN (default): quick to type in the car.",
                "6-digit PIN: harder to guess.",
                "No protection: no PIN. Anyone connected to the same Wi-Fi or hotspot can watch your screen and control the phone. Use only on your own password-protected hotspot.",
            ),
            p("Enter the PIN once in each browser. The browser then stays paired."),
            p("\"New PIN\" makes a new PIN and keeps paired browsers. \"Forget paired viewers\" unpairs all browsers; they must enter the PIN again."),
        ),
    ),
    HelpSection(
        "Settings explained",
        listOf(
            h("Video & quality"),
            p("Sharper gives a clearer picture, Smoother gives more motion. \"Keep screen on\" stops the phone from locking while you share (sharing stops when the screen locks). \"Force JPEG mode\" always sends pictures instead of video; use it only if video does not work."),
            h("Screen capture"),
            p("Choose what Android shares when you tap Start: the entire screen, or let Android ask you each time (one app or the entire screen). Android 13 and older always share the entire screen."),
            h("Audio"),
            p("See \"Sound\" below."),
            h("Remote control"),
            p("The same switch as on Home, plus the state of the accessibility permission and how to turn it on."),
            h("Security"),
            p("Viewer protection, the PIN, \"New PIN\" and \"Forget paired viewers\"."),
            h("Secure connection (HTTPS)"),
            p("Get, renew or remove the free certificate, or import your own .p12 file."),
            h("Network"),
            p("The virtual address is a special address (169.254.77.1 by default) that the Tesla browser accepts. Leave it on unless you know you need another one."),
            h("Updates"),
            p("Information about new versions of the app."),
            h("Advanced streaming"),
            p("Fine settings for delay and smoothness: network options, codec, bitrate, and how the page plays frames. The defaults are good for most people. \"Reset to defaults\" undoes your changes."),
            h("Testing"),
            p("The automatic test tries several settings in the car and shows which works best. Sit in the car, open the page, tap Start test. Run it once parked and once driving."),
            h("Debugging"),
            p("Technical information for finding problems: probe result, current codec and encoder, statistics. You can share it with the developer."),
            h("About & safety"),
            p("Version, license (MIT) and the safety notice."),
        ),
    ),
    HelpSection(
        "Sound",
        listOf(
            p("WebMirror does not send sound to the browser yet."),
            p("With the default \"Bluetooth\" option the phone plays the sound to the car over Bluetooth. Bluetooth sound arrives a little later than the picture, so the page has a delay setting to match them. Set it in the page until lips and sound fit."),
            p("\"Off\" turns that hint off. \"Browser\" sound is planned for later and cannot be chosen yet."),
        ),
    ),
    HelpSection(
        "Troubleshooting",
        listOf(
            h("The page does not load"),
            bullets(
                "Check that the hotspot is on and the car is joined to it.",
                "In the Tesla, turn on \"Remain connected in Drive\".",
                "Type the address exactly as shown on Home, including https:// and :8443 (or http:// and :8080). Better: scan the QR code.",
                "Make sure WebMirror shows Mirroring on Home.",
            ),
            h("\"Your connection is not private\""),
            p("The certificate is missing, expired or only a test certificate. Open Settings > Secure connection (HTTPS) and get a real certificate."),
            h("The PIN is asked again"),
            p("Either \"Forget paired viewers\" was used, the browser's data was cleared, or more than 20 browsers paired since. Enter the PIN again."),
            h("The picture freezes while driving"),
            p("The car's Wi-Fi connection probably dropped. Check that \"Remain connected in Drive\" is on. The page reconnects by itself."),
            h("Black screen for some apps"),
            p("Some apps, for example banking, video and streaming apps, block screen capture on purpose. This cannot be changed."),
            h("Sharing stopped"),
            p("The phone screen locked or you stopped sharing in Android's status bar. Tap Resume on Home."),
        ),
    ),
    HelpSection(
        "Safety",
        listOf(
            p("WebMirror is for passengers only. The driver must never watch or operate the mirrored screen while driving."),
            p("Follow the traffic laws of your country."),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun HelpScreen() {
    var open by rememberSaveable { mutableIntStateOf(FIRST_OPEN) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Help", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        HELP_SECTIONS.forEachIndexed { index, section ->
            HelpCard(section, expanded = open == index) { open = if (open == index) -1 else index }
        }
    }
}

@Composable
private fun HelpCard(section: HelpSection, expanded: Boolean, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (b in section.blocks) HelpBlockView(b)
                }
            }
        }
    }
}

@Composable
private fun HelpBlockView(block: HelpBlock) {
    when (block) {
        is HelpBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
        is HelpBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        is HelpBlock.Steps -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEachIndexed { i, t ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(t, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        is HelpBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in block.items) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge)
                    Text(t, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
