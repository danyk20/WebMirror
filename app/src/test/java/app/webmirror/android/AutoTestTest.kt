package app.webmirror.android

import app.webmirror.android.stream.StreamConfig
import app.webmirror.android.test.Aggregate
import app.webmirror.android.test.PageSummary
import app.webmirror.android.test.PhaseAccumulator
import app.webmirror.android.test.PhaseResult
import app.webmirror.android.test.PhaseStatus
import app.webmirror.android.test.PhoneSide
import app.webmirror.android.test.ReportFormat
import app.webmirror.android.test.Scoring
import app.webmirror.android.test.StatsSample
import app.webmirror.android.test.TestOptions
import app.webmirror.android.test.TestPlan
import app.webmirror.android.test.TestRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTestTest {

    // ---- plan ----

    @Test
    fun planHasTheNinePhasesInOrderAndUniqueNames() {
        val plan = TestPlan.phases()
        assertEquals(
            listOf(
                "default", "jitter-40ms", "jitter-30ms", "jitter-10ms", "no-adaptive-bitrate", "codec-h264", "codec-hevc", "codec-vp9", "codec-av1",
            ),
            plan.map { it.name },
        )
        assertEquals(9, plan.size)
        assertEquals(plan.size, plan.map { it.config.id }.toSet().size)
    }

    @Test
    fun everyPhaseChangesOnlyItsOwnFieldsFromTheDefaults() {
        val base = StreamConfig().toMap()
        for (p in TestPlan.phases()) {
            val changed = p.config.toMap().filter { (k, v) -> base[k] != v }.keys
            when (p.name) {
                "default" -> assertTrue(changed.isEmpty())
                "codec-vp9", "codec-av1" -> assertEquals(setOf("codec", "allowSoftwareEncoder"), changed)
                else -> assertEquals("${p.name}: $changed", 1, changed.size)
            }
            assertFalse(p.forceJpeg)
        }
        val byName = TestPlan.phases().associateBy { it.name }
        assertEquals("vp9", byName.getValue("codec-vp9").config.codec)
        assertTrue(byName.getValue("codec-av1").config.allowSoftwareEncoder)
        assertFalse(byName.getValue("codec-hevc").config.allowSoftwareEncoder)
        assertEquals("auto", byName.getValue("default").config.codec)
    }

    @Test
    fun phaseConfigsSurviveStoringWithoutChangingTheirId() {
        for (p in TestPlan.phases()) assertEquals(p.name, p.config.id, StreamConfig.fromMap(p.config.toMap()).id)
    }

    @Test
    fun optionsAndNames() {
        assertEquals(40, TestOptions().measureSeconds)
        assertEquals(20, TestOptions(phaseSeconds = 90, quick = true).measureSeconds)
        assertEquals(5, TestOptions(phaseSeconds = 1).measureSeconds)
        assertEquals("my-run-_2", TestOptions.sanitizeName(" my run/_2 "))
        assertEquals("test", TestOptions.sanitizeName("///"))
    }

    // ---- aggregation ----

    @Test
    fun avgMinMaxAndPercentile() {
        val v = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(25.0, Aggregate.avg(v)!!, 1e-9)
        assertEquals(10.0, Aggregate.min(v)!!, 1e-9)
        assertEquals(40.0, Aggregate.max(v)!!, 1e-9)
        assertNull(Aggregate.avg(emptyList()))
        assertNull(Aggregate.p95(emptyList()))
        // nearest rank: 20 values 1..20 -> 95th percentile is the 19th
        assertEquals(19.0, Aggregate.p95((1..20).map { it.toDouble() })!!, 1e-9)
        assertEquals(95.0, Aggregate.p95((1..100).map { it.toDouble() }.shuffled())!!, 1e-9)
        assertEquals(7.0, Aggregate.p95(listOf(7.0))!!, 1e-9)
        assertEquals(20.0, Aggregate.percentile(v, 50.0)!!, 1e-9)
    }

    @Test
    fun parsesTheAdaptiveTargetAndTheStreamFps() {
        assertEquals(3200, Aggregate.parseTarget("3200/4000 kbps"))
        assertEquals(40, Aggregate.parseTarget("40/50 quality fixed"))
        assertNull(Aggregate.parseTarget(""))
        assertNull(Aggregate.parseTarget(null))
        assertEquals(30, Aggregate.parseStreamFps("584x1280 @30 fps (h264)"))
        assertTrue(Aggregate.isJpegStream("584x1280 @20 fps (jpeg)"))
        assertFalse(Aggregate.isJpegStream("584x1280 @30 fps (h264)"))
    }

    private fun sample(vid: Int = 1, cfg: String = "aaaa", fps: Double = 30.0, e2e: List<Int> = listOf(100, 200), skipped: Long = 0, reconnects: Long = 0, srvDropped: Long = 0) =
        StatsSample(vid = vid, cfgId = cfg, fps = fps, e2eRaw = e2e, rttMs = 10.0, lateMs = 5.0, netLateMs = 2.0, skipped = skipped, reconnects = reconnects, srvDropped = srvDropped)

    @Test
    fun attributionByConfigIdAndSettleWindow() {
        // settle ends at 5000, phase ends at 45000, stats every 2000 ms
        val acc = PhaseAccumulator("aaaa", 5_000, 45_000)
        assertFalse(acc.offer(3_000, sample()))              // during settle
        assertFalse(acc.offer(6_000, sample()))              // arrived after settle, but covers time before it
        assertFalse(acc.offer(8_000, sample(cfg = "bbbb")))  // another configuration
        assertTrue(acc.offer(7_000, sample()))               // covers 5000..7000, exactly after the settle period
        assertTrue(acc.offer(9_000, sample()))
        assertFalse(acc.offer(45_001, sample()))             // after the end
        assertTrue(acc.offer(45_000, sample()))
        assertEquals(3, acc.sampleCount)
    }

    @Test
    fun onlyTheFirstViewerCounts() {
        val acc = PhaseAccumulator("aaaa", 0, 100_000)
        assertTrue(acc.offer(2_000, sample(vid = 4)))
        assertFalse(acc.offer(3_000, sample(vid = 5)))
        assertTrue(acc.offer(4_000, sample(vid = 4)))
        assertEquals(4, acc.vid)
    }

    @Test
    fun summaryAggregatesTheAcceptedSamples() {
        val acc = PhaseAccumulator("aaaa", 0, 100_000)
        acc.offer(2_000, sample(fps = 30.0, e2e = (1..10).map { it * 10 }, skipped = 5, reconnects = 1, srvDropped = 2))
        acc.offer(4_000, sample(fps = 20.0, e2e = (11..20).map { it * 10 }, skipped = 9, reconnects = 1, srvDropped = 7))
        val s = acc.summary()
        assertEquals(2, s.samples)
        assertEquals(25.0, s.fpsAvg!!, 1e-9)
        assertEquals(20.0, s.fpsMin!!, 1e-9)
        assertEquals(20, s.e2eFrames)
        assertEquals(105.0, s.e2eAvg!!, 1e-9)
        assertEquals(190.0, s.e2eP95!!, 1e-9)
        assertEquals(200.0, s.e2eMax!!, 1e-9)
        assertEquals(10.0, s.rttAvg!!, 1e-9)
        assertEquals(4, s.skipped)
        assertEquals(0, s.reconnects)
        assertEquals(5, s.srvDropped)
    }

    @Test
    fun aCounterThatRestartedIsTakenAsItIs() {
        val acc = PhaseAccumulator("aaaa", 0, 100_000)
        acc.offer(2_000, sample(skipped = 50))
        acc.offer(4_000, sample(skipped = 3))
        assertEquals(3, acc.summary().skipped)
    }

    @Test
    fun emptyAccumulatorHasNoNumbers() {
        val s = PhaseAccumulator("aaaa", 0, 1000).summary()
        assertEquals(0, s.samples)
        assertNull(s.fpsAvg)
        assertNull(s.e2eP95)
    }

    // ---- score and ranking ----

    private fun result(
        name: String, p95: Double?, fps: Double = 30.0, expected: Int = 30, reconnects: Long = 0, resets: Long = 0,
        status: String = PhaseStatus.OK, samples: Int = 10,
    ) = PhaseResult(
        name = name, description = "", cfgId = "x", config = "", status = status, note = "", attempts = 1, expectedFps = expected,
        page = PageSummary(samples, fps, fps, p95, p95, p95, 100, 10.0, 5.0, 2.0, 0, 0, 0, resets, reconnects, 0),
        phone = PhoneSide(4.0, 3, 3000, 3500.0, "enc", "stream"),
    )

    @Test
    fun scoreIsP95PlusPenalties() {
        assertEquals(300.0, Scoring.score(result("a", 300.0)), 1e-9)
        // 80 % of 30 fps = 24; at 12 fps the shortfall is half -> +500
        assertEquals(800.0, Scoring.score(result("b", 300.0, fps = 12.0)), 1e-9)
        assertEquals(300.0, Scoring.score(result("c", 300.0, fps = 24.0)), 1e-9) // exactly 80 %: no penalty
        assertEquals(300.0 + 2 * 2000, Scoring.score(result("d", 300.0, reconnects = 2)), 1e-9)
        assertEquals(300.0 + 250, Scoring.score(result("e", 300.0, resets = 1)), 1e-9)
        assertEquals(300.0 + 5000, Scoring.score(result("f", 300.0, status = PhaseStatus.DISCONNECTED)), 1e-9)
        assertEquals(Scoring.NO_DATA_SCORE, Scoring.score(result("g", null, samples = 0)), 1e-9)
        assertTrue(Scoring.score(result("h", null)) > 100_000) // no e2e: after everything with e2e
    }

    @Test
    fun rankingSortsBestFirstAndPutsUnsupportedLastWithoutRank() {
        val ranked = Scoring.rank(
            listOf(
                result("slow", 500.0),
                result("legacy", 900.0),
                result("same-as-default", 100.0, status = PhaseStatus.UNSUPPORTED),
                result("fast-but-choppy", 200.0, fps = 6.0),
                result("best", 250.0),
                result("none", null, samples = 0, status = PhaseStatus.NO_DATA),
            ),
        )
        assertEquals(listOf("best", "slow", "legacy", "fast-but-choppy", "none", "same-as-default"), ranked.map { it.name })
        assertEquals(listOf(1, 2, 3, 4, 5, null), ranked.map { it.rank })
        assertTrue(ranked.all { it.score != null })
    }

    @Test
    fun reportTextContainsTheNumbersAndEscapesNotes() {
        val r = Scoring.rank(listOf(result("best", 120.0), result("legacy", 480.0).copy(note = "a \"quoted\", note")))
        val run = TestRun("par ked", 1_700_000_000_000, TestOptions(runName = "par ked"), "smoother", "Dev", 36, true, r)
        val json = ReportFormat.toJson(run)
        assertTrue(json.contains("\"name\": \"best\""))
        assertTrue(json.contains("\"e2eP95\": 120.00"))
        assertTrue(json.contains("a \\\"quoted\\\", note"))
        assertTrue(json.contains("\"scoreFormula\""))
        val csv = ReportFormat.toCsv(run)
        val lines = csv.lines().filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("rank,phase,score"))
        assertTrue(lines[2].contains("\"a \"\"quoted\"\", note\""))
        assertEquals("Test finished — best: best (e2e P95 120 ms)", ReportFormat.summaryLine(run))
        assertTrue(ReportFormat.fileStem(1_700_000_000_000, "par ked").endsWith("-par-ked"))
    }

    @Test
    fun unsupportedNoteForForcedCodecsThatWereNotUsed() {
        val plan = TestPlan.phases().associateBy { it.name }
        val h264 = "H.264 584x1280 @30 fps\nprofile: constrained baseline"
        assertNull(TestPlan.unsupportedNote(plan.getValue("default"), h264, false, "h264"))
        assertNull(TestPlan.unsupportedNote(plan.getValue("codec-hevc"), "HEVC 584x1280 @30 fps", false, "hevc"))
        assertNotNull(TestPlan.unsupportedNote(plan.getValue("codec-hevc"), h264, false, "h264"))
        assertNotNull(TestPlan.unsupportedNote(plan.getValue("codec-vp9"), h264, false))
        assertNull(TestPlan.unsupportedNote(plan.getValue("codec-vp9"), "VP9 584x1280 @30 fps", false))
        assertTrue(TestPlan.unsupportedNote(plan.getValue("codec-av1"), h264, false, "hevc")!!.contains("hevc was measured"))
        assertNotNull(TestPlan.unsupportedNote(plan.getValue("codec-h264"), h264, true))
    }

    @Test
    fun reportCarriesCodecEncoderAndEncodeFps() {
        val r = result("codec-vp9", 130.0).let {
            it.copy(phone = it.phone.copy(mode = "vp9", codec = "vp09.00.31.08", encoder = "c2.android.vp9.encoder", hardware = false, encodeFps = 24.5))
        }
        val run = TestRun("t", 1_700_000_000_000, TestOptions(runName = "t"), "smoother", "Dev", 36, true, Scoring.rank(listOf(r)))
        val json = ReportFormat.toJson(run)
        assertTrue(json.contains("\"mode\": \"vp9\""))
        assertTrue(json.contains("\"codec\": \"vp09.00.31.08\""))
        assertTrue(json.contains("\"hardware\": false"))
        assertTrue(json.contains("\"encodeFps\": 24.50"))
        val csv = ReportFormat.toCsv(run)
        assertTrue(csv.lines().first { it.startsWith("rank,") }.contains("mode,codec,encoder,hardware,encodeFps"))
        assertTrue(csv.contains("vp9,vp09.00.31.08,c2.android.vp9.encoder,false,24.50"))
    }
}
