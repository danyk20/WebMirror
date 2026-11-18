package app.webmirror.android.stream

/**
 * Browser audio (plan 2.4.1): the pure decisions, free of Android classes so they are unit-tested on the JVM.
 */
object AudioSpec {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = CHANNELS * 2 // s16le interleaved

    /** The Android Opus encoder (c2.android.opus.encoder) has no public frame-duration setting: it always makes 20 ms frames. */
    const val OPUS_FRAME_MS = 20

    const val CODEC_OPUS = "opus"
    const val CODEC_PCM = "pcm"
}

/**
 * The two audio profiles (Settings > Audio, only used by the "In the browser" mode), all tunables of the PHONE side in one
 * place. The page has the matching buffer table (`AUDIO_PROFILES` in player.js); keep the two in step.
 *
 * - [FAST]: 10 ms frames. Opus cannot do 10 ms here ([AudioSpec.OPUS_FRAME_MS]), so Fast always sends PCM in 10 ms packets
 *   (no codec lookahead). The Opus values are kept for the day an encoder can do 10 ms; they are not used while
 *   [opusPossible] is false.
 * - [QUALITY] (default): Opus, 20 ms frames, 192 kbit/s, highest encoder complexity.
 */
enum class AudioProfile(
    val key: String,
    val frameMs: Int,
    val opusBitrate: Int,
    /** MediaFormat.KEY_COMPLEXITY (0..10; clamped to the encoder's range). */
    val opusComplexity: Int,
) {
    FAST("fast", 10, 96_000, 1),
    QUALITY("quality", 20, 192_000, 10);

    val framesPerChunk: Int get() = AudioSpec.SAMPLE_RATE / 1000 * frameMs
    val chunkBytes: Int get() = framesPerChunk * AudioSpec.BYTES_PER_FRAME

    /** Opus can be used only when the encoder's fixed frame length equals this profile's chunk length. */
    val opusPossible: Boolean get() = frameMs == AudioSpec.OPUS_FRAME_MS

    companion object {
        fun fromKey(key: String?): AudioProfile = entries.firstOrNull { it.key == key } ?: QUALITY
    }
}

/** What a viewer said about audio: [opus] = its page can decode Opus (`hello.audio.opus`), [wants] = its sound is on (`{t:"audio",on}`). */
data class ViewerAudio(val opus: Boolean, val wants: Boolean = false)

/** What the capture side has to do: capture only while [active]; [codec] ("opus"/"pcm", "" = browser audio off) and [profile]. */
data class AudioPlan(val active: Boolean, val codec: String, val profile: AudioProfile) {
    companion object {
        val OFF = AudioPlan(false, "", AudioProfile.QUALITY)
    }
}

object AudioPolicy {
    /**
     * One audio format for all viewers (like video mode): Opus only if the profile allows it, the phone has an Opus encoder and
     * EVERY audio-capable viewer (not only those with sound on, so the format does not change when somebody taps) can decode it.
     */
    fun codec(profile: AudioProfile, phoneOpus: Boolean, viewers: List<ViewerAudio>): String =
        if (profile.opusPossible && phoneOpus && viewers.all { it.opus }) AudioSpec.CODEC_OPUS else AudioSpec.CODEC_PCM

    /** Capture runs only in browser mode and while at least one viewer wants sound. */
    fun plan(browserMode: Boolean, profile: AudioProfile, phoneOpus: Boolean, viewers: List<ViewerAudio>): AudioPlan =
        if (!browserMode) AudioPlan(false, "", profile)
        else AudioPlan(viewers.any { it.wants }, codec(profile, phoneOpus, viewers), profile)
}

/**
 * Maps a frame index of the recording to the phone's monotonic clock in microseconds (the clock of the video pts).
 * A measurement says "frame [frame] was captured at [ns] (System.nanoTime)". The offset `ns - frame/rate` is smoothed so the
 * pts of consecutive chunks are evenly spaced.
 * - exact (AudioRecord.getTimestamp): low-pass, and a jump over 30 ms is taken at once.
 * - not exact (read-return time of a chunk, always a little late): moves down fast, up very slowly (the earliest estimate is the truth).
 */
class AudioPtsMapper(private val rate: Int = AudioSpec.SAMPLE_RATE) {
    private var offsetNs: Double? = null

    fun reset() { offsetNs = null }

    fun measure(frame: Long, ns: Long, exact: Boolean) {
        val m = ns - frame * 1e9 / rate
        val o = offsetNs
        offsetNs = when {
            o == null -> m
            exact -> if (Math.abs(m - o) > JUMP_NS) m else o + (m - o) * 0.1
            else -> o + (m - o) * (if (m < o) 0.5 else 0.01)
        }
    }

    /** Microseconds on the phone clock of [frame]; 0 before the first measurement. */
    fun ptsUs(frame: Long): Long = ((frame * 1e9 / rate + (offsetNs ?: 0.0)) / 1000.0).toLong()

    private companion object {
        const val JUMP_NS = 30_000_000.0
    }
}

/**
 * Audio waiting for one viewer's socket, capped at [capMs] of sound: when it is exceeded the OLDEST audio is dropped (the page
 * handles gaps). Never blocks, never touched by video flow control. The caller serialises access.
 */
class AudioBacklog(val capMs: Int = DEFAULT_CAP_MS) {
    private class Entry(val packet: Packet, val ms: Int)

    private val q = ArrayDeque<Entry>()
    private var totalMs = 0

    /** Packets dropped because the cap was exceeded. */
    var dropped = 0L
        private set

    val size: Int get() = q.size
    val queuedMs: Int get() = totalMs

    fun offer(p: Packet, durationMs: Int) {
        q.addLast(Entry(p, durationMs))
        totalMs += durationMs
        while (totalMs > capMs && q.size > 1) {
            totalMs -= q.removeFirst().ms
            dropped++
        }
    }

    fun poll(): Packet? {
        val e = q.removeFirstOrNull() ?: return null
        totalMs -= e.ms
        return e.packet
    }

    fun clear() {
        q.clear()
        totalMs = 0
    }

    companion object {
        const val DEFAULT_CAP_MS = 300
    }
}
