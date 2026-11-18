# Contributing to WebMirror

Thank you for your interest. Bug reports, ideas and pull requests are welcome.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before you start

- Search the [issues](https://github.com/danyk20/WebMirror/issues) first.
- For anything larger than a small fix, open an issue and describe the change before you write code. This avoids wasted work.
- Report security problems privately. See [SECURITY.md](SECURITY.md).

## Set up

You need JDK 17 and the Android SDK (platform 37). Android Studio provides both. Open the project folder in Android Studio, or use the Gradle wrapper:

```bash
./gradlew assembleGithubDebug
./gradlew installGithubDebug
```

## Test

Run all tests before you open a pull request:

```bash
./gradlew testDebugUnitTest
for f in app/src/test/js/*.test.js; do node "$f"; done
```

Add or update tests for the behavior you change. Pure logic lives in small classes so that it can be tested on the JVM.

## Code style

- Kotlin: follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). Match the style of the surrounding code. Keep functions small and comments short and useful.
- Web page (`app/src/main/assets/web/`): plain JavaScript with no build step, because the car browser can be old. Do not use `?.`, `??`, BigInt or modules. The rules are listed in [docs/development/PLAN.md](docs/development/PLAN.md).
- User-visible text lives in the Compose screens and in the Help page (`ui/HelpPage.kt`). Keep it short and in plain language.
- Never commit keystores, certificates, tokens or passwords.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep the change focused. One topic per pull request.
3. Write a clear commit message: a short summary line, then the reason if it is not obvious.
4. Make sure the tests pass and the app builds.
5. Fill in the pull request template. Add screenshots for UI changes.
6. A maintainer reviews it. Please respond to comments; the change merges when the review is done.

## Changelog

Add a line under "Unreleased" in [CHANGELOG.md](CHANGELOG.md) for changes that users notice.

## Releasing

Maintainers only. Releases are built, signed and published by GitHub Actions when a version tag is pushed.

One-time setup: store the signing key as repository secrets with `scripts/setup-release-secrets.sh <keystore>`.

For each release:

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts` (`versionCode` = major * 10000 + minor * 100 + patch).
2. In [CHANGELOG.md](CHANGELOG.md), rename "Unreleased" to the new version with its date, add a new empty "Unreleased" section and update the compare links.
3. Commit and push to `main`.
4. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

The workflow checks that the tag matches `versionName`, runs the tests, signs the APK, verifies the signing certificate and publishes `WebMirror-<version>.apk` with its `.sha256` file. A version with a suffix such as `1.0.0-beta.1` is marked as a pre-release. The signed Play bundle is attached to the workflow run only.

## License

Your contributions are licensed under the [MIT License](LICENSE).
