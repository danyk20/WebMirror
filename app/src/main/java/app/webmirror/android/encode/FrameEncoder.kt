package app.webmirror.android.encode

import android.view.Surface

/** What [app.webmirror.android.capture.ScreenCapture] needs from an encoder, whatever the codec. */
interface FrameEncoder {
    /** Where the VirtualDisplay must draw. */
    val surface: Surface

    /** Stop delivering packets without releasing anything; [stop] follows once the display moved on. */
    fun pause()

    fun stop()

    /** Ask for a keyframe as soon as possible. A no-op for JPEG, where every frame is complete. */
    fun requestKeyframe()
}
