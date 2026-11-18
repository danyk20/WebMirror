package app.webmirror.android

import app.webmirror.android.stream.AudioBacklog
import app.webmirror.android.stream.AudioPlan
import app.webmirror.android.stream.AudioPolicy
import app.webmirror.android.stream.AudioProfile
import app.webmirror.android.stream.AudioPtsMapper
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import app.webmirror.android.stream.ViewerAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlanTest {

    private val q = AudioProfile.QUALITY
    private val f = AudioProfile.FAST

    @Test
    fun opusOnlyWhenEveryAudioViewerAndThePhoneCanAndTheProfileAllowsIt() {
        assertEquals("opus", AudioPolicy.codec(q, true, listOf(ViewerAudio(true), ViewerAudio(true))))
        assertEquals("opus", AudioPolicy.codec(q, true, emptyList()))
        assertEquals("pcm", AudioPolicy.codec(q, true, listOf(ViewerAudio(true), ViewerAudio(false))))
        assertEquals("pcm", AudioPolicy.codec(q, false, listOf(ViewerAudio(true))))
        // Fast = 10 ms frames, which the Android Opus encoder cannot make: always PCM.
        assertEquals("pcm", AudioPolicy.codec(f, true, listOf(ViewerAudio(true))))
    }

    @Test
    fun captureRunsOnlyInBrowserModeWithAViewerWhoWantsSound() {
        val idle = listOf(ViewerAudio(true, wants = false))
        val wants = listOf(ViewerAudio(true, wants = false), ViewerAudio(false, wants = true))
        assertEquals(AudioPlan(false, "opus", q), AudioPolicy.plan(true, q, true, idle))
        assertEquals(AudioPlan(true, "pcm", q), AudioPolicy.plan(true, q, true, wants))
        assertEquals(AudioPlan(false, "", q), AudioPolicy.plan(false, q, true, wants))
    }

    @Test
    fun profileSizes() {
        assertEquals(480, f.framesPerChunk)
        assertEquals(1920, f.chunkBytes)
        assertEquals(960, q.framesPerChunk)
        assertEquals(3840, q.chunkBytes)
        assertEquals(AudioProfile.QUALITY, AudioProfile.fromKey(null))
        assertEquals(AudioProfile.FAST, AudioProfile.fromKey("fast"))
    }

    @Test
    fun exactTimestampsGiveEvenlySpacedPts() {
        val m = AudioPtsMapper(48_000)
        // frame 0 captured at 1 s on the phone clock
        m.measure(0, 1_000_000_000L, true)
        assertEquals(1_000_000L, m.ptsUs(0))
        assertEquals(1_020_000L, m.ptsUs(960))
        // consistent later measurement changes nothing
        m.measure(48_000, 2_000_000_000L, true)
        assertEquals(1_000_000L + 20_000L * 50, m.ptsUs(48_000))
    }

    @Test
    fun smallJitterIsSmoothedLargeJumpIsFollowed() {
        val m = AudioPtsMapper(48_000)
        m.measure(0, 0, true)
        m.measure(48_000, 1_005_000_000L, true) // 5 ms late
        val p = m.ptsUs(48_000)
        assertTrue(p in 1_000_000L..1_001_000L) // moved by about 10% of 5 ms
        m.measure(96_000, 2_100_000_000L, true) // 100 ms: a real jump
        assertEquals(2_100_000L, m.ptsUs(96_000))
    }

    @Test
    fun fallbackMeasurementsTrustTheEarliestEstimate() {
        val m = AudioPtsMapper(48_000)
        m.measure(960, 20_000_000L + 8_000_000L, false) // first read returned 8 ms late
        for (i in 2..40) m.measure(960L * i, 20_000_000L * i + 1_000_000L, false) // later ones only 1 ms late
        val err = m.ptsUs(960L * 40) - (20_000L * 40 + 1000)
        assertTrue("error $err us", Math.abs(err) < 600)
    }

    private fun pkt(i: Int) = Packet(Packet.TYPE_AUDIO, 0, i * 20_000L, ByteArray(3840))

    @Test
    fun backlogDropsTheOldestBeyondTheCap() {
        val b = AudioBacklog(300)
        for (i in 0 until 15) b.offer(pkt(i), 20)
        assertEquals(15, b.size)
        assertEquals(0L, b.dropped)
        b.offer(pkt(15), 20)
        b.offer(pkt(16), 20)
        assertEquals(15, b.size)
        assertEquals(2L, b.dropped)
        assertEquals(2 * 20_000L, b.poll()!!.ptsUs) // the two oldest are gone
        assertEquals(280, b.queuedMs)
        b.clear()
        assertNull(b.poll())
        assertEquals(0, b.queuedMs)
    }

    @Test
    fun backlogCountsDurationsNotPackets() {
        val b = AudioBacklog(300)
        for (i in 0 until 31) b.offer(pkt(i), 10) // 310 ms of 10 ms packets
        assertEquals(30, b.size)
        assertEquals(1L, b.dropped)
    }

    // ---- hub: hello and plan ----

    private class Viewer(var audio: ViewerAudio?) : StreamSink {
        val audioPackets = ArrayList<Packet>()
        val hellos = ArrayList<String>()
        override val audioCaps: ViewerAudio? get() = audio
        override fun onHello(json: String) { hellos.add(json) }
        override fun onPacket(p: Packet) {}
        override fun onAudio(p: Packet, durationMs: Int) { audioPackets.add(p) }
    }

    @Test
    fun helloCarriesTheAudioObjectAndTheChosenFormat() {
        val hub = StreamHub()
        hub.setAudioEncoders(true)
        hub.update { it.copy(audio = "bluetooth") }
        assertTrue(hub.helloJson().contains(""""audio":{"mode":"bluetooth"}"""))
        hub.update { it.copy(audio = "browser") }
        assertTrue(hub.helloJson(), hub.helloJson().contains(""""audio":{"mode":"browser","codec":"opus","sampleRate":48000,"channels":2,"frameMs":20,"profile":"quality"}"""))
        hub.update { it.copy(audioProfile = "fast") }
        assertTrue(hub.helloJson(), hub.helloJson().contains(""""audio":{"mode":"browser","codec":"pcm","sampleRate":48000,"channels":2,"frameMs":10,"profile":"fast"}"""))
    }

    @Test
    fun aViewerWithoutOpusSwitchesEveryoneToPcmAndCaptureFollowsWantedSound() {
        val hub = StreamHub()
        hub.setAudioEncoders(true)
        val plans = ArrayList<AudioPlan>()
        hub.audioRequester = { plans.add(it) }
        hub.update { it.copy(audio = "browser") }
        val a = Viewer(ViewerAudio(opus = true))
        hub.register(a)
        assertTrue(hub.helloJson().contains(""""codec":"opus""""))
        assertTrue(plans.none { it.active })
        a.audio = ViewerAudio(opus = true, wants = true)
        hub.audioChanged()
        assertEquals(AudioPlan(true, "opus", q), plans.last())
        val b = Viewer(ViewerAudio(opus = false))
        hub.register(b)
        assertTrue(hub.helloJson().contains(""""codec":"pcm""""))
        assertEquals("pcm", plans.last().codec)
        assertTrue(a.hellos.last().contains(""""codec":"pcm""""))
        // Audio goes only to viewers that want it.
        hub.onAudio(pkt(1), 20)
        assertEquals(1, a.audioPackets.size)
        assertEquals(0, b.audioPackets.size)
        // Nobody wants sound any more: capture stops.
        a.audio = ViewerAudio(opus = true, wants = false)
        hub.audioChanged()
        assertFalse(plans.last().active)
        // Leaving viewers are replanned too.
        hub.unregister(b)
        assertEquals("opus", hub.audioPlan().codec)
    }

    @Test
    fun switchingTheModeOffStopsCapture() {
        val hub = StreamHub()
        val plans = ArrayList<AudioPlan>()
        hub.audioRequester = { plans.add(it) }
        hub.update { it.copy(audio = "browser") }
        hub.register(Viewer(ViewerAudio(true, wants = true)))
        assertTrue(plans.last().active)
        hub.update { it.copy(audio = "off") }
        assertFalse(plans.last().active)
        assertTrue(hub.helloJson().contains(""""audio":{"mode":"off"}"""))
    }
}
