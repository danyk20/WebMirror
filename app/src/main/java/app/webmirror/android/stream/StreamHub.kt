package app.webmirror.android.stream

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out point between encoders (later) and sinks. Minimal in M0: session list, broadcast, hello.
 * Mode logic and GOP cache come in M2/M4.
 */
class StreamHub {

    interface Events {
        fun onViewerCountChanged(count: Int)
        fun onStats(json: String)
        fun onProbe(json: String)
    }

    private val sinks = CopyOnWriteArrayList<StreamSink>()

    @Volatile
    var events: Events? = null

    /** Set by the encoder owner (M2). Called when a viewer lost sync or its queue overflowed. */
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

    fun broadcast(packet: Packet) {
        for (s in sinks) s.onPacket(packet)
    }

    fun broadcastHello() {
        val json = helloJson()
        for (s in sinks) s.onHello(json)
    }

    /** Phone -> page `hello` (plan 2.1). M0 has no stream yet, so the description is a placeholder. */
    fun helloJson(): String =
        """{"t":"hello","v":$PROTOCOL_VERSION,"mode":"jpeg","codec":"","width":0,"height":0,"fps":0,"audio":"off"}"""

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
    }
}
