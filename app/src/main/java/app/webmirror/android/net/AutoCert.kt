package app.webmirror.android.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import app.webmirror.android.net.acme.UrlConnectionTransport
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.webmirror.android.BuildConfig
import app.webmirror.android.MainActivity
import app.webmirror.android.MirrorService
import app.webmirror.android.R
import app.webmirror.android.Settings
import app.webmirror.android.net.acme.AcmeAccountKey
import app.webmirror.android.net.acme.AcmeDirectories
import app.webmirror.android.net.acme.CertIssuer
import app.webmirror.android.net.acme.DuckDns
import app.webmirror.android.net.acme.IssueRequest
import app.webmirror.android.net.acme.IssueResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** What the wizard / "Renew now" shows while an issuance runs and after it. */
data class IssueRun(
    val running: Boolean = false,
    val lines: List<String> = emptyList(),
    val result: IssueResult? = null,
)

/**
 * Automatic free certificate (DuckDNS + Let's Encrypt), fully on the phone. Owns the persisted [AutoCertState]
 * (secrets encrypted with [SecretBox]), the process-wide issuance lock, and the scope that runs issuances so they
 * survive screen rotation and leaving the wizard.
 */
object AutoCert {
    private const val TAG = "WebMirror"
    private const val PREFS = "webmirror-autocert"
    private const val WORK_PERIODIC = "webmirror-autocert-periodic"
    private const val WORK_NOW = "webmirror-autocert-now"
    private const val CHANNEL_ID = "webmirror_cert"
    private const val NOTIFICATION_ID = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow(AutoCertState())
    val state: StateFlow<AutoCertState> = _state.asStateFlow()

    private val _run = MutableStateFlow(IssueRun())
    val run: StateFlow<IssueRun> = _run.asStateFlow()

    // ---- persistence ----

    fun refresh(context: Context) {
        _state.value = load(context)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): AutoCertState {
        val p = prefs(context)
        if (!p.getBoolean("enabled", false)) return AutoCertState()
        var s = AutoCertState(
            enabled = true,
            subdomain = p.getString("subdomain", "") ?: "",
            notBeforeMs = p.getLong("notBefore", 0),
            notAfterMs = p.getLong("notAfter", 0),
            staging = p.getBoolean("staging", false),
            lastAttemptMs = p.getLong("lastAttempt", 0),
            agreedToTermsAt = p.getLong("agreedToTermsAt", 0),
            lastError = p.getString("lastError", null),
            ipError = p.getString("ipError", null),
            accountX509 = p.getString("accountX509", null)?.let { Base64.decode(it, Base64.NO_WRAP) },
        )
        try {
            s = s.copy(
                token = p.getString("tokenEnc", null)?.let { SecretBox.decryptString(it) } ?: "",
                accountPkcs8 = p.getString("accountPkcs8Enc", null)?.let { SecretBox.decrypt(it) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "automatic certificate secrets cannot be read", e)
            s = s.copy(token = "", accountPkcs8 = null, lastError = "The saved DuckDNS token cannot be read. Turn automatic certificate off and set it up again.")
        }
        return s
    }

    private fun save(context: Context, s: AutoCertState) {
        val e = prefs(context).edit().clear()
        if (s.enabled) {
            e.putBoolean("enabled", true)
                .putString("subdomain", s.subdomain)
                .putLong("notBefore", s.notBeforeMs)
                .putLong("notAfter", s.notAfterMs)
                .putBoolean("staging", s.staging)
                .putLong("lastAttempt", s.lastAttemptMs)
                .putLong("agreedToTermsAt", s.agreedToTermsAt)
                .putString("lastError", s.lastError)
                .putString("ipError", s.ipError)
                .putString("accountX509", s.accountX509?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            if (s.token.isNotEmpty()) e.putString("tokenEnc", SecretBox.encryptString(s.token))
            s.accountPkcs8?.let { e.putString("accountPkcs8Enc", SecretBox.encrypt(it)) }
        }
        e.commit()
        _state.value = s
    }

    // ---- turning off ----

    /** Forgets token and account key. [removeCertificate] false is used when the user imports a .p12 by hand. */
    fun turnOff(context: Context, removeCertificate: Boolean) {
        job?.cancel()
        try { WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC) } catch (_: Exception) {}
        prefs(context).edit().clear().commit()
        SecretBox.deleteKey()
        _state.value = AutoCertState()
        _run.value = IssueRun()
        if (removeCertificate) TlsStore.remove(context.applicationContext)
    }

    // ---- issuing ----

    /** Wizard: first issuance. [subdomain] must be valid-looking; an empty [token] keeps the saved one; the issuer validates again. */
    fun startSetup(context: Context, subdomain: String, token: String, staging: Boolean) {
        val base = AutoCertLogic.setupBase(load(context), subdomain, token, BuildConfig.DEBUG && staging)
        startRun(context.applicationContext, base)
    }

    /** "Renew now". */
    fun startRenewNow(context: Context) {
        val s = load(context)
        if (!s.enabled) return
        startRun(context.applicationContext, s)
    }

    fun cancelRun() {
        job?.cancel()
    }

    fun clearRun() {
        if (!_run.value.running) _run.value = IssueRun()
    }

    private fun startRun(context: Context, base: AutoCertState) {
        if (_run.value.running) return
        _run.value = IssueRun(running = true)
        job = scope.launch {
            try {
                val r = issueLocked(context, base) { line -> _run.update { it.copy(lines = it.lines + line) } }
                _run.update { it.copy(running = false, result = r ?: IssueResult.Rejected("Another certificate request is already running.")) }
            } catch (e: CancellationException) {
                _run.update { it.copy(running = false, result = IssueResult.Cancelled()) }
                throw e
            }
        }
    }

    /** Background check used by the worker and at app start. */
    suspend fun renewIfNeeded(context: Context) {
        val app = context.applicationContext
        val s = load(app)
        _state.value = s
        TlsStore.refresh(app)
        val has = TlsStore.state.value.info != null
        if (!AutoCertLogic.shouldRun(s, has, System.currentTimeMillis(), forced = false)) return
        val r = issueLocked(app, s) { } ?: return
        if (r is IssueResult.Failure && r !is IssueResult.Cancelled) {
            val now = System.currentTimeMillis()
            val cur = load(app)
            if (AutoCertLogic.shouldNotify(cur, TlsStore.state.value.info != null, now)) notifyFailure(app)
        }
    }

    /** Returns null when another issuance holds the lock. On success the certificate is imported and the state saved. */
    private suspend fun issueLocked(context: Context, base: AutoCertState, progress: (String) -> Unit): IssueResult? {
        if (!lock.tryLock()) return null
        try {
            val ip = Settings(context).virtualIp.trim()
            if (!app.webmirror.android.net.Ipv4.isValid(ip)) {
                return fail(context, base, IssueResult.InvalidInput("The virtual address in the app is not valid. Fix it first."))
            }
            val key = if (base.accountPkcs8 != null && base.accountX509 != null) AcmeAccountKey(base.accountPkcs8, base.accountX509) else null
            val issuer = CertIssuer(
                transport = UrlConnectionTransport(context.applicationContext),
                directoryUrl = if (BuildConfig.DEBUG && base.staging) AcmeDirectories.STAGING else AcmeDirectories.PRODUCTION,
                progress = progress,
            )
            val r = runInterruptible(Dispatchers.IO) {
                issuer.issue(IssueRequest(base.subdomain, base.token, ip, agreedToTerms = true, accountKey = key))
            }
            if (r !is IssueResult.Success) return fail(context, base, r as IssueResult.Failure)

            val err = TlsStore.import(context, r.pkcs12, r.password.toCharArray())
            if (err != null) return fail(context, base, IssueResult.Rejected(err))
            val sub = base.subdomain.let { app.webmirror.android.net.acme.CertInput.normalizeSubdomain(it) ?: it }
            save(context, AutoCertLogic.onSuccess(base.copy(subdomain = sub), r.notBeforeMs, r.notAfterMs,
                System.currentTimeMillis(), r.accountKey.pkcs8, r.accountKey.x509))
            schedule(context)
            progress("Certificate installed.")
            return r
        } finally {
            lock.unlock()
        }
    }

    private fun fail(context: Context, base: AutoCertState, f: IssueResult.Failure): IssueResult.Failure {
        if (base.enabled && f !is IssueResult.Cancelled) {
            save(context, AutoCertLogic.onFailure(base, System.currentTimeMillis(), f.message))
        }
        return f
    }

    // ---- DuckDNS address ----

    /** The virtual address changed: point the DuckDNS name at it. Errors are stored and shown in the section. */
    fun updateIp(context: Context, ip: String) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val s = load(app)
            if (!s.enabled || s.token.isEmpty()) return@launch
            val err = DuckDns.updateIp(s.subdomain, s.token, ip, UrlConnectionTransport(app))
            save(app, s.copy(ipError = err?.let { "Could not update the DuckDNS address: $it" }))
        }
    }

    // ---- scheduling ----

    /** Periodic check every 12 h (needs network) plus one check now. Safe to call repeatedly. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoCertWorker>(12, TimeUnit.HOURS).setConstraints(net).build(),
        )
    }

    /** At app start: make sure the periodic work exists and run a check now when the feature is on. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (!_state.value.enabled) return
        schedule(app)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AutoCertWorker>().setConstraints(net).build(),
        )
    }

    // ---- notification ----

    private fun notifyFailure(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Certificate", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WebMirror could not renew your HTTPS certificate")
            .setContentText("Open the app to fix it before the certificate expires.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    /** URL to open in the car. */
    fun carUrl(fqdn: String) = "https://$fqdn:${MirrorService.TLS_PORT}"
}

class AutoCertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            AutoCert.renewIfNeeded(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WebMirror", "automatic certificate check failed", e)
        }
        return Result.success()
    }
}
