# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Smoothest (60 fps) video option with automatic fallback to 30 fps when the viewer cannot keep up.
- Heat protection: when the phone reports it is getting hot, WebMirror lowers the frame rate (30, then 15 fps) and the bitrate by itself and restores them one minute after it cools down. The stream status shows "(phone hot)".
- Automatic test phase `fps-60` for the Smoothest preset.
- Typing from the car: Settings > Remote control > "Keyboard for typing from the car" lets you use the Tesla browser's own keyboard (Android 13 or newer) instead of the phone keyboard in the picture. The accessibility service then learns only whether a text field is selected and its kind, never its text.

### Changed

- Release builds are shrunk with R8; each GitHub release also includes the mapping files needed to read crash reports.
- GitHub Actions updated to their Node.js 24 versions.
- Smoothest (60 fps) is the default video setting. Installs that already chose a setting keep it.

## [0.1.0] - 2026-11-18

### Added

- Screen mirroring from the phone to any modern browser over the phone's hotspot, with no app on the car side.
- Automatic codec choice between H.264, HEVC, VP9 and AV1 (WebCodecs) based on hardware support on the phone and in the browser. JPEG fallback for plain `http`.
- Low-latency pipeline: ack-based in-flight limit, newest-frame-wins queue, adaptive bitrate, dead-connection detection and automatic reconnect.
- Browser audio from playback capture: Opus (High quality) or 10 ms PCM (Fast), synchronized with the picture, plus a delay slider for Bluetooth sound.
- Remote touch control from the car through an accessibility service, including multi-touch gestures such as pinch. The service injects touches only and reads nothing.
- Pairing PIN (4 digits, 6 digits or off), with "New PIN" and "Forget paired viewers".
- Free HTTPS certificate set up on the phone with DuckDNS and Let's Encrypt, renewed automatically. Import of your own `.p12` file.
- Virtual address `169.254.77.1` so the Tesla browser can reach the phone.
- Quick Settings tile to start and stop mirroring.
- Home, Settings and Help screens, setup checklist and an in-app user guide.
- Automatic test that compares settings in the car and reports the best one.
- Update checker with optional in-app install (GitHub version). Separate `play` flavor without self-update.
- Release signing from environment variables or Gradle properties.
- Documentation, safety notice and MIT license.

[Unreleased]: https://github.com/danyk20/WebMirror/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/danyk20/WebMirror/releases/tag/v0.1.0
