package app.webmirror.android.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.webmirror.android.MirrorService
import app.webmirror.android.net.acme.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** GitHub flavor: checks GitHub Releases and installs updates through PackageInstaller. */
internal object UpdateEngine {
    private const val WORK_NAME = "webmirror-update-check"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var postponeJob: Job? = null

    @Volatile private var release: ReleaseInfo? = null
    @Volatile private var apk: File? = null

    fun currentRelease(): ReleaseInfo? = release

    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (UpdatePrefs(context).autoCheck) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
        // Leftover downloads are useless once we are up to date.
        scope.launch { if (Updates.state.value !is UpdateState.Installing) cleanupIfCurrent(context) }
    }

    private fun cleanupIfCurrent(context: Context) {
        val seen = UpdatePrefs(context).lastSeenVersion
        if (seen == null || !Version.isNewer(seen, app.webmirror.android.BuildConfig.VERSION_NAME)) File(context.cacheDir, "updates").deleteRecursively()
    }

    fun checkNow(context: Context) {
        scope.launch { runCheck(context, background = false) }
    }

    fun downloadAndInstall(context: Context) {
        val r = release ?: (Updates.state.value as? UpdateState.Available)?.release ?: return
        scope.launch { downloadAndInstall(context, r) }
    }

    /** Used by [UpdateWorker]. Never throws. */
    suspend fun runCheck(context: Context, background: Boolean) = lock.withLock {
        val prefs = UpdatePrefs(context)
        val busy = Updates.state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing) return@withLock
        Updates.setState(UpdateState.Checking)
        val result = try {
            withContext(Dispatchers.IO) { UpdateChecker(context).check() }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            prefs.lastError = msg
            Updates.setState(UpdateState.Failed(msg))
            return@withLock
        }
        prefs.lastCheckMs = System.currentTimeMillis()
        prefs.lastError = null
        when (result) {
            CheckResult.NoInfo -> Updates.setState(UpdateState.NoInfo)
            CheckResult.UpToDate -> { prefs.lastSeenVersion = null; Updates.setState(UpdateState.UpToDate) }
            is CheckResult.Available -> {
                val r = result.release
                release = r
                prefs.lastSeenVersion = r.version
                Updates.setState(UpdateState.Available(r))
                if (background) handleAutomatic(context, prefs, r)
            }
        }
    }

    private fun handleAutomatic(context: Context, prefs: UpdatePrefs, r: ReleaseInfo) {
        when (UpdatePolicy.decideAuto(prefs.autoInstall, MirrorService.state.value.running, canInstallPackages(context))) {
            AutoAction.Install -> scope.launch { downloadAndInstall(context, r) }
            AutoAction.Postpone -> {
                Updates.setState(UpdateState.Postponed(r))
                postponeJob?.cancel()
                postponeJob = scope.launch {
                    MirrorService.state.first { !it.running }
                    downloadAndInstall(context, r)
                }
            }
            AutoAction.NotifyOnly -> if (prefs.lastNotifiedVersion != r.version) {
                prefs.lastNotifiedVersion = r.version
                UpdateNotifications.available(context, r)
            }
        }
    }

    private suspend fun downloadAndInstall(context: Context, r: ReleaseInfo) {
        postponeJob?.cancel()
        if (!canInstallPackages(context)) { Updates.setState(UpdateState.NeedsInstallPermission(r)); return }
        try {
            val installer = UpdateInstaller(context, UrlConnectionTransport(context))
            var file = apk?.takeIf { it.exists() && release == r }
            if (file == null) {
                Updates.setState(UpdateState.Downloading(r, 0f))
                file = withContext(Dispatchers.IO) { installer.download(r) { Updates.setState(UpdateState.Downloading(r, it)) } }
                apk = file
            }
            withContext(Dispatchers.IO) { installer.verifyApk(file) }
            Updates.setState(UpdateState.ReadyToInstall(r))
            Updates.setState(UpdateState.Installing(r))
            withContext(Dispatchers.IO) { installer.install(file) }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            UpdatePrefs(context).lastError = msg
            Updates.setState(UpdateState.Failed(msg))
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UpdateEngine.runCheck(applicationContext, background = true)
        return Result.success()
    }
}
