package app.webmirror.android.stream

/** Receives everything the hub produces. Today the only sink is a WebSocket viewer. */
interface StreamSink {
    fun onHello(json: String)
    fun onPacket(p: Packet)

    /** Called about once a second: a chance to send a liveness message. */
    fun onPing() {}

    /** A short message for the viewer's screen (plan 2.1 `notice`); [json] is the whole text message. */
    fun onNotice(json: String) {}

    /** Closes this viewer's connection (the page reconnects by itself). */
    fun disconnect() {}

    /** What the viewer said in its `hello` (decode support per codec), or null while it has not said hello yet. */
    val viewerCaps: CodecChooser.ViewerCaps? get() = null

    /** This viewer's congestion signals for the bitrate controller, or null if it has none. */
    fun congestion(nowMs: Long): BitrateController.ViewerSignal? = null

    /** The viewer's window in physical pixels (from its `view` message), or null while unknown. */
    val viewBox: ViewBox? get() = null
}

/** A viewer's window size in physical pixels (CSS size times devicePixelRatio). */
data class ViewBox(val width: Int, val height: Int)
