# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.3.3] - 2026-11-25

### Fixed

- Banking apps no longer object after mirroring stops: the remote-control accessibility service is bound only while mirroring.
- A paused session now stops after 2 hours (Settings, adjustable or Never), and right away when WebMirror is swiped away while paused, so the VPN no longer lingers and blocks banking apps.

## [0.3.2] - 2026-11-22

### Changed

- The virtual address (the local VPN) now runs only while mirroring and is switched off when you stop, so the VPN icon disappears and other apps (for example streaming apps) are not affected. It comes back automatically when you start mirroring.

### Fixed

- Sound in the browser checks the microphone permission right before capturing, so withdrawing it while mirroring shows a clear message instead of an error. Android Lint now runs on every change.

## [0.3.1] - 2026-11-20

### Added

- Warning on Home when the car cannot reach the phone because the virtual address is off, with a "Fix" button that switches it on again. The mirroring notification says the same and opens WebMirror when tapped.

### Changed

- When another VPN app or Android switches the virtual address off, WebMirror now remembers it and tells you (Home, notification, Settings > Network) instead of silently showing "Off".
- Rotate on the car screen: when the app on the phone only works upright (for example a home screen), the car page says so instead of seemingly doing nothing.

### Fixed

- Switching the virtual address off in Settings > Network now really switches it off (the VPN stayed active before).

## [0.3.0] - 2026-11-19

### Added

- Rotate button on the car screen: turns the phone between portrait and landscape as if you turned it by hand. Allow it once in Settings > Remote control > "Rotate from the car" (Android asks for "Modify system settings"). WebMirror puts your own auto-rotate setting back when mirroring stops.
- Project website with setup guide, FAQ and troubleshooting: https://danyk20.github.io/WebMirror/

## [0.2.1] - 2026-11-18

### Fixed

- Updates: "Download and install" did nothing while WebMirror was not yet allowed to install apps. It now opens Android's permission page and continues by itself when you come back.
- Updates: release notes are shown as clean text instead of raw Markdown.

## [0.2.0] - 2026-11-18

### Added

- Smoothest (60 fps) video option with automatic fallback to 30 fps when the viewer cannot keep up.
- Heat protection: when the phone reports it is getting hot, WebMirror lowers the frame rate (30, then 15 fps) and the bitrate by itself and restores them one minute after it cools down. The stream status shows "(phone hot)".
- Automatic test phase `fps-60` for the Smoothest preset.
- Typing from the car: Settings > Remote control > "Keyboard for typing from the car" lets you use the Tesla browser's own keyboard (Android 13 or newer) instead of the phone keyboard in the picture. The accessibility service then learns only whether a text field is selected and its kind, never its text.

### Changed

- Release builds are shrunk with R8; each GitHub release also includes the mapping files needed to read crash reports.
- GitHub Actions updated to their Node.js 24 versions.
- Smoothest (60 fps) is the default video setting. Installs that already chose a setting keep it.
- New app icon (a phone broadcasting its screen), also used for the Quick Settings tile, notifications and the browser tab; Android 13+ themed icon.

### Fixed

- The Home status line no longer shows the codec twice.

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

[Unreleased]: https://github.com/danyk20/WebMirror/compare/v0.3.3...HEAD
[0.3.3]: https://github.com/danyk20/WebMirror/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/danyk20/WebMirror/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/danyk20/WebMirror/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/danyk20/WebMirror/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/danyk20/WebMirror/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/danyk20/WebMirror/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/danyk20/WebMirror/releases/tag/v0.1.0
