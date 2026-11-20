# Car test results

Fill in after each car test. Save every probe JSON (Share button in the app) next to this file.

## Car test 1 (M1.4): address matrix

Setup: phone hotspot on, mobile data on, car Wi-Fi joined to the hotspot with "Remain connected in Drive" enabled, app started, virtual IP on.

Date: ____  Car software version: ____  Phone: ____

| # | URL typed in the car | Tests | Loads? | Probe JSON file | Notes |
|---|---|---|---|---|---|
| 1 | `http://<hotspot-ip>:8080` | private IP directly | | | |
| 2 | `http://<name-for-private-ip>:8080` | DNS name -> private IP | | | |
| 3 | `http://198.51.100.1:8080` | virtual IP, reserved range | | | |
| 4 | `http://3.3.3.3:8080` | virtual IP, public-looking | | | |
| 5 | `http://<name>:8080` | DNS name -> virtual IP | | | |
| 6 | `https://<name>:8443` | HTTPS -> secure context -> WebCodecs | | | |

Rows that worked, repeated with a passenger watching in Drive (page stays up?): ____

### Decision gate after M1

- Official URL form for the car: ____
- `VideoDecoder` present and H.264 supported on the HTTPS row? ____
- Car mode (H.264 + mandatory HTTPS, or JPEG with HTTPS optional): ____
- If no row loaded: stop and review.

## Car test 1b: hostnames and IPv6

Setup as car test 1. The app lists "Addresses" and "Names (for the car test)" (public service sslip.io; the car needs internet through the phone to resolve them).

Date: ____  Car software version: ____  Phone: ____

| # | URL typed in the car | Tests | Loads? | Probe JSON file | Notes |
|---|---|---|---|---|---|
| 1 | `http://<hotspot-ipv4-with-dashes>.sslip.io:8080` | hostname -> hotspot IPv4 | | | |
| 2 | `http://[<phone-ipv6>]:8080` | raw IPv6 | | | |
| 3 | `http://<ipv6-with-dashes>.sslip.io:8080` | hostname -> IPv6 | | | |

`http://test-ipv6.com` in the car browser reports IPv6 connectivity? ____

## Desk test M1.1 — virtual address over the phone hotspot (2026-11-16)

Phone: Galaxy S22 (SM-S901U1), Android 16. Mac joined to the phone's hotspot (phone `swlan0` 10.15.23.199).

| Test from the Mac | Result |
|---|---|
| `http://10.15.23.199:8080/probe.html` (hotspot address) | 200 — works |
| `ping 198.51.100.1` (virtual address) | answered by the phone (ttl 64) |
| `http://198.51.100.1:8099` (closed port) | refused immediately (reaches the phone) |
| `http://198.51.100.1:8080/probe.html` | **times out** |

**Cause (confirmed):** Android's connectivity module installs an "ingress discard" rule for every address owned by a `VpnService` interface. `adb shell dumpsys connectivity trafficcontroller` shows `sIngressDiscardMap: [/198.51.100.1]: tun0, tun0`: packets for that address are delivered to an app socket only if they arrive on `tun0`. Packets from hotspot clients arrive on `swlan0` and are dropped. Routing itself is correct. `Builder.allowBypass()` does not remove the rule (tried).

**Conclusion:** the virtual-address approach of plan M1.1 does not work on current Android. The code stays in the repo but must not be relied on. Decision on the replacement is open.

## Desk findings 2026-11-17 (phone on mobile data, hotspot on)

- **IPv6 is not shared with hotspot clients on this phone/carrier.** The hotspot's upstream is a separate IPv4-only interface (`rmnet_data7`, 10.204.153.240); the phone's public IPv6 address lives on a different one (`rmnet_data8`). `dumpsys tethering` shows `IPv6TetheringCoordinator ... UpstreamNetworkState{null}` and `swlan0` has only a link-local IPv6 address. So the car gets no IPv6 from the phone, and the raw-IPv6 and IPv6-name rows of car test 1b cannot work here.
- **A link-local virtual address avoids Android's VPN filter.** Android skips its ingress-discard rule for link-local addresses. With the virtual address set to `169.254.77.1`, `sIngressDiscardMap` is empty, the phone routes hotspot packets for that address to itself (`ip route get 169.254.77.1 from <client> iif swlan0` → `local`), and `arp_ignore` is 0, so the phone also answers ARP for it on the hotspot. Not yet confirmed from a real hotspot client; whether the Tesla browser accepts 169.254.x.x is unknown.

## Car test 1c — to do

| # | URL typed in the car | Loads? |
|---|---|---|
| 1 | `http://169.254.77.1:8080/probe.html` (virtual address, link-local) | **yes** (parked) |
| 2 | `http://10-15-23-199.sslip.io:8080/probe.html` (name → hotspot address) | |

### Car test 1c result (2026-11-17, parked)

**`http://169.254.77.1:8080` loads in the Tesla browser.** The earlier "refused to connect" failures (car test 1 and the first try of 1c) were because WebMirror's server had stopped: Android closed the app process while it was in the background. The phone itself rejected the connections, which proves the car's requests reach the phone. Whether the plain hotspot address (`10.15.23.199`) is blocked by the car is therefore still unproven; it does not matter now.

Probe results from the car:

| Item | Value |
|---|---|
| Browser | Chrome 148 on Linux x86_64 (`Mozilla/5.0 (X11; Linux x86_64) ... Chrome/148.0.0.0`) |
| Screen / window / DPR | 869x1391 / 856x668 / 1.38 |
| Secure context over plain HTTP | no |
| `VideoDecoder` (WebCodecs) | **not available** over plain HTTP |
| `MediaSource`, `<video>` H.264 `canPlayType` | yes / "probably" (not usable: `<video>` is restricted while driving) |
| WebSocket, `createImageBitmap`, `OffscreenCanvas`, `AudioContext` | yes |
| `AudioWorklet` | no (needs secure context) |
| Canvas drawing 1280x720 ImageBitmap | 59.6 fps |
| WebSocket throughput phone to car | 38.3 Mbit/s |

### Decision gate after M1 (2026-11-17)

- **Car URL:** `http://169.254.77.1:8080`. The virtual address defaults to `169.254.77.1` (link-local, so Android's VPN ingress filter does not apply).
- **Car video mode over plain HTTP:** JPEG (no WebCodecs without a secure context). Throughput and canvas speed are ample for JPEG.
- **H.264 in the car** needs HTTPS: a DNS name that resolves to `169.254.77.1` plus a real certificate for it (plan M1.3). Open question for the owner: is there a domain to use?
- **Must fix:** the server must survive the app going to the background (foreground service, plan M2.1).
- Not yet tested: the page while the car is in Drive.

## Desk test M2/M4 on the phone (2026-11-17, Galaxy S22, Android 16, viewer on the Mac over USB forwarding)

| Check | Result |
|---|---|
| Foreground service while mirroring | yes, type mediaProjection |
| Stream size (Smoother preset) | 584x1280 |
| JPEG, scrolling | 30 fps, ~69 KB/frame, ~17 Mbit/s |
| JPEG, still screen | ~1.5 fps, <1 Mbit/s |
| H.264 (`avc1.64001F`), scrolling | 30 fps, ~27 KB/frame, ~6.7 Mbit/s, 0 dropped in the player |
| H.264, still screen | ~6 fps, ~0.3 Mbit/s |
| JPEG-only viewer joins / leaves | stream switches h264 → jpeg → h264 live |
| App in background | keeps streaming |
| Screen locked | capture stops (Android); viewers get `paused` and stay connected; server keeps running as a specialUse foreground service |
| HTTPS on 8443 with the imported certificate | serves `mirror.example.com` with a valid chain |
| Resume after pause (new consent) | works; service back to type mediaProjection, viewers get the stream again |
| Rotation | stream reconfigures to 1280x584 within ~10 s of rotating and the player rescales |

Certificate: Let's Encrypt for `mirror.example.com`, issued 2026-11-17, expires 2027-02-15, renewed manually with a DNS TXT record. The DNS provider's `ns1` sometimes answers from a stale copy of the zone; the renewal helper therefore waits for ns2–ns4 only.

## Car test 2 (2026-11-17, first real mirroring in the car)

- `https://mirror.example.com:8443` loads with a valid certificate; WebCodecs available; **H.264 plays in the car**, parked and while driving.
- Typing the address without the scheme, or with the wrong one, fails: `http://` to 8443 gives ERR_EMPTY_RESPONSE; `https://169.254.77.1:8443` cannot work (certificate is for the name only).
- **Freeze on shifting into Drive was the car dropping the phone's hotspot**, not a decoder block. Fixed with the Tesla Wi-Fi option "Remain connected in Drive" for the hotspot. The page kept showing "Connected, 0 fps" because nothing detected the dead connection; heartbeat + auto-reconnect being added.
- JPEG mode (Force JPEG) also plays in Drive; started at 10–15 fps, rose to ~30 fps later.
- **Delay phone → car is too large** (amount not yet measured); latency control being added.
- Bluetooth audio delay offset: not yet set.

## Desk run of the automatic test (2026-11-17, Quick mode, viewer = headless Chrome on the Mac over USB)

Full report: `docs/development/test-runs/20261117-205929-desk.csv` / `.json`. All 16 phases completed, no reconnects, no decoder resets, no drops; adaptive bitrate stayed at 4000 kbps (no false congestion after the fix in bba69d5).

- Capture → draw delay (e2e P95) is ~98 ms with defaults; **80 ms of it is the jitter buffer**. `jitter-30ms` gave 48 ms, `jitter-150ms` 167 ms. The pipeline itself (encode + transfer + decode) is roughly 15–20 ms on this link.
- On this fast, clean link the other options make no measurable difference (97–102 ms); they are meant for the car's slower, jittery Wi-Fi, so the car runs are what matters.
- `legacy` (all improvements off) had higher RTT (23 ms vs ~4 ms) and a worse max (122 ms).
- `pacing-vsync` adds ~15 ms; `jpeg` 105 ms at 20 fps and ~12 Mbit/s vs ~4 Mbit/s for H.264.

## Car test 3 (2026-11-18): probe + automatic test, parked and driving

Reports: `docs/development/test-runs/20261118-000858-desk.*` (**parked** — the run was named "desk" by mistake) and `docs/development/test-runs/20261118-001628-driving.*`. Both runs completed all 16 phases, no reconnects, no decoder resets.

**Probe (Tesla Chrome 148, https):** `VideoDecoder.isConfigSupported` true for H.264 (Baseline/Main/High), **HEVC** (`hev1`/`hvc1`, L93 and L120) and **VP9**, all also with `prefer-hardware` at 720p and 1080p. AV1 only without hardware (software decode). `isConfigSupported` does not prove real decoding works; an HEVC stream test is needed.

| Phase | Parked e2e P95 (ms) | Driving e2e P95 (ms) | Notes |
|---|---|---|---|
| jitter-30ms | **60** (max 229, fps min 16.5) | **63** (max 67) | best in both runs; one hiccup parked |
| default (jitter 80) | 110 | 109 | adaptive bitrate cut the target to 800–1300 kbps without visible congestion |
| no-adaptive-bitrate | 113 | 114 | full 4 Mbit/s quality |
| legacy (all off) | 118 | 113 | higher RTT (17–27 ms vs ~8–12 ms) |
| other single options | 109–115 | 109–114 | within noise |
| pacing-vsync | 125 | 127 | +15 ms |
| jpeg | 144 | 144 | 20 fps, ~11–12 Mbit/s |
| jitter-150ms | 183 | 183 | |

**Conclusions**
- The car's Wi-Fi link is good both parked and driving (RTT ~8–12 ms, no drops). The pipeline is ~25 ms; the jitter buffer dominates the delay.
- Lowering the jitter buffer from 80 to 30 ms halves the delay. A small safety margin (40 ms) is suggested as the new default.
- Adaptive bitrate lowered quality in both runs without improving delay: too sensitive and too slow to recover. Either tune or default off.
- The automatic test does not cover the Park → Drive transition (the earlier ~2 s lag); check that manually on the next drive.
- Next candidate: HEVC as an option (car reports hardware support; phone has a hardware HEVC encoder).

## Desk run with automatic codec choice (2026-11-18, Quick, headless Chrome over USB)

Report: `docs/development/test-runs/20261118-010857-desk-hevc.*` (the earlier `20261118-005721-desk-hevc` has no e2e: the ping timer was frozen by a dead TLS viewer, fixed in b74e951).

| Phase | Codec / encoder | e2e P95 (ms) | Notes |
|---|---|---|---|
| jitter-20ms | HEVC hw | 39 | max 93 (one spike) |
| jitter-30ms | HEVC hw | 49 | |
| codec-h264 | H.264 hw | 57 | |
| no-adaptive-bitrate | HEVC hw | 58 | |
| codec-hevc / default (jitter 40, auto → HEVC) | HEVC hw | 59 | |
| codec-vp9 | VP9 **software** | 70 | phone still encodes 30 fps |
| codec-av1 | — | — | software AV1 encoder cannot be created; fell back to HEVC |

- All phases 30 fps, 0 drops, 4 Mbit/s CBR. At equal bitrate H.264 and HEVC have the same delay; HEVC's benefit would be picture quality per bit, which this test does not measure.
- Dead-viewer fix verified: a silent HTTPS viewer was dropped after ~12 s while another viewer kept receiving a ping every second (max gap 1.0 s).
- Found: on Android's Conscrypt the TLS "peek first byte" wrap failed, so the first HTTPS connection after each start was reset and `http://…:8443` was not redirected. Fix in progress (ReplaySocket).

## Car test 4 (2026-11-18): automatic codec choice, 8-phase Quick test, parked and driving

Reports: `docs/development/test-runs/20261118-011853-parked.*`, `docs/development/test-runs/20261118-012257-driving.*`. Phases run one after another, so a Wi-Fi disturbance hits whichever phase is running at that moment.

| Phase | Parked P95 / max (ms) | Driving P95 / max (ms) | RTT parked / driving (ms) |
|---|---|---|---|
| jitter-20ms (HEVC) | **38 / 39** | **40 / 41** | 4 / 4 |
| codec-h264 | 58 / 60 | 59 / 59 | 5 / 5 |
| default (jitter 40, auto → HEVC) | 59 / 60 | 80 / 471 | 5 / 47 |
| codec-hevc | 248 / 773 | 80 / 110 | 70 / 19 |
| jitter-30ms | 89 / 447 | 78 / 435 | 33 / 48 |
| no-adaptive-bitrate | 377 / 1639 | 73 / 83 | 224 / 15 |
| codec-vp9 (software) | 71 / 71 | 71 / 73 | 4 / 3 |

- The car chose **HEVC** automatically (hardware on both sides) and played it at 30 fps.
- Unlike car test 3, the Wi-Fi link had disturbances in several phases (RTT averages 15–224 ms, a few drops and skips, fps dips to 2.5–18). They come and go over time and do not follow the options: the same codec/buffer was clean in one run and disturbed in the other. Single runs therefore cannot rank the options that differ by a few ms.
- On a clean link: ~38–40 ms with a 20 ms buffer, ~58–59 ms with the 40 ms default; H.264 and HEVC equal at the same bitrate; software VP9 +12 ms.
- Disturbances of 400–1600 ms are far larger than any jitter buffer; they are absorbed by flow control/skipping, not by the buffer. So a small buffer (20–30 ms) costs little.
- Measured bitrate ~8 Mbit/s although the preset target is 4 Mbit/s: to check (possibly two viewers connected, e.g. a second car tab).
- Owner's observations in the car: overlay showed **mode hevc**; picture as sharp as before, maybe slightly better; **no more ~2 s lag when shifting Park → Drive** (the backlog fix from 18c6bb6 works in the car).

## Remote control (2026-11-18)

- Car (Tesla browser) reports multi-touch correctly: 1–5 fingers counted on the page.
- Pinch zoom failed at first. Causes found on the phone and fixed:
  1. Strokes in one frame had different durations; Android treated the shorter as lifted (fixed 82662a3).
  2. "Can't continue gesture due to points mismatch": continuations must start at the end point Android measures with PathMeasure (fixed 41a3a3c).
  3. Android cancels an injected gesture when a new finger joins while another is held. Fingers now start together (first finger waits 60 ms); a later finger lifts and re-places the held ones (65e695c).
- Verified on the phone from a desk viewer (DevTools touch events, same path as the car): pinch-out and pinch-in with the second finger 400 ms late both zoom Google Maps, no cancelled gestures. Picture stays centred with the compact control bar (owner confirmed in the car).
- Owner confirmed in the car (2026-11-18): all gestures work on the Tesla screen, including pinch zoom.

## Car test 5 (2026-11-20): can the car reach the phone without the virtual address (VPN)?

Background: the virtual address had been switched off because Android withdrew WebMirror's VPN consent (another VPN app or the system), so the DuckDNS name pointed at an address the phone no longer had and the car timed out. Question: can WebMirror do without the VPN by pointing the name at the real hotspot address?

| URL in the Tesla browser | Result |
|---|---|
| `http://10.15.23.199:8080` (phone's real hotspot address) | "refused to connect" |
| `http://10-15-23-199.nip.io:8080` (public name resolving to that address) | "refused to connect" |
| Same `http://10.15.23.199:8080` from the Mac on the same hotspot | loads (HTTP 200) |
| `https://485652.duckdns.org:8443` (name -> `169.254.77.1`, virtual address on) | loads, full-quality stream |

- **The Tesla browser itself refuses private (RFC 1918) addresses, also when reached through a DNS name.** The phone accepts hotspot clients on the real address (the Mac on the same hotspot gets the page), so the refusal is in the car.
- Decision: the link-local virtual address (local VPN) stays required for the car. Pointing the name at the hotspot address is not an option.

## Car test 6 (2026-11-20): 0.3.1 features

- **Rotate from the car:** the phone turns both ways in apps that can rotate (YouTube player, Maps); the stream follows in about 0.2-0.3 s. Apps that request portrait only (YouTube home: `SCREEN_ORIENTATION_USER_PORTRAIT`) stay upright, as when turning the phone by hand; the car page now explains it.
- **Virtual address warning:** with the virtual address switched off during mirroring the notification reads "The car can't reach the phone: virtual address off. Tap to fix."; Home > Fix brought it back and the Tesla reconnected.
- **Restore:** after Stop the user's auto-rotate (on) was back; the crash-recovery restore also ran after an app update during a rotated session.
