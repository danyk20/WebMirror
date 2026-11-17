package app.webmirror.android.server

import android.util.Log
import app.webmirror.android.stream.Expirable
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import app.webmirror.android.stream.VideoOutbox
import app.webmirror.android.stream.ViewBox
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONObject
import java.io.IOException

/**
 * One connected viewer. Exactly one writer thread sends everything, so frames never interleave and a slow
 * viewer cannot block the producer. Text messages go through a small FIFO; video goes through a
 * [VideoOutbox] (ack-based flow control, newest frame wins) so it can never build up latency.
 */
class ClientSession(
    handshake: NanoHTTPD.IHTTPSession,
    private val hub: StreamHub,
) : NanoWSD.WebSocket(handshake), StreamSink, Expirable {

    private sealed interface Outgoing {
        class Bin(val packet: Packet) : Outgoing
        class Text(val text: String) : Outgoing
        data object Close : Outgoing
    }

    /** Guards [control] and [outbox]; the writer thread waits on it. */
    private val mon = Object()

    /** Text messages (and blast dummies, which are not video): FIFO, hard safety limit [MAX_QUEUE]. */
    private val control = ArrayDeque<Outgoing>()

    /** Video: flow-controlled, never piles up (see [VideoOutbox]). */
    private val outbox = VideoOutbox(hub.config.maxInFlight, hub.config.dropAfterMs)
    private var writer: Thread? = null
    private var lastKeyframeAskMs = 0L

    @Volatile
    private var stopped = false

    @Volatile
    private var lastSeenNanos = System.nanoTime()

    @Volatile
    private var viewerH264: Boolean? = null

    override val h264Capable: Boolean? get() = viewerH264

    @Volatile
    private var viewPx: ViewBox? = null

    override val viewBox: ViewBox? get() = viewPx

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    // ---- StreamSink (called from producer threads) ----

    override fun onHello(json: String) {
        if (stopped) return
        synchronized(mon) {
            // A new stream description invalidates everything waiting; H.264 resumes at a keyframe.
            outbox.reset(json.contains("\"mode\":\"h264\""))
            addControl(Outgoing.Text(json))
            mon.notifyAll()
        }
        hub.requestKeyframe()
    }

    override fun onPacket(p: Packet) {
        if (stopped) return
        val wantKeyframe: Boolean
        synchronized(mon) {
            val cfg = hub.config
            outbox.maxInFlight = cfg.maxInFlight
            outbox.dropAfterMs = cfg.dropAfterMs
            wantKeyframe = outbox.offer(p, nowMs())
            mon.notifyAll()
        }
        if (wantKeyframe) hub.requestKeyframe()
    }

    override fun onPing() {
        if (stopped) return
        synchronized(mon) {
            if (control.none { it is Outgoing.Text && it.text.startsWith(PING_PREFIX) }) {
                // The numbers let the page show how much the server holds back for it.
                addControl(Outgoing.Text("""{"t":"ping","inFlight":${outbox.inFlightCount},"pending":${outbox.pendingCount}}"""))
                mon.notifyAll()
            }
        }
    }

    /** Caller holds [mon]. Text messages are tiny; when the hard limit is hit the oldest one goes. */
    private fun addControl(item: Outgoing) {
        if (control.size >= MAX_QUEUE) control.removeFirst()
        control.addLast(item)
    }

    private fun handleAck(ptsUs: Long) {
        if (ptsUs < 0) return
        synchronized(mon) {
            outbox.ack(ptsUs, nowMs())
            mon.notifyAll()
        }
    }

    // ---- Expirable (idle sweep) ----

    override fun closeIfIdle(maxIdleMs: Long): Boolean {
        if (stopped) return false
        val idleMs = (System.nanoTime() - lastSeenNanos) / 1_000_000
        if (idleMs <= maxIdleMs) return false
        Log.i(TAG, "viewer idle for $idleMs ms, closing")
        shutdown()
        forceCloseSocket()
        return true
    }

    // ---- NanoWSD.WebSocket callbacks ----

    /** Called by NanoWSD for every received frame of any opcode (incl. ping/pong); used as liveness mark. */
    override fun debugFrameReceived(frame: WebSocketFrame?) {
        lastSeenNanos = System.nanoTime()
    }

    override fun onOpen() {
        lastSeenNanos = System.nanoTime()
        val t = Thread({ writeLoop() }, "ws-writer")
        t.isDaemon = true
        writer = t
        t.start()
        hub.register(this)
    }

    override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        // A closed browser tab arrives as InternalServerError "Handler terminated without closing the
        // connection" from NanoWSD; that is a normal disconnect, not an error.
        if (code == CloseCode.InternalServerError && reason?.startsWith("Handler terminated") == true) {
            Log.i(TAG, "viewer disconnected")
        } else {
            Log.i(TAG, "viewer disconnected ($code${if (reason.isNullOrEmpty()) "" else ": $reason"}, remote=$initiatedByRemote)")
        }
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
                "view" -> handleView(obj)
                "ack" -> handleAck(obj.optLong("pts", -1))
                "stats" -> {
                    // Add what only the server knows, so one log line tells the whole story.
                    synchronized(mon) {
                        obj.put("inFlight", outbox.inFlightCount)
                        obj.put("srvPending", outbox.pendingCount)
                        obj.put("srvDropped", outbox.dropped)
                    }
                    hub.reportStats(obj.toString())
                }
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

    /** `{t:"view", w, h, dpr}`: the page's window in CSS pixels and the device pixel ratio. */
    private fun handleView(obj: JSONObject) {
        val dpr = obj.optDouble("dpr", 1.0).let { if (it.isNaN() || it < 0.5 || it > 8.0) 1.0 else it }
        val w = obj.optDouble("w", 0.0) * dpr
        val h = obj.optDouble("h", 0.0) * dpr
        if (w < 16 || h < 16 || w > 16384 || h > 16384) return
        viewPx = ViewBox(w.toInt(), h.toInt())
        hub.viewChanged()
    }

    private fun handleHello(obj: JSONObject) {
        if (obj.optInt("v", -1) != StreamHub.PROTOCOL_VERSION) {
            synchronized(mon) {
                addControl(Outgoing.Text("""{"t":"denied","reason":"version"}"""))
                addControl(Outgoing.Close)
                mon.notifyAll()
            }
            return
        }
        viewerH264 = obj.optBoolean("h264", false)
        hub.greet(this)
    }

    /** M0.9 throughput test: 100 dummy packets of 50 000 bytes payload = 5 MB, then a text marker. */
    private fun blast() {
        val dummy = Packet(Packet.TYPE_BLAST, 0, 0, ByteArray(BLAST_PACKET_BYTES))
        for (i in 0 until BLAST_PACKETS) {
            if (!offerBlocking(Outgoing.Bin(dummy))) return
        }
        offerBlocking(
            Outgoing.Text("""{"t":"blastDone","packets":$BLAST_PACKETS,"bytes":${BLAST_PACKETS * BLAST_PACKET_BYTES}}"""),
        )
    }

    /** Waits (at most 5 s) for room in the control queue; the blast must not be dropped. */
    private fun offerBlocking(item: Outgoing): Boolean {
        val deadline = nowMs() + 5000
        synchronized(mon) {
            while (control.size >= MAX_QUEUE) {
                val left = deadline - nowMs()
                if (stopped || left <= 0) return false
                mon.wait(left)
            }
            if (stopped) return false
            control.addLast(item)
            mon.notifyAll()
            return true
        }
    }

    // ---- The single writer ----

    private fun writeLoop() {
        try {
            while (!stopped) {
                var item: Outgoing? = null
                var askKeyframe = false
                synchronized(mon) {
                    val now = nowMs()
                    if (control.isNotEmpty()) {
                        item = control.removeFirst()
                        mon.notifyAll() // room for a blocked blast
                    } else {
                        if (outbox.checkStall(now)) askKeyframe = true
                        val p = outbox.next(now)
                        if (p != null) {
                            item = Outgoing.Bin(p)
                        } else {
                            // The hub rate-limits requests, so asking again while waiting is cheap and
                            // covers a request that was swallowed (static screen, nothing else arrives).
                            if (outbox.needsKeyframe) askKeyframe = true
                            if (!askKeyframe) mon.wait(WAIT_MS)
                        }
                    }
                }
                if (askKeyframe) hub.requestKeyframe()
                when (val it = item) {
                    null -> Unit
                    is Outgoing.Bin -> send(it.packet.toBytes())
                    is Outgoing.Text -> send(it.text)
                    Outgoing.Close -> {
                        close(CloseCode.NormalClosure, "denied", false)
                        break
                    }
                }
                if (askKeyframe && item == null) synchronized(mon) { mon.wait(WAIT_MS) }
            }
        } catch (e: InterruptedException) {
            // shutdown
        } catch (e: IOException) {
            Log.i(TAG, "write failed: $e")
            failWriter()
        } catch (e: Exception) {
            Log.w(TAG, "writer error: $e")
            failWriter()
        }
    }

    /** Writer died: unregister the viewer and close the socket so the reader thread ends too. */
    private fun failWriter() {
        shutdown()
        forceCloseSocket()
    }

    /** Closing the handshake input stream closes the underlying socket (best effort). */
    private fun forceCloseSocket() {
        try {
            handshakeRequest.inputStream.close()
        } catch (e: Exception) {
            // already closed
        }
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        hub.unregister(this)
        synchronized(mon) {
            control.clear()
            outbox.reset(false)
            mon.notifyAll()
        }
        writer?.interrupt()
    }

    companion object {
        private const val TAG = "WebMirror"
        /** Hard safety limit of queued text messages. Video never waits in a queue (see [VideoOutbox]). */
        const val MAX_QUEUE = 120

        private const val WAIT_MS = 200L
        private const val PING_PREFIX = """{"t":"ping""""
        const val BLAST_PACKETS = 100
        const val BLAST_PACKET_BYTES = 50_000
    }
}
