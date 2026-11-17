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

**Current state of the machine.** Project folder `/Users/danielkosc/Documents/Projects/WebMirror` is empty and not a git repo. Installed: JDK 17, `adb`, `node`. Missing: Android Studio, Android SDK, Gradle.

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
│  ├ H264Encoder (MediaCodec)  ─┐           │ packets  │   │          → canvas        │
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
| 0 | 1 | `type` | `1` = H.264 access unit, `2` = JPEG image, `3` = PCM audio (M5), `0` = probe throughput dummy (M0 `blast`; ignored by the player) |
| 1 | 1 | `flags` | bit 0 = keyframe (only for type 1) |
| 2 | 2 | reserved | `0` |
| 4 | 4 | `ptsHi` | upper 32 bits of presentation time in microseconds |
| 8 | 4 | `ptsLo` | lower 32 bits |
| 12 | n | payload | type 1: Annex-B H.264 (start codes `00 00 00 01`), keyframes always start with SPS+PPS. type 2: a complete JPEG file. type 3: PCM 16-bit little-endian, 48 000 Hz, stereo interleaved. |

`pts` is the phone's monotonic clock (`System.nanoTime()/1000`). Video and audio use the same clock; that is what makes sync possible. In JavaScript read it as `hi * 4294967296 + lo` (do not use BigInt, old browsers lack it).

**Text WebSocket message** = one JSON object with a `t` field.

| Direction | `t` | Fields | Meaning |
|---|---|---|---|
| page → phone | `hello` | `v:1, h264:boolean, pin:string` | First message. `h264` = result of the capability check. |
| phone → page | `hello` | `v:1, mode:"h264"\|"jpeg"\|"paused", codec:"avc1.xxxxxx", width, height, fps, audio:"bluetooth"\|"browser"\|"off"` | Stream description. Sent again whenever anything in it changes (rotation, preset, mode). `mode:"paused"`: screen capture ended on the phone (screen lock, status-bar chip, system) but the server and the viewer connections stay up; no packets follow until a normal `hello` arrives after the user taps Start again. The page keeps its last picture and shows "Paused on the phone. Open WebMirror and tap Start." Other fields are the last known values and may be ignored. |
| phone → page | `denied` | `reason:"pin"\|"version"` | Then the phone closes the socket. |
| page → phone | `kf` | — | "I lost sync, send a keyframe." |
| page → phone | `stats` | `fps, queue, dropped, bufferMs, ua` | Every 2 s. Also acts as keep-alive. |
| page → phone | `probe` | `results:{...}` | Probe page results (M0). |
| page → phone | `blast` | — | M0 throughput test: phone sends 100 binary packets of type `0` (50 000 bytes payload each), then `blastDone`. |
| phone → page | `blastDone` | `packets, bytes` | End marker of a `blast`. |
| page → phone | `input` | reserved for future touch/typing | Not implemented. Phone ignores unknown `t`. |

### 2.2 Video modes
The phone can only attach one output to the screen capture at a time (Android 14+ allows one virtual display per capture session). So the server runs in **one mode for all viewers**:
- `h264` when every connected viewer said `h264:true`.
- `jpeg` as soon as any connected viewer said `h264:false`. Back to `h264` when that viewer leaves.
Every page implements both decoders and follows the `mode` in the latest `hello`.

### 2.3 Quality presets (starting values, tuned after car test M3)
| Preset | Longest side of the image | Max fps | H.264 bitrate | JPEG quality |
|---|---|---|---|---|
| Sharper | 1920 px | 15 | 6 Mbit/s | 80 |
| Smoother | 1280 px | 30 | 4 Mbit/s | 65 |

Size rule: scale the real screen size so the longest side equals the preset value (never upscale), keep aspect ratio, round each side **down to a multiple of 8**.

### 2.4 Sync and delay (client side)
- The page keeps `base = minimum over the last 200 packets of (performance.now() − pts/1000)`. This maps phone time to page time.
- A packet is due at `due = pts/1000 + base + bufferMs + offsetMs`.
  - `bufferMs` = 80 (smooths network jitter).
  - `offsetMs` = user slider 0…1500, default 0, stored in `localStorage` (wrapped in try/catch). This is the Bluetooth-audio compensation.
- Packets wait **encoded** in a queue (cheap) and are handed to the decoder when due. Decoded frames are drawn immediately.
- In M5 (browser audio) the audio clock replaces `performance.now()` as the reference; see M5.

---

## 3. Project layout (final)

```
WebMirror/
├── .gitignore                      # standard Android + *.p12 *.jks *.pem local.properties
├── README.md                       # how to build, run, test in the car
├── docs/PLAN.md                    # copy of this plan
├── docs/car-test-results.md        # filled in after each car test
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
        │   ├── encode/H264Encoder.kt       # MediaCodec
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
3. Copy this plan to `docs/PLAN.md`.
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

**M1.4 Car test 1** — fill `docs/car-test-results.md`
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

**Decision gate after M1 (write the decision into `docs/car-test-results.md`):**
- Which URL form is the official one for the car.
- `VideoDecoder` present and H.264 supported on the HTTPS row → car mode is H.264, HTTPS is mandatory for the car.
- Otherwise → car mode is JPEG; do M4 before M3 and treat HTTPS as optional.
- If no row loads at all → stop and review (next option: study how TeslAA/TslaMirror route, e.g. tunnel routes + packet handling).

**Done when** the table is filled, JSON files are saved in `docs/`, and the decision is written down.

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

**M2.3 `encode/H264Encoder.kt`**
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

**Car test 2** (record in `docs/car-test-results.md`): for each preset, 5 minutes parked and 5 minutes driving as passenger: fps, dropped frames, queue size, subjective smoothness; the delay value that gives lip sync over Bluetooth; whether the stream survives a Park → Drive change and a phone signal loss.
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
- **Touch/typing from the browser:** page sends `{t:"input", kind:"tap"|"swipe"|"text", x, y, ...}` with coordinates normalised 0–1; phone executes them with an `AccessibilityService` (`dispatchGesture`, `ACTION_SET_TEXT`).
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
