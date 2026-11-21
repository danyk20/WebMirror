# Privacy Policy

Last updated: 2026-11-18

**In short: your data is yours, and it stays on your device.**

WebMirror does not collect, store or share any data about you, with the developer or with anyone else, for analytics, advertising or any other purpose. There is:

- **no registration**: no account, no sign-in, no email address, no phone number,
- **no data collection**: no analytics, no crash reporting, no usage statistics, no advertising IDs, no tracking SDKs,
- **no WebMirror server**: the developer runs no backend, so there is nowhere your data could be sent, stored or leaked from.

The source code is public, so anyone can check these statements.

## What the app does with your data

WebMirror shares your phone's screen, and optionally its sound, with browsers on your local network (your phone's hotspot). This data goes directly from your phone to the browsers that you pair with a PIN. It is never sent to a server of the developer. Nothing is recorded or saved.

Data that stays on your phone:

- Settings, and the list of paired browsers.
- If you set up a free HTTPS certificate: your DuckDNS name, your DuckDNS token and the Let's Encrypt account key. The token and the key are encrypted with a key in the Android Keystore. The certificate is stored on the phone too.
- Optional test reports from the automatic test, created only when you run it. You choose whether to share them.

Android cloud backup is switched off for WebMirror, so this data is not copied to Google Drive or anywhere else. Uninstalling the app deletes all of it.

## The website

This website has no analytics, no cookies, no advertising and makes no requests to third parties. It is a set of static pages hosted on GitHub Pages; GitHub, as the host, processes basic request data such as IP addresses under its own privacy policy.

## Network connections outside your local network

The app connects to the internet only in these cases:

| Service | When | Why |
| --- | --- | --- |
| DuckDNS (`duckdns.org`) | While you get or renew a certificate | Point your name at the phone's address |
| Let's Encrypt | While you get or renew a certificate | Issue the certificate |
| Cloudflare DNS | While you get or renew a certificate | Check that the DNS record is visible |
| GitHub API (`api.github.com`) | GitHub version only, to check for and download updates | Find the latest release |

These services receive what is needed for their function, such as your DuckDNS name and your IP address, under their own privacy policies. The Google Play version does not contact GitHub.

## Permissions and why they are used

- **Screen capture (MediaProjection):** to mirror the screen. Android asks each time you start.
- **Microphone (`RECORD_AUDIO`):** only to capture the sound the phone plays, for sound in the browser. The microphone itself is not recorded.
- **Accessibility service:** for remote control. It injects the touches sent from a paired browser. It does not read, collect or store what is on your screen. Only if you choose "Tesla keyboard" for typing from the car (Android 13 or newer), while a browser controls the phone, it also learns whether a text field is selected and what kind of field it is (text, number, phone, password, email or web address, and its Enter-key action). It never reads the text in the field or anything else on the screen. The text typed in the car goes straight into the selected field and is not logged or stored.
- **VPN service:** creates the local virtual address (`169.254.77.1`) so the car browser can reach the phone. It does not route or inspect your internet traffic.
- **Notifications:** to show that mirroring is running and to warn about certificate renewal problems.
- **Foreground service and wake lock:** to keep mirroring running and the screen on if you choose.
- **Network and Wi-Fi state:** to show the addresses the phone can be reached at.
- **Install packages** (`REQUEST_INSTALL_PACKAGES`, GitHub version only): to install an update that you approve.

## Children

WebMirror is not directed at children and collects no data from anyone.

## Changes

If this policy changes, the new version is published in this repository with a new date.

## Contact

Open an issue at <https://github.com/danyk20/WebMirror/issues>. For security problems, see [SECURITY.md](SECURITY.md).
