package app.webmirror.android.net

import java.net.Inet4Address
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

    fun url(ip: String, port: Int): String = "http://$ip:$port"
}
