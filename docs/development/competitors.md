# Competitor comparison (internal)

Researched 2026-11-21 from public web pages. Play Store pages could not be read, so Play "Data safety" details are **unverified** for every Android app. Recheck before quoting any of this publicly. The public website and README compare by category only and name no competitor.

| App | Platform | Price | Extra hardware | Account | Cloud / relay | Data collection | Open source | Sound | Touch | Latency |
|---|---|---|---|---|---|---|---|---|---|---|
| **WebMirror** | Android 10+ | Free | No | None | None, local hotspot only | None (verifiable in code) | Yes, MIT | Yes, in sync in the browser, or Bluetooth | Yes, multi-touch and typing | ~40 ms measured |
| [TesMirror](https://puppytigerstudio.github.io/en/tesmirror/guide.html) | Android 10+ | unverified | No | unverified | Local hotspot (per guide) | unverified | Not stated | Not mentioned | Yes (accessibility service) | Not stated |
| TeslAA ([Electrek 2021](https://electrek.co/2021/09/30/android-auto-tesla-vehicles-browser-app/)) | Android (Android Auto in the browser) | ~$6.50 in 2021, current unverified | No | unverified | Uses an external web page; relay unverified | unverified | unverified | unverified | unverified | Not stated |
| [Tesla Android / Tesdroid](https://github.com/tesla-android/android-raspberry-pi) | Raspberry Pi box + any phone | ~EUR 289 unit (sold out when checked); DIY build is free but heavy | **Yes** | Optional store account | unverified | Privacy-focused claims | Yes, GPL-3.0 | Yes | Yes | Not stated |
| [ScreenStream](https://github.com/dkrivoruchko/ScreenStream) | Android 7+ | Free; Play version has ads | No | No | Local MJPEG, or WebRTC via a signaling server | unverified | Yes, MIT | Not in Local mode | No remote control | Not stated (MJPEG locally) |
| [TslaMirror](https://apps.apple.com/us/app/tslamirror/id1542447409) | iPhone | Free + in-app purchase | No | Not stated | Local hotspot | "Data Not Collected" label | No | Yes, manual start, may drift | **No** | Not stated |
| [TesDisplay](https://apps.apple.com/app/TesDisplay/id6469987744) | iPhone | Free + Pro $4.99 | No | Not stated | unverified | "Data Not Collected" label | Not stated | Partial | Not stated | Not stated; reviews report lag |

## Where WebMirror is clearly stronger

- The only free, open-source Android option found with **sound, touch and typing** in the car browser together.
- **No hardware**: the hardware route costs about EUR 289 and was sold out.
- **Verifiable privacy**: open code, no account, no relay or signaling server, no ads. Competitors rely on store labels.
- **Modern video**: WebCodecs with H.264/HEVC/VP9/AV1, up to 60 fps, measured ~40 ms. No competitor publishes a latency figure.
- **Free HTTPS certificate set up on the phone**: not offered by any competitor found.

## Where competitors are honestly better

- **iPhone support** (TslaMirror, TesDisplay). WebMirror is Android only.
- **Native car UI**: Android Auto / CarPlay style (TeslAA, Tesla Android box), DRM video and always-on LTE (box).
- **Maturity and reach**: ScreenStream supports Android 7+, WebRTC over the internet and RTSP, and is on F-Droid and Play.
- **Store presence and reviews** (TesMirror, TslaMirror).
