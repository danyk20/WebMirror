package app.webmirror.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import app.webmirror.android.stream.AudioPlan
import app.webmirror.android.stream.AudioProfile
import app.webmirror.android.stream.AudioPtsMapper
import app.webmirror.android.stream.AudioSpec
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import java.io.IOException

/** Whether (and which) Opus encoder MediaCodec offers; looked up once. */
object OpusSupport {
    val encoderName: String? by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) } }?.name
        } catch (e: Exception) {
            Log.w("WebMirror", "cannot list codecs for Opus: $e")
            null
        }
    }
    val available: Boolean get() = encoderName != null
}

/**
 * Captures what the phone plays (AudioPlaybackCapture on the screen-capture projection) and feeds it to [StreamHub.onAudio] as
 * Opus or PCM packets whose pts are on the video clock (System.nanoTime based). One instance per projection. [apply] starts,
 * stops or restarts the worker thread to match the plan; the video is never touched.
 */
class BrowserAudio(
    private val context: Context,
    private val projection: MediaProjection,
    private val hub: StreamHub,
    /** A human-readable problem ("" never; null = problem gone) for the app. */
    private val onProblem: (String?) -> Unit,
) {
    private val lock = Any()
    private var worker: Worker? = null
    private var current = AudioPlan.OFF

    fun apply(plan: AudioPlan) {
        synchronized(lock) {
            if (plan == current && (worker != null) == plan.active) return
            stopWorker()
            current = plan
            if (plan.active) {
                val w = Worker(plan)
                worker = w
                w.start()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            stopWorker()
            current = AudioPlan.OFF
        }
    }

    private fun stopWorker() {
        val w = worker ?: return
        worker = null
        w.requestStop()
        if (Thread.currentThread() !== w) {
            try { w.join(1500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private inner class Worker(private val plan: AudioPlan) : Thread("audio-capture") {
        @Volatile private var stopping = false
        @Volatile private var rec: AudioRecord? = null

        fun requestStop() {
            stopping = true
            try { rec?.stop() } catch (e: Exception) { /* not recording yet */ }
        }

        override fun run() {
            var record: AudioRecord? = null
            var enc: OpusPacketEncoder? = null
            try {
                record = createRecord()
                rec = record
                if (plan.codec == AudioSpec.CODEC_OPUS) {
                    enc = try {
                        OpusPacketEncoder(plan.profile)
                    } catch (e: Exception) {
                        Log.w(TAG, "Opus encoder unusable, switching to PCM: $e")
                        hub.setAudioEncoders(false) // replans: PCM for everybody
                        return
                    }
                }
                onProblem(null)
                loop(record, enc)
            } catch (e: SecurityException) {
                Log.w(TAG, "audio capture not allowed: $e")
                onProblem("Sound capture was refused by Android (permission or the system).")
            } catch (e: Exception) {
                if (!stopping) {
                    Log.w(TAG, "audio capture failed: $e")
                    onProblem("Sound capture failed: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                try { record?.stop() } catch (e: Exception) { /* ignore */ }
                try { record?.release() } catch (e: Exception) { /* ignore */ }
                enc?.release()
            }
        }

        private fun createRecord(): AudioRecord {
            // Playback capture needs RECORD_AUDIO. The service only plans sound when it is granted, but the user can
            // withdraw it at any time; the SecurityException is reported by run() like a refusal from Android.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO is not granted")
            }
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioSpec.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min * 2, plan.profile.chunkBytes * 10))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                throw IllegalStateException("the audio recorder could not be initialised")
            }
            return r
        }

        private fun loop(record: AudioRecord, enc: OpusPacketEncoder?) {
            val chunkFrames = plan.profile.framesPerChunk
            val chunkBytes = plan.profile.chunkBytes
            val frameMs = plan.profile.frameMs
            val mapper = AudioPtsMapper()
            val ts = AudioTimestamp()
            var frameIndex = 0L
            record.startRecording()
            Log.i(TAG, "audio capture started: ${plan.codec}, ${frameMs} ms chunks, profile ${plan.profile.key}")
            while (!stopping) {
                val buf = ByteArray(chunkBytes)
                var off = 0
                while (off < chunkBytes && !stopping) {
                    val n = record.read(buf, off, chunkBytes - off, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IOException("audio read error $n")
                    off += n
                }
                if (stopping) break
                val nowNs = System.nanoTime()
                val endFrame = frameIndex + chunkFrames
                if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    mapper.measure(ts.framePosition, ts.nanoTime, true)
                } else {
                    mapper.measure(endFrame, nowNs, false) // fallback: the chunk ended about when the read returned
                }
                val pts = mapper.ptsUs(frameIndex)
                frameIndex = endFrame
                if (enc != null) {
                    enc.encode(buf, pts) { data, p -> hub.onAudio(Packet(Packet.TYPE_AUDIO, Packet.FLAG_AUDIO_OPUS, p, data), frameMs) }
                } else {
                    hub.onAudio(Packet(Packet.TYPE_AUDIO, 0, pts, buf), frameMs)
                }
            }
            Log.i(TAG, "audio capture stopped")
        }
    }

    companion object {
        private const val TAG = "WebMirror"
    }
}

/**
 * Opus through MediaCodec, synchronous mode on the capture thread: one 20 ms PCM chunk in, zero or more packets out. The pts of an
 * output packet is the pts of the oldest input not yet answered (one packet per input, so the order is the match); the
 * encoder's own timestamps are not relied on. The codec-config buffer (OpusHead) is dropped: WebCodecs needs no `description`
 * for plain stereo Opus.
 */
class OpusPacketEncoder(private val profile: AudioProfile) {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val ptsFifo = ArrayDeque<Long>()

    init {
        val name = OpusSupport.encoderName ?: throw IllegalStateException("no Opus encoder")
        val mime = MediaFormat.MIMETYPE_AUDIO_OPUS
        val c = MediaCodec.createByCodecName(name)
        try {
            val f = MediaFormat.createAudioFormat(mime, AudioSpec.SAMPLE_RATE, AudioSpec.CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, profile.opusBitrate)
                val range = try { c.codecInfo.getCapabilitiesForType(mime).encoderCapabilities?.complexityRange } catch (e: Exception) { null }
                setInteger(MediaFormat.KEY_COMPLEXITY, range?.clamp(profile.opusComplexity) ?: profile.opusComplexity)
            }
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (ignored: Exception) { }
            throw e
        }
        codec = c
    }

    fun encode(pcm: ByteArray, ptsUs: Long, out: (ByteArray, Long) -> Unit) {
        val idx = codec.dequeueInputBuffer(20_000)
        if (idx >= 0) {
            val ib = codec.getInputBuffer(idx) ?: throw IllegalStateException("no input buffer")
            if (ib.capacity() < pcm.size) throw IllegalStateException("Opus input buffer too small (${ib.capacity()})")
            ib.clear()
            ib.put(pcm)
            codec.queueInputBuffer(idx, 0, pcm.size, ptsUs, 0)
            ptsFifo.addLast(ptsUs)
            if (ptsFifo.size > 50) ptsFifo.removeFirst()
        }
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, 0)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (oi < 0) continue // format / buffers changed
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isConfig && info.size > 0) {
                val data = ByteArray(info.size)
                val ob = codec.getOutputBuffer(oi)
                if (ob != null) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.get(data)
                    out(data, ptsFifo.removeFirstOrNull() ?: info.presentationTimeUs)
                }
            }
            codec.releaseOutputBuffer(oi, false)
        }
    }

    fun release() {
        try { codec.stop() } catch (e: Exception) { /* ignore */ }
        try { codec.release() } catch (e: Exception) { /* ignore */ }
    }
}
