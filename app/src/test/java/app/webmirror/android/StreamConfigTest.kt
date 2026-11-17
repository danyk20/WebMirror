package app.webmirror.android

import app.webmirror.android.encode.EncoderOptions
import app.webmirror.android.stream.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** org.json is not available on the JVM test classpath, so the round trip goes through the map form. */
class StreamConfigTest {

    @Test
    fun defaultsMatchTheSpec() {
        val c = StreamConfig()
        assertTrue(c.tcpNoDelay)
        assertEquals(64, c.sendBufferKb)
        assertTrue(c.lowLatencyEncoder)
        assertEquals("baseline", c.profile)
        assertEquals("cbr", c.bitrateMode)
        assertFalse(c.intraRefresh)
        assertEquals(2, c.keyframeIntervalS)
        assertFalse(c.fitToViewer)
        assertEquals(6, c.maxInFlight)
        assertEquals(150, c.dropAfterMs)
        assertEquals(40, c.jitterBufferMs)
        assertEquals("h264", c.codec)
        assertEquals(2, c.skipLateFrames)
        assertTrue(c.adaptiveBitrate)
        assertEquals(800, c.minBitrateKbps)
        assertEquals("immediate", c.pacing)
    }

    @Test
    fun mapRoundTrip() {
        val c = StreamConfig(
            tcpNoDelay = false, sendBufferKb = 0, lowLatencyEncoder = false, profile = "high", codec = "hevc", bitrateMode = "vbr",
            intraRefresh = true, keyframeIntervalS = 5, fitToViewer = true, maxInFlight = 2, dropAfterMs = 400,
            jitterBufferMs = 10, skipLateFrames = 0, adaptiveBitrate = false, minBitrateKbps = 1500, pacing = "vsync",
        )
        assertEquals(c, StreamConfig.fromMap(c.toMap()))
        assertEquals(StreamConfig(), StreamConfig.fromMap(StreamConfig().toMap()))
        assertEquals(16, c.toMap().size)
    }

    @Test
    fun everyFieldHasALabel() {
        assertEquals(StreamConfig().toMap().keys.toList(), StreamConfig.LABELS.keys.toList())
    }

    @Test
    fun invalidOrMissingValuesFallBackAndNumbersAreClamped() {
        val c = StreamConfig.fromMap(
            mapOf("profile" to "bogus", "bitrateMode" to 3, "maxInFlight" to 5000, "jitterBufferMs" to -5, "tcpNoDelay" to "yes"),
        )
        assertEquals("baseline", c.profile)
        assertEquals("cbr", c.bitrateMode)
        assertEquals(200, c.maxInFlight)
        assertEquals(0, c.jitterBufferMs)
        assertTrue(c.tcpNoDelay)
        assertEquals("immediate", StreamConfig.fromMap(mapOf("pacing" to "raf")).pacing)
        assertEquals(20000, StreamConfig.fromMap(mapOf("minBitrateKbps" to 99999)).minBitrateKbps)
        assertEquals(100, StreamConfig.fromMap(mapOf("minBitrateKbps" to 1)).minBitrateKbps)
        assertEquals(StreamConfig(), StreamConfig.fromMap(emptyMap()))
        assertEquals(StreamConfig(), StreamConfig.fromJson(null as String?))
        assertEquals(StreamConfig(), StreamConfig.fromJson("   "))
    }

    @Test
    fun diffSaysWhichPartsChanged() {
        val a = StreamConfig()
        assertFalse(a.diff(a.copy()).any)
        assertEquals(StreamConfig.Diff(true, false, false, false, false), a.diff(a.copy(profile = "main")))
        assertEquals(StreamConfig.Diff(true, false, false, false, false), a.diff(a.copy(keyframeIntervalS = 4)))
        assertEquals(StreamConfig.Diff(true, false, false, false, false), a.diff(a.copy(lowLatencyEncoder = false)))
        assertEquals(StreamConfig.Diff(false, true, false, false, false), a.diff(a.copy(fitToViewer = true)))
        assertEquals(StreamConfig.Diff(false, false, true, false, false), a.diff(a.copy(sendBufferKb = 0)))
        assertEquals(StreamConfig.Diff(false, false, true, false, false), a.diff(a.copy(tcpNoDelay = false)))
        assertEquals(StreamConfig.Diff(false, false, false, true, false), a.diff(a.copy(maxInFlight = 3)))
        assertEquals(StreamConfig.Diff(false, false, false, true, false), a.diff(a.copy(dropAfterMs = 50)))
        assertEquals(StreamConfig.Diff(false, false, false, false, true), a.diff(a.copy(jitterBufferMs = 120)))
        assertEquals(StreamConfig.Diff(false, false, false, false, true), a.diff(a.copy(skipLateFrames = 0)))
        assertEquals(StreamConfig.Diff(false, false, false, false, false, true), a.diff(a.copy(adaptiveBitrate = false)))
        assertEquals(StreamConfig.Diff(false, false, false, false, false, true), a.diff(a.copy(minBitrateKbps = 500)))
        assertEquals(StreamConfig.Diff(false, false, false, false, true), a.diff(a.copy(pacing = "vsync")))
        val many = a.diff(a.copy(profile = "high", maxInFlight = 2, jitterBufferMs = 0))
        assertTrue(many.encoder && many.outbox && many.page && !many.socket && !many.fit)
    }

    @Test
    fun idIsStableAndChangesWithAnyField() {
        val a = StreamConfig()
        assertEquals(a.id, StreamConfig().id)
        assertEquals(8, a.id.length)
        assertNotEquals(a.id, a.copy(dropAfterMs = 151).id)
        assertNotEquals(a.id, a.copy(profile = "auto").id)
    }

    @Test
    fun pageJsonCarriesOnlyThePageFieldsAndTheId() {
        val c = StreamConfig(jitterBufferMs = 120, skipLateFrames = 3)
        val j = c.pageJson()
        assertTrue(j.contains(""""id":"${c.id}""""))
        assertTrue(j.contains(""""jitterBufferMs":120"""))
        assertTrue(j.contains(""""skipLateFrames":3"""))
        assertTrue(j.contains("""pacing":"immediate""""))
        assertTrue(StreamConfig(pacing = "vsync").pageJson().contains("""pacing":"vsync""""))
        assertFalse(j.contains("profile"))
    }

    // ---- encoder option choices ----

    @Test
    fun baselinePrefersConstrainedBaselineThenBaseline() {
        val both = setOf(EncoderOptions.AVC_PROFILE_BASELINE, EncoderOptions.AVC_PROFILE_CONSTRAINED_BASELINE, EncoderOptions.AVC_PROFILE_HIGH)
        assertEquals(EncoderOptions.AVC_PROFILE_CONSTRAINED_BASELINE, EncoderOptions.pickProfile("baseline", both).profile)
        assertEquals(
            EncoderOptions.AVC_PROFILE_BASELINE,
            EncoderOptions.pickProfile("baseline", setOf(EncoderOptions.AVC_PROFILE_BASELINE)).profile,
        )
    }

    @Test
    fun unlistedProfileFallsBackToAuto() {
        val onlyHigh = setOf(EncoderOptions.AVC_PROFILE_HIGH)
        assertNull(EncoderOptions.pickProfile("baseline", onlyHigh).profile)
        assertNull(EncoderOptions.pickProfile("main", onlyHigh).profile)
        assertTrue(EncoderOptions.pickProfile("main", onlyHigh).description.contains("not listed"))
        assertEquals(EncoderOptions.AVC_PROFILE_HIGH, EncoderOptions.pickProfile("high", onlyHigh).profile)
        assertNull(EncoderOptions.pickProfile("auto", onlyHigh).profile)
    }

    @Test
    fun levelIsTheSmallestListedThatFits() {
        val listed = setOf(0x200, 0x400, 0x800, 0x1000, 0x8000)   // 3.1, 3.2, 4, 4.1, 5.1
        // 576x1280 @30: 36*80=2880 MBs, 86400 MB/s -> level 3.1 (3600 / 108000)
        assertEquals(0x200, EncoderOptions.pickLevel(576, 1280, 30, listed))
        // 864x1920 @15: 54*120=6480 MBs > 5120 -> level 4 (8192 / 245760), 97200 MB/s ok
        assertEquals(0x800, EncoderOptions.pickLevel(864, 1920, 15, listed))
        assertNull(EncoderOptions.pickLevel(576, 1280, 30, emptySet()))
    }
}
