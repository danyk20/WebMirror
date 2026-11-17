package app.webmirror.android

import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHubTest {

    private class Recorder : StreamSink {
        val log = ArrayList<String>()
        override fun onHello(json: String) { log.add("hello") }
        override fun onPacket(p: Packet) { log.add("packet${p.type}") }
    }

    @Test
    fun helloJsonDescribesTheStream() {
        val hub = StreamHub()
        hub.update { it.copy(width = 576, height = 1280, fps = 30, audio = "bluetooth") }
        assertEquals(
            """{"t":"hello","v":1,"mode":"jpeg","codec":"","width":576,"height":1280,"fps":30,"audio":"bluetooth"}""",
            hub.helloJson(),
        )
    }

    @Test
    fun changeBroadcastsHelloButSameValueDoesNot() {
        val hub = StreamHub()
        val r = Recorder()
        hub.register(r)
        hub.update { it.copy(width = 8, height = 8) }
        hub.update { it.copy(width = 8, height = 8) }
        assertEquals(listOf("hello"), r.log)
    }

    @Test
    fun newViewerGetsHelloThenLastJpeg() {
        val hub = StreamHub()
        hub.update { it.copy(width = 8, height = 8) }
        hub.onPacket(Packet(Packet.TYPE_JPEG, 0, 1, ByteArray(1)))
        hub.onPacket(Packet(Packet.TYPE_JPEG, 0, 2, ByteArray(1)))
        val late = Recorder()
        hub.greet(late)
        assertEquals(listOf("hello", "packet2"), late.log)
    }

    @Test
    fun sizeChangeDropsCachedJpeg() {
        val hub = StreamHub()
        hub.update { it.copy(width = 8, height = 8) }
        hub.onPacket(Packet(Packet.TYPE_JPEG, 0, 1, ByteArray(1)))
        hub.update { it.copy(width = 16, height = 8) }
        val late = Recorder()
        hub.greet(late)
        assertTrue(late.log == listOf("hello"))
    }
}
