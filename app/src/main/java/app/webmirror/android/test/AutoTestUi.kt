package app.webmirror.android.test

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.webmirror.android.MirrorService
import app.webmirror.android.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The "Automatic test" section of the main screen: options, Start / Abort, progress, and the list of saved reports. */
@Composable
fun AutoTestSection(settings: Settings, onOpenReport: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val run by AutoTestRunner.state.collectAsState()
    val service by MirrorService.state.collectAsState()
    var name by remember { mutableStateOf(settings.testRunName) }
    var secondsText by remember { mutableStateOf(settings.testPhaseSeconds.toString()) }
    var quick by remember { mutableStateOf(settings.testQuick) }
    var usePattern by remember { mutableStateOf(settings.testUsePattern) }
    var reports by remember { mutableStateOf<List<TestReports.Info>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(run.status, run.lastReport, refresh) {
        reports = withContext(Dispatchers.IO) { TestReports.list(context) }
    }
    // Reload when returning from the detail screen (it may have deleted a report).
    androidx.compose.runtime.DisposableEffect(Unit) {
        refresh++
        onDispose { }
    }
    val active = run.active
    val plan = remember { TestPlan.phases() }

    Text("Automatic test", style = MaterialTheme.typography.titleLarge)
    Text(
        "Sit in the car, open the player page, tap Start. The phone cycles through ${plan.size} streaming configurations, " +
            "measures each one on the car and ranks them. Run it once parked and once driving.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40); settings.testRunName = name },
        label = { Text("Run name (e.g. parked, driving)") },
        singleLine = true,
        enabled = !active,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = secondsText,
        onValueChange = { secondsText = it.filter { c -> c.isDigit() }.take(3); secondsText.toIntOrNull()?.let { v -> settings.testPhaseSeconds = v } },
        label = { Text("Phase length, seconds (plus ${TestOptions.SETTLE_SECONDS} s settling that is not measured)") },
        singleLine = true,
        enabled = !active && !quick,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    CheckRow("Quick (${TestOptions.QUICK_PHASE_SECONDS} s phases)", quick, !active) { quick = it; settings.testQuick = it }
    CheckRow("Use test pattern (off: use whatever is on screen, e.g. play a video yourself)", usePattern, !active) {
        usePattern = it
        settings.testUsePattern = it
    }
    val options = TestOptions(
        runName = name, phaseSeconds = secondsText.toIntOrNull() ?: TestOptions.DEFAULT_PHASE_SECONDS, quick = quick, useTestPattern = usePattern,
    )
    val totalMin = plan.size * (options.settleSeconds + options.measureSeconds) / 60.0
    Text("Up to about ${"%.0f".format(totalMin)} minutes in all (codecs the phone or the car cannot use are skipped).", style = MaterialTheme.typography.bodySmall)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { AutoTestRunner.start(context, options) }, enabled = !active) { Text("Start test") }
        OutlinedButton(onClick = { AutoTestRunner.abort() }, enabled = active) { Text("Abort") }
    }
    if (!active && (!service.running || service.viewerCount < 1)) {
        Text(
            "Needs mirroring running and a viewer connected; the test waits for both after Start.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    when (run.status) {
        AutoTestRunner.Status.WAITING -> {
            Text("Waiting to start", fontWeight = FontWeight.Bold)
            for (m in run.missing) Text(m, color = MaterialTheme.colorScheme.error)
        }
        AutoTestRunner.Status.RUNNING -> {
            Text("Phase ${run.phaseIndex}/${run.phaseTotal}: ${run.phaseName} (${run.stage}, ${run.remainingS} s)", fontWeight = FontWeight.Bold)
            Text(run.message, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { run.phaseIndex.toFloat() / maxOf(1, run.phaseTotal) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("About ${run.totalRemainingS / 60}:${"%02d".format(run.totalRemainingS % 60)} left. The test pattern is on screen; Back on the phone aborts.")
        }
        AutoTestRunner.Status.FINISHED, AutoTestRunner.Status.ABORTED -> Text(run.message, fontWeight = FontWeight.Bold)
        AutoTestRunner.Status.IDLE -> Unit
    }

    Text("Test results", style = MaterialTheme.typography.titleMedium)
    if (reports.isEmpty()) Text("(none yet)", style = MaterialTheme.typography.bodySmall)
    else OutlinedButton(onClick = { confirmAll = true }, enabled = !active) { Text("Delete all results") }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            text = { Text("Delete all ${reports.size} test results? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    scope.launch {
                        withContext(Dispatchers.IO) { TestReports.deleteAll(context) }
                        refresh++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
    for (r in reports) {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.startedAtMs))
        OutlinedButton(onClick = { onOpenReport(r.stem) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("${r.runName}  $date${if (r.completed) "" else "  (aborted)"}")
                Text(
                    "best: ${r.best ?: "-"}${r.bestP95?.let { " (e2e P95 ${Math.round(it)} ms)" } ?: ""}, ${r.phases} phases",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

private class Col(val title: String, val width: Dp, val text: (PhaseResult) -> String)

private fun f0(d: Double?) = if (d == null) "-" else Math.round(d).toString()
private fun f1(d: Double?) = if (d == null) "-" else "%.1f".format(d)

private val COLUMNS = listOf(
    Col("#", 30.dp) { it.rank?.toString() ?: "-" },
    Col("phase", 150.dp) { it.name },
    Col("score", 60.dp) { f0(it.score) },
    Col("e2e P95", 62.dp) { f0(it.page.e2eP95) },
    Col("e2e avg", 62.dp) { f0(it.page.e2eAvg) },
    Col("e2e max", 62.dp) { f0(it.page.e2eMax) },
    Col("fps avg/min", 82.dp) { "${f1(it.page.fpsAvg)}/${f0(it.page.fpsMin)}" },
    Col("rtt", 44.dp) { f0(it.page.rttAvg) },
    Col("late", 44.dp) { f0(it.page.lateMs) },
    Col("net", 44.dp) { f0(it.page.netLateMs) },
    Col("skip/repl", 66.dp) { "${it.page.skipped}/${it.page.replaced}" },
    Col("dec rst", 52.dp) { it.page.decoderResets.toString() },
    Col("reconn", 52.dp) { it.page.reconnects.toString() },
    Col("Mbit/s", 52.dp) { f1(it.phone.mbps) },
    Col("srv drop", 56.dp) { it.page.srvDropped.toString() },
    Col("kf req", 44.dp) { it.phone.keyframes.toString() },
    Col("target min/avg", 96.dp) { "${it.phone.targetMin ?: "-"}/${f0(it.phone.targetAvg)}" },
    Col("mode", 52.dp) { it.phone.mode.ifEmpty { "-" } },
    Col("encoder", 190.dp) { if (it.phone.encoder.isEmpty()) "-" else "${it.phone.encoder} ${if (it.phone.hardware == true) "hw" else if (it.phone.hardware == false) "SW" else ""}" },
    Col("enc fps", 52.dp) { f1(it.phone.encodeFps) },
    Col("codec string", 130.dp) { it.phone.codec.ifEmpty { "-" } },
    Col("status", 96.dp) { it.status },
)

/** Detail view of one report: table sorted by score (best first), Share button. */
@Composable
fun TestReportScreen(stem: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember(stem) { mutableStateOf<TestReports.Loaded?>(null) }
    var failed by remember(stem) { mutableStateOf(false) }
    var confirmDelete by remember(stem) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stem) {
        val r = withContext(Dispatchers.IO) { TestReports.load(context, stem) }
        report = r
        failed = r == null
    }
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = { shareReport(context, stem) }, enabled = report != null) { Text("Share (JSON + CSV)") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                text = { Text("Delete this test result? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) { TestReports.delete(context, stem) }
                            onBack()
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
        if (failed) Text("Cannot read this report.", color = MaterialTheme.colorScheme.error)
        val r = report ?: return@Column
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAtMs))
        Text("${r.runName}  $date", style = MaterialTheme.typography.titleLarge)
        Text(
            "${r.device}, preset ${r.preset}, ${r.phaseSeconds} s measured per phase${if (r.completed) "" else ", ABORTED (partial)"}.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Milliseconds unless noted. Best first; orange rows: the phase's option did not apply. encode fps = frames out of the encoder per second on the phone.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                for (c in COLUMNS) Text(c.title, modifier = Modifier.width(c.width), fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
            for (p in r.phases) {
                Row(
                    Modifier.background(
                        when {
                            p.reference -> Color(0x553F8CFF)
                            p.status != PhaseStatus.OK -> Color(0x33FF8A00)
                            else -> Color.Transparent
                        },
                    ),
                ) {
                    for (c in COLUMNS) Text(c.text(p), modifier = Modifier.width(c.width), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
        val notes = r.phases.filter { it.note.isNotEmpty() }
        if (notes.isNotEmpty()) {
            Text("Notes", style = MaterialTheme.typography.titleMedium)
            for (p in notes) Text("${p.name}: ${p.note}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Score", style = MaterialTheme.typography.titleMedium)
        Text(r.formula.ifEmpty { Scoring.FORMULA }, style = MaterialTheme.typography.bodySmall)
        Text("Configuration of each phase", style = MaterialTheme.typography.titleMedium)
        for (p in r.phases) Text("${p.name} (${p.cfgId}): ${p.config}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

private fun shareReport(context: Context, stem: String) {
    val authority = "${context.packageName}.files"
    val uris = arrayListOf(
        FileProvider.getUriForFile(context, authority, TestReports.jsonFile(context, stem)),
        FileProvider.getUriForFile(context, authority, TestReports.csvFile(context, stem)),
    )
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, "WebMirror test $stem")
        clipData = ClipData.newRawUri("report", uris[0]).also { cd -> cd.addItem(ClipData.Item(uris[1])) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share test report"))
}
