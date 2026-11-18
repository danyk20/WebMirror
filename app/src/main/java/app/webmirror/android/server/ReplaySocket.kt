package app.webmirror.android.server

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel

/**
 * Wraps an accepted socket whose first byte(s) were already read: [getInputStream] returns those bytes
 * first, then continues with the real stream. Everything else is delegated, so an SSLSocket layered on
 * top (SSLSocketFactory.createSocket(socket, host, port, true)) sees an untouched connection.
 */
class ReplaySocket(private val real: Socket, consumed: ByteArray) : Socket() {
    private val input: InputStream by lazy {
        val tail = real.getInputStream()
        val head = ByteArrayInputStream(consumed)
        object : InputStream() {
            override fun read(): Int = if (head.available() > 0) head.read() else tail.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                if (head.available() > 0) head.read(b, off, len) else tail.read(b, off, len)
            override fun available(): Int = head.available() + tail.available()
            override fun close() = tail.close()
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = real.getOutputStream()
    override fun getInetAddress(): InetAddress? = real.inetAddress
    override fun getLocalAddress(): InetAddress = real.localAddress
    override fun getPort(): Int = real.port
    override fun getLocalPort(): Int = real.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = real.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = real.localSocketAddress
    override fun getChannel(): SocketChannel? = null
    override fun connect(endpoint: SocketAddress?) = real.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = real.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = real.bind(bindpoint)
    override fun isConnected(): Boolean = real.isConnected
    override fun isBound(): Boolean = real.isBound
    override fun isClosed(): Boolean = real.isClosed
    override fun isInputShutdown(): Boolean = real.isInputShutdown
    override fun isOutputShutdown(): Boolean = real.isOutputShutdown
    override fun close() = real.close()
    override fun shutdownInput() = real.shutdownInput()
    override fun shutdownOutput() = real.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { real.soTimeout = timeout }
    override fun getSoTimeout(): Int = real.soTimeout
    override fun setTcpNoDelay(on: Boolean) { real.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = real.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { real.keepAlive = on }
    override fun getKeepAlive(): Boolean = real.keepAlive
    override fun setSendBufferSize(size: Int) { real.sendBufferSize = size }
    override fun getSendBufferSize(): Int = real.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { real.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = real.receiveBufferSize
    override fun setSoLinger(on: Boolean, linger: Int) = real.setSoLinger(on, linger)
    override fun getSoLinger(): Int = real.soLinger
    override fun setReuseAddress(on: Boolean) { real.reuseAddress = on }
    override fun getReuseAddress(): Boolean = real.reuseAddress
    override fun setOOBInline(on: Boolean) { real.oobInline = on }
    override fun getOOBInline(): Boolean = real.oobInline
    override fun setTrafficClass(tc: Int) { real.trafficClass = tc }
    override fun getTrafficClass(): Int = real.trafficClass
    override fun sendUrgentData(data: Int) = real.sendUrgentData(data)
    override fun toString(): String = "ReplaySocket($real)"
}
