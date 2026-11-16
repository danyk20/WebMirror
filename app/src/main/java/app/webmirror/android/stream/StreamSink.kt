package app.webmirror.android.stream

/** Receives everything the hub produces. Today the only sink is a WebSocket viewer. */
interface StreamSink {
    fun onHello(json: String)
    fun onPacket(p: Packet)
}
