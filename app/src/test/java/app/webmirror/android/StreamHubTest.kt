package app.webmirror.android

import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHubTest {

    private class Recorder(var h264: Boolean? = null, var hevc: Boolean = false) : StreamSink {
        val log = ArrayList<String>()
        override val h264Capable: Boolean? get() = h264
        override val hevcCapable: Boolean get() = hevc
        override fun onHello(json: String) { log.add("hello") }
        override fun onPacket(p: Packet) { log.add("packet${p.type}") }
    }

    @Test
    fun helloJsonDescribesTheStream() {
        val hub = StreamHub()
        hub.update { it.copy(width = 576, height = 1280, fps = 30, audio = "bluetooth") }
        assertEquals(
            """{"t":"hello","v":1,"mode":"jpeg","codec":"","width":576,"height":1280,"fps":30,"audio":"bluetooth","cfg":${StreamConfig().pageJson()}}""",
            hub.helloJson(),
        )
    }

    @Test
    fun applyConfigSendsPageFieldsInHelloAndKeepsThemAfterReset() {
        val hub = StreamHub()
        hub.applyConfig(StreamConfig(jitterBufferMs = 200, skipLateFrames = 0))
        assertTrue(hub.helloJson().contains(""""jitterBufferMs":200"""))
        assertTrue(hub.helloJson().contains(""""skipLateFrames":0"""))
        assertEquals(200, hub.config.jitterBufferMs)
        hub.reset()
        assertTrue(hub.helloJson().contains(""""jitterBufferMs":200"""))
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

    private fun h264(key: Boolean, pts: Long = 0) =
        Packet(Packet.TYPE_H264, if (key) Packet.FLAG_KEYFRAME else 0, pts, ByteArray(1))

    @Test
    fun pausedHelloKeepsSizeAndHasModePaused() {
        val hub = StreamHub()
        hub.update { it.copy(width = 576, height = 1280, fps = 30) }
        hub.update { it.copy(mode = StreamHub.MODE_PAUSED) }
        assertTrue(hub.helloJson().contains(""""mode":"paused""""))
        assertTrue(hub.helloJson().contains(""""width":576"""))
    }

    @Test
    fun gopCacheStartsAtLastKeyframeAndIsSentToNewViewers() {
        val hub = StreamHub()
        hub.onVideoConfig(StreamHub.MODE_H264, "avc1.42C01F", 8, 8, 30)
        hub.onPacket(h264(false))                // before any keyframe: ignored by the cache
        hub.onPacket(h264(true))
        hub.onPacket(h264(false))
        hub.onPacket(h264(true))
        hub.onPacket(h264(false))
        hub.onPacket(h264(false))
        val late = Recorder()
        hub.greet(late)
        assertEquals(listOf("hello", "packet1", "packet1", "packet1"), late.log)
        assertTrue(hub.helloJson().contains("avc1.42C01F"))
    }

    @Test
    fun oversizedGopRequestsAKeyframe() {
        var now = 0L
        val hub = StreamHub { now }
        var requests = 0
        hub.keyframeRequester = { requests++ }
        hub.onPacket(h264(true))
        for (i in 1 until StreamHub.MAX_GOP_PACKETS) hub.onPacket(h264(false))
        assertEquals(0, requests)
        hub.onPacket(h264(false))
        assertEquals(1, requests)
    }

    @Test
    fun keyframeRequestsAreLimitedToOnePer500Ms() {
        var now = 1_000L
        val hub = StreamHub { now }
        var requests = 0
        hub.keyframeRequester = { requests++ }
        hub.requestKeyframe()
        now += 499
        hub.requestKeyframe()
        assertEquals(1, requests)
        now += 1
        hub.requestKeyframe()
        assertEquals(2, requests)
    }

    @Test
    fun modeIsH264OnlyIfEveryViewerCanAndSwitchesLive() {
        val hub = StreamHub()
        val wanted = ArrayList<String>()
        hub.modeRequester = { wanted.add(it) }
        val a = Recorder()
        val b = Recorder()
        hub.register(a)
        hub.register(b)
        a.h264 = true
        hub.greet(a)                       // b has not said hello yet: unknown, not counted
        assertEquals(listOf("h264"), wanted)
        b.h264 = false
        hub.greet(b)                       // any h264:false forces jpeg
        assertEquals(listOf("h264", "jpeg"), wanted)
        hub.unregister(b)                  // the JPEG-only viewer left: back to h264
        assertEquals(listOf("h264", "jpeg", "h264"), wanted)
        hub.unregister(a)                  // nobody left: no needless switch
        assertEquals(3, wanted.size)
    }

    @Test
    fun forceJpegWinsOverCapableViewers() {
        val hub = StreamHub()
        val wanted = ArrayList<String>()
        hub.modeRequester = { wanted.add(it) }
        val a = Recorder(true)
        hub.register(a)
        hub.greet(a)
        hub.forceJpeg = true
        hub.forceJpeg = false
        assertEquals(listOf("h264", "jpeg", "h264"), wanted)
    }

    @Test
    fun hevcOnlyIfConfiguredAndEveryViewerCanDecodeIt() {
        val hub = StreamHub()
        val wanted = ArrayList<String>()
        hub.modeRequester = { wanted.add(it) }
        val a = Recorder()
        val b = Recorder()
        hub.register(a)
        hub.register(b)
        a.h264 = true
        a.hevc = true
        hub.greet(a)                                   // codec still h264 in the config
        assertEquals(listOf("h264"), wanted)
        hub.applyConfig(StreamConfig(codec = "hevc"))  // the only viewer can: switch
        assertEquals(listOf("h264", "hevc"), wanted)
        b.h264 = true
        b.hevc = false
        hub.greet(b)                                   // an H.264-only viewer joins: back to h264
        assertEquals(listOf("h264", "hevc", "h264"), wanted)
        hub.unregister(b)
        assertEquals(listOf("h264", "hevc", "h264", "hevc"), wanted)
        hub.applyConfig(StreamConfig(codec = "h264"))
        assertEquals("h264", wanted.last())
        hub.forceJpeg = true
        assertEquals("jpeg", wanted.last())
    }

    @Test
    fun hevcKeyframesFillTheGopCacheLikeH264() {
        val hub = StreamHub()
        hub.onVideoConfig(StreamHub.MODE_HEVC, "hev1.1.6.L93.B0", 576, 1280, 30)
        hub.onPacket(h264(true))
        hub.onPacket(h264(false))
        val r = Recorder()
        hub.register(r)
        hub.greet(r)
        assertEquals(listOf("hello", "packet1", "packet1"), r.log)
        assertTrue(hub.helloJson().contains(""""mode":"hevc","codec":"hev1.1.6.L93.B0""""))
    }
}
