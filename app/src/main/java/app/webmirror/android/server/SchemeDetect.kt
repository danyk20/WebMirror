package app.webmirror.android.server

/** Pure helpers for the "wrong scheme" cases (JVM-testable). */
object SchemeDetect {
    enum class Kind { TLS_HANDSHAKE, PLAIN_HTTP, OTHER }

    const val MAX_HEAD_BYTES = 8192

    /** What a new connection is, judged by its first byte: 0x16 = TLS handshake, an upper-case letter = HTTP method. */
    fun classify(firstByte: Int): Kind = when {
        firstByte == 0x16 -> Kind.TLS_HANDSHAKE
        firstByte in 'A'.code..'Z'.code -> Kind.PLAIN_HTTP
        else -> Kind.OTHER
    }

    private val HOST = Regex("""^([A-Za-z0-9.\-]+|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?$""")
    private val PATH = Regex("""^/[\x21-\x7E]*$""")

    /** True when [head] holds the whole request head (ends with an empty line). */
    fun headComplete(head: String): Boolean = head.contains("\r\n\r\n") || head.contains("\n\n")

    /**
     * Builds the `301` answer for a plain-HTTP request that reached the TLS port: same host (with port) and
     * path over https. Returns null when the request has no usable Host header (the caller just closes).
     */
    fun redirectToHttps(head: String): ByteArray? {
        val lines = head.split("\n").map { it.trimEnd('\r') }
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        var path = if (requestLine.size >= 2) requestLine[1] else "/"
        if (!PATH.matches(path)) path = "/"
        var host: String? = null
        for (l in lines.drop(1)) {
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0 && l.substring(0, i).trim().equals("host", ignoreCase = true)) {
                host = l.substring(i + 1).trim()
                break
            }
        }
        if (host == null || !HOST.matches(host)) return null
        return ("HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://$host$path\r\n" +
            "Content-Length: 0\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
    }

    /**
     * Plain-HTTP page request -> https URL, only when the Host header is exactly [certHost] (so access by
     * raw IP keeps working). Null = serve normally.
     */
    fun httpsPageRedirect(uri: String, hostHeader: String?, certHost: String?, httpsPort: Int): String? {
        if (certHost == null || hostHeader == null) return null
        if (uri != "/" && uri != "/index.html") return null
        val name = hostHeader.trim().substringBefore(':')
        if (!name.equals(certHost, ignoreCase = true)) return null
        return "https://$certHost:$httpsPort/"
    }
}
