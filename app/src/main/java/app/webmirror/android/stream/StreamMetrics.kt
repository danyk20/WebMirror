package app.webmirror.android.stream

import java.util.concurrent.atomic.AtomicLong

/** Running totals of what the phone side did, read by the automatic test (deltas between two moments). */
class StreamMetrics {
    /** Bytes of video packets written to viewers (header + payload). */
    val bytesSent = AtomicLong()

    /** Keyframe requests that reached the encoder (after the hub's rate limit). */
    val keyframeRequests = AtomicLong()

    /** Frames the encoders delivered to the hub (video and JPEG), for the phone-side encode fps of the automatic test. */
    val encodedFrames = AtomicLong()
}
