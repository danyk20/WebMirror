package app.webmirror.android.net

/** Pure helpers to read names out of certificate subjects (JVM-testable). */
object CertNames {

    /** Common name from an RFC 2253 distinguished name such as `CN=example.com,O=Acme\, Inc`, or null. */
    fun commonName(rfc2253: String): String? {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < rfc2253.length) {
            val c = rfc2253[i]
            if (c == '\\' && i + 1 < rfc2253.length) {
                cur.append(rfc2253[i + 1])
                i += 2
                continue
            }
            if (c == ',' || c == '+') {
                parts.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        parts.add(cur.toString())
        for (p in parts) {
            val t = p.trim()
            if (t.length > 3 && t.substring(0, 3).equals("CN=", ignoreCase = true)) {
                return t.substring(3).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Names worth putting in a URL: SAN DNS names without wildcards, else the CN if it looks like a host name. */
    fun hostNames(dnsNames: List<String>, commonName: String?): List<String> {
        val names = dnsNames.filter { it.isNotEmpty() && !it.contains('*') }.distinct()
        if (names.isNotEmpty()) return names
        if (dnsNames.isEmpty() && commonName != null && commonName.contains('.') &&
            commonName.none { it == '*' || it == ' ' }
        ) {
            return listOf(commonName)
        }
        return emptyList()
    }
}
