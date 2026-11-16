package app.webmirror.android.server

import android.util.Log
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * One connected viewer. All outgoing messages (binary packets and text control messages) go through
 * one bounded queue drained by exactly one writer thread, so frames never interleave and a slow
 * viewer cannot block the producer.
 */
class ClientSession(
    handshake: NanoHTTPD.IHTTPSession,
    private val hub: StreamHub,
) : NanoWSD.WebSocket(handshake), StreamSink {

    private sealed interface Outgoing {
        class Bin(val packet: Packet) : Outgoing
        class Text(val text: String) : Outgoing
        data object Close : Outgoing
    }

    private val queue = LinkedBlockingDeque<Outgoing>(MAX_QUEUE)
    private var writer: Thread? = null

    @Volatile
    private var needsKeyframe = false

    @Volatile
    private var stopped = false

    @Volatile
    var viewerH264 = false
        private set

    // ---- StreamSink (called from producer threads) ----

    override fun onHello(json: String) {
        enqueueText(json)
    }

    override fun onPacket(p: Packet) {
        if (stopped) return
        if (p.type == Packet.TYPE_H264 && needsKeyframe) {
            if (!p.isKeyframe) return
            needsKeyframe = false
        }
        if (queue.offerLast(Outgoing.Bin(p))) return

        // Queue full: drop every queued packet (keep text messages), wait for the next keyframe.
        queue.removeIf { it is Outgoing.Bin }
        hub.requestKeyframe()
        if (p.type == Packet.TYPE_H264 && !p.isKeyframe) {
            needsKeyframe = true
        } else {
            queue.offerLast(Outgoing.Bin(p))
        }
    }

    private fun enqueueText(text: String) {
        if (stopped) return
        if (!queue.offerLast(Outgoing.Text(text))) {
            queue.removeIf { it is Outgoing.Bin }
            queue.offerLast(Outgoing.Text(text))
        }
    }

    // ---- NanoWSD.WebSocket callbacks ----

    override fun onOpen() {
        val t = Thread({ writeLoop() }, "ws-writer")
        t.isDaemon = true
        writer = t
        t.start()
        hub.register(this)
    }

    override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        Log.i(TAG, "viewer closed: $code $reason remote=$initiatedByRemote")
        shutdown()
    }

    override fun onException(exception: IOException?) {
        Log.i(TAG, "viewer exception: $exception")
        shutdown()
    }

    override fun onPong(pong: WebSocketFrame?) {}

    override fun onMessage(message: WebSocketFrame?) {
        try {
            if (message == null || message.opCode != WebSocketFrame.OpCode.Text) return
            val text = message.textPayload
            val obj = JSONObject(text)
            when (obj.optString("t")) {
                "hello" -> handleHello(obj)
                "kf" -> hub.requestKeyframe()
                "stats" -> hub.reportStats(text)
                "probe" -> {
                    val results = obj.optJSONObject("results")
                    hub.reportProbe(results?.toString(2) ?: text)
                }
                "blast" -> blast()
                else -> Unit // unknown types (including future "input") are ignored
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad message ignored: $e")
        }
    }

    private fun handleHello(obj: JSONObject) {
        if (obj.optInt("v", -1) != StreamHub.PROTOCOL_VERSION) {
            enqueueText("""{"t":"denied","reason":"version"}""")
            enqueueClose()
            return
        }
        viewerH264 = obj.optBoolean("h264", false)
        enqueueText(hub.helloJson())
    }

    private fun enqueueClose() {
        if (!queue.offerLast(Outgoing.Close)) {
            queue.clear()
            queue.offerLast(Outgoing.Close)
        }
    }

    /** M0.9 throughput test: 100 dummy packets of 50 000 bytes payload = 5 MB, then a text marker. */
    private fun blast() {
        val dummy = Packet(Packet.TYPE_BLAST, 0, 0, ByteArray(BLAST_PACKET_BYTES))
        for (i in 0 until BLAST_PACKETS) {
            // Blocking offer: the blast must not be dropped, but never wait forever for a dead writer.
            if (stopped || !queue.offerLast(Outgoing.Bin(dummy), 5, TimeUnit.SECONDS)) return
        }
        queue.offerLast(
            Outgoing.Text("""{"t":"blastDone","packets":$BLAST_PACKETS,"bytes":${BLAST_PACKETS * BLAST_PACKET_BYTES}}"""),
            5, TimeUnit.SECONDS,
        )
    }

    // ---- The single writer ----

    private fun writeLoop() {
        try {
            while (!stopped) {
                when (val item = queue.take()) {
                    is Outgoing.Bin -> send(item.packet.toBytes())
                    is Outgoing.Text -> send(item.text)
                    Outgoing.Close -> {
                        close(CloseCode.NormalClosure, "denied", false)
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            // shutdown
        } catch (e: IOException) {
            Log.i(TAG, "write failed: $e")
        } catch (e: Exception) {
            Log.w(TAG, "writer error: $e")
        }
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        hub.unregister(this)
        queue.clear()
        writer?.interrupt()
    }

    companion object {
        private const val TAG = "WebMirror"
        const val MAX_QUEUE = 120
        const val BLAST_PACKETS = 100
        const val BLAST_PACKET_BYTES = 50_000
    }
}
