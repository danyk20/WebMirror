package app.webmirror.android.stream

import app.webmirror.android.input.ControlArbiter
import app.webmirror.android.input.InputEvent
import app.webmirror.android.input.InputSink
import app.webmirror.android.input.KbdState
import app.webmirror.android.input.Typing
import app.webmirror.android.input.TypingKey
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out point between encoders and sinks. Holds the stream description (`hello`), the picture a new
 * viewer needs to see something immediately (last JPEG, or the H.264 packets since the last keyframe),
 * and decides which video mode the viewers need (plan 2.2).
 *
 * [nowMs] is injectable for tests.
 */
class StreamHub(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** Pairing PIN and tokens (plan 2.1.4); the default has pairing off. Set by the service owner, read by every session. */
    @Volatile
    var pairing: Pairing = Pairing(MemoryPairingStore())

    interface Events {
        fun onViewerCountChanged(count: Int)
        fun onStats(json: String)
        fun onProbe(json: String)
        fun onStreamChanged(info: HelloInfo)
    }

    /** What the phone tells the page about the stream (plan 2.1 `hello`). */
    data class HelloInfo(
        val mode: String = MODE_JPEG,
        val codec: String = "",
        /** Name of the MediaCodec encoder in use ("" for JPEG) and whether it is hardware (null = unknown/none). */
        val encoder: String = "",
        val hardware: Boolean? = null,
        /** Why this mode was chosen (`hello.why`), e.g. "auto: hevc hw on phone and all viewers". */
        val why: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        /** True while [fps] is the automatic 30 fps fallback of a 60 fps preset (`FpsGovernor`). */
        val fpsFallback: Boolean = false,
        /** Why the picture is limited besides the fallback: "hot" while heat protection lowers the rate or quality (`ThermalPolicy`), else "". */
        val fpsLimit: String = "",
        /** Audio mode key (`hello.audio.mode`): "bluetooth", "browser" or "off". */
        val audio: String = "off",
        /** Browser audio only: "opus" or "pcm" as chosen for the viewers ("" = none) and the [AudioProfile] key (`hello.audio.profile`). */
        val audioCodec: String = "",
        val audioProfile: String = AudioProfile.QUALITY.key,
        /** The page-side part of [StreamConfig] as a JSON object text (`hello.cfg`). */
        val cfg: String = StreamConfig().pageJson(),
        /** Remote control (plan 2.1 `hello.control`): the phone's switch is on / the accessibility service is connected. */
        val controlAllowed: Boolean = false,
        val controlReady: Boolean = false,
        /** Some viewer owns the control right now (`hello.control.ownerPresent`), so others need not ask. */
        val controlOwnerPresent: Boolean = false,
        /** Typing (plan 2.1.5): the user chose the Tesla keyboard / that choice cannot work here (Android older than 13). */
        val kbdTesla: Boolean = false,
        val kbdUnsupported: Boolean = false,
    ) {
        /** "60 fps", "30 fps (fallback)" or "30 fps (phone hot)": the rate the stream really has, for the app and the page. */
        val fpsText: String get() = if (fpsLimit == FPS_LIMIT_HOT) "$fps fps (phone hot)" else if (fpsFallback) "$fps fps (fallback)" else "$fps fps"

        /** `{"mode":"browser","codec":"opus","sampleRate":48000,"channels":2,"frameMs":20,"profile":"quality"}`; other modes only have `mode`. */
        private fun audioJson(): String =
            if (audio == AUDIO_BROWSER && audioCodec.isNotEmpty()) {
                val p = AudioProfile.fromKey(audioProfile)
                """{"mode":"$audio","codec":"$audioCodec","sampleRate":${AudioSpec.SAMPLE_RATE},"channels":${AudioSpec.CHANNELS},"frameMs":${p.frameMs},"profile":"${p.key}"}"""
            } else {
                """{"mode":"$audio"}"""
            }

        fun toJson(): String {
            val kbdJson = (if (kbdTesla) ",\"kbd\":\"tesla\"" else "") + (if (kbdUnsupported) ",\"kbdUnsupported\":true" else "")
            val fallbackJson = (if (fpsFallback) ",\"fpsFallback\":true" else "") + (if (fpsLimit.isNotEmpty()) ",\"fpsLimit\":${JsonText.quote(fpsLimit)}" else "")
            return """{"t":"hello","v":$PROTOCOL_VERSION,"mode":"$mode","codec":"$codec","why":${JsonText.quote(why)},"encoder":${JsonText.quote(encoder + if (hardware == true) " (hw)" else if (hardware == false) " (sw)" else "")},"width":$width,"height":$height,"fps":$fps$fallbackJson,"audio":${audioJson()},"cfg":$cfg,"control":{"allowed":$controlAllowed,"ready":$controlReady,"ownerPresent":$controlOwnerPresent$kbdJson}}"""
        }
    }

    private val sinks = CopyOnWriteArrayList<StreamSink>()

    /** Guards [info] and [lastJpeg] and keeps hello/packet ordering consistent for every sink. */
    private val lock = Any()
    private var info = HelloInfo()
    private var lastJpeg: Packet? = null
    private val gop = ArrayList<Packet>()
    private var lastKeyframeRequestMs: Long? = null
    private var forceJpegFlag = false
    private var lastWantedMode = MODE_JPEG
    private var lastChoice = CodecChooser.Choice(MODE_JPEG, null, false, "jpeg: no viewer yet")
    private var fallbackNote: String? = null
    private var lastNotice: String? = null
    private var lastNoticeAtMs = 0L
    private var lastNoticeTtlMs = 0L
    private var lastAudioPlan = AudioPlan.OFF

    /** Who may control the phone (see "Remote control" in the plan). */
    val control = ControlArbiter()

    /** Where accepted input events go (the accessibility service); set by the service owner. */
    @Volatile
    var inputSink: InputSink? = null

    /**
     * The phone's switch or the accessibility service changed: tells the viewers (new `hello`), and when somebody
     * owned the control and lost it, that viewer is told and its touches are lifted.
     */
    fun setControl(allowed: Boolean, ready: Boolean) {
        val dropped = control.setState(allowed, ready)
        if (dropped != null) {
            inputSink?.releaseAll()
            sinks.firstOrNull { it.viewerId == dropped.first }
                ?.onText("""{"t":"control","on":false,"owner":false,"reason":"${dropped.second}"}""")
        }
        update { it.copy(controlAllowed = allowed, controlReady = ready, controlOwnerPresent = control.ownerId != null) }
        syncTyping()
    }

    /** Ownership changed: viewers learn through `hello.control.ownerPresent` (no-op when unchanged). */
    private fun syncOwner() {
        update { it.copy(controlOwnerPresent = control.ownerId != null) }
        syncTyping()
    }

    private var typingActive = false
    private var typingOwner: Int? = null

    /**
     * Typing from the car (plan 2.1.5): the user's choice ([tesla]) and whether this Android can do it ([supported]).
     * Viewers learn through `hello.control.kbd` / `kbdUnsupported`; the keyboard of the page goes away when the mode changes.
     */
    fun setTypingKeyboard(tesla: Boolean, supported: Boolean) {
        update { it.copy(kbdTesla = tesla && supported, kbdUnsupported = tesla && !supported) }
        syncTyping()
    }

    /** True while the Tesla keyboard is in effect (the choice and Android 13+). */
    val teslaKeyboard: Boolean get() = synchronized(lock) { info.kbdTesla }

    /** Starts / stops the service's text-focus watching and hides the keyboard of the page that had it when typing stops or moves on. */
    @Synchronized
    private fun syncTyping() {
        val owner = control.ownerId
        val active = Typing.active(teslaKeyboard, owner != null)
        if (active == typingActive && (!active || owner == typingOwner)) return
        val previous = typingOwner
        if (typingActive && previous != null) sinks.firstOrNull { it.viewerId == previous }?.onText(KbdState.HIDDEN.toJson())
        typingActive = active
        typingOwner = if (active) owner else null
        inputSink?.setTypingActive(active)
    }

    /** The service reported a text-focus change (kind of field only). Forwarded to the control owner while typing is active. */
    fun kbdChanged(state: KbdState) {
        val owner = synchronized(this) { if (typingActive) typingOwner else null } ?: return
        sinks.firstOrNull { it.viewerId == owner }?.onText(state.toJson())
    }

    /** `{t:"type", text}` of viewer [viewerId], already validated; dropped unless it owns the control and typing is active. */
    fun typeText(viewerId: Int, text: String) {
        if (control.canInput(viewerId) && teslaKeyboard) inputSink?.typeText(text)
    }

    /** `{t:"key", key}` of viewer [viewerId], already parsed; same gates as [typeText]. */
    fun pressKey(viewerId: Int, key: TypingKey) {
        if (control.canInput(viewerId) && teslaKeyboard) inputSink?.pressKey(key)
    }

    /** `{t:"control", on}` of [sink]; returns the answer to send back. */
    fun controlRequest(sink: StreamSink, on: Boolean): ControlArbiter.Reply {
        val reply = control.request(sink.viewerId, on)
        if (!on) inputSink?.releaseAll()
        syncOwner()
        return reply
    }

    /** An already validated `input` event of viewer [viewerId]; dropped unless that viewer owns the control. */
    fun controlInput(viewerId: Int, ev: InputEvent) {
        if (control.canInput(viewerId)) inputSink?.submit(ev)
    }

    /** Totals for the automatic test (bytes sent, keyframe requests). */
    val metrics = StreamMetrics()

    @Volatile
    var events: Events? = null

    /** The active stream configuration. Read live by sessions (flow control) and the server (socket options). */
    @Volatile
    var config: StreamConfig = StreamConfig()
        private set

    /** What the phone's encoders can do at the current picture size; set by the capture owner. */
    @Volatile
    var phone: CodecChooser.PhoneCaps = CodecChooser.PhoneCaps()
        private set

    fun setPhone(c: CodecChooser.PhoneCaps) {
        if (c == phone) return
        phone = c
        recomputeMode()
    }

    /** The encoder (MediaCodec name, hardware?) to create for [mode] now, or null if the phone has none. */
    fun encoderFor(mode: String): Pair<String, Boolean>? = synchronized(lock) {
        val c = lastChoice
        if (c.mode == mode && c.encoder != null) c.encoder to c.hardware
        else CodecChooser.encoderForMode(mode, phone, config.allowSoftwareEncoder)
    }

    /** The capture owner could not build the chosen encoder and used another one; [text] says why (null = no problem). */
    fun noteFallback(text: String?) {
        synchronized(lock) { fallbackNote = text }
    }

    /** `hello.why` for [mode]: the choice's reason, or the fallback note when the encoder for the choice failed. */
    fun whyFor(mode: String): String = synchronized(lock) {
        if (lastChoice.mode == mode) lastChoice.why
        else "$mode: fallback from ${lastChoice.mode} (${fallbackNote ?: "its encoder could not be created"})"
    }

    /** Makes [c] the active configuration and sends its page-side part to every viewer in a new `hello`. */
    fun applyConfig(c: StreamConfig) {
        config = c
        update { it.copy(cfg = c.pageJson()) }
        recomputeMode() // the codec choice may have changed
    }

    /** Set by the capture owner: a viewer's reported window changed (for "fit to viewer"). */
    @Volatile
    var viewListener: (() -> Unit)? = null

    /** The largest (by area) window reported by any viewer, or null. */
    fun viewerBox(): ViewBox? = sinks.mapNotNull { it.viewBox }.maxByOrNull { it.width.toLong() * it.height }

    /** A viewer reported a new window size. */
    fun viewChanged() {
        viewListener?.invoke()
    }

    /** Set by the encoder owner. Called when a viewer lost sync or its queue overflowed. */
    @Volatile
    var keyframeRequester: (() -> Unit)? = null

    /**
     * Set by the capture owner: called (without any hub lock held) when the video mode the viewers need
     * differs from what is being sent. The owner switches the encoder and then reports through
     * [update] / [onVideoConfig].
     */
    @Volatile
    var modeRequester: ((String) -> Unit)? = null

    /** Registered viewers only: a session that has not passed pairing is never in [sinks] (see ClientSession.admit). */
    val viewerCount: Int get() = sinks.size

    var forceJpeg: Boolean
        get() = synchronized(lock) { forceJpegFlag }
        set(value) {
            synchronized(lock) { forceJpegFlag = value }
            recomputeMode()
        }

    /**
     * The mode the viewers need (plan 2.2), by [CodecChooser]: "Force JPEG" wins; else every viewer that said hello
     * counts (its decode support per codec) together with the phone's encoders and `config.codec` /
     * `config.allowSoftwareEncoder`. Without any such viewer the mode that was wanted last stays (no needless
     * encoder switch when the last viewer leaves).
     */
    fun wantedMode(): String = synchronized(lock) { computeChoice()?.mode ?: lastWantedMode }

    /** The decode capabilities of every registered viewer that said hello (for the automatic test). */
    fun viewerCaps(): List<CodecChooser.ViewerCaps> = sinks.mapNotNull { it.viewerCaps }

    private fun computeChoice(): CodecChooser.Choice? {
        if (forceJpegFlag) return CodecChooser.Choice(MODE_JPEG, null, false, "jpeg: Force JPEG is on")
        val caps = sinks.mapNotNull { it.viewerCaps }
        if (caps.isEmpty()) return null
        return CodecChooser.choose(config.codec, config.allowSoftwareEncoder, phone, caps)
    }

    /** Recomputes the wanted mode and, when it changed, asks the capture owner to switch. */
    fun recomputeMode() {
        val wanted: String
        var changed = false
        var whyChanged: String? = null
        synchronized(lock) {
            val choice = computeChoice() ?: return
            val oldWhy = lastChoice.why
            lastChoice = choice
            wanted = choice.mode
            if (wanted == lastWantedMode) {
                if (choice.why != oldWhy && info.mode == wanted) whyChanged = choice.why
            } else {
                lastWantedMode = wanted
                fallbackNote = null
                changed = true
            }
        }
        whyChanged?.let { w -> update { it.copy(why = w) } }
        if (changed) modeRequester?.invoke(wanted)
    }

    fun register(sink: StreamSink) {
        sinks.add(sink)
        events?.onViewerCountChanged(sinks.size)
        recomputeAudio()
    }

    fun unregister(sink: StreamSink) {
        if (sinks.remove(sink)) {
            if (control.release(sink.viewerId)) inputSink?.releaseAll()
            syncOwner()
            events?.onViewerCountChanged(sinks.size)
            recomputeMode()
            recomputeAudio()
            viewChanged()
        }
    }

    /**
     * Called by encoders for every packet. JPEG packets are remembered for viewers that join later; H.264
     * packets go into the GOP cache (everything since the last keyframe).
     */
    fun onPacket(packet: Packet) {
        var needKeyframe = false
        synchronized(lock) {
            if (packet.type == Packet.TYPE_JPEG || packet.type == Packet.TYPE_H264) metrics.encodedFrames.incrementAndGet()
            if (packet.type == Packet.TYPE_JPEG) lastJpeg = packet
            if (packet.type == Packet.TYPE_H264) {
                if (packet.isKeyframe) {
                    gop.clear()
                    gop.add(packet)
                } else if (gop.isNotEmpty()) {
                    gop.add(packet) // deltas before the first keyframe are useless to a late viewer
                    if (gop.size > MAX_GOP_PACKETS) needKeyframe = true
                }
            }
            for (s in sinks) s.onPacket(packet)
        }
        if (needKeyframe) requestKeyframe()
    }

    /** The video encoder has its parameter sets: announce the stream ([mode] h264 or hevc, with its codec string). */
    fun onVideoConfig(mode: String, codec: String, width: Int, height: Int, fps: Int, encoder: String = "", hardware: Boolean? = null) {
        update {
            it.copy(mode = mode, codec = codec, encoder = encoder, hardware = hardware, why = whyFor(mode), width = width, height = height, fps = fps, fpsFallback = fpsFallback, fpsLimit = fpsLimit)
        }
    }

    /**
     * Changes the stream description and, if anything changed, sends a new `hello` to every viewer.
     * A change of picture size drops the cached JPEG (it has the old size).
     */
    fun update(change: (HelloInfo) -> HelloInfo) {
        var audioChanged = false
        synchronized(lock) {
            val old = info
            val new = change(old)
            if (new == old) return
            audioChanged = new.audio != old.audio || new.audioProfile != old.audioProfile
            info = new
            if (new.width != old.width || new.height != old.height || new.mode != old.mode || new.codec != old.codec) {
                lastJpeg = null
                gop.clear()
            }
            val json = new.toJson()
            for (s in sinks) s.onHello(json)
            events?.onStreamChanged(new)
        }
        if (audioChanged) recomputeAudio()
    }

    // ---- browser audio (plan 2.4.1) ----

    /** Whether the phone has an Opus encoder; set by the capture owner. */
    @Volatile
    var phoneOpus: Boolean = false
        private set

    fun setAudioEncoders(opus: Boolean) {
        if (opus == phoneOpus) return
        phoneOpus = opus
        recomputeAudio()
    }

    /**
     * Set by the audio capture owner: called (without any hub lock held) whenever the audio plan changes (mode or profile,
     * a viewer joined/left/turned sound on or off, the codec choice changed).
     */
    @Volatile
    var audioRequester: ((AudioPlan) -> Unit)? = null

    /** The current plan (what the capture should be doing now). */
    fun audioPlan(): AudioPlan = synchronized(lock) { lastAudioPlan }

    /** Recomputes the plan; updates `hello.audio.codec` for the viewers and asks the capture owner to follow when it changed. */
    fun recomputeAudio() {
        val plan: AudioPlan
        val changed: Boolean
        val helloCodec: String
        synchronized(lock) {
            val browser = info.audio == AUDIO_BROWSER
            plan = AudioPolicy.plan(browser, AudioProfile.fromKey(info.audioProfile), phoneOpus, sinks.mapNotNull { it.audioCaps })
            changed = plan != lastAudioPlan
            lastAudioPlan = plan
            helloCodec = if (browser) plan.codec else ""
        }
        update { if (it.audioCodec == helloCodec) it else it.copy(audioCodec = helloCodec) }
        if (changed) audioRequester?.invoke(plan)
    }

    /** A viewer's audio state changed (its hello or `{t:"audio"}`). */
    fun audioChanged() = recomputeAudio()

    /** Called by the audio capture for every packet of [durationMs] ms. Not under the hub lock: audio must never wait for video. */
    fun onAudio(packet: Packet, durationMs: Int) {
        for (s in sinks) if (s.audioCaps?.wants == true) s.onAudio(packet, durationMs)
    }

    /** Forgets everything about the previous stream (used when mirroring stops). */
    fun reset() {
        synchronized(lock) {
            info = HelloInfo(cfg = config.pageJson(), controlAllowed = info.controlAllowed, controlReady = info.controlReady, kbdTesla = info.kbdTesla, kbdUnsupported = info.kbdUnsupported)
            control.clearOwner()
            lastJpeg = null
            gop.clear()
            lastKeyframeRequestMs = null
            lastWantedMode = MODE_JPEG
            lastChoice = CodecChooser.Choice(MODE_JPEG, null, false, "jpeg: no viewer yet")
            fallbackNote = null
            lastNotice = null
            targetText = ""
            fpsFallback = false
            fpsLimit = ""
            lastAudioPlan = AudioPlan.OFF
        }
        syncTyping()
    }

    fun helloJson(): String = synchronized(lock) { info.toJson() }

    /**
     * Called when a viewer's own `hello` arrived: stream description first, then the last picture (the
     * GOP cache in h264 mode, so it shows a picture at once even if the screen is static). The viewer's
     * `h264` answer may change the wanted mode.
     */
    fun greet(sink: StreamSink) {
        synchronized(lock) {
            sink.onHello(info.toJson())
            if (isVideoMode(info.mode)) {
                for (p in gop) sink.onPacket(p)
            } else {
                val jpeg = lastJpeg
                if (jpeg != null) sink.onPacket(jpeg)
            }
            val notice = lastNotice
            if (notice != null) {
                val left = lastNoticeTtlMs - (nowMs() - lastNoticeAtMs)
                if (left > 1000) sink.onNotice(noticeJson(notice, (left / 1000).toInt()))
            }
        }
        recomputeMode()
    }

    private fun noticeJson(text: String, ttlS: Int) = """{"t":"notice","text":${JsonText.quote(text)},"ttlS":$ttlS}"""

    /** Shows [text] on every viewer's screen for [ttlS] seconds (empty text clears it); a viewer that joins meanwhile gets it too. */
    fun notice(text: String, ttlS: Int) {
        val json = noticeJson(text, ttlS)
        synchronized(lock) {
            lastNotice = text.ifEmpty { null }
            lastNoticeAtMs = nowMs()
            lastNoticeTtlMs = ttlS * 1000L
            for (s in sinks) s.onNotice(json)
        }
    }

    /** Closes every viewer connection; pages reconnect after about a second (new connections get new socket options). */
    fun disconnectAll() {
        for (s in sinks) s.disconnect()
    }

    /** Pairing was turned on: viewers admitted without a pairing token are disconnected (they reconnect and are asked for the PIN). */
    fun disconnectUnpaired() {
        for (s in sinks) if (!s.paired) s.disconnect()
    }

    /** "Forget paired viewers": every viewer that got in with a pairing token or PIN is disconnected. */
    fun disconnectPaired() {
        for (s in sinks) if (s.paired) s.disconnect()
    }

    /** Congestion signals of every viewer, for the bitrate controller. */
    fun congestionSignals(nowMs: Long = nowMs()): List<BitrateController.ViewerSignal> =
        sinks.mapNotNull { it.congestion(nowMs) }

    /** The viewers' numbers for the 60 to 30 fps fallback ([FpsGovernor]). */
    fun fpsSignals(nowMs: Long = nowMs()): List<FpsGovernor.Viewer> = sinks.mapNotNull { it.fpsViewer(nowMs) }

    /**
     * Set by the capture owner before it builds an encoder: the rate it is about to use is the automatic fallback, not the preset's.
     * Goes into the next `hello` (`onVideoConfig`, or `update` for JPEG).
     */
    @Volatile
    var fpsFallback: Boolean = false

    /** Set like [fpsFallback]: [FPS_LIMIT_HOT] while heat protection limits the picture, else "". */
    @Volatile
    var fpsLimit: String = ""

    /** Current adaptive target as short text ("3200/4000 kbps", "40/50 quality"), empty when none; shown to viewers. */
    @Volatile
    var targetText: String = ""

    /** Sends the liveness ping to every viewer (the page reconnects when it hears nothing for 3 s). */
    fun pingAll() {
        for (s in sinks) s.onPing()
    }

    /** Closes and unregisters every sink that has been silent for longer than [maxIdleMs]. */
    fun sweepIdle(maxIdleMs: Long) {
        for (s in sinks) {
            if (s is Expirable) s.closeIfIdle(maxIdleMs)
        }
    }

    /** Asks the encoder for a keyframe, at most once per [KEYFRAME_MIN_INTERVAL_MS]. */
    fun requestKeyframe() {
        synchronized(lock) {
            val now = nowMs()
            val last = lastKeyframeRequestMs
            if (last != null && now - last < KEYFRAME_MIN_INTERVAL_MS) return
            lastKeyframeRequestMs = now
        }
        val request = keyframeRequester
        if (request != null) {
            metrics.keyframeRequests.incrementAndGet()
            request()
        }
    }

    fun reportStats(json: String) {
        events?.onStats(json)
    }

    fun reportProbe(json: String) {
        events?.onProbe(json)
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        /** `HelloInfo.fpsLimit` while heat protection limits the picture. */
        const val FPS_LIMIT_HOT = "hot"

        const val MODE_JPEG = "jpeg"
        const val MODE_H264 = "h264"
        const val MODE_HEVC = "hevc"
        const val MODE_VP9 = "vp9"
        const val MODE_AV1 = "av1"
        const val AUDIO_BROWSER = "browser"

        /** h264, hevc, vp9 and av1 share packet type 1 and the keyframe/GOP handling. */
        fun isVideoMode(mode: String): Boolean = mode in CodecChooser.VIDEO_CODECS
        const val MAX_GOP_PACKETS = 300
        const val KEYFRAME_MIN_INTERVAL_MS = 500L

        /** Capture has stopped but the server is up; the page keeps its last picture and shows a message. */
        const val MODE_PAUSED = "paused"
    }
}
