package app.webmirror.android

import app.webmirror.android.server.ReplaySocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplaySocketTest {
    @Test
    fun replaysConsumedBytesThenRealStreamAndDelegates() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use { accepted ->
                    client.getOutputStream().apply { write("ABCDEF".toByteArray()); flush() }
                    val first = accepted.getInputStream().read()
                    val rs = ReplaySocket(accepted, byteArrayOf(first.toByte()))
                    val buf = ByteArray(6)
                    var n = 0
                    while (n < 6) n += rs.getInputStream().read(buf, n, 6 - n)
                    assertEquals("ABCDEF", String(buf))
                    assertEquals(accepted.port, rs.port)
                    assertEquals(accepted.inetAddress, rs.inetAddress)
                    assertNull(rs.channel)
                    rs.soTimeout = 1234
                    assertEquals(1234, accepted.soTimeout)
                    rs.getOutputStream().apply { write(7); flush() }
                    assertEquals(7, client.getInputStream().read())
                    rs.close()
                    assertEquals(true, accepted.isClosed)
                }
            }
        }
    }
}
