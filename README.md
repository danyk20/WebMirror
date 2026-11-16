# WebMirror

Android app (Android 10+, Kotlin) that turns the phone into a small web server to mirror its screen to a browser on the same network. Target browser: a Tesla Model S (MCU2) passenger browser. The full plan is in [docs/PLAN.md](docs/PLAN.md). Current state: milestone M0 (skeleton app, web server, probe page).

## Open in Android Studio

1. Install Android Studio and run its setup wizard (installs the Android SDK and platform tools).
2. Open this folder (`File > Open`). Keep "Gradle JDK" on the bundled JDK (Settings > Build Tools > Gradle). The build needs JDK 17+.
3. The Gradle wrapper (`./gradlew`, Gradle 9.9.0) is in the repo; Android Studio uses it automatically.
4. Let Android Studio install any missing SDK platform (API 37) and build tools it asks for.
5. If you create a `local.properties` it is git-ignored; Android Studio writes `sdk.dir` there.

Versions (see `gradle/libs.versions.toml`): AGP 9.5.1, Gradle 9.9.0 (AGP needs at least 9.6.0), Kotlin 2.4.21, Compose BOM 2026.09.00, compileSdk/targetSdk 37, minSdk 29.

## Run on the phone

1. Enable Developer options and USB debugging, connect the phone, check `adb devices` shows `device`.
2. Run the `app` configuration. In the app tap **Start**. Keep the app open on screen (until M2 the service is not a foreground service).
3. The app lists the URLs (`http://<ip>:8080`) and a QR code of the first one.

## M0 "Done when" checks

- `./gradlew test` passes (`PacketTest`).
- Laptop on the same Wi-Fi opens `http://<phone-ip>:8080/probe.html`, sees the results table, and the probe JSON appears in the app text box (Share button sends it via the share sheet).
- Laptop joined to the phone's hotspot can do the same (use the phone's hotspot address shown in the app).
- Close the browser tab: the viewer count in the app drops within 5 seconds.
- Optional: `adb forward tcp:18080 tcp:8080` and open `http://127.0.0.1:18080/probe.html` (port 18080 because another program on the development Mac already uses localhost:8080).

## Notes

- Web client files are plain files in `app/src/main/assets/web/` (no build step). The coding rules in section 3 of the plan apply: no `?.`, no `??`, no BigInt, no modules.
- Certificates and keys (`*.p12`, `*.jks`, `*.pem`, `*.keystore`) are git-ignored and must never be committed.
- Car test results go in [docs/car-test-results.md](docs/car-test-results.md).
