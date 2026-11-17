package app.webmirror.android.stream

/** Receives everything the hub produces. Today the only sink is a WebSocket viewer. */
interface StreamSink {
    fun onHello(json: String)
    fun onPacket(p: Packet)

    /** Called about once a second: a chance to send a liveness message. */
    fun onPing() {}

    /** What the viewer said in its `hello` (`h264`), or null while it has not said hello yet. */
    val h264Capable: Boolean? get() = null
}
