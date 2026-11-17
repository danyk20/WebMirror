package app.webmirror.android.test

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Saved reports: `filesDir/tests/<yyyyMMdd-HHmmss>-<runName>.json` and `.csv`. */
object TestReports {

    /** One saved report in the list. */
    data class Info(val stem: String, val runName: String, val startedAtMs: Long, val completed: Boolean, val best: String?, val bestP95: Double?, val phases: Int)

    /** A report opened for the detail view. */
    data class Loaded(
        val stem: String,
        val runName: String,
        val startedAtMs: Long,
        val completed: Boolean,
        val preset: String,
        val device: String,
        val phaseSeconds: Int,
        val formula: String,
        val phases: List<PhaseResult>,
    )

    fun dir(context: Context): File = File(context.filesDir, "tests").apply { mkdirs() }

    fun jsonFile(context: Context, stem: String) = File(dir(context), "$stem.json")

    fun csvFile(context: Context, stem: String) = File(dir(context), "$stem.csv")

    /** Writes the JSON and CSV files; returns the file stem. */
    fun save(context: Context, run: TestRun): String {
        val stem = ReportFormat.fileStem(run.startedAtMs, run.runName)
        jsonFile(context, stem).writeText(ReportFormat.toJson(run))
        csvFile(context, stem).writeText(ReportFormat.toCsv(run))
        return stem
    }

    /** Newest first. */
    fun list(context: Context): List<Info> =
        (dir(context).listFiles { f -> f.name.endsWith(".json") }?.toList().orEmpty())
            .sortedByDescending { it.name }
            .mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    val phases = o.optJSONArray("phases")
                    var best: String? = null
                    var bestP95: Double? = null
                    if (phases != null) {
                        for (i in 0 until phases.length()) {
                            val p = phases.getJSONObject(i)
                            if (p.optInt("rank", 0) == 1) {
                                best = p.optString("name")
                                bestP95 = if (p.isNull("e2eP95")) null else p.optDouble("e2eP95")
                            }
                        }
                    }
                    Info(f.name.removeSuffix(".json"), o.optString("runName"), o.optLong("startedAtMs"), o.optBoolean("completed", true), best, bestP95, phases?.length() ?: 0)
                } catch (e: Exception) {
                    null
                }
            }

    fun load(context: Context, stem: String): Loaded? = try {
        val o = JSONObject(jsonFile(context, stem).readText())
        val arr = o.getJSONArray("phases")
        fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
        val phases = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            PhaseResult(
                name = p.optString("name"), description = p.optString("description"), cfgId = p.optString("cfgId"),
                config = p.optString("config"), status = p.optString("status"), note = p.optString("note"),
                attempts = p.optInt("attempts", 1), expectedFps = p.optInt("expectedFps"),
                page = PageSummary(
                    samples = p.optInt("samples"), fpsAvg = p.dbl("fpsAvg"), fpsMin = p.dbl("fpsMin"), e2eAvg = p.dbl("e2eAvg"),
                    e2eP95 = p.dbl("e2eP95"), e2eMax = p.dbl("e2eMax"), e2eFrames = p.optInt("e2eFrames"), rttAvg = p.dbl("rttAvg"),
                    lateMs = p.dbl("lateMs"), netLateMs = p.dbl("netLateMs"), skipped = p.optLong("skipped"), replaced = p.optLong("replaced"),
                    dropped = p.optLong("dropped"), decoderResets = p.optLong("decoderResets"), reconnects = p.optLong("reconnects"),
                    srvDropped = p.optLong("srvDropped"),
                ),
                phone = PhoneSide(
                    mbps = p.optDouble("mbps", 0.0), keyframes = p.optLong("keyframes"),
                    targetMin = if (p.isNull("targetMin")) null else p.optInt("targetMin"), targetAvg = p.dbl("targetAvg"),
                    encoderState = p.optString("encoderState"), stream = p.optString("stream"),
                    mode = p.optString("mode"), codec = p.optString("codec"), encoder = p.optString("encoder"),
                    hardware = if (p.isNull("hardware") || !p.has("hardware")) null else p.optBoolean("hardware"),
                    encodeFps = p.optDouble("encodeFps", 0.0),
                ),
                score = p.dbl("score"), rank = if (p.isNull("rank")) null else p.optInt("rank"),
            )
        }
        Loaded(
            stem, o.optString("runName"), o.optLong("startedAtMs"), o.optBoolean("completed", true), o.optString("preset"),
            o.optString("device"), o.optInt("phaseSeconds"), o.optString("scoreFormula"), phases,
        )
    } catch (e: Exception) {
        null
    }
}
