package app.webmirror.android.stream

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out point between encoders and sinks. Holds the stream description (`hello`), the picture a new
 * viewer needs to see something immediately (last JPEG, or the H.264 packets since the last keyframe),
 * and decides which video mode the viewers need (plan 2.2).
 *
 * [nowMs] is injectable for tests.
 */
class StreamHub(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    interface Events {
        fun onViewerCountChanged(count: Int)
        fun onStats(json: String)
        fun onProbe(json: String)
        fun onStreamChanged(info: HelloInfo)
    }

    /** What the phone tells the page about the stream (plan 2.1 `hello`). */
    data class HelloInfo(
        val mode: String = MODE_JPEG,
        val codec: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val audio: String = "off",
    ) {
        fun toJson(): String =
            """{"t":"hello","v":$PROTOCOL_VERSION,"mode":"$mode","codec":"$codec","width":$width,"height":$height,"fps":$fps,"audio":"$audio"}"""
    }

    private val sinks = CopyOnWriteArrayList<StreamSink>()

    /** Guards [info] and [lastJpeg] and keeps hello/packet ordering consistent for every sink. */
    private val lock = Any()
    private var info = HelloInfo()
    private var lastJpeg: Packet? = null
    private val gop = ArrayList<Packet>()
    private var lastKeyframeRequestMs: Long? = null
    private var forceJpegFlag = false
    private var lastWantedMode = MODE_JPEG

    @Volatile
    var events: Events? = null

    /** Set by the encoder owner. Called when a viewer lost sync or its queue overflowed. */
    @Volatile
    var keyframeRequester: (() -> Unit)? = null

    /**
     * Set by the capture owner: called (without any hub lock held) when the video mode the viewers need
     * differs from what is being sent. The owner switches the encoder and then reports through
     * [update] / [onVideoConfig].
     */
    @Volatile
    var modeRequester: ((String) -> Unit)? = null

    val viewerCount: Int get() = sinks.size

    var forceJpeg: Boolean
        get() = synchronized(lock) { forceJpegFlag }
        set(value) {
            synchronized(lock) { forceJpegFlag = value }
            recomputeMode()
        }

    /**
     * The mode the viewers need (plan 2.2): h264 only if every viewer that has said hello said `h264:true`
     * and "Force JPEG" is off; any `h264:false` viewer forces jpeg. Without any such viewer the mode that
     * was wanted last stays (no needless encoder switch when the last viewer leaves).
     */
    fun wantedMode(): String = synchronized(lock) { computeWanted() }

    private fun computeWanted(): String {
        if (forceJpegFlag) return MODE_JPEG
        var known = 0
        for (s in sinks) {
            when (s.h264Capable) {
                false -> return MODE_JPEG
                true -> known++
                null -> Unit
            }
        }
        return if (known > 0) MODE_H264 else lastWantedMode
    }

    /** Recomputes the wanted mode and, when it changed, asks the capture owner to switch. */
    fun recomputeMode() {
        val wanted: String
        synchronized(lock) {
            wanted = computeWanted()
            if (wanted == lastWantedMode) return
            lastWantedMode = wanted
        }
        modeRequester?.invoke(wanted)
    }

    fun register(sink: StreamSink) {
        sinks.add(sink)
        events?.onViewerCountChanged(sinks.size)
    }

    fun unregister(sink: StreamSink) {
        if (sinks.remove(sink)) {
            events?.onViewerCountChanged(sinks.size)
            recomputeMode()
        }
    }

    /**
     * Called by encoders for every packet. JPEG packets are remembered for viewers that join later; H.264
     * packets go into the GOP cache (everything since the last keyframe).
     */
    fun onPacket(packet: Packet) {
        var needKeyframe = false
        synchronized(lock) {
            if (packet.type == Packet.TYPE_JPEG) lastJpeg = packet
            if (packet.type == Packet.TYPE_H264) {
                if (packet.isKeyframe) {
                    gop.clear()
                    gop.add(packet)
                } else if (gop.isNotEmpty()) {
                    gop.add(packet) // deltas before the first keyframe are useless to a late viewer
                    if (gop.size > MAX_GOP_PACKETS) needKeyframe = true
                }
            }
            for (s in sinks) s.onPacket(packet)
        }
        if (needKeyframe) requestKeyframe()
    }

    /** The H.264 encoder has its SPS/PPS: announce the stream (mode h264 with its codec string). */
    fun onVideoConfig(codec: String, width: Int, height: Int, fps: Int) {
        update { it.copy(mode = MODE_H264, codec = codec, width = width, height = height, fps = fps) }
    }

    /**
     * Changes the stream description and, if anything changed, sends a new `hello` to every viewer.
     * A change of picture size drops the cached JPEG (it has the old size).
     */
    fun update(change: (HelloInfo) -> HelloInfo) {
        synchronized(lock) {
            val old = info
            val new = change(old)
            if (new == old) return
            info = new
            if (new.width != old.width || new.height != old.height || new.mode != old.mode || new.codec != old.codec) {
                lastJpeg = null
                gop.clear()
            }
            val json = new.toJson()
            for (s in sinks) s.onHello(json)
            events?.onStreamChanged(new)
        }
    }

    /** Forgets everything about the previous stream (used when mirroring stops). */
    fun reset() {
        synchronized(lock) {
            info = HelloInfo()
            lastJpeg = null
            gop.clear()
            lastKeyframeRequestMs = null
            lastWantedMode = MODE_JPEG
        }
    }

    fun helloJson(): String = synchronized(lock) { info.toJson() }

    /**
     * Called when a viewer's own `hello` arrived: stream description first, then the last picture (the
     * GOP cache in h264 mode, so it shows a picture at once even if the screen is static). The viewer's
     * `h264` answer may change the wanted mode.
     */
    fun greet(sink: StreamSink) {
        synchronized(lock) {
            sink.onHello(info.toJson())
            if (info.mode == MODE_H264) {
                for (p in gop) sink.onPacket(p)
            } else {
                val jpeg = lastJpeg
                if (jpeg != null) sink.onPacket(jpeg)
            }
        }
        recomputeMode()
    }

    /** Sends the liveness ping to every viewer (the page reconnects when it hears nothing for 3 s). */
    fun pingAll() {
        for (s in sinks) s.onPing()
    }

    /** Length of one frame of the current stream in ms (33 when unknown). */
    fun frameIntervalMs(): Long {
        val fps = synchronized(lock) { info.fps }
        return if (fps > 0) 1000L / fps else 33L
    }

    /** Closes and unregisters every sink that has been silent for longer than [maxIdleMs]. */
    fun sweepIdle(maxIdleMs: Long) {
        for (s in sinks) {
            if (s is Expirable) s.closeIfIdle(maxIdleMs)
        }
    }

    /** Asks the encoder for a keyframe, at most once per [KEYFRAME_MIN_INTERVAL_MS]. */
    fun requestKeyframe() {
        synchronized(lock) {
            val now = nowMs()
            val last = lastKeyframeRequestMs
            if (last != null && now - last < KEYFRAME_MIN_INTERVAL_MS) return
            lastKeyframeRequestMs = now
        }
        keyframeRequester?.invoke()
    }

    fun reportStats(json: String) {
        events?.onStats(json)
    }

    fun reportProbe(json: String) {
        events?.onProbe(json)
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MODE_JPEG = "jpeg"
        const val MODE_H264 = "h264"
        const val MAX_GOP_PACKETS = 300
        const val KEYFRAME_MIN_INTERVAL_MS = 500L

        /** Capture has stopped but the server is up; the page keeps its last picture and shows a message. */
        const val MODE_PAUSED = "paused"
    }
}
