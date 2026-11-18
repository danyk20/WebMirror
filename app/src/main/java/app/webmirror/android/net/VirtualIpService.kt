package app.webmirror.android.net

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import app.webmirror.android.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VirtualIpState(
    val active: Boolean = false,
    val address: String? = null,
    val error: String? = null,
)

/**
 * Gives the phone an extra IPv4 address by holding a local VPN interface open. No routes, no DNS, and
 * the tunnel is never read or written: its only job is to make the phone own [address].
 * Only this app is attached to the VPN (addAllowedApplication), so no other app is affected.
 */
class VirtualIpService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra(EXTRA_IP)
        if (ip == null || !Ipv4.isValid(ip)) {
            fail("Invalid virtual address: ${ip ?: "(none)"}")
            return START_NOT_STICKY
        }
        closeTunnel()
        try {
            tun = Builder()
                .setSession("WebMirror virtual address")
                .addAddress(ip, 32)
                .addAllowedApplication(packageName)
                .allowBypass()
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            Log.w(TAG, "cannot establish virtual address $ip", e)
            fail("Cannot create the virtual address: ${e.message ?: e.javaClass.simpleName}")
            return START_NOT_STICKY
        }
        if (tun == null) {
            fail("VPN permission is missing or was revoked. Switch the virtual address on again.")
            return START_NOT_STICKY
        }
        Log.i(TAG, "virtual address $ip active")
        _state.value = VirtualIpState(active = true, address = ip)
        return START_NOT_STICKY
    }

    /** The user or another VPN app revoked us: close the tunnel and make the switch honest. */
    override fun onRevoke() {
        Log.i(TAG, "virtual address revoked")
        closeTunnel()
        Settings(this).virtualIpEnabled = false
        _state.value = VirtualIpState(error = "The virtual address was switched off by the system or by another VPN app.")
        super.onRevoke() // stops the service
    }

    override fun onDestroy() {
        closeTunnel()
        _state.update { it.copy(active = false, address = null) }
        super.onDestroy()
    }

    private fun fail(message: String) {
        closeTunnel()
        Settings(this).virtualIpEnabled = false
        _state.value = VirtualIpState(error = message)
        stopSelf()
    }

    private fun closeTunnel() {
        try {
            tun?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed: $e")
        }
        tun = null
    }

    companion object {
        private const val TAG = "WebMirror"
        private const val EXTRA_IP = "ip"

        private val _state = MutableStateFlow(VirtualIpState())
        val state: StateFlow<VirtualIpState> = _state.asStateFlow()

        /** Call only after `VpnService.prepare(context)` returned null (consent granted). */
        fun start(context: Context, ip: String): Boolean = try {
            context.startService(Intent(context, VirtualIpService::class.java).putExtra(EXTRA_IP, ip))
            true
        } catch (e: IllegalStateException) {
            // BackgroundServiceStartNotAllowedException (API 31+, a subclass) when the app is not in the foreground,
            // e.g. the screen is off. Not fatal: the next resume tries again.
            Log.w(TAG, "cannot start the virtual address service now (app not in the foreground?): $e")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "virtual address service start refused: $e")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VirtualIpService::class.java))
        }
    }
}
