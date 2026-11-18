# WebMirror — implementation plan

## 1. Context

**What we are building.** An Android app (Android 10+, Kotlin) that turns the phone into a small web server and mirrors its screen, and later its audio, to any browser on the same network, with no plugins. Primary target: the browser of a 2015 Tesla Model S with the MCU2 upgrade, used by a passenger while the car is driving. Secondary: any laptop/tablet browser.

**Decisions already agreed with the user.**
- Phone does all heavy work (capture, scale, encode). Browser only decodes and draws.
- Video: H.264 → WebSocket → WebCodecs → `<canvas>`. Automatic fallback: JPEG frames over the same socket. No `<video>` tag, no WebRTC.
- Audio is a user choice. Version 1: phone plays audio to the car over Bluetooth and the page delays video by an adjustable offset. Later: audio through the browser.
- User chooses "Sharper" (resolution) or "Smoother" (frame rate).
- View only for now. Typing/touch later. Local network only for now. Internet relay later. Both must be possible without redesign.
- Personal use first, but built so it can go on Google Play.

**What we do not know yet (must be measured in the car, not guessed).**
| # | Unknown | Why it matters | Resolved in |
|---|---|---|---|
| U1 | Which addresses the Tesla browser will open. Community reports say it refuses private addresses (192.168.x.x etc.). Similar apps work around it with a "virtual IP" created by a local Android VPN interface. | Decides how the car reaches the phone | M1 car test |
| U2 | Whether the Tesla browser has WebCodecs and accepts H.264 in it. One mirroring app reported H.264 problems after Tesla update 2025.8.4. | Decides H.264 vs JPEG as the car's mode | M1 car test |
| U3 | Whether HTTPS with a real certificate is needed (WebCodecs only exists on secure pages). | Decides whether a domain + certificate is mandatory | M1 car test |
| U4 | What resolution/fps the MCU2 can decode smoothly. | Sets the preset numbers | M3 car test |

**Current state of the machine.** Project folder `the project folder` is empty and not a git repo. Installed: JDK 17, `adb`, `node`. Missing: Android Studio, Android SDK, Gradle.

---

## 1a. Who does the work (user instruction)

- **Implementation: Sonnet 5.5 at medium effort.** Every coding step in §4 is handed to a Sonnet 5.5 subagent (medium effort), one milestone step or a small group of steps at a time, with the relevant sections of this plan as its brief.
- **Fable 5.2 is used only for:** debugging that Sonnet could not resolve after a reasonable attempt, hard problems (e.g. the virtual-IP routing in M1.1 if the desk test fails, A/V sync issues), and important decisions (the decision gates after car tests, any change to the protocol or architecture in §2).
- Fable does not write routine code. It briefs the Sonnet agent, checks the "Done when" list of each milestone against the actual result (build output, test output), and reports to the user.
- Steps that need the user: installing Android Studio via its wizard, plugging in the phone, the domain/certificate (M1.3), and both car tests.

## 2. Architecture

```
PHONE (app)                                             BROWSER (car / laptop)
┌───────────────────────────────────────────┐          ┌──────────────────────────────┐
│ MainActivity (Compose UI, settings, URL)  │          │ index.html + player.js       │
│ MirrorService (foreground service)        │          │                              │
│  ├ ScreenCapture: MediaProjection         │  WS      │  WebSocket                   │
│  │   └ VirtualDisplay ─► surface          │ binary   │   ├ H.264 → VideoDecoder     │
│  ├ VideoEncoder (H.264/HEVC/VP9/AV1) ─┐           │ packets  │   │          → canvas        │
│  ├ JpegEncoder (ImageReader) ─┼► StreamHub├─────────►│   ├ JPEG  → createImageBitmap│
│  ├ AudioCapture (M5)         ─┘    │      │          │   │          → canvas        │
│  ├ WebServer (NanoHTTPD + WS) ◄────┘      │◄─────────┤   └ PCM (M5) → Web Audio     │
│  └ VirtualIpService (VpnService, M1)      │  JSON    │  Jitter buffer + delay offset│
└───────────────────────────────────────────┘ control  └──────────────────────────────┘
```

Rules that keep future features cheap:
- **`StreamHub`** is the only thing encoders talk to. It fans packets out to "sinks". Today the only sink is the local WebSocket server. A future internet relay is just another sink.
- **The WebSocket is two-way** and has a JSON control channel. Future touch/typing is a new JSON message type (`input`), nothing else changes.
- **Every message carries a protocol version** in the first `hello`.

### 2.1 Wire protocol (version 1) — the contract between phone and page

**Binary WebSocket message** = 12-byte header + payload. All integers big-endian.

| Offset | Size | Field | Values |
|---|---|---|---|
| 0 | 1 | `type` | `1` = video access unit (H.264, HEVC, VP9 or AV1, see below), `2` = JPEG image, `3` = audio (browser audio mode: Opus or PCM, see 2.4.1), `0` = probe throughput dummy (M0 `blast`; ignored by the player) |
| 1 | 1 | `flags` | bit 0 = keyframe (only for type 1); type 3: bit 1 (value 2) = Opus packet, clear = PCM |
| 2 | 2 | reserved | `0` |
| 4 | 4 | `ptsHi` | upper 32 bits of presentation time in microseconds |
| 8 | 4 | `ptsLo` | lower 32 bits |
| 12 | n | payload | type 1: Annex-B H.264 **or HEVC** (start codes `00 00 00 01`; which one is given by `hello.mode`/`codec`, so no new packet type is needed), keyframes always start with the parameter sets (SPS+PPS; HEVC: VPS+SPS+PPS). HEVC keyframes are IRAP pictures (NAL types 16..23). **VP9** (`hello.mode:"vp9"`): one VP9 frame (or superframe) per packet, no start codes, keyframe flag from the encoder; no codec config, the codec string comes from the first keyframe. **AV1** (`"av1"`): one temporal unit in low-overhead OBU format; every keyframe contains the sequence header OBU (the encoder's codec-config sequence header is inserted after the temporal delimiter if the keyframe lacks it). The page configures VP9/AV1 without `description` (W3C WebCodecs VP9 and AV1 registrations: "description is not used"; an AV1 key chunk must contain a sequence header OBU before the first frame header, the temporal delimiter may be omitted). Packet type 1 stays "video" for all four. type 2: a complete JPEG file. type 3: one audio chunk, 48 000 Hz stereo: flags bit 1 set = one raw Opus packet (no Ogg, no `description` needed), clear = PCM 16-bit little-endian interleaved; 10 or 20 ms per packet (`hello.audio.frameMs`); `pts` = phone clock of the first sample. Audio is never acknowledged. |

`pts` is the phone's monotonic clock (`System.nanoTime()/1000`). Video and audio use the same clock; that is what makes sync possible. In JavaScript read it as `hi * 4294967296 + lo` (do not use BigInt, old browsers lack it).

**Text WebSocket message** = one JSON object with a `t` field.

| Direction | `t` | Fields | Meaning |
|---|---|---|---|
| page → phone | `hello` | `v:1, audio?:{opus:boolean}, codecs:{h264:{sw,hw}, hevc:{sw,hw}, vp9:{sw,hw}, av1:{sw,hw}}, h264:boolean, hevc:boolean, pin:string, token?:string` | First message. `token` = the pairing token the page stored (see 2.1.4); `pin` is unused (always empty). `codecs.X.sw` = `VideoDecoder.isConfigSupported` is true at 1280x720 with the representative string (`avc1.42E01F`, `hev1.1.6.L93.B0`, `vp09.00.31.08`, `av01.0.08M.08`), `hw` = also true with `hardwareAcceleration:"prefer-hardware"`; each check in try/catch. `h264` / `hevc` are the old booleans (h264 = decodable at all, hevc = hardware), kept for compatibility; a page that sends no `codecs` is understood through them. |
| phone → page | `hello` | `v:1, mode:"h264"\|"hevc"\|"vp9"\|"av1"\|"jpeg"\|"paused", codec:"avc1.xxxxxx"\|"hev1.P.C.Lxx.B0"\|"vp09.PP.LL.DD"\|"av01.P.LLT.DD", why, encoder, width, height, fps, audio:{mode:"bluetooth"\|"browser"\|"off", codec?:"opus"\|"pcm", sampleRate?:48000, channels?:2, frameMs?:10\|20, profile?:"fast"\|"quality"}, cfg:{id, summary, jitterBufferMs, skipLateFrames, pacing}, control:{allowed, ready, ownerPresent}` | Stream description. `why` = why the phone chose this mode (e.g. `auto: hevc hw on phone and all viewers`, `fixed vp9 not usable (...); auto: ...`, `h264: fallback from hevc (...)` when an encoder could not be created); `encoder` = MediaCodec name and `(hw)`/`(sw)`; both shown in the overlay and in the app. `fpsFallback` (only when `true`) = `fps` is the automatic 30 fps fallback of the 60 fps preset (see 2.3). Sent again whenever anything in it changes (rotation, preset, mode, fps fallback). `mode:"paused"`: screen capture ended on the phone (screen lock, status-bar chip, system) but the server and the viewer connections stay up; no packets follow until a normal `hello` arrives after the user taps Start again. The page keeps its last picture and shows "Paused on the phone. Open WebMirror and tap Start." Other fields are the last known values and may be ignored. `control` = remote control state: `allowed` = the phone's "Allow control from viewers" switch is on, `ready` = the accessibility service is connected, `ownerPresent` = some viewer owns the control right now (see 2.1.3); a hello without it means no control. |
| page → phone | `audio` | `on:boolean` | This page's sound is on (context running, not muted) or off. The phone captures audio only while at least one viewer says `on:true` and the mode is "browser". Sent after each `hello` of the phone and whenever it changes. See 2.4.1. |
| phone → page | `pair` | `reason:"required"\|"wrong"\|"locked", pinLength, retryInMs?` | Pairing is on and the `hello` had no token or an unknown one (`required`), the PIN sent was wrong (`wrong`), or PIN attempts are locked out (`locked`, with `retryInMs`). `pinLength` = number of digits of the PIN (4 or 6); the page sizes its dots and key limit by it (a phone that omits it means 6). Nothing else is sent to that session until it paired (see 2.1.4). |
| page → phone | `pair` | `pin:string` | The PIN the user typed, answer to a `pair`. |
| phone → page | `paired` | `token` | The PIN was right: `token` (random, 192 bit, hex) proves pairing from now on; the page keeps it in `localStorage` and sends it in every later `hello`. Directly followed by the phone's normal `hello` and stream. |
| phone → page | `denied` | `reason:"pin"\|"version"` | Then the phone closes the socket. |
| phone → page | `ping` | `ts, off?, rtt?, inFlight, pending, target` | Every 1 s to every viewer (liveness; the page reconnects if it received nothing, text or binary, for 3 s, showing "Reconnecting…" over the last picture). `ts` = the phone's monotonic clock in µs (same clock as packet pts) at the moment of sending; the page answers at once with `pong`. `off` = clock offset in µs, page clock minus phone clock, from the sample with the smallest round trip in the last 30 s (absent until the first pong arrived); `rtt` = the last measured round trip in ms. `inFlight, pending, target` = the phone's flow-control state for this viewer, shown in the overlay; `target` = current adaptive bitrate/quality as text, e.g. `"3200/4000 kbps"`, `"40/50 quality"`, empty when none. |
| page → phone | `pong` | `ts, pageTs` | Immediate answer to `ping`: `ts` echoed, `pageTs` = `performance.now()` (ms, fractional) when answering. The phone computes `rtt = receive time - ts` and `offset = pageTs*1000 - (ts + rtt/2)` (NTP style, symmetric delay assumed) and keeps the sample with the smallest rtt of the last 30 s. The page measures for every drawn frame `e2eMs = performance.now() - (pts/1000 + off/1000)`, the time from the phone's capture timestamp to drawing; the offset is forgotten when the socket closes. |
| phone → page | `notice` | `text, ttlS` | A short message shown at the top of the page for `ttlS` seconds (empty `text` clears it). Used by the automatic test for progress and the final "Test finished — best: ..."; a viewer that joins within the time to live gets the last notice. |
| page → phone | `kf` | — | "I lost sync, send a keyframe." |
| page → phone | `ack` | `pts` | Flow control. Sent for every video frame the page is finished with, drawn or discarded (cumulative: all frames up to `pts`, in µs of the frame's header pts). The phone counts frames sent but not yet acknowledged per viewer and sends no new video while `maxInFlight` (config, default 6) are in flight. While held back, newer frames replace older ones: JPEG keeps only the newest; H.264 drops everything waiting once the oldest has waited longer than `dropAfterMs` (config, default 150) and resumes at the next keyframe. If no ack arrives for 1.5 s the count is reset. |
| page → phone | `view` | `w, h, dpr` | The page's window in CSS pixels and `devicePixelRatio`. Sent after `hello` and (debounced 300 ms) on resize. Used only when `fitToViewer` is on. |
| page → phone | `stats` | `cfgId, fps, queue, dropped, skipped, replaced, decoderResets, reconnects, e2eAvg, e2eP95, e2eMax, e2eN, e2eRaw, rttMs, bufferMs, lateMs, lateMaxMs, netLateMs, decodeQueue, inDecoder, srvInFlight, ua` | Every 2 s. Also acts as keep-alive. `lateMs` = average over the interval of how much later than its due time a frame reached the screen (measured from the phone's pts, so a backlog inside the browser counts); `netLateMs` = same for arrival only. `cfgId` = `hello.cfg.id`, so stats can be attributed to a configuration. `replaced` = part of `skipped`: decoded frames replaced by a newer one before vsync drawing (not treated as congestion). `e2eAvg/e2eP95/e2eMax` (ms; `null` until the clock offset is known) = absolute delay of the frames drawn in the interval (see `pong`), `e2eN` = their count, `e2eRaw` = at most 150 of the per-frame values (rounded ms) for the automatic test's aggregation (the phone removes it from its log/state); `rttMs` = average of the `ping.rtt` values received in the interval; `decoderResets` and `reconnects` are cumulative counters (decoder re-created after an error or overload; connections lost after having been open). The phone adds `vid` (viewer id), `clockRttMs`, `inFlight, srvPending, srvDropped, srvResetDropped, target` (the adaptive controller state) before logging it. `dropped` and `srvDropped` are congestion drops only; frames lost to a deliberate reset (new viewer, hello, encoder rebuild, keyframe wait) are `kfwait` / `srvResetDropped` and the controller ignores all signals for 2 s after a reset, config apply or viewer connect. |
| page → phone | `probe` | `results:{...}` | Probe page results (M0). |
| page → phone | `blast` | — | M0 throughput test: phone sends 100 binary packets of type `0` (50 000 bytes payload each), then `blastDone`. |
| phone → page | `blastDone` | `packets, bytes` | End marker of a `blast`. |
| page → phone | `control` | `on:boolean` | Remote control on/off for this viewer (see "Remote control" below). Turning on succeeds only if `hello.control.allowed && ready` and no other viewer owns the control. |
| phone → page | `control` | `on, owner:boolean, reason?` | Answer to a successful `control` request (`on:true, owner:true`), to an "off" request (`on:false, owner:false`), or the phone's notice that the owner lost the control (`on:false, owner:false, reason:"notAllowed"\|"notReady"`; the phone switch was turned off or the accessibility service disconnected). |
| phone → page | `controlDenied` | `reason:"notAllowed"\|"notReady"\|"busy"` | A `control` on request was refused: the phone's switch is off, the accessibility service is not connected, or another viewer owns the control. |
| page → phone | `input` | `e:"down"\|"move"\|"up"\|"cancel", id, x, y, ts, w?, h?` | One pointer event while the viewer owns the control. `id` = small integer per finger (0..1 000 000, page-assigned, reusable after `up`/`cancel`); `x`,`y` = position normalised to the picture, 0..1 (down/move require them; up/cancel may omit them = last position); `ts` = page `performance.now()` in ms (only differences are used, for stroke durations); `w`,`h` = size of the picture the page shows (`hello.width/height`), so the phone can ignore input for an old orientation. The page sends at most one `move` per animation frame per pointer. The phone ignores input from non-owners, while control is not allowed, with values outside 0..1, malformed, and beyond 120 messages per second per viewer (`up`/`cancel` are never dropped). Unknown `t` is still ignored. |

### 2.1.1 Stream configuration (`StreamConfig`)

One object (`stream/StreamConfig.kt`, persisted in `Settings`) holds every latency/smoothness tunable. `MirrorService.applyConfig` applies a change live and only what changed: encoder fields rebuild the encoder (new encoder, `setSurface` on the same VirtualDisplay); `fitToViewer` re-evaluates the size; flow-control fields are read live by the sessions; socket fields apply to new connections; page fields travel to every viewer in `hello.cfg` (`id` = CRC32 of the whole config, 8 hex digits; `summary` = one line for the overlay; `jitterBufferMs`; `skipLateFrames`). The page echoes `id` as `stats.cfgId`.

| Field | Default | Meaning |
|---|---|---|
| `tcpNoDelay` | true | TCP_NODELAY on accepted sockets (new connections) |
| `sendBufferKb` | 64 | socket send buffer; 0 = system default (new connections) |
| `codec` | auto | `auto` / `h264` / `hevc` / `vp9` / `av1`. `auto` is chosen by `stream/CodecChooser` (pure) from hardware support on the phone (`encode/CodecSupport`: `MediaCodecList(REGULAR_CODECS)` encoders per MIME, hardware = `isHardwareAccelerated && !isSoftwareOnly` on API 29+, and `areSizeAndRateSupported` for the current size/fps; logged once and shown in the app under "Codecs on this phone") and on every connected viewer (`hello.codecs`). **Rule:** a candidate qualifies only if the phone has a hardware encoder for it (software encoders only with `allowSoftwareEncoder`, tried after all hardware ones) and every viewer reports *hardware* decode. Order of `CodecChooser.PREFERENCE` (a constant list): HEVC, AV1, VP9, H.264. If none qualifies, H.264 with software decode on the viewers (the only software decode accepted; the phone's H.264 encoder, even a software one, is the last resort), else JPEG. A fixed choice is used if the phone (its hardware encoder, or software with `allowSoftwareEncoder`) and every viewer (hardware decode; H.264: any decode) support it, otherwise the auto result, with the reason in `hello.why`. **H.264 is the only codec measured as the reference in the car; HEVC, AV1 and VP9 need car verification (the `codec-*` test phases).** Encoders are created by name (`MediaCodec.createByCodecName`) so the chosen hardware/software encoder is guaranteed. If creating or configuring it fails: chosen codec -> H.264 -> JPEG, logged and explained in `why`. Recomputed when a viewer joins, leaves or says hello, and on a change of `codec` / `allowSoftwareEncoder` / the picture size (mode switch). Per codec: HEVC profile Main fixed, no level requested; VP9 profile 0; AV1 Main 8-bit; the same low-latency / CBR / intra-refresh / keyframe keys where supported; no B-frame key for VP9/AV1. Codec strings: H.264 and HEVC from the SPS (below), **VP9** `vp09.PP.LL.DD` = profile and bit depth parsed from the first keyframe's uncompressed header (VP9 spec 6.2) and level from size x fps by the Annex A level table (`encode/Vp9Util`; 584x1280@30 gives `vp09.00.31.08`), **AV1** `av01.P.LLT.DD` from `seq_profile`, `seq_level_idx[0]`, `seq_tier[0]` and the bit depth of the sequence header OBU (AV1 spec 5.5; `encode/Av1Util`, tested with real libaom output). Sources: W3C WebCodecs VP9 and AV1 codec registrations, "VP Codec ISO Media File Format Binding" and "AV1 Codec ISO Media File Format Binding" codec parameter strings. Verified only by unit tests; real VP9/AV1 hardware encoders and the car's decoders are untested. HEVC: the codec string is built from the SPS (`encode/HevcUtil`): `hev1.` + optional profile-space letter (A/B/C) + `general_profile_idc` + `.` + the 32 compatibility flags in reverse bit order, hex without leading zeros + `.` + `L`/`H` (tier) + `general_level_idc` + `.` + the constraint bytes in hex, trailing zero bytes omitted (ISO/IEC 14496-15 Annex E.3, as summarised by the W3C WebCodecs HEVC registration and MDN's codecs parameter guide), e.g. `hev1.1.6.L93.B0` |
| `allowSoftwareEncoder` | false | auto mode and fixed choices may use software encoders (e.g. `c2.android.vp9.encoder` on the Galaxy S22) only when true; used by the `codec-vp9` / `codec-av1` test phases |
| `lowLatencyEncoder` | true | API 30+: `KEY_LATENCY=1`, `KEY_PRIORITY=0`, plus `KEY_LOW_LATENCY=1` if `FEATURE_LowLatency` is supported |
| `profile` | baseline | baseline (Constrained Baseline, else Baseline) / main / high / auto; requested only if listed in `profileLevels`, else auto (logged); `KEY_MAX_B_FRAMES=0` always |
| `bitrateMode` | cbr | cbr / vbr, only if `isBitrateModeSupported` |
| `intraRefresh` | false | if `FEATURE_IntraRefresh`: `KEY_INTRA_REFRESH_PERIOD`=fps and a 10 s keyframe interval; keyframes on request still work |
| `keyframeIntervalS` | 2 | `KEY_I_FRAME_INTERVAL` when intra refresh is off |
| `fitToViewer` | false | encoded size = largest size inside the viewers' physical window (`w*dpr` x `h*dpr`; the largest viewer wins), phone aspect, capped by the preset's long side, multiples of 16; re-encode only if the size changes by more than 10% |
| `maxInFlight` | 6 | frames sent but not acknowledged (VideoOutbox); range 1..200 |
| `dropAfterMs` | 150 | H.264: drop waiting packets and wait for a keyframe only if the oldest waited longer; range 10..20000 |
| `adaptiveBitrate` | true | `stream/BitrateController` (pure), evaluated every 500 ms by `ScreenCapture` on the worst viewer. Congestion = real evidence only: outbox drops (congestion drops, not reset drops) or page skips (not vsync replacements) since the last tick, oldest waiting frame waited > `dropAfterMs`, or page `decodeQueue` > 2 (stats fresher than 3 s). Network lateness alone (`netLateMs`) and waits shorter than `dropAfterMs` are not congestion (the old rule "waited > `dropAfterMs`/2 = 75 ms" cut the target to 800-1300 kbit/s in the car with 0 drops and no delay benefit: car test 3). The target is cut only after congestion in 2 consecutive ticks: x 0.75 (not below the floor), then at least 1 s before the next cut. After 2 s without congestion: +25 % (at least 1 unit, not above the preset value), then again 2 s (800 to 4000 kbit/s in about 16 s). Applied with `MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)`, no rebuild; in JPEG mode the same controller moves the JPEG quality between 30 and the preset quality. The target resets to the preset value when the encoder is rebuilt. Shown in the encoder state, in `ping.target` (page overlay) and `stats.target` (log/state); changes logged at INFO, at most every 2 s |
| `minBitrateKbps` | 800 | floor of the H.264 target, kbit/s (clamped to the preset bitrate) |
| `jitterBufferMs` | 20 | page: buffer added to the due time (plan 2.4); settings migration 1 resets a saved 80 or 40 to 20 once |
| `skipLateFrames` | 2 | page: skip frames later than this many frame intervals; 0 = never |
| `pacing` | immediate | page: `immediate` draws every decoded frame at once; `vsync` keeps only the newest decoded frame (closing an older undrawn one, counted in `skipped`/`replaced`) and draws it from a `requestAnimationFrame` loop once per refresh; if no rAF callback ran for 100 ms (throttled), it draws at once (`hello.cfg.pacing`) |

The UI ("Advanced streaming options") shows the effective encoder state (profile from the SPS, which options the encoder accepted, encoded size); the same text is logged at INFO ("encoder state").

### 2.1.2 Automatic test (`test/`)

One button on the phone runs the whole comparison from the car. The test pattern (`TestPatternActivity`: moving bar, scrolling text, frame counter, millisecond clock, coloured noise, phase name and remaining time in a corner; Back aborts) or whatever is on screen is the content. Preconditions (mirroring running, not paused, at least one viewer) are waited for, never failed. Each phase of `TestPlan.phases(base)` (an editable list of full `StreamConfig`s; base = the owner's current settings; 7 phases: `default` = the owner's current settings as the baseline, `jitter-40ms` (the one robustness comparison for poor Wi-Fi), `codec-h264`, `codec-hevc`, `codec-vp9`, `codec-av1` (forced codecs; the last two with `allowSoftwareEncoder` so the S22 can try its software encoders), and `fps-60` (the owner's settings with the Smoothest preset, 60 fps; `PhaseSpec.preset`, applied with the config and restored for the other phases; its config id equals `default`'s, so stats are told apart by time only; if the stream fell back to 30 fps during the phase it is scored against 60 fps and noted "fell back to 30 fps") A forced-codec phase is skipped (status `skipped`, reason in the note, listed last without a rank) when `TestPlan.skipReason` finds that `CodecChooser.choose` would not pick that codec: no usable encoder on the phone, or a viewer cannot decode it in hardware (H.264: at all); the phase is otherwise run even if it equals what auto picks. Another codec measured anyway is still marked unsupported. Removed on 2026-11-18: legacy, no-lowlatency-encoder, profile-high, vbr, intra-refresh-on, fit-to-viewer-on, inflight-3, inflight-12, drop-after-50ms, drop-after-400ms, pacing-vsync, jitter-150ms, jpeg and the old hevc phase (no measurable difference in two car runs, docs/development/car-test-results.md), and then jitter-30ms, jitter-10ms and no-adaptive-bitrate (car and desk measurements settled jitter 20 ms and adaptive bitrate on); the options themselves remain in `StreamConfig` and the UI) is applied with `MirrorService.applyConfig`, then 5 s settle (not measured) and 40 s (Quick: 20 s) measured. Stats count only if their `cfgId` is the phase's, they cover time after the settle period, and they come from the same viewer. Socket-option changes reconnect the viewer so they take effect. A phase during which the viewer disconnected or reconnected is repeated once. Phases whose encoder option could not be applied are marked `unsupported` and listed last without a rank. At the end the owner's `StreamConfig`, preset and Force-JPEG are restored.

Score (lower is better): `e2e P95 + fps penalty + 2000 per reconnect + 250 per decoder reset + 5000 if the viewer disconnected`; fps penalty = `max(0, 0.8 x preset fps - average fps) / (0.8 x preset fps) x 1000`. E2e P95 is taken over all frames drawn in the measured window (from `stats.e2eRaw`). Each phase also reports the chosen mode, the exact codec string, the encoder name, hardware or software, and the phone's encode fps (frames out of the encoder per second in the measured window, `StreamMetrics.encodedFrames`). Reports: `filesDir/tests/<yyyyMMdd-HHmmss>-<runName>.json` and `.csv` (`adb shell run-as app.webmirror.android cat files/tests/<name>.csv`), listed in the app under "Test results" with a table sorted by score and a Share button; the page gets a `notice` "Test finished — best: <phase>".

### 2.1.3 Remote control (touch from the viewer)

Control starts ON in the page: when a hello says `allowed && ready` and nobody owns the control, the page asks for it at once. If the user taps the toggle OFF, the page remembers that in `localStorage` (try/catch) and does not ask automatically next time; tapping ON clears it. If another viewer owns the control the page shows "Another screen is controlling" and does not retry in a loop; it asks again once a hello says `ownerPresent:false` (one automatic request per period in which nobody owned it). The passenger can then operate the phone by touching the mirrored picture: tap, double tap, long press, swipe, drag, fling and multi-finger gestures (pinch, two-finger scroll/rotate). Typing is not part of it.

**Phone.** `input/RemoteInputService` is an `AccessibilityService` (`canPerformGestures="true"`, no event types, no window content) that injects the touches with `dispatchGesture`. `input/GestureEngine` (pure) turns the live events into frames: pointer down starts a stroke with `willContinue=true` (zero-length path, a touch that stays down), every following frame continues each active pointer's stroke with `continueStroke` from the end point of its previous segment, `up` ends it with `willContinue=false`. Taps, long presses and double taps result from the down/up timing; a tap or long press that began and ended while a frame was in flight becomes one stroke whose duration is the hold time. One frame = one `GestureDescription` with one stroke per active finger (a held finger that did not move is a zero-length continuation, otherwise its pointer would be lifted); the next frame is dispatched only in `onCompleted` of the previous one (dispatching while a gesture is in progress cancels it); events arriving meanwhile are coalesced to the newest position per finger. Segment duration = the page's time difference (so velocity, and with it fling, is preserved), clamped to 5..100 ms (taps: up to 1000 ms), played a quarter faster when the event had to wait, so the replay does not fall behind. Limits honoured: path with one contour and non-negative bounds, positive durations, max strokes per gesture = `min(10, GestureDescription.getMaxStrokeCount())` (extra fingers are ignored), a new touch never starts in the same frame in which another lifts. Coordinates: `DisplayGeometry` maps 0..1 onto `Display.getRealSize()` in the CURRENT rotation (the stream is a scaled copy of the whole display); when the rotation changes during a gesture, or the viewer's picture has the other orientation (`w`,`h`), the touches are lifted / the input is ignored. `onCancelled` (e.g. the user touches the real screen) forgets all touches. A touch held without any input for 15 s is lifted.

**Security model.**
1. *Phone switch:* "Allow control from viewers" in the app (`Settings.remoteControlAllowed`), **persisted, default ON**, never reset by mirroring start/stop; if the owner switches it off it stays off until he switches it on again. It has an effect only while the accessibility service is enabled. `hello.control = {allowed, ready, ownerPresent}` tells the viewers; `ready` = the accessibility service is connected, `ownerPresent` = some viewer owns the control. The hello is sent again when any of them changes.
2. *Accessibility permission:* Android requires the user to enable "WebMirror remote control" in Settings > Accessibility; the app only offers a button that opens that screen and never enables it itself.
3. *Single owner:* the first viewer that sends `control on` owns the control; others get `controlDenied busy`. The owner leaving (socket closed), turning control off, the switch going off or the service disconnecting releases it (touches are lifted; the owner is told through `control on:false, owner:false, reason`).
4. Input is validated and rate limited per viewer (120 messages per second), ignored from non-owners.
**Trade-off of the default:** while the switch is on (and the accessibility service enabled), anyone on the hotspot who opens the page controls the phone (the first one to ask wins). The protection is the hotspot password plus the pairing PIN (2.1.4, on by default). With the PIN switched off, anyone on the network who opens the page controls the phone.

**Page.** The compact toggle (104x44 CSS px, "Control on" green / "Control off" grey) and a 40x40 menu button live in the black area, never over the picture: `computeLayout` (player.js, tested by `app/src/test/js/layout.test.js` under node) always gives the picture the largest centred size that fits the window, exactly as without remote control, and puts the controls centred in the right bar (or the bottom bar when that one is larger), 4 px away from the picture. Only when neither bar is thick enough (controls + 8 px) the picture shrinks, by the smallest amount and symmetrically, so it stays centred; recomputed on resize, rotation and every `hello`. The bar is shown only while `hello.control.allowed`. While control is ON, touches on the picture are sent as `input` (Pointer Events with `touch-action:none`, Touch Events fallback) and do not open the overlay; the menu button opens it. A finger indicator in the bar shows how many pointers the browser currently reports (and the maximum) to find out whether the car's screen delivers multi-touch.

**Google Play risk.** Google Play's policy for the AccessibilityService API allows it only for services that help users with disabilities, unless the app is a clearly disclosed exception, and requires a prominent in-app disclosure and a Play Console declaration; using it to inject touches for remote control is a gamble for a Play listing and may be rejected. Plan: keep this feature working in sideloaded/personal builds, and for a Play release either remove it (a separate build flavour without the service) or submit it with the declaration and disclosure and accept the risk.

### 2.1.4 Pairing (PIN)

Setting "Require pairing PIN" (`Settings.pairingRequired`, **persisted, default ON**, also for existing installs). It protects watching and remote control; static files (page, scripts, probe) stay public, only `/ws` is protected. There is no exemption for loopback (other apps on the phone could connect to 127.0.0.1 and inject touches).

* The phone keeps a PIN of 4 digits (default) or 6 digits (`Settings.pairingPinLength`, chosen under Settings > Security > Viewer protection; `SecureRandom`, persisted, shown on the Home screen while protection is on; "New PIN" makes another one and does not unpair anyone; changing the length makes a new PIN of that length and keeps the pairing tokens; migration 2: installs that never chose a length get 4 digits and a new 4-digit PIN). "No protection" = `pairingRequired` false and a set of pairing tokens (192 bit random hex, persisted, at most 20, oldest dropped).
* Page `hello` carries `token` if the page has one. If pairing is required and the token is missing or unknown, the phone answers `pair {reason:"required"}` and sends **nothing else** to that session: no `hello`, no video, no `ping`, no control state; it ignores everything except `hello` and `pair`. The page shows a PIN pad and answers `pair {pin}`. Correct PIN: `paired {token}` followed by the normal `hello` and stream; wrong: `pair {reason:"wrong"}`.
* Rate limit, global (not per address, LAN addresses can be spoofed): 5 wrong PINs within 60 s lock all PIN attempts for 60 s (`pair {reason:"locked", retryInMs}`; the fifth wrong PIN is already answered with `locked`). A correct PIN during the lockout is refused too. PIN and tokens are compared in constant time (`MessageDigest.isEqual`).
* A session that is not paired is not a viewer: it is not registered in `StreamHub` (`ClientSession.admit` registers it), so it does not count for `viewerCount`, the codec/mode choice (`computeChoice`), the adaptive bitrate (`congestionSignals`), `viewerBox`, notices, pings or remote-control ownership. It is closed after 2 minutes without pairing. While the PIN panel is shown the page does not run its 3 s silence watchdog (no ping arrives).
* "Forget paired viewers" deletes all tokens and disconnects every viewer that got in through pairing; turning the setting ON disconnects viewers that were admitted without pairing; they reconnect and are asked for the PIN. The app shows the number of stored tokens.
* Pairing off: exactly the old behaviour (sessions are registered at open, `token` is ignored). The app warns: "Anyone on the same network can watch your screen and control the phone."
* The probe page reuses the stored token; when pairing is needed it says so and skips the throughput test.

Logic: `stream/Pairing.kt` (pure; injectable clock and random), tested by `PairingTest`; the page side (`player.js`) is a thin state machine.

### 2.2 Video modes
The phone can only attach one output to the screen capture at a time (Android 14+ allows one virtual display per capture session). So the server runs in **one mode for all viewers**, chosen by `CodecChooser` (see `codec` in 2.1.1): `h264`, `hevc`, `vp9` or `av1` when every connected viewer can decode the chosen codec as the rule there demands, and `jpeg` when some viewer cannot decode even H.264 (or "Force JPEG" is on). Back to a video mode when that viewer leaves. Every page implements the decoders (the four video modes share one WebCodecs implementation, configured from `hello.codec` with `optimizeForLatency`, no `description`, `prefer-hardware` where the capability check found hardware) and follows the `mode` in the latest `hello`.

### 2.3 Quality presets (starting values, tuned after car test M3)
| Preset | Longest side of the image | Max fps | H.264 bitrate | JPEG quality |
|---|---|---|---|---|
| Sharper | 1920 px | 15 | 6 Mbit/s | 70 |
| Smoother (default) | 1280 px | 30 (JPEG: 20) | 4 Mbit/s | 50 |
| Smoothest (60 fps) | 1280 px | 60 (JPEG: 20) | 6 Mbit/s | 50 |

Smoothest: `KEY_FRAME_RATE` and `max-fps-to-encoder` 60, keyframe interval in seconds unchanged (a GOP is 120 frames), `KEY_REPEAT_PREVIOUS_FRAME_AFTER` 100 ms as before. The adaptive bitrate controller works as for the other presets (ceiling 6 Mbit/s).

**Automatic fallback to 30 fps** (`stream/FpsGovernor`, pure, JVM-tested; every tunable is a constant in its companion). Active only with Smoothest and at least one viewer. `ScreenCapture` evaluates a window every 2 s with the viewers' last `stats` (`fps` = frames drawn per second, `decodeQueue`, page `dropped` and `skipped` minus vsync `replaced`) plus the outbox's congestion drops (`ClientSession.fpsViewer`) and the encoder's frames per second (`StreamMetrics.encodedFrames`). A window counts only while the screen is changing (encoder >= 45 fps; a static screen yields about 10 fps and breaks the streak). It is bad when any viewer with fresh stats draws < 45 fps, has a decode queue >= 4, or lost >= 5 % of its frames. Three bad windows in a row (about 6 s) cap the stream at 30 fps. The cap is applied by rebuilding the encoder at 30 fps (the same path as a preset change: new encoder, `setSurface` on the same display, one keyframe; `max-fps-to-encoder` is a configure-time key and is not changed live), with the bitrate ceiling `Preset.bitrateAt(30)` = 4 Mbit/s. 60 fps is retried after 60 s; a retry that fails again within 60 s of its start doubles the wait (120, 240, 480 s, at most 600 s), a retry that holds longer resets it. A preset change, a different set of viewers, and (for the grace period of 4 s) any encoder rebuild reset or pause the judgement. The decision is logged under the tag `WebMirror` ("fps governor: ..."). `hello.fps` is always the real rate; `hello.fpsFallback: true` (only present then) marks the fallback, shown by the page overlay (`1280x720 @30 fps (fallback)`) and the app (Home stream text, Settings > Debugging "Current stream" and encoder state). Player at 60 fps: frames are drawn at once (pacing "immediate", default) or once per `requestAnimationFrame` ("vsync"); the jitter buffer in ms is unchanged; the late-skip limit (`skipLateFrames` intervals) is never shorter than 60 ms (two 60 fps intervals are only 33 ms) and the VideoDecoder backlog that forces a decoder reset grows with the rate (3 up to 30 fps, 6 at 60 fps), both in `player.js` between FPS-MATH markers, tested by `app/src/test/js/fps.test.js`.
Size rule: scale the real screen size so the longest side equals the preset value (never upscale), keep aspect ratio, round each side **down to a multiple of 8**.

### 2.4 Sync and delay (client side)
- The page keeps `base = minimum over the last 200 packets of (performance.now() − pts/1000)`. This maps phone time to page time.
- A packet is due at `due = pts/1000 + base + bufferMs + offsetMs`.
  - `bufferMs` = 20 by default (`StreamConfig.jitterBufferMs`; smooths network jitter; 80 and then 40 were earlier defaults, car tests chose 20).
  - `offsetMs` = user slider 0…1500, default 0, stored in `localStorage` (wrapped in try/catch). This is the Bluetooth-audio compensation.
- Packets wait **encoded** in a queue (cheap) and are handed to the decoder when due. Decoded frames are drawn immediately.
- With browser audio and sound on, the audio clock replaces `performance.now()` as the reference; see 2.4.1.

### 2.4.1 Browser audio

Settings > Audio: Bluetooth (default, section 2.4 unchanged), **In the browser**, Off. `hello.audio` is an object: `{mode, codec, sampleRate:48000, channels:2, frameMs, profile}`; `codec`, `frameMs` and `profile` only in browser mode (`codec` is the format all viewers get).

**Phone (`audio/BrowserAudio.kt`, `stream/AudioPlan.kt`).**
- Capture: `AudioRecord` with `AudioPlaybackCaptureConfiguration.Builder(projection)` (usages MEDIA, GAME, UNKNOWN), 48 kHz stereo PCM16, a dedicated thread reading exactly one chunk per read (10 or 20 ms). `RECORD_AUDIO` is requested when the user selects "In the browser" (Settings > Audio) and from the Start flow (also the Quick Settings tile); if denied the stream goes on without sound (`hello.audio.mode:"off"`, a note in the app and a notice on the page).
- Capture runs only while mode = browser AND at least one viewer sent `{t:"audio", on:true}`; it starts and stops with the projection, and live on mode, profile or viewer changes (`StreamHub.audioRequester`), never touching the video encoder.
- Timestamps: same clock as the video pts (`System.nanoTime()/1000`). Each chunk is mapped from its frame index with `AudioRecord.getTimestamp(…, TIMEBASE_MONOTONIC)` (smoothed; `AudioPtsMapper`), fallback: read-return time of the chunk (earliest estimate wins).
- **Profiles** (`AudioProfile`, one table; the page has `AUDIO_PROFILES` for its buffers):

| Profile | Chunk | Format | Page buffer start / min / max | Grow on underrun | Shrink |
|---|---|---|---|---|---|
| Fast | 10 ms | PCM always (the Android Opus encoder has no frame-duration setting and always makes 20 ms frames; PCM has no codec lookahead). Opus values kept in the table (96 kbit/s, low complexity) for an encoder that can do 10 ms | 0.04 / 0.03 / 0.15 s | +0.01 s | −0.01 s after 15 s without underrun |
| High quality (default) | 20 ms | Opus 192 kbit/s, highest encoder complexity, PCM if Opus is not possible | 0.10 / 0.08 / 0.40 s | +0.03 s | −0.01 s after 60 s without underrun |

- **Format choice** (`AudioPolicy.codec`): one format for all viewers. Opus only if the profile allows it, the phone has an Opus encoder (`MediaCodecList`, e.g. `c2.android.opus.encoder`) and every audio-capable viewer reported `hello.audio.opus` (= `AudioDecoder.isConfigSupported({codec:'opus',sampleRate:48000,numberOfChannels:2})`; WebCodecs exists only on secure pages); otherwise PCM. The choice does not depend on who has sound on, so it does not flip when somebody taps. Joining/leaving viewers re-run it; a change is a new `hello` and a new encoder, packets are self-describing by their flags. If the Opus encoder fails at runtime the phone falls back to PCM for everybody. Raw Opus packets need no `description` in WebCodecs for plain stereo; the encoder's OpusHead config buffer is dropped.
- Transport (`ClientSession`): audio goes through a per-viewer `AudioBacklog` that the writer drains before every video frame and after text messages; capped at 300 ms of sound (oldest dropped). It is not acknowledged, not counted by `VideoOutbox` / `BitrateController`, and not in `bytesSent`.

**Page (`player.js`).**
- A "Tap for sound" button (104×44 px, in the black bar next to the control button; the bar appears for it even without remote control, picture layout unchanged) creates `AudioContext({sampleRate:48000, latencyHint:'interactive'})` (webkit fallback). The same button then mutes/unmutes. The choice is kept in `localStorage`; a remembered "on" resumes at the next touch. Muting tells the phone `on:false`.
- Decode: Opus → `AudioDecoder` → `AudioData.copyTo(f32-planar)`; PCM: Int16 → Float32. Each chunk is an `AudioBufferSourceNode` started at its scheduled time (an overlap after a re-anchor is trimmed with the source offset, not played twice).
- **Audio is the master clock.** At the first packet `audioBase = ctx.currentTime + target − pts`; a chunk plays at `pts + audioBase`. Underrun (chunk start already past): target grows by the profile's step (max per profile), re-anchor. Drift (scheduled time more than `target + 0.1 s` ahead of `currentTime`): re-anchor. Quiet period (15 s Fast / 60 s Quality) without underrun: target shrinks by 0.01 s down to the profile's minimum. Reset (new format, reconnect, tab shown again) re-anchors; the adapted target stays. A profile switch takes the new profile's start target.
- **Video follows audio** while sound is playing and audio packets keep arriving (<600 ms gap): a picture with pts P is due when the sound for P is heard: `heardContextTime ≥ P/1e6 + audioBase + trim`. The heard time is `AudioContext.getOutputTimestamp()` (context time currently output, paired with `performance.now()`), or `currentTime − outputLatency` (`baseLatency`) where that is missing. Otherwise (sound off, muted, not started, stale) the normal jitter-buffer timing of 2.4 is unchanged. The delay slider is the fine trim in this mode (−300…+300 ms, own `localStorage` key); in all other cases it is the Bluetooth delay 0…1500 ms as before. Expect the picture delay to grow to about the audio buffer plus output latency (≈ 0.1–0.15 s).
- Stats overlay: audio format, chunk size, profile, state, buffer ms (target), underruns, decoder errors; `stats` messages carry `audioBufMs`, `audioUnderruns`.


---

## 3. Project layout (final)

```
WebMirror/
├── .gitignore                      # standard Android + *.p12 *.jks *.pem local.properties
├── README.md                       # how to build, run, test in the car
├── docs/development/PLAN.md        # copy of this plan
├── docs/development/car-test-results.md  # filled in after each car test
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/assets/web/        # the web client, plain files, no build step
        │   ├── index.html  player.js  style.css
        │   └── probe.html  probe.js
        ├── main/java/app/webmirror/android/
        │   ├── MainActivity.kt             # Compose UI
        │   ├── MirrorService.kt            # foreground service, owns everything below
        │   ├── Settings.kt                 # SharedPreferences wrapper
        │   ├── capture/ScreenCapture.kt    # MediaProjection + VirtualDisplay
        │   ├── capture/CaptureSize.kt      # preset → width/height (pure function)
        │   ├── encode/VideoEncoder.kt      # MediaCodec, H.264 and HEVC (+ AvcUtil, HevcUtil)
        │   ├── encode/JpegEncoder.kt       # ImageReader → Bitmap → JPEG
        │   ├── encode/AvcUtil.kt           # codec string from SPS (pure function)
        │   ├── audio/AudioCapture.kt       # M5
        │   ├── stream/Packet.kt            # header encode (pure function)
        │   ├── stream/StreamHub.kt         # fan-out, GOP cache, mode switching
        │   ├── stream/StreamSink.kt        # interface
        │   ├── server/WebServer.kt         # NanoWSD subclass, HTTP + WS
        │   ├── server/ClientSession.kt     # per-viewer queue + writer thread
        │   ├── net/VirtualIpService.kt     # VpnService (M1)
        │   ├── net/Addresses.kt            # list local IPs, build URLs
        │   └── net/TlsStore.kt             # import/load PKCS#12 (M1)
        └── test/java/app/webmirror/android/   # JVM unit tests
```

`applicationId` and package: `app.webmirror.android` (can be changed any time before the first Play upload, never after).

**Libraries (add to `libs.versions.toml`; use the newest stable version of each at the time of writing):**
- `org.nanohttpd:nanohttpd:2.3.1` and `org.nanohttpd:nanohttpd-websocket:2.3.1` — tiny HTTP + WebSocket server with TLS support.
- `com.google.zxing:core` — QR code generation.
- Jetpack Compose + Material 3 (as generated by the Android Studio wizard).
- Tests: JUnit 4 (as generated).

**Web client coding rules (the Tesla browser may be an old Chromium):**
- No build tools, no frameworks, no ES modules. One `<script src>` per page.
- Allowed syntax: `const/let`, arrow functions, `async/await`, template strings. **Not allowed:** optional chaining `?.`, `??`, BigInt, top-level await, private class fields.
- Every newer API is feature-checked before use (`if ('VideoDecoder' in window)`).

---

## 4. Milestones

Each milestone ends with a **Done when** list. Do not start the next milestone until every item passes. Commit to git at the end of every numbered step.

### M0 — Toolchain, skeleton app, web server, probe page

**M0.1 Install tools**
1. Install Android Studio (latest stable): `brew install --cask android-studio`. Start it, run the setup wizard with default options (installs the SDK, platform tools, an emulator image).
2. In Android Studio → Settings → Build Tools → Gradle, keep "Gradle JDK" on the bundled JDK.
3. On the phone: enable Developer options and USB debugging. Run `adb devices` and confirm the phone is listed as `device`.

**M0.2 Create the project**
1. Android Studio → New Project → "Empty Activity" (Compose). Name `WebMirror`, package `app.webmirror.android`, save location = the existing project folder, language Kotlin, **Minimum SDK API 29**, build config Kotlin DSL. Keep the compile/target SDK the wizard proposes.
2. `git init`, add `.gitignore` (Android template plus `*.p12`, `*.jks`, `*.pem`, `*.keystore`), first commit.
3. Copy this plan to `docs/development/PLAN.md`.
4. Run the app on the phone once to prove the toolchain works.

**M0.3 Manifest**
Add permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.
Declare `<service android:name=".MirrorService" android:foregroundServiceType="mediaProjection" android:exported="false"/>`.

**M0.4 `server/WebServer.kt`**
- `class WebServer(port: Int, private val assets: AssetManager, private val hub: StreamHub) : NanoWSD(port)`.
- Override `serveHttp(session)`:
  - Map `/` → `web/index.html`; any other path → `web/<path>`. Reject paths containing `..` with 404.
  - Content types: `.html` text/html, `.js` application/javascript, `.css` text/css.
  - Add header `Cache-Control: no-store` (so updated app versions are never served stale).
- Override `openWebSocket(handshake)`: accept only path `/ws`; return a `ClientSession`.
- Start with `start(0, true)`. **The `0` matters:** NanoHTTPD's default 5-second read timeout would kill idle WebSockets.
- Before writing code, open the NanoWSD source in the IDE (Ctrl/Cmd-click) and confirm the exact method names for version 2.3.1.

**M0.5 `server/ClientSession.kt`**
- Extends `NanoWSD.WebSocket`.
- Owns a `LinkedBlockingDeque<Packet>` (max 120 items) and **one writer thread** that takes packets and calls `send(bytes)`. No other thread may call `send`. This prevents interleaved frames and stops one slow viewer from blocking the encoder.
- If the queue is full: clear it, set `needsKeyframe = true`, and drop H.264 delta packets until the next keyframe arrives (then clear the flag). Tell the hub to request a keyframe.
- `onMessage`: parse JSON text messages, dispatch by `t`. Ignore unknown types. Wrap in try/catch; malformed input must never crash the app.
- `onClose` / `onException`: stop the writer thread, unregister from the hub.

**M0.6 `stream/Packet.kt`, `StreamSink.kt`, `StreamHub.kt` (minimal for now)**
- `Packet(type: Int, flags: Int, ptsUs: Long, payload: ByteArray)` with `fun toBytes(): ByteArray` producing the 12-byte header + payload exactly as in §2.1.
- `interface StreamSink { fun onHello(json: String); fun onPacket(p: Packet) }`.
- `StreamHub`: thread-safe list of sessions (`CopyOnWriteArrayList`), `broadcast(packet)`, `broadcastHello()`. Mode logic and GOP cache come in M2/M4.
- Unit test `PacketTest`: encode a packet with `ptsUs = 0x1_0000_0002`, assert bytes 4–11 are `00 00 00 01 00 00 00 02`.

**M0.7 `MirrorService.kt` (server only for now)**
- In M0 and M1 this is a normal started service (`startService`) that only starts and stops `WebServer`. Do **not** call `startForeground` yet: the `mediaProjection` service type may only be used after the user grants screen-capture consent, which arrives in M2.1. Consequence: until M2, keep the app open on screen while testing.
- Create the notification channel and the ongoing notification ("WebMirror is running", action "Stop") now, so M2 only has to add the `startForeground` call.
- Expose state through a `StateFlow<ServiceState>` in a companion object: `running`, `urls`, `viewerCount`, `lastStats`, `lastProbe`.

**M0.8 `net/Addresses.kt` and `MainActivity.kt`**
- `Addresses.localIpv4()`: iterate `NetworkInterface.getNetworkInterfaces()`, return every non-loopback IPv4 address with its interface name.
- UI (one screen): Start/Stop button, list of URLs (`http://<ip>:8080`), a QR code of the first URL, viewer count, and a scrollable text box showing the last probe result JSON with a "Share" button (Android share sheet).

**M0.9 Probe page `assets/web/probe.html` + `probe.js`**
Collect and display as a table, then send as `{t:"probe", results}` over `/ws`:
- `navigator.userAgent`, `location.href`, `window.isSecureContext`, screen size, `devicePixelRatio`.
- Presence of: `WebSocket`, `VideoDecoder`, `AudioContext`/`webkitAudioContext`, `AudioWorklet`, `createImageBitmap`, `OffscreenCanvas`, `MediaSource`, `RTCPeerConnection`, `localStorage` (test inside try/catch).
- If `VideoDecoder` exists: `await VideoDecoder.isConfigSupported({codec, codedWidth, codedHeight})` for codecs `avc1.42E01F` (Baseline 3.1), `avc1.4D401F` (Main 3.1), `avc1.640028` (High 4.0), each at 1280×720 and 1920×1080. Record `supported` for each.
- Canvas speed: draw a 1280×720 `ImageBitmap` to a canvas in a `requestAnimationFrame` loop for 3 s, report average fps.
- WebSocket throughput: ask the phone (control message `{t:"blast"}`) to send 5 MB of dummy binary packets of 50 kB; report Mbit/s received. Implement the `blast` handler in `ClientSession`.
- A `<video>` element check: create one, call `canPlayType('video/mp4; codecs="avc1.42E01F"')`, record the string.

**Done when**
- `./gradlew test` passes.
- Laptop on the same Wi-Fi opens `http://<phone-ip>:8080/probe.html`, sees the table, and the JSON appears in the app.
- Laptop joined to the **phone's hotspot** can do the same.
- Killing the browser tab reduces the viewer count within 5 s.

---

### M1 — Getting the car to open the page (addresses, virtual IP, HTTPS) + car test 1

Purpose: answer U1, U2, U3 before writing any video code.

**M1.1 `net/VirtualIpService.kt`** — give the phone an extra, public-looking IP address
- Class extends `android.net.VpnService`. Manifest entry with `android:permission="android.permission.BIND_VPN_SERVICE"` and the `android.net.VpnService` intent filter.
- Start flow in `MainActivity`: `VpnService.prepare(context)`; if it returns an Intent, launch it and wait for `RESULT_OK` (system "connection request" dialog); then start the service.
- In the service:
  ```kotlin
  val tun = Builder()
      .setSession("WebMirror virtual address")
      .addAddress(virtualIp, 32)          // e.g. "198.51.100.1"
      .addAllowedApplication(packageName) // do not affect any other app
      .setBlocking(true)
      .establish()                        // keep `tun` open; close it to stop
  ```
  Add **no routes and no DNS servers**. We never read or write the tunnel. Its only job is to make the phone own `virtualIp`, so that packets a hotspot client sends to that address are delivered to our server.
- Setting "Virtual address" in the app: free-text IPv4 field with two quick-pick buttons: `198.51.100.1` (reserved documentation range, collides with nothing) and `3.3.3.3` (a real public address used by a similar open project; it is only shadowed locally for devices on the phone's hotspot).
- When active, the UI shows extra URLs `http://<virtualIp>:8080`.
- **This mechanism is an assumption.** Verify at the desk first: laptop on the phone's hotspot runs `curl -v http://198.51.100.1:8080/probe.html`. If it times out, capture `adb shell ip addr`, `adb shell ip rule`, `adb shell ip route show table all` and stop for a design review (fallback ideas: add `addRoute(virtualIp, 32)` and answer packets from the tunnel with a small user-space TCP stack; or abandon virtual IP and rely on a DNS name).

**M1.2 `net/TlsStore.kt`** — optional HTTPS on port 8443
- UI button "Import certificate (.p12)": Storage Access Framework file picker → copy to app-private storage → ask for the password → store the password with `EncryptedSharedPreferences` (or Android Keystore-wrapped).
- Load: `KeyStore.getInstance("PKCS12")` → `KeyManagerFactory` → `SSLContext.getInstance("TLS")` → `webServerTls.makeSecure(sslContext.serverSocketFactory, null)`.
- Run a second `WebServer` instance on 8443 sharing the same `StreamHub`. HTTP on 8080 always stays available.
- The certificate is **never** committed to git or packaged in the APK.

**M1.3 Domain and certificate (one-time, on the Mac; user action needed)**
1. Get a hostname: either a domain the user owns, or a free `something.duckdns.org`.
2. Set its A record to the chosen virtual IP (and, for the test, a second name pointing to the hotspot's private IP).
3. Issue a Let's Encrypt certificate with the DNS-01 challenge (e.g. `acme.sh --issue --dns dns_duckdns -d name.duckdns.org`). No server needs to be reachable from the internet.
4. Convert: `openssl pkcs12 -export -inkey key.pem -in fullchain.pem -out webmirror.p12`. Copy to the phone, import in the app.
5. Note the expiry date in `README.md` (renew every ~60 days).

**M1.4 Car test 1** — fill `docs/development/car-test-results.md`
Setup: phone hotspot on, mobile data on, car Wi-Fi joined to the hotspot with "Remain connected in Drive" enabled, app started, virtual IP on.
Open `/probe.html` through each row; record "loads?" and press Share in the app to save each JSON.

| # | URL typed in the car | Tests |
|---|---|---|
| 1 | `http://<hotspot-ip>:8080` | private IP directly |
| 2 | `http://<name-for-private-ip>:8080` | DNS name → private IP |
| 3 | `http://198.51.100.1:8080` | virtual IP, reserved range |
| 4 | `http://3.3.3.3:8080` | virtual IP, public-looking |
| 5 | `http://<name>:8080` | DNS name → virtual IP |
| 6 | `https://<name>:8443` | HTTPS → secure context → WebCodecs |

Repeat the rows that worked while a passenger watches with the car in Drive, to confirm the page stays up.

**Decision gate after M1 (write the decision into `docs/development/car-test-results.md`):**
- Which URL form is the official one for the car.
- `VideoDecoder` present and H.264 supported on the HTTPS row → car mode is H.264, HTTPS is mandatory for the car.
- Otherwise → car mode is JPEG; do M4 before M3 and treat HTTPS as optional.
- If no row loads at all → stop and review (next option: study how TeslAA/TslaMirror route, e.g. tunnel routes + packet handling).

**Done when** the table is filled, JSON files are saved in `docs/development/`, and the decision is written down.

---

### M2 — Screen capture + H.264 + WebCodecs player (desk milestone, test with laptop Chrome)

Developer shortcut: `adb forward tcp:18080 tcp:8080` (local port 18080, because this Mac already has another program on localhost:8080), then open `http://127.0.0.1:18080` in desktop Chrome. `127.0.0.1` counts as a secure page, so WebCodecs works without any certificate.

**M2.1 Consent and service start order (get this exactly right; wrong order crashes on Android 14+)**
1. `MainActivity`: on Android 13+ request `POST_NOTIFICATIONS`.
2. User taps Start → `mediaProjectionManager.createScreenCaptureIntent()` → launch with `ActivityResultContracts.StartActivityForResult`.
3. On `RESULT_OK`: `startForegroundService(Intent(this, MirrorService::class.java).putExtra("code", resultCode).putExtra("data", data))`.
4. In `MirrorService.onStartCommand`: **first** `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`, **then** `getMediaProjection(code, data)`.
5. Immediately `projection.registerCallback(callback, handler)` (mandatory on Android 14+ before creating the display). In `callback.onStop()` → stop everything and `stopSelf()` (the user can end capture from the status bar).
6. The consent result can be used **once**. Never store it for a later session.

**M2.2 `capture/CaptureSize.kt`** — pure function `compute(screenW, screenH, maxLongSide): Size` per §2.3. Unit tests: 1080×2400 @1920 → 864×1920; 1080×2400 @1280 → 576×1280; 2400×1080 @1280 → 1280×576; 720×1280 @1920 → 720×1280 (no upscale).

**M2.3 `encode/VideoEncoder.kt` (was H264Encoder)**
```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
    setInteger(KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
    setInteger(KEY_BIT_RATE, preset.bitrate)
    setInteger(KEY_FRAME_RATE, preset.fps)
    setInteger(KEY_I_FRAME_INTERVAL, 2)                    // keyframe every 2 s
    setLong(KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)      // static screen still yields ≥10 fps
    setFloat(KEY_MAX_FPS_TO_ENCODER, preset.fps.toFloat()) // caps 60/120 Hz screens
}
codec = MediaCodec.createEncoderByType(MIMETYPE_VIDEO_AVC)
codec.setCallback(callback, handler)   // handler on its own HandlerThread
codec.configure(format, null, null, CONFIGURE_FLAG_ENCODE)
inputSurface = codec.createInputSurface()
codec.start()
```
- Do **not** set a profile; the default has no B-frames, which is what we need for low delay.
- In `onOutputBufferAvailable(index, info)`:
  - Copy the bytes out of the buffer, then `releaseOutputBuffer(index, false)`.
  - If `info.flags and BUFFER_FLAG_CODEC_CONFIG != 0`: this is SPS+PPS. Store as `configBytes`; compute `codecString = AvcUtil.codecString(configBytes)`; tell the hub (`hub.onVideoConfig(...)`). Do not send it as a packet.
  - Else: `key = info.flags and BUFFER_FLAG_KEY_FRAME != 0`. Payload = `configBytes + data` if key, else `data`. `hub.onPacket(Packet(1, if (key) 1 else 0, info.presentationTimeUs, payload))`.
- `requestKeyframe()`: `codec.setParameters(Bundle().apply { putInt(PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })`.
- `stop()`: `codec.stop(); codec.release(); inputSurface.release()`; all inside try/catch.

**M2.4 `encode/AvcUtil.kt`** — pure function. Find the first NAL unit after a start code (`00 00 01` or `00 00 00 01`) whose type (`byte and 0x1F`) is 7 (SPS). The three bytes after the NAL header byte are profile, constraints, level. Return `"avc1." + those three bytes as 6 upper-case hex digits`. Unit test with `00 00 00 01 67 42 C0 1F ...` → `avc1.42C01F`.

**M2.5 `capture/ScreenCapture.kt`**
- Real screen size: `windowManager.maximumWindowMetrics.bounds` on API 30+, `display.getRealMetrics` on API 29.
- Create the display **once per projection**: `projection.createVirtualDisplay("WebMirror", w, h, densityDpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder.inputSurface, null, handler)`.
- To change size or target (rotation, preset change, mode switch) **never create a second display**. Do: build the new encoder → `virtualDisplay.resize(w, h, dpi)` → `virtualDisplay.setSurface(newSurface)` → stop the old encoder.
- Rotation: register a `DisplayManager.DisplayListener`; on change, recompute the size; if width/height changed, reconfigure as above and have the hub broadcast a new `hello`.

**M2.6 `stream/StreamHub.kt` (full version)**
- Holds current `HelloInfo` (mode, codec, width, height, fps, audio).
- **GOP cache:** a list of H.264 packets since the last keyframe. On keyframe: clear, then add. On delta: add. If it exceeds 300 packets, call `encoder.requestKeyframe()`.
- When a session completes its `hello`: send it the phone's `hello` JSON, then every packet in the GOP cache, then live packets. This gives a new viewer a picture instantly even if the screen is static.
- On `kf` from a client: `encoder.requestKeyframe()` (at most once per 500 ms).
- All shared state guarded by one lock or confined to one thread.

**M2.7 Web player `index.html`, `style.css`, `player.js`**
- Page: black background, one `<canvas>` centred and scaled with CSS `object-fit: contain` behaviour (compute size in JS on `resize`), a hidden overlay (shown on tap) with: status text, stats, delay slider, fullscreen button.
- `<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">`.
- Connect: `new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws')`, `binaryType = 'arraybuffer'`.
- On open: check H.264 support (`'VideoDecoder' in window` and `isConfigSupported` for `avc1.42E01F`), send `hello`.
- On phone `hello` with `mode:"h264"`: (re)create the decoder:
  ```js
  decoder = new VideoDecoder({ output: onFrame, error: onDecoderError });
  decoder.configure({ codec: hello.codec, optimizeForLatency: true });
  waitingForKey = true;
  ```
  No `description` field: that tells the browser the stream is Annex-B with SPS/PPS inside keyframes, which is what the phone sends.
- On binary message: parse header (`DataView`), push `{type, key, pts, data}` to the jitter queue (§2.4).
- Pump (every `requestAnimationFrame`): for each queued packet that is due: if `waitingForKey` and not key → drop; else `decoder.decode(new EncodedVideoChunk({type: key ? 'key' : 'delta', timestamp: pts, data}))`.
- `onFrame(frame)`: resize canvas to `frame.displayWidth/Height` if changed; `ctx.drawImage(frame, 0, 0)`; **`frame.close()`** (forgetting this leaks GPU memory and freezes the page after a few seconds).
- Overload protection: if `decoder.decodeQueueSize > 10` → drop queued packets until the next keyframe, set `waitingForKey = true`, send `{t:"kf"}`.
- `onDecoderError`: log, recreate the decoder, send `{t:"kf"}`.
- Reconnect: on socket close, retry every 1 s forever; show "Reconnecting…".
- Every 2 s send `stats`.
- When the tab becomes visible again (`visibilitychange`): flush queue, `waitingForKey = true`, send `kf`.

**Done when**
- Desktop Chrome shows the phone screen; scrolling on the phone looks smooth; measured glass-to-glass delay under 300 ms (film both screens showing a stopwatch).
- Rotating the phone updates the picture within 1 s with correct aspect ratio.
- Opening a second browser tab shows a picture within 0.5 s even when the phone screen is static.
- 30 minutes of continuous mirroring: no growth in page memory (Chrome Task Manager), no app crash.
- Stopping capture from the Android status bar stops the service cleanly; Start works again afterwards.
- Unit tests for `CaptureSize`, `AvcUtil`, `Packet` pass.

---

### M3 — Delay offset, presets, stats, keep-awake + car test 2

1. **Delay slider** in the page overlay (0–1500 ms, step 10 ms, big touch targets, plus −/+ buttons). Persist per browser.
2. **Audio mode setting** in the app: `Bluetooth (car speakers)` / `Browser` (disabled until M5) / `Off`. Sent in `hello.audio`. In Bluetooth mode the overlay shows the hint "Adjust delay until lips match sound".
3. **Preset switch** in the app (Sharper / Smoother). Changing it reconfigures encoder + display as in M2.5 and re-sends `hello`.
4. **Stats**: overlay shows fps, decode queue, dropped frames, resolution, mode. App shows the last `stats` per viewer.
5. **Keep screen on** setting: while mirroring hold `PowerManager.SCREEN_DIM_WAKE_LOCK` (deprecated but functional; add `@Suppress("DEPRECATION")`). Release on stop.
6. **Sync helper**: page `/sync.html` is not needed; instead document in README: play any "audio video sync test" clip on the phone and move the slider until the flash matches the beep.

**Car test 2** (record in `docs/development/car-test-results.md`): for each preset, 5 minutes parked and 5 minutes driving as passenger: fps, dropped frames, queue size, subjective smoothness; the delay value that gives lip sync over Bluetooth; whether the stream survives a Park → Drive change and a phone signal loss.
Then **tune the numbers in §2.3** to the highest settings the car holds with zero growing queue.

**Done when** audio over Bluetooth and picture are in sync in the car to within what the eye can tell, and the stream runs 20 minutes while driving without manual reload.

---

### M4 — JPEG fallback mode

1. `encode/JpegEncoder.kt`: `ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)`. `setOnImageAvailableListener` on a HandlerThread:
   - `acquireLatestImage()`; if less than `1000/fps` ms since the last sent frame → `close()` and return.
   - Copy to a reusable `Bitmap`: plane 0 has `rowStride` which may be larger than `w*4`. Create the bitmap with width `rowStride / pixelStride`, `copyPixelsFromBuffer`, then crop to `w` when compressing (or draw into a second `w×h` bitmap).
   - `bitmap.compress(JPEG, preset.jpegQuality, reusableByteArrayOutputStream)`.
   - `hub.onPacket(Packet(2, 0, image.timestamp / 1000, bytes))`; always `image.close()` in `finally`.
2. `StreamHub` mode logic per §2.2: on every viewer join/leave recompute the mode. On change: build the new encoder, `virtualDisplay.setSurface(...)`, stop the old one, broadcast `hello`. Cache the **last JPEG** and send it to new viewers.
3. `player.js` JPEG path: when due, `createImageBitmap(new Blob([data], {type:'image/jpeg'}))` → `drawImage` → `bitmap.close()`. If `createImageBitmap` is missing, use an `Image` with `URL.createObjectURL` and revoke the URL in `onload`. Never decode more than one image at a time: if one is in progress, keep only the newest pending packet.
4. App setting "Force JPEG mode" (for testing and as a manual override in the car).

**Done when** a browser with WebCodecs disabled (open over plain `http://<lan-ip>:8080` in desktop Chrome — not localhost — where `VideoDecoder` is unavailable) shows the stream; an H.264 viewer connected at the same time switches to JPEG and back when the other leaves; no memory growth over 30 minutes.

---

### M5 — Audio through the browser

**Superseded by 2.4.1** (Opus instead of PCM as the normal format, profiles, adaptive buffer). Original plan, kept for reference:

1. Manifest: add `RECORD_AUDIO`; request it at runtime when the user selects audio mode "Browser". Min API 29 already satisfies playback capture.
2. `audio/AudioCapture.kt`:
   - `AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(USAGE_MEDIA).addMatchingUsage(USAGE_GAME).addMatchingUsage(USAGE_UNKNOWN).build()`.
   - `AudioRecord.Builder().setAudioFormat(48 kHz, stereo, PCM_16BIT).setAudioPlaybackCaptureConfig(config).build()`.
   - Thread loop: read 3840 bytes (20 ms). `pts = System.nanoTime()/1000 − 20_000`. `hub.onPacket(Packet(3, 0, pts, bytes))`.
   - Known limits to state in the UI: apps can forbid capture (some music/video apps will be silent); protected video shows black.
   - Verify on the test phone whether the phone's own speaker must be muted and whether muting media volume also silences the capture; document the result and, if needed, show a hint.
3. `player.js` audio path:
   - A "Tap to start sound" button creates `new (window.AudioContext || window.webkitAudioContext)({sampleRate: 48000})` (browsers require a user gesture).
   - For each PCM packet: convert Int16 → Float32 into an `AudioBuffer`; schedule with `AudioBufferSourceNode.start(nextTime)`; `nextTime += 0.02`. First packet: `nextTime = ctx.currentTime + 0.15` and remember `audioBasePts = pts`, `audioBaseTime = nextTime`.
   - If `nextTime` falls behind `ctx.currentTime` (underrun) or runs more than 0.4 s ahead: reset the mapping as for the first packet.
   - **Audio is the master clock:** in this mode a video packet is due when `ctx.currentTime ≥ audioBaseTime + (pts − audioBasePts)/1e6 + offsetMs/1000`. Reuse the same slider as a fine trim (allow negative values, −300…+300 ms, in this mode).
4. Each viewer can mute locally (overlay button); the phone setting decides whether audio packets are produced at all.

**Done when** a laptop plays picture and sound from the phone with lip sync error not visible on a sync-test clip, for 30 minutes without drift; switching audio mode in the app takes effect without reconnecting.

---

### M6 — Google Play readiness

1. **Access PIN:** app shows a random 4-digit PIN (regenerate button). Page shows a numeric keypad when the phone answers `denied: pin`; PIN remembered in `localStorage`. Server: max 5 wrong attempts per minute per IP, then refuse for 1 minute. Setting to disable the PIN (off = warning text).
2. **Onboarding screen** (first start): what the app does, what is captured, that everything stays on the local network, and a safety notice: the driver must not watch the screen while driving; the user is responsible for following local law.
3. **Privacy policy** (hosted page + link in app): no data leaves the device; no analytics.
4. **Release build:** signing config from a keystore outside the repo, R8 enabled; keep rules for NanoHTTPD if needed; verify the release build end-to-end.
5. **Play Console declarations:** foreground service type `mediaProjection` (short demo video), `VpnService` usage declaration (explain: device-local virtual address only, no traffic is tunnelled), Data safety form (no data collected), content rating.
6. **Certificates for the public:** the personal `.p12` import stays as "advanced". A general solution (per-install certificates issued by a small backend under our own domain) is a **separate future project**; until then public users get JPEG mode over HTTP, or H.264 where the browser allows WebCodecs without HTTPS.
7. Robustness pass: start/stop 20 times, hotspot off/on, incoming call, screen lock, low-memory kill → the notification and state are always correct.

---

### Future (not in this plan, hooks already in place)
- **Typing from the browser:** touch is done (see "Remote control"); typing would be a new `input` kind executed with `ACTION_SET_TEXT`.
- **Internet streaming:** a `RelaySink` implementing `StreamSink` connects out to a relay server that forwards the same protocol to remote viewers; add Opus audio and adaptive bitrate at that point.

---

## 5. Verification summary

| Level | How |
|---|---|
| Unit | `./gradlew test` — `PacketTest`, `CaptureSizeTest`, `AvcUtilTest`. |
| Desk, H.264 | `adb forward tcp:18080 tcp:8080` → Chrome `http://127.0.0.1:18080`. Stopwatch delay test, rotation, second tab, 30-minute soak with Chrome Task Manager open. |
| Desk, JPEG | Chrome at `http://<phone-lan-ip>:8080` (no WebCodecs on plain HTTP) and the "Force JPEG" setting. |
| Desk, virtual IP | Laptop on the phone hotspot: `curl -v http://<virtual-ip>:8080/probe.html`. |
| Desk, HTTPS | Laptop with a hosts-file entry for the certificate's name → `https://<name>:8443`, padlock valid, WebCodecs active. |
| Car test 1 (M1) | Address matrix + probe JSON → decision gate. |
| Car test 2 (M3) | Presets, Bluetooth delay value, parked and driving, 20-minute run. |
| Release (M6) | Release APK on a clean phone, full flow, robustness list. |

Debug aids to build in from the start: log tag `WebMirror` everywhere; `stats` from every viewer visible in the app; "Share probe result" button.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Virtual IP via `VpnService` does not deliver hotspot traffic on some phones | Desk test in M1.1 before the car; documented fallback and stop-for-review point |
| Tesla browser has no WebCodecs / H.264 broken by a car update | JPEG mode (M4) is a full alternative; "Force JPEG" switch |
| Certificate key cannot be shipped in a public app | Personal import now; backend-issued certificates as a later project |
| Play review questions `VpnService` use | Clear declaration; if refused, ship Play version without virtual IP and keep it in a sideload build |
| Apps blocking capture (black video / silent audio) | Cannot be solved; state it in onboarding |
| MCU2 too slow for 1080p-class decode | Presets are data, tuned in car test 2 |

## 7. Sources for the Tesla-specific assumptions
- Private-address blocking: teslamotorsclub.com threads "Redirect browser to private IP range" and "When in Tesla and on wifi I can't browse to local sites"; Firewalla community post "Accessing local servers from my Tesla via Wifi".
- Virtual IP through a local VPN interface: TslaMirror app FAQ (App Store listing); TeslAA coverage (Android Central, Electrek).
- H.264 regression after Tesla 2025.8.4: TslaMirror App Store release notes.
