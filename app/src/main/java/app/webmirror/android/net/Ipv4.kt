package app.webmirror.android.net

/** Pure IPv4 text validation (JVM-testable). */
object Ipv4 {

    /** True for exactly four dot-separated decimal octets 0..255 (no spaces, no signs, no leading zeros). */
    fun isValid(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return false
            if (!p.all { it in '0'..'9' }) return false
            if (p.length > 1 && p[0] == '0') return false
            if (p.toInt() > 255) return false
        }
        return true
    }
}
