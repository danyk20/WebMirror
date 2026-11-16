package app.webmirror.android.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class TlsInfo(
    val commonName: String?,
    val dnsNames: List<String>,
    val notAfterMs: Long,
) {
    /** Names to put into https URLs. */
    val hostNames: List<String> get() = CertNames.hostNames(dnsNames, commonName)
}

data class TlsState(
    val info: TlsInfo? = null,
    /** Set when a stored certificate exists but cannot be loaded. */
    val error: String? = null,
)

/**
 * The user's own PKCS#12 certificate for the optional HTTPS server. The file lives in app-private storage;
 * its password is encrypted with an AES/GCM key that never leaves the Android Keystore.
 * Nothing here is ever committed or packaged in the APK: the certificate is imported on the phone at run time.
 */
object TlsStore {
    private const val TAG = "WebMirror"
    private const val FILE_NAME = "webmirror-tls.p12"
    private const val PREFS = "webmirror-tls"
    private const val PREF_PASSWORD = "password"
    private const val KEY_ALIAS = "webmirror_tls_password"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MAX_FILE_BYTES = 1_000_000

    private val _state = MutableStateFlow(TlsState())
    val state: StateFlow<TlsState> = _state.asStateFlow()

    /** Reloads [state] from what is stored on disk. */
    fun refresh(context: Context) {
        val file = file(context)
        if (!file.exists()) {
            _state.value = TlsState()
            return
        }
        _state.value = try {
            val pw = loadPassword(context)
            TlsState(info = describe(openKeyStore(FileInputStream(file).use { it.readBytes() }, pw).second))
        } catch (e: Exception) {
            Log.w(TAG, "stored certificate cannot be loaded", e)
            TlsState(error = "The stored certificate cannot be loaded (${e.message ?: e.javaClass.simpleName}). Remove it and import it again.")
        }
    }

    /** Reads a picked file, at most [MAX_FILE_BYTES]. Throws IOException when too large. */
    fun readPicked(context: Context, uri: android.net.Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw IOException("the file is too large for a certificate")
            }
            return out.toByteArray()
        }
    }

    /**
     * Validates [bytes] with [password] by actually loading the key store, and only then saves it.
     * Returns null on success or a readable error; on error nothing is kept.
     */
    fun import(context: Context, bytes: ByteArray, password: CharArray): String? {
        val ks: KeyStore
        val alias: String
        try {
            val opened = openKeyStore(bytes, password)
            alias = opened.first
            ks = opened.second
            sslContext(ks, password) // proves the key manager can use it
        } catch (e: IOException) {
            Log.i(TAG, "p12 rejected: $e")
            return "Cannot open the file: wrong password, or it is not a PKCS#12 (.p12) file."
        } catch (e: Exception) {
            Log.i(TAG, "p12 rejected: $e")
            return e.message ?: "The certificate cannot be used."
        }
        if (ks.getCertificate(alias) !is X509Certificate) return "The file contains no usable certificate."

        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            val encrypted = encrypt(String(password))
            tmp.writeBytes(bytes)
            val target = file(context)
            if (!tmp.renameTo(target)) throw IOException("cannot save the certificate")
            val saved = prefs(context).edit().putString(PREF_PASSWORD, encrypted).commit()
            if (!saved) throw IOException("cannot save the password")
        } catch (e: Exception) {
            Log.w(TAG, "saving certificate failed", e)
            tmp.delete()
            wipe(context)
            return "Cannot store the certificate: ${e.message ?: e.javaClass.simpleName}"
        }
        refresh(context)
        return null
    }

    fun remove(context: Context) {
        wipe(context)
        refresh(context)
    }

    /** SSLContext for the stored certificate. Throws when there is none or it cannot be loaded. */
    fun sslContext(context: Context): SSLContext {
        val pw = loadPassword(context)
        val (_, ks) = openKeyStore(FileInputStream(file(context)).use { it.readBytes() }, pw)
        return sslContext(ks, pw)
    }

    // ---- internals ----

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wipe(context: Context) {
        file(context).delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
        prefs(context).edit().clear().commit()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "cannot delete keystore key: $e")
        }
    }

    /** Loads a PKCS#12 and returns the alias of its first private-key entry with the key store. */
    private fun openKeyStore(bytes: ByteArray, password: CharArray): Pair<String, KeyStore> {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(bytes), password)
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IllegalArgumentException("The file contains no private key.")
        if (ks.getKey(alias, password) == null) throw IllegalArgumentException("The private key cannot be read.")
        return alias to ks
    }

    private fun sslContext(ks: KeyStore, password: CharArray): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun describe(ks: KeyStore): TlsInfo {
        val alias = ks.aliases().toList().first { ks.isKeyEntry(it) }
        val cert = ks.getCertificate(alias) as X509Certificate
        val dns = ArrayList<String>()
        try {
            for (entry in cert.subjectAlternativeNames.orEmpty()) {
                if (entry.size >= 2 && entry[0] == 2) (entry[1] as? String)?.let { dns.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read subject alternative names: $e")
        }
        return TlsInfo(
            commonName = CertNames.commonName(cert.subjectX500Principal.getName("RFC2253")),
            dnsNames = dns.distinct(),
            notAfterMs = cert.notAfter.time,
        )
    }

    private fun loadPassword(context: Context): CharArray {
        val stored = prefs(context).getString(PREF_PASSWORD, null) ?: throw IOException("no stored password")
        return decrypt(stored).toCharArray()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(':')
        if (parts.size != 2) throw IOException("stored password is malformed")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
