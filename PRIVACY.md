# Privacy Policy

Last updated: 2026-11-18

WebMirror does not collect, store or share personal data with the developer or with anyone else. It has no accounts, no analytics, no advertising and no tracking.

## What the app does with your data

WebMirror shares your phone's screen, and optionally its sound, with browsers on your local network (your phone's hotspot). This data goes directly from your phone to the browsers that you pair with a PIN. It is never sent to a server of the developer. Nothing is recorded or saved.

Data that stays on your phone:

- Settings, and the list of paired browsers.
- If you set up a free HTTPS certificate: your DuckDNS name, your DuckDNS token and the Let's Encrypt account key. The token and the key are encrypted with a key in the Android Keystore. The certificate is stored on the phone too.
- Optional test reports from the automatic test, created only when you run it. You choose whether to share them.

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
- **Accessibility service:** for remote control. It only injects the touches sent from a paired browser. It does not read, collect or store what is on your screen.
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
