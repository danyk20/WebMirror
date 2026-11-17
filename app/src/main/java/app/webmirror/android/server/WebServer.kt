package app.webmirror.android.server

import android.content.res.AssetManager
import android.util.Log
import app.webmirror.android.stream.StreamHub
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTP server for the web client (assets/web) plus the /ws WebSocket endpoint.
 * Start with `start(0, true)`: timeout 0 so idle WebSockets are not killed by the 5 s read timeout.
 */
class WebServer(
    port: Int,
    private val assets: AssetManager,
    private val hub: StreamHub,
) : NanoWSD(port) {

    /** Set on the TLS port by [enableTls]; null on the plain port. */
    private var tlsFactory: SSLSocketFactory? = null

    @Volatile
    private var consumedUnsupported = false

    /** Plain port only: when set, page requests with exactly this Host header are redirected to https. */
    @Volatile
    var httpsRedirectHost: String? = null

    @Volatile
    var httpsPort: Int = 8443

    /**
     * Makes this the TLS server. NanoHTTPD's own makeSecure() cannot be used because its SSLServerSocket
     * hides the first bytes of a connection (a plain-HTTP request would only produce a handshake error and
     * its bytes are gone). Instead the server accepts plain TCP sockets, looks at the first byte, and for
     * real TLS wraps the socket itself with the first byte handed over (SSLSocketFactory.createSocket(
     * socket, consumed, autoClose)).
     */
    fun enableTls(ctx: SSLContext) {
        tlsFactory = ctx.socketFactory
        serverSocketFactory = object : ServerSocketFactory {
            override fun create(): ServerSocket = ServerSocket()
        }
    }

    /** NanoWSD.openWebSocket cannot refuse a request, so non-/ws upgrade requests are rejected here. */
    override fun serve(session: IHTTPSession): Response {
        if (!isWebsocketRequested(session)) {
            val target = SchemeDetect.httpsPageRedirect(session.uri ?: "", session.headers["host"], httpsRedirectHost, httpsPort)
            if (target != null) {
                val r = NanoHTTPD.newFixedLengthResponse(Response.Status.FOUND, "text/plain; charset=utf-8", "")
                r.addHeader("Location", target)
                r.addHeader("Cache-Control", "no-store")
                return r
            }
        }
        if (isWebsocketRequested(session) && session.uri != "/ws") {
            return notFound()
        }
        return super.serve(session)
    }

    /**
     * NanoHTTPD 2.3.1 calls this on its accept thread for every accepted socket (ServerRunnable.run ->
     * createClientHandler), so it is the place to tune the socket. A small kernel send buffer keeps the
     * data that sits in the OS, invisible to our flow control, to a few tens of milliseconds of video.
     */
    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        tuneSocket(finalAccept)
        return SniffingHandler(finalAccept)
    }

    /**
     * Runs on its own connection thread. Looks at the first byte before NanoHTTPD sees the connection:
     * plain port: TLS handshake (0x16) -> close at once so the browser falls back quickly;
     * TLS port: 0x16 -> TLS; HTTP method letter -> 301 to https; anything else -> close.
     */
    private inner class SniffingHandler(private val raw: Socket) : ClientHandler(raw.getInputStream(), raw) {
        override fun run() {
            val real: ClientHandler? = try {
                prepare()
            } catch (e: Exception) {
                Log.d(TAG, "connection dropped while sniffing: $e")
                null
            }
            if (real == null) {
                try { raw.close() } catch (e: Exception) { /* already closed */ }
            } else {
                real.run() // closes the sockets when the connection ends
            }
            asyncRunner.closed(this)
        }

        private fun prepare(): ClientHandler? {
            val tls = tlsFactory
            if (tls != null && consumedUnsupported) return wrapTls(tls, null)
            raw.soTimeout = SNIFF_TIMEOUT_MS
            val first = raw.getInputStream().read()
            raw.soTimeout = 0
            if (first < 0) return null
            val kind = SchemeDetect.classify(first)
            if (tls == null) {
                if (kind == SchemeDetect.Kind.TLS_HANDSHAKE) return null
                val in2 = SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), raw.getInputStream())
                return ClientHandler(in2, raw)
            }
            return when (kind) {
                SchemeDetect.Kind.TLS_HANDSHAKE -> wrapTls(tls, first)
                SchemeDetect.Kind.PLAIN_HTTP -> {
                    redirect(first)
                    null
                }
                SchemeDetect.Kind.OTHER -> null
            }
        }

        private fun wrapTls(tls: SSLSocketFactory, first: Int?): ClientHandler? {
            val ssl: Socket = try {
                if (first != null) {
                    val m = CREATE_WITH_CONSUMED ?: throw UnsupportedOperationException("createSocket(consumed) missing")
                    m.invoke(tls, raw, ByteArrayInputStream(byteArrayOf(first.toByte())), true) as Socket
                } else {
                    (tls.createSocket(raw, null, raw.port, true) as SSLSocket).also { it.useClientMode = false }
                }
            } catch (e: Exception) {
                // Some platform does not support handing over consumed bytes: stop sniffing from now on.
                Log.w(TAG, "TLS wrap with consumed bytes failed, sniffing off: $e")
                consumedUnsupported = true
                return null
            }
            return ClientHandler(ssl.getInputStream(), ssl)
        }

        /** Reads the request head (first byte already read) and answers with a 301 to the https URL. */
        private fun redirect(first: Int) {
            val buf = StringBuilder().append(first.toChar())
            raw.soTimeout = SNIFF_TIMEOUT_MS
            val input = raw.getInputStream()
            while (buf.length < SchemeDetect.MAX_HEAD_BYTES && !SchemeDetect.headComplete(buf.toString())) {
                val b = input.read()
                if (b < 0) break
                buf.append((b and 0xFF).toChar())
            }
            val answer = SchemeDetect.redirectToHttps(buf.toString()) ?: return
            raw.getOutputStream().apply {
                write(answer)
                flush()
            }
            Log.i(TAG, "plain HTTP on the TLS port: redirected to https")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = ClientSession(handshake, hub)

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: return notFound()
        if (uri.contains("..") || uri.contains('\\') || uri.contains('\u0000')) return notFound()
        val path = if (uri == "/") "index.html" else uri.trimStart('/')
        if (path.isEmpty() || path.endsWith("/")) return notFound()
        return try {
            val bytes = assets.open("web/$path").use { it.readBytes() }
            val r = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                mimeFor(path),
                bytes.inputStream(),
                bytes.size.toLong(),
            )
            r.addHeader("Cache-Control", "no-store")
            r
        } catch (e: IOException) {
            Log.d(TAG, "404 $uri")
            notFound()
        }
    }

    private fun notFound(): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found")
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "WebMirror"
        const val SEND_BUFFER_BYTES = 64 * 1024
        private const val SNIFF_TIMEOUT_MS = 10_000

        /**
         * SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean): a server-mode TLS socket over a
         * connected socket whose first bytes were already read. Looked up by reflection because the Kotlin
         * compiler does not see it in the Android SDK stubs; it exists at run time on Android 10+ (Conscrypt).
         */
        private val CREATE_WITH_CONSUMED: java.lang.reflect.Method? = try {
            SSLSocketFactory::class.java.getMethod(
                "createSocket", Socket::class.java, InputStream::class.java, java.lang.Boolean.TYPE,
            )
        } catch (e: Exception) {
            null
        }

        fun tuneSocket(s: Socket) {
            try {
                s.sendBufferSize = SEND_BUFFER_BYTES
                s.tcpNoDelay = true
            } catch (e: Exception) {
                Log.w(TAG, "cannot tune socket: $e")
            }
        }
    }
}
