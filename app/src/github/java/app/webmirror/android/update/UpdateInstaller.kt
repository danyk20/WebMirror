package app.webmirror.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.webmirror.android.net.acme.HttpTransport
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateInstaller(private val context: Context, private val transport: HttpTransport) {
    private val dir get() = File(context.cacheDir, "updates")

    /** Downloads the APK (cache/updates), verifying size and the optional SHA-256. [onProgress] gets 0f..1f or -1f. */
    @Throws(IOException::class)
    fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "WebMirror-${release.version}.apk")
        val tmp = File(dir, file.name + ".part")
        val conn = UpdateNetwork.open(context, release.apkUrl)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "WebMirror-Android")
            if (conn.responseCode != 200) throw IOException("Download failed (status ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) { lastReport = now; onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f) }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw IOException("The download is incomplete or the wrong size.")
        } catch (e: IOException) {
            tmp.delete(); throw e
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256Url != null) {
            val expected = ReleaseParser.parseSha256(transport.request("GET", release.sha256Url).text)
            if (expected != null && expected != actual) { tmp.delete(); throw IOException("The downloaded file does not match its checksum.") }
        }
        if (!tmp.renameTo(file)) throw IOException("Could not store the download.")
        return file
    }

    /** Throws [IOException] unless the APK is this app, newer, and signed by the same certificate as the installed app. */
    @Throws(IOException::class)
    fun verifyApk(file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("The downloaded file is not a valid app package.")
        if (archive.packageName != context.packageName) throw IOException("The downloaded app is a different app.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("The downloaded app is not newer than the installed one.")
        val newSigners = signers(archive)
        val oldSigners = signers(installed)
        if (newSigners.isEmpty() || oldSigners.isEmpty()) throw IOException("Could not read the app signature.")
        val history = installed.signingInfo?.signingCertificateHistory?.map { sha(it.toByteArray()) }?.toSet().orEmpty()
        if (newSigners != oldSigners && !newSigners.all { it in history }) throw IOException("The downloaded app is signed by a different key; not installing.")
    }

    private fun signers(p: PackageInfo): Set<String> =
        p.signingInfo?.apkContentsSigners?.map { sha(it.toByteArray()) }?.toSet().orEmpty()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Commits a PackageInstaller session; the result arrives at [InstallResultReceiver]. */
    @Throws(IOException::class)
    fun install(file: File) {
        val pi = context.packageManager.packageInstaller
        pi.mySessions.filter { it.appPackageName == context.packageName }.forEach { try { pi.abandonSession(it.sessionId) } catch (_: Exception) {} }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("webmirror.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds status extras to this intent.
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) {}
            throw if (e is IOException) e else IOException(e.message ?: "Could not start the install.")
        }
    }
}
