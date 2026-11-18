package app.webmirror.android.net.acme

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** [headers] keys are lower-case. */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/** Network seam so the whole flow can run against a fake server. Implementations throw [IOException] on network failure. */
interface HttpTransport {
    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResponse
}

/** Real transport over HttpURLConnection. Never logs URLs (DuckDNS URLs contain the token). */
class UrlConnectionTransport(
    context: Context? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    private val cm: ConnectivityManager? = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)

    /**
     * The real internet network, bypassing the app's own virtual-address VPN (which has no routes or DNS).
     * Prefers validated, then Wi-Fi over cellular, over anything else.
     */
    private fun underlyingNetwork(): Network? = try {
        cm?.allNetworks?.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            var score = 0
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 2
            else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 1
            n to score
        }?.maxByOrNull { it.second }?.first
    } catch (_: Exception) { null }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val n = underlyingNetwork()
        return (if (n != null) n.openConnection(u) else u.openConnection()) as HttpURLConnection
    }

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val conn = open(url)
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "WebMirror-Android")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val h = HashMap<String, String>()
            for ((k, v) in conn.headerFields) if (k != null && v.isNotEmpty()) h[k.lowercase()] = v.first()
            return HttpResponse(status, h, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

/** DuckDNS "update" calls outside the certificate flow. */
object DuckDns {
    /**
     * Points [subdomain] (bare name or full name) at [ip]. Returns null on success or a readable error.
     * Blocking; call from a background thread. The token never appears in the message.
     */
    fun updateIp(subdomain: String, token: String, ip: String, transport: HttpTransport = UrlConnectionTransport()): String? {
        val sub = CertInput.normalizeSubdomain(subdomain) ?: return "The DuckDNS name is not valid."
        val t = token.trim()
        if (!CertInput.isValidToken(t)) return "The DuckDNS token is not valid."
        return try {
            val r = transport.request("GET", "https://www.duckdns.org/update?domains=$sub&token=$t&ip=$ip")
            if (r.status == 200 && r.text.trim().startsWith("OK")) null
            else "DuckDNS refused the address update: check that the name and the token belong together."
        } catch (e: IOException) {
            "Could not reach DuckDNS: ${(e.message ?: e.javaClass.simpleName).replace(t, "***")}"
        }
    }
}
