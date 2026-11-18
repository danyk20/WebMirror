# Security Policy

## Supported versions

Only the latest release receives security fixes. Please update before you report a problem.

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Older releases | No |

## Reporting a vulnerability

Do not open a public issue for a security problem.

Report it privately with GitHub: open the [Security tab](https://github.com/danyk20/WebMirror/security), choose **Report a vulnerability**, and describe the problem. Include:

- what you found and why it matters,
- the app version and Android version,
- steps to reproduce, or a proof of concept.

You can expect a first reply within a few days. Fixes are released as soon as they are ready, and you are credited if you wish.

## Scope

In scope:

- **PIN pairing:** bypassing or guessing the PIN, or reading a paired viewer's credential.
- **Remote control:** any way for an unpaired or unauthorized client to inject touches, or for the accessibility service to read screen content.
- **TLS and certificates:** weaknesses in the HTTPS server, in certificate issuing and renewal, or in the storage of the DuckDNS token and the account key.
- **The local web server:** access to the screen, sound or files without pairing.
- **Updates:** a way to make the GitHub version install a file that was not signed by the release key.

Out of scope:

- Using the app with "No protection" selected. Anyone on the network can connect by design.
- Attacks that need a rooted phone or physical access to an unlocked phone.
- Problems in Android, in browsers or in third-party services such as DuckDNS and Let's Encrypt.
- Apps that block screen capture and show a black picture.
