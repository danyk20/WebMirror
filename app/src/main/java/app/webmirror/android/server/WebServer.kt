package app.webmirror.android.server

import android.content.res.AssetManager
import android.util.Log
import app.webmirror.android.stream.StreamHub
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * HTTP server for the web client (assets/web) plus the /ws WebSocket endpoint.
 * Start with `start(0, true)`: timeout 0 so idle WebSockets are not killed by the 5 s read timeout.
 */
class WebServer(
    port: Int,
    private val assets: AssetManager,
    private val hub: StreamHub,
) : NanoWSD(port) {

    /** NanoWSD.openWebSocket cannot refuse a request, so non-/ws upgrade requests are rejected here. */
    override fun serve(session: IHTTPSession): Response {
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
        return super.createClientHandler(finalAccept, inputStream)
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
