# Competitor comparison (internal)

Researched 2026-11-21. Google Play listings read in Chrome (Swiss store, English), App Store and project pages on the web. "Data" is the developer's own Google Play Data safety declaration (self-declared, not audited). Recheck before quoting any of this publicly. The public website and README compare by category only and name no competitor.

## Android apps that mirror to the Tesla browser

| App | Price | Ads | Downloads | Data (Play Data safety) | Notable |
|---|---|---|---|---|---|
| **WebMirror** | Free | No | — | None collected; open source, verifiable | Sound in sync, touch + typing, H.264/HEVC/VP9/AV1, 60 fps, ~40 ms measured, free HTTPS cert, PIN |
| [TslaMirror](https://play.google.com/store/apps/details?id=com.hustmobile.teslamirror) (Hustmobile) | In-app purchases | No | 10K+ (2.6★) | None collected | MJPEG/H.264/H.265, sound (up to 96 kHz), single-app casting, **Android Auto casting**; also on iPhone ($0.99/month or $5.99 lifetime) |
| [Tesla Display](https://play.google.com/store/apps/details?id=io.github.blackpill.tesladisplay) (Super Ratel) | Free + in-app purchases | **Yes** | 10K+ | **Collects** app activity, performance, device IDs | Touch control, sound, video links |
| [Tesla Air](https://play.google.com/store/apps/details?id=cc.tsla.air) | **$2.99/month or $14.99 lifetime** (3-day trial) | No | 10+ | None collected | Opens tsla.cc in the car; picture and sound stay on the hotspot |
| [Carmiro](https://play.google.com/store/apps/details?id=com.wovex.carmiro) / [Carmiro Lite](https://play.google.com/store/apps/details?id=com.wovex.carmiro_lite) | CHF 7.90 / free with **ads** | Lite: yes | 5+ / 100+ (4.7★) | **Collects** location, app activity, device IDs | Separate second screen with its own launcher, split screen, touch + typing |
| [MOMEE](https://play.google.com/store/apps/details?id=com.momee.teslahud) | In-app purchases, 30-day trial | No | 1K+ | None collected, "data isn't encrypted" | Touch control, app shortcuts, App Cast beta (needs ADB) |
| [T-Cast](https://play.google.com/store/apps/details?id=com.borlagames.tcastmirrorcar) | In-app purchases | No | 100+ | **Collects and shares** device IDs | Mirror mode + Android Auto mode via a companion plugin |
| [Tesvo](https://play.google.com/store/apps/details?id=com.imran.tesvo) | 7-day trial, then one-time purchase | No | 100+ (4.7★) | None collected, "data isn't encrypted" | No account, local only, MJPEG or H.264, tesvo.app address, weather bar |
| [TesMirror](https://play.google.com/store/apps/details?id=com.puppytiger.tesmirror) | Free | **Yes** | 100+ | None collected | Touch control; no sound mentioned |
| [ScreenStream](https://play.google.com/store/apps/details?id=info.dvkr.screenstream) | Free (Play version has ads) | **Yes** | 5M+ (3.7★) | **Collects and shares** app activity, performance, device IDs | Open source (MIT), Android 7+, local MJPEG (no sound, no touch), WebRTC via signaling server, RTSP |
| [Duplila](https://play.google.com/store/apps/details?id=com.duplila.screenshare) | Free + in-app purchases | **Yes** | 50K+ (4.4★) | **Collects** personal info and more; shares IDs | General Android-to-Android via ADB, not Tesla-specific |

## Other approaches

| Product | Notes |
|---|---|
| [Tesla Android / Tesdroid](https://github.com/tesla-android/android-raspberry-pi) | Raspberry Pi box plugged into the car, about EUR 289 (sold out when checked), open source (GPL-3.0), CarPlay / Android Auto, LTE |
| [TesDisplay](https://apps.apple.com/app/TesDisplay/id6469987744) (iPhone) | Free + Pro $4.99, "Data Not Collected", reviews report lag |
| TeslAA (AAWireless) | Android Auto in the browser; last solid info from 2021, current status unverified |

## Where WebMirror is clearly stronger

- **Free, with no ads, subscriptions or trials.** Every Tesla-specific competitor found has ads, a subscription, a paid unlock or a paid app.
- **No data collected, and anyone can check it.** Five of the ten Android listings declare collecting data (device IDs, app activity, even location), and two declare sharing it. The rest rely on a self-declared label. WebMirror is the only open-source Tesla-specific app.
- **Encrypted connection.** Two "no data collected" competitors declare "data isn't encrypted". WebMirror offers HTTPS with a free certificate set up on the phone.
- **Video quality.** Competitors list MJPEG, H.264 and H.265 at most. WebMirror adds VP9 and AV1, up to 60 fps, and publishes a measured ~40 ms delay. No competitor publishes a delay.
- **The full set in one app:** sound in sync, touch, typing with the Tesla keyboard, Rotate, PIN pairing and heat protection.

## Where competitors are honestly better

- **Android Auto in the car** (TslaMirror, T-Cast, the Tesla Android box).
- **A separate second screen** that runs apps independently of the phone (Carmiro, TslaMirror, MOMEE beta).
- **iPhone support** (TslaMirror, TesDisplay).
- **Extras:** weather bar and scratch pad (Tesvo), split screen (Carmiro), lossless 96 kHz audio (TslaMirror).
- **Reach and store presence:** ScreenStream (5M+ downloads, Android 7+), TslaMirror (10K+), Google Play listings and reviews. WebMirror is not on Play yet.
