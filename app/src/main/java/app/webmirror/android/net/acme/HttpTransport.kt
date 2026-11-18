package app.webmirror.android.net.acme

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
class UrlConnectionTransport(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
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
