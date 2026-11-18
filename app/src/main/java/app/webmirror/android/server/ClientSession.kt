package app.webmirror.android.server

import android.util.Log
import app.webmirror.android.input.InputEvent
import app.webmirror.android.input.RateLimiter
import app.webmirror.android.stream.AudioBacklog
import app.webmirror.android.stream.BitrateController
import app.webmirror.android.stream.ClockSync
import app.webmirror.android.stream.CodecChooser
import app.webmirror.android.stream.Expirable
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.Pairing
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import app.webmirror.android.stream.VideoOutbox
import app.webmirror.android.stream.ViewBox
import app.webmirror.android.stream.ViewerAudio
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
    /** The plain TCP socket under any TLS layer; closing it never blocks and unblocks a stuck writer. */
    private val rawSocket: java.net.Socket? = null,
) : NanoWSD.WebSocket(handshake), StreamSink, Expirable {

    private sealed interface Outgoing {
        class Bin(val packet: Packet) : Outgoing
        class Text(val text: String) : Outgoing

        /** Liveness ping; its text (with a fresh timestamp) is made at the moment it is sent. */
        data object Ping : Outgoing
        data object Close : Outgoing
    }

    /** Guards [control] and [outbox]; the writer thread waits on it. */
    private val mon = Object()

    /** Text messages (and blast dummies, which are not video): FIFO, hard safety limit [MAX_QUEUE]. */
    private val control = ArrayDeque<Outgoing>()

    /** Audio: sent before video, never flow-controlled or acknowledged, capped at ~300 ms (oldest dropped). Guarded by [mon]. */
    private val audioQ = AudioBacklog()

    /** Video: flow-controlled, never piles up (see [VideoOutbox]). */
    private val outbox = VideoOutbox(hub.config.maxInFlight, hub.config.dropAfterMs)
    private var writer: Thread? = null
    private var lastKeyframeAskMs = 0L

    @Volatile
    private var stopped = false

    @Volatile
    private var lastSeenNanos = System.nanoTime()

    @Volatile
    private var caps: CodecChooser.ViewerCaps? = null

    override val viewerCaps: CodecChooser.ViewerCaps? get() = caps

    @Volatile
    private var audio: ViewerAudio? = null

    override val audioCaps: ViewerAudio? get() = audio

    @Volatile
    private var viewPx: ViewBox? = null

    override val viewBox: ViewBox? get() = viewPx

    private val id = nextId.incrementAndGet()

    override val viewerId: Int get() = id

    /**
     * Pairing gate (plan 2.1.4). A session is registered in the hub, and so counts as a viewer for the codec choice,
     * mode, bitrate control, window size and remote-control ownership, only once it is [admitted]: right at open when
     * pairing is off, else after a hello with a valid token or a correct PIN. Until then it is invisible to the hub
     * and ignores everything but `hello` and `pair`.
     */
    @Volatile
    private var admitted = false

    @Volatile
    private var pairedViaPairing = false

    override val paired: Boolean get() = pairedViaPairing

    /** The page's hello while it waits for the PIN; processed like a normal hello once the PIN was right. */
    private var pendingHello: JSONObject? = null

    private val openedAtMs = nowMs()

    /** At most 120 `input` messages per second from this viewer (up/cancel are never refused). */
    private val inputLimiter = RateLimiter()

    /** Clock offset to this page, from ping/pong round trips (plan 2.1). */
    private val clock = ClockSync()

    /** From the page's last `stats`: cumulative skipped frames, decoder queue size and when it arrived. */
    @Volatile
    private var pageSkipped = 0L

    @Volatile
    private var pageDecodeQueue = 0

    @Volatile
    private var pageStatsAtMs = 0L

    /** No congestion signal before this time: a new viewer (or a new hello) is a reset, not congestion. */
    @Volatile
    private var graceUntilMs = nowMs() + BitrateController.GRACE_MS

    override fun congestion(nowMs: Long): BitrateController.ViewerSignal? {
        if (stopped || nowMs < graceUntilMs) return null
        synchronized(mon) {
            val fresh = nowMs - pageStatsAtMs < STATS_FRESH_MS
            return BitrateController.ViewerSignal(
                id, outbox.oldestPendingWaitMs(nowMs), outbox.dropped, pageSkipped,
                if (fresh) pageDecodeQueue else 0,
            )
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    // ---- StreamSink (called from producer threads) ----

    override fun onHello(json: String) {
        if (stopped) return
        synchronized(mon) {
            // A new stream description invalidates everything waiting; H.264 resumes at a keyframe.
            outbox.reset(StreamHub.isVideoMode(MODE_REGEX.find(json)?.groupValues?.get(1).orEmpty()))
            graceUntilMs = nowMs() + BitrateController.GRACE_MS
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

    override fun onAudio(p: Packet, durationMs: Int) {
        if (stopped) return
        synchronized(mon) {
            audioQ.offer(p, durationMs)
            mon.notifyAll()
        }
    }

    override fun onPing() {
        if (stopped) return
        synchronized(mon) {
            if (control.none { it === Outgoing.Ping }) {
                addControl(Outgoing.Ping)
                mon.notifyAll()
            }
        }
    }

    override fun onNotice(json: String) {
        if (stopped) return
        synchronized(mon) {
            addControl(Outgoing.Text(json))
            mon.notifyAll()
        }
    }

    override fun onText(json: String) {
        if (stopped) return
        synchronized(mon) {
            addControl(Outgoing.Text(json))
            mon.notifyAll()
        }
    }

    override fun disconnect() {
        Log.i(TAG, "viewer disconnected by the server (automatic test or socket change)")
        shutdown()
        forceCloseSocket()
    }

    /**
     * Caller holds [mon]. `ts` = phone clock now (µs), answered by the page with `pong`; `off` = page clock minus
     * phone clock (µs) from the best round trip of the last 30 s; `rtt` = the last round trip in ms. The other
     * numbers let the page show how much the server holds back for it.
     */
    private fun pingText(): String {
        val nowUs = System.nanoTime() / 1000
        val best = clock.best(nowMs())
        val rtt = clock.lastRttUs
        return buildString {
            append("""{"t":"ping","ts":""").append(nowUs)
            if (best != null) append(""","off":""").append(best.offsetUs)
            if (rtt >= 0) append(""","rtt":""").append(rtt / 1000).append('.').append((rtt % 1000) / 100)
            append(""","inFlight":""").append(outbox.inFlightCount)
            append(""","pending":""").append(outbox.pendingCount)
            append(""","kfw":""").append(outbox.resetDropped)
            append(""","target":"""").append(hub.targetText).append(""""}""")
        }
    }

    private fun handlePong(obj: JSONObject) {
        val ts = obj.optLong("ts", -1)
        val pageTs = obj.optDouble("pageTs", Double.NaN)
        if (ts < 0) return
        clock.add(nowMs(), ts, System.nanoTime() / 1000, pageTs)
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
        if (!hub.pairing.required) admit(false)
    }

    /** Registers this session as a viewer. Pairing turned on meanwhile and no token: not allowed in. */
    private fun admit(viaPairing: Boolean) {
        if (stopped) return
        pairedViaPairing = viaPairing
        admitted = true
        pendingHello = null
        hub.register(this)
        if (!viaPairing && hub.pairing.required) disconnect() // pairing was switched on while this session was being admitted
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
            if (!admitted) {
                // Not paired (yet): nothing but the pairing conversation is looked at.
                when (obj.optString("t")) {
                    "hello" -> handleHello(obj)
                    "pair" -> handlePair(obj)
                }
                return
            }
            when (obj.optString("t")) {
                "hello" -> handleHello(obj)
                "pong" -> handlePong(obj)
                "kf" -> hub.requestKeyframe()
                "view" -> handleView(obj)
                "ack" -> handleAck(obj.optLong("pts", -1))
                "audio" -> handleAudio(obj.optBoolean("on", false))
                "stats" -> {
                    // Add what only the server knows, so one log line tells the whole story.
                    pageSkipped = obj.optLong("skipped", 0) - obj.optLong("replaced", 0) // vsync replacements are not congestion
                    pageDecodeQueue = obj.optInt("decodeQueue", 0)
                    pageStatsAtMs = nowMs()
                    obj.put("vid", id)
                    clock.best(nowMs())?.let { obj.put("clockRttMs", it.rttUs / 1000.0) }
                    synchronized(mon) {
                        obj.put("target", hub.targetText)
                        obj.put("inFlight", outbox.inFlightCount)
                        obj.put("srvPending", outbox.pendingCount)
                        obj.put("srvDropped", outbox.dropped)
                        obj.put("srvResetDropped", outbox.resetDropped)
                        obj.put("srvAudioDropped", audioQ.dropped)
                    }
                    hub.reportStats(obj.toString())
                }
                "probe" -> {
                    val results = obj.optJSONObject("results")
                    hub.reportProbe(results?.toString(2) ?: text)
                }
                "blast" -> blast()
                "control" -> onText(hub.controlRequest(this, obj.optBoolean("on", false)).toJson())
                "input" -> handleInput(obj)
                else -> Unit // unknown types are ignored
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad message ignored: $e")
        }
    }

    /**
     * `{t:"input", e, id, x, y, ts, w?, h?}` (remote control). Everything is validated and rate limited here; the hub
     * drops it unless this viewer owns the control. Never throws.
     */
    private fun handleInput(obj: JSONObject) {
        val e = obj.optString("e")
        val essential = e == "up" || e == "cancel"
        if (!inputLimiter.allow(nowMs(), essential)) return
        if (!hub.control.canInput(id)) return
        val ev = InputEvent.validate(
            e, obj.optInt("id", -1), obj.optDouble("x", Double.NaN), obj.optDouble("y", Double.NaN),
            obj.optDouble("ts", Double.NaN), obj.optInt("w", 0), obj.optInt("h", 0),
        ) ?: return
        hub.controlInput(id, ev)
    }

    /** `{t:"audio", on}`: this page's sound is on (it can play) or off. Capture runs only while somebody wants sound. */
    private fun handleAudio(on: Boolean) {
        val a = audio ?: return
        audio = a.copy(wants = on)
        if (!on) synchronized(mon) { audioQ.clear() }
        hub.audioChanged()
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
        if (!admitted) {
            val pairing = hub.pairing
            if (!pairing.required) {
                admit(false)
            } else if (pairing.isValidToken(obj.optString("token", ""))) {
                admit(true)
            } else {
                pendingHello = obj
                val left = pairing.lockedForMs()
                onText(if (left > 0) pairJson("locked", left) else pairJson("required", 0))
                return
            }
            if (!admitted) return
        }
        completeHello(obj)
    }

    /** `{t:"pair", pin}` from a session that waits for the PIN. */
    private fun handlePair(obj: JSONObject) {
        val hello = pendingHello ?: return
        val pairing = hub.pairing
        if (!pairing.required) { // switched off while the panel was open
            admit(false)
            if (admitted) completeHello(hello)
            return
        }
        when (val r = pairing.attempt(obj.optString("pin", ""))) {
            is Pairing.Attempt.Ok -> {
                admit(true)
                if (!admitted) return
                onText("""{"t":"paired","token":"${r.token}"}""")
                completeHello(hello)
            }
            is Pairing.Attempt.Wrong -> {
                Log.i(TAG, "wrong pairing PIN${if (r.lockedForMs > 0) ", locked for ${r.lockedForMs / 1000} s" else ""}")
                onText(if (r.lockedForMs > 0) pairJson("locked", r.lockedForMs) else pairJson("wrong", 0))
            }
            is Pairing.Attempt.Locked -> onText(pairJson("locked", r.retryInMs))
        }
    }

    private fun pairJson(reason: String, retryInMs: Long): String =
        if (retryInMs > 0) """{"t":"pair","reason":"$reason","pinLength":${hub.pairing.pinLength},"retryInMs":$retryInMs}"""
        else """{"t":"pair","reason":"$reason","pinLength":${hub.pairing.pinLength}}"""

    /** The normal processing of a hello, for a session that is admitted. */
    private fun completeHello(obj: JSONObject) {
        if (obj.optInt("v", -1) != StreamHub.PROTOCOL_VERSION) {
            synchronized(mon) {
                addControl(Outgoing.Text("""{"t":"denied","reason":"version"}"""))
                addControl(Outgoing.Close)
                mon.notifyAll()
            }
            return
        }
        caps = parseCaps(obj)
        // `audio:{opus}` is present only when the page can play audio at all.
        audio = obj.optJSONObject("audio")?.let { ViewerAudio(it.optBoolean("opus", false), audio?.wants == true) }
        hub.greet(this)
    }

    /** `codecs: {h264:{sw,hw}, ...}` of the page's hello; pages that only send the old `h264` / `hevc` booleans are still understood. */
    private fun parseCaps(obj: JSONObject): CodecChooser.ViewerCaps {
        val o = obj.optJSONObject("codecs") ?: return CodecChooser.ViewerCaps.legacy(obj.optBoolean("h264", false), obj.optBoolean("hevc", false))
        val m = HashMap<String, CodecChooser.Support>()
        for (c in CodecChooser.VIDEO_CODECS) {
            val e = o.optJSONObject(c) ?: continue
            val hw = e.optBoolean("hw", false)
            m[c] = CodecChooser.Support(sw = e.optBoolean("sw", false) || hw, hw = hw)
        }
        return CodecChooser.ViewerCaps(m)
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
                if (!admitted && nowMs() - openedAtMs > UNPAIRED_TIMEOUT_MS) {
                    Log.i(TAG, "viewer did not pair in time, closing")
                    shutdown()
                    forceCloseSocket()
                    break
                }
                var item: Outgoing? = null
                var askKeyframe = false
                synchronized(mon) {
                    val now = nowMs()
                    if (control.isNotEmpty()) {
                        item = control.removeFirst()
                        if (item === Outgoing.Ping) item = Outgoing.Text(pingText())
                        mon.notifyAll() // room for a blocked blast
                    } else {
                        val a = audioQ.poll() // audio goes before the next video frame
                        if (a != null) {
                            item = Outgoing.Bin(a)
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
                }
                if (askKeyframe) hub.requestKeyframe()
                when (val it = item) {
                    null -> Unit
                    is Outgoing.Bin -> {
                        val bytes = it.packet.toBytes()
                        send(bytes)
                        if (it.packet.type != Packet.TYPE_BLAST && it.packet.type != Packet.TYPE_AUDIO) hub.metrics.bytesSent.addAndGet(bytes.size.toLong())
                    }
                    Outgoing.Ping -> Unit
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

    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Never blocks. Closes the raw TCP socket (below TLS) with SO_LINGER 0, so the close is an RST and does not wait
     * for a dead peer; closing the SSL streams would try to write close_notify and could block forever. A writer
     * stuck in send fails as soon as the raw socket is closed. Runs on a short-lived thread as a second safeguard.
     */
    private fun forceCloseSocket() {
        if (!closing.compareAndSet(false, true)) return
        AsyncClose.run("ws-closer-$id") {
            val raw = rawSocket
            if (raw != null) {
                try { raw.setSoLinger(true, 0) } catch (e: Exception) { /* already closed */ }
                raw.close()
            } else {
                handshakeRequest.inputStream.close()
            }
        }
    }

    /** A writer stuck in a blocking send on a dead peer ignores interrupt; if it is still alive after a grace period, cut the socket. */
    private fun watchStuckWriter(w: Thread) {
        AsyncClose.run("ws-watchdog-$id") {
            w.join(WRITER_GRACE_MS)
            if (w.isAlive) {
                Log.w(TAG, "writer stuck after shutdown, closing the raw socket")
                forceCloseSocket()
            }
        }
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        hub.unregister(this)
        synchronized(mon) {
            control.clear()
            audioQ.clear()
            outbox.reset(false)
            mon.notifyAll()
        }
        writer?.let {
            it.interrupt()
            watchStuckWriter(it)
        }
    }

    companion object {
        private const val TAG = "WebMirror"
        /** Hard safety limit of queued text messages. Video never waits in a queue (see [VideoOutbox]). */
        const val MAX_QUEUE = 120

        private val MODE_REGEX = Regex("\"mode\":\"(\\w+)\"")
        private const val WAIT_MS = 200L

        /** A session that has not passed pairing is closed after this long (plan 2.1.4). */
        const val UNPAIRED_TIMEOUT_MS = 120_000L
        private const val WRITER_GRACE_MS = 3000L
        private const val STATS_FRESH_MS = 3000L
        private val nextId = java.util.concurrent.atomic.AtomicInteger()
        const val BLAST_PACKETS = 100
        const val BLAST_PACKET_BYTES = 50_000
    }
}
