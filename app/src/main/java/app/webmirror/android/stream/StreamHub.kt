package app.webmirror.android.stream

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out point between encoders and sinks. Holds the stream description (`hello`) and the picture a new
 * viewer needs to see something immediately. Adding H.264 later means adding a mode value, a codec string
 * and a GOP cache next to [lastJpeg]; nothing here has to be rewritten.
 */
class StreamHub {

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

    @Volatile
    var events: Events? = null

    /** Set by the encoder owner. Called when a viewer lost sync or its queue overflowed. */
    @Volatile
    var keyframeRequester: (() -> Unit)? = null

    val viewerCount: Int get() = sinks.size

    fun register(sink: StreamSink) {
        sinks.add(sink)
        events?.onViewerCountChanged(sinks.size)
    }

    fun unregister(sink: StreamSink) {
        if (sinks.remove(sink)) events?.onViewerCountChanged(sinks.size)
    }

    /** Called by encoders for every packet. JPEG packets are remembered for viewers that join later. */
    fun onPacket(packet: Packet) {
        synchronized(lock) {
            if (packet.type == Packet.TYPE_JPEG) lastJpeg = packet
            for (s in sinks) s.onPacket(packet)
        }
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
            if (new.width != old.width || new.height != old.height || new.mode != old.mode) lastJpeg = null
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
        }
    }

    fun helloJson(): String = synchronized(lock) { info.toJson() }

    /** Called when a viewer's own `hello` arrived: stream description first, then the last picture. */
    fun greet(sink: StreamSink) {
        synchronized(lock) {
            sink.onHello(info.toJson())
            val jpeg = lastJpeg
            if (jpeg != null) sink.onPacket(jpeg)
        }
    }

    /** Closes and unregisters every sink that has been silent for longer than [maxIdleMs]. */
    fun sweepIdle(maxIdleMs: Long) {
        for (s in sinks) {
            if (s is Expirable) s.closeIfIdle(maxIdleMs)
        }
    }

    fun requestKeyframe() {
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

        /** Capture has stopped but the server is up; the page keeps its last picture and shows a message. */
        const val MODE_PAUSED = "paused"
    }
}
