package app.webmirror.android.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException

data class LocalAddress(val interfaceName: String, val ip: String)

object Addresses {

    /** Every non-loopback IPv4 address of an interface that is up, with its interface name. */
    fun localIpv4(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        result.add(LocalAddress(nif.name, ip))
                    }
                }
            }
        } catch (e: SocketException) {
            // return what we have
        }
        return result
    }

    /**
     * Every global (2000::/3) or unique-local (fc00::/7) IPv6 address of an interface that is up, with its
     * interface name, in compressed lowercase form. Loopback, link-local and multicast are skipped.
     * Temporary privacy addresses are not filtered out.
     */
    fun localIpv6(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet6Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress || addr.isAnyLocalAddress) continue
                    val first = addr.address[0].toInt() and 0xff
                    val global = (first and 0xe0) == 0x20
                    val uniqueLocal = (first and 0xfe) == 0xfc
                    if (!global && !uniqueLocal) continue
                    result.add(LocalAddress(nif.name, formatIpv6(addr.address)))
                }
            }
        } catch (e: SocketException) {
            // return what we have
        }
        return result
    }

    /** Compressed lowercase text form (RFC 5952) of a 16-byte IPv6 address. */
    fun formatIpv6(bytes: ByteArray): String {
        require(bytes.size == 16) { "IPv6 address needs 16 bytes" }
        val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
        // Longest run of zero groups (first wins on ties), only runs of 2 or more are compressed.
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(Integer.toHexString(groups[i]))
            i++
        }
        return sb.toString()
    }

    /** Address without a `%zone` suffix, lowercase. */
    private fun clean(ip: String): String = ip.substringBefore('%').lowercase()

    /** `http://10.0.0.2:8080` or `http://[2001:db8::1]:8080` (zone stripped). */
    fun url(ip: String, port: Int): String {
        val a = clean(ip)
        return if (a.contains(':')) "http://[$a]:$port" else "http://$a:$port"
    }

    /**
     * sslip.io host name that resolves to [ip]: IPv4 dots and IPv6 colons both become dashes
     * (`10.15.23.199` -> `10-15-23-199.sslip.io`, `2001:db8::1` -> `2001-db8--1.sslip.io`).
     */
    fun sslipHost(ip: String): String = clean(ip).replace('.', '-').replace(':', '-') + ".sslip.io"

    fun sslipUrl(ip: String, port: Int): String = "http://${sslipHost(ip)}:$port"

    /** URLs for the UI, in three groups. */
    data class UrlList(
        /** http://ip:port for IPv4 (virtual address last among them), then IPv6. */
        val addresses: List<String> = emptyList(),
        /** sslip.io host name URL for each entry of [addresses], same order. */
        val names: List<String> = emptyList(),
        /** https URLs for the certificate host names. */
        val https: List<String> = emptyList(),
    ) {
        fun isEmpty(): Boolean = addresses.isEmpty() && https.isEmpty()

        companion object {
            val EMPTY = UrlList()
        }
    }

    /**
     * IPv4 addresses first (the virtual address, if active, after the others), then IPv6, no duplicates.
     * Names are the sslip.io form of the same addresses. https URLs for every certificate host name.
     */
    fun buildUrls(
        localIps: List<String>,
        virtualIp: String?,
        httpPort: Int,
        httpsNames: List<String>,
        httpsPort: Int,
        localIpv6: List<String> = emptyList(),
    ): UrlList {
        val ips = LinkedHashSet<String>()
        for (ip in localIps) if (ip != virtualIp) ips.add(clean(ip))
        if (virtualIp != null) ips.add(clean(virtualIp))
        for (ip in localIpv6) ips.add(clean(ip))
        val https = LinkedHashSet<String>()
        for (name in httpsNames) https.add("https://$name:$httpsPort")
        return UrlList(
            addresses = ips.map { url(it, httpPort) },
            names = ips.map { sslipUrl(it, httpPort) },
            https = https.toList(),
        )
    }
}
