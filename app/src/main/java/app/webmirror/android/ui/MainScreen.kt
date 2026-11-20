package app.webmirror.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.webmirror.android.CertWizardScreen
import app.webmirror.android.SafetyNoticeScreen
import app.webmirror.android.Settings
import app.webmirror.android.net.AutoCert
import app.webmirror.android.test.TestReportScreen
import app.webmirror.android.update.UpdateState
import app.webmirror.android.update.Updates

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", AppIcons.Home),
    SETTINGS("Settings", AppIcons.Settings),
    HELP("Help", AppIcons.Info),
}

/** What [MainScreen] needs from the activity. */
internal class MainActions(
    val onStart: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onVirtualIp: (Boolean, String) -> Unit,
    val pickedP12: ByteArray?,
    val pickError: String?,
    val onPickCertificate: () -> Unit,
    val onCertificateDone: () -> Unit,
    val openUpdates: Boolean,
    val onUpdatesOpened: () -> Unit,
)

/**
 * The whole app UI. Navigation is plain state: the selected [Tab] and, inside Settings, the open [SettingsPage]. The
 * safety notice (first run) and the certificate wizard / a test report cover everything else.
 */
@Composable
internal fun MainScreen(settings: Settings, actions: MainActions) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var openReport by rememberSaveable { mutableStateOf<String?>(null) }
    var safetyAccepted by remember { mutableStateOf(settings.safetyAcceptedVersion >= Settings.SAFETY_NOTICE_VERSION) }
    var showWizard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(actions.openUpdates) {
        if (actions.openUpdates) {
            tab = Tab.SETTINGS
            page = SettingsPage.UPDATES
            actions.onUpdatesOpened()
        }
    }
    val updateAvailable by Updates.state.collectAsState()

    // Back: sub-page -> Settings list -> Home -> leaves the app (handler disabled on Home).
    BackHandler(enabled = safetyAccepted && !showWizard && openReport == null && page != null) { page = null }
    BackHandler(enabled = safetyAccepted && !showWizard && openReport == null && page == null && tab != Tab.HOME) { tab = Tab.HOME }
    BackHandler(enabled = showWizard) { showWizard = false }

    if (!safetyAccepted) {
        // Must be accepted before the app can be used; Exit closes the app.
        SafetyNoticeScreen(
            onAccept = { settings.safetyAcceptedVersion = Settings.SAFETY_NOTICE_VERSION; safetyAccepted = true },
            onExit = { (context as? android.app.Activity)?.finish() },
        )
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

    val openWizard = { AutoCert.clearRun(); showWizard = true }
    val settingsActions = SettingsActions(
        onVirtualIp = actions.onVirtualIp,
        pickedP12 = actions.pickedP12,
        pickError = actions.pickError,
        onPickCertificate = actions.onPickCertificate,
        onCertificateDone = actions.onCertificateDone,
        onOpenWizard = openWizard,
        onOpenReport = { openReport = it },
        onOpenHelp = { page = null; tab = Tab.HELP },
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { page = null; tab = t },
                        icon = {
                            if (t == Tab.SETTINGS && updateAvailable is UpdateState.Available) {
                                BadgedBox(badge = { Badge() }) { Icon(t.icon, contentDescription = null) }
                            } else {
                                Icon(t.icon, contentDescription = null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.HOME -> HomeScreen(settings, HomeActions(actions.onStart, actions.onRequestNotifications, openWizard) {
                        actions.onVirtualIp(true, settings.virtualIp)
                    })
                    Tab.SETTINGS -> {
                        val current = page
                        if (current == null) SettingsHome(settings) { page = it }
                        else SettingsSubPage(current, settings, settingsActions) { page = null }
                    }
                    Tab.HELP -> HelpScreen()
                }
            }
        }
    }
}
