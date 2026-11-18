package app.webmirror.android.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens connections on the real internet network, bypassing the app's own VpnService (which has no routes or DNS).
 * Same selection as net/acme UrlConnectionTransport; used for the streamed APK download.
 */
internal object UpdateNetwork {
    fun open(context: Context, url: String): HttpURLConnection {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val u = URL(url)
        val n = try {
            cm.allNetworks.mapNotNull { net ->
                val c = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
                var score = 0
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 2
                else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 1
                net to score
            }.maxByOrNull { it.second }?.first
        } catch (_: Exception) { null }
        return (if (n != null) n.openConnection(u) else u.openConnection()) as HttpURLConnection
    }
}
