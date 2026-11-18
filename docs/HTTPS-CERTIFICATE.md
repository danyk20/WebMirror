# HTTPS for WebMirror: get a free certificate

Over plain `http://169.254.77.1:8080` your car browser has no WebCodecs. WebMirror then falls back to JPEG mode (about 10-15 fps). For smooth video the page must open over HTTPS with a certificate the browser trusts.

The app can get one for you, for free, on the phone, in about two minutes. You need a free DuckDNS account.

## Get a free certificate in the app

Open WebMirror, scroll to **HTTPS certificate** and tap **Get a free certificate**.

1. **Create your free address.** Tap **Open duckdns.org** and sign in (Google, GitHub, ...). In "sub domain" type a name, for example `mycar-mirror`, and tap "add domain". You don't need to change the "current ip": the app sets it. Your address will be `<name>.duckdns.org`.
2. **Copy your token.** It is shown at the top of the DuckDNS page after you sign in. Back in the app, enter your DuckDNS name (`name`, `name.duckdns.org` or the full link all work; the app shows the final `name.duckdns.org`) and paste the token (**Paste** reads the clipboard, **Show** reveals it).
3. **Agree to Let's Encrypt terms.** Tick "I agree to the Let's Encrypt Subscriber Agreement" (the link opens <https://letsencrypt.org/repository/>). Let's Encrypt is a free, non-profit certificate authority.
4. **Get certificate.** The app shows its progress. It says: "This takes about one to two minutes. Keep the app open." You can cancel at any time.

When it is done the app shows the address to open in the car, `https://<name>.duckdns.org:8443`, with a QR code.

In the Tesla: Wi-Fi, your phone's hotspot, turn on **Remain connected in Drive**. Then open the address in the car browser. Type `https://` and the port `:8443`. Port 8080 is plain HTTP.

### Renewal

The certificate is renewed automatically: the app checks every 12 hours (and when it starts) and renews when less than a third of the lifetime is left. The section then shows "Automatic: <name>.duckdns.org, valid until <date>, renews automatically". **Renew now** renews at once. If a renewal fails and less than 14 days remain, you get the notification "WebMirror could not renew your HTTPS certificate". Open the app, check the error, and tap **Renew now**.

Renewal needs the phone to be online (mobile data or Wi-Fi) at some point in the last weeks.

If you change the virtual address in the app, the app updates the DuckDNS address by itself. An error is shown in the section if that fails.

**Turn off automatic certificate** forgets your token and the Let's Encrypt account key and removes the certificate. Importing a `.p12` by hand also turns automatic mode off.

### Change the name or the token

When automatic mode is on, the section also shows **Change name or token**. It opens the same wizard with your name filled in. Leave the token field empty to keep the saved token ("Leave empty to keep the saved token"), or paste a new one.

### Test certificates (debug builds only)

Debug builds of the app have, at the very bottom of the wizard under "For app developers only", a collapsed section (**Show developer options**) with a switch for the Let's Encrypt test server. Test certificates are NOT trusted by browsers or the car. Leave this off. With it on, the button reads "Get TEST certificate (not trusted)". If a test certificate is installed, the section shows "This is a TEST certificate. Browsers and the car do NOT trust it." and a **Get a real certificate** button that opens the wizard. Switching between test and real starts a new Let's Encrypt account automatically.

### Privacy and storage

Your DuckDNS name and token are stored only on this phone. The token and the account key are encrypted with a key in the Android Keystore. The app talks to duckdns.org, Let's Encrypt and Cloudflare DNS (to check the record) only while getting or renewing the certificate. Nothing is sent to the app author.

### If something fails

- **DuckDNS refused**: check the name and the token (copy the token again).
- **Rate limit**: Let's Encrypt allows only a few certificates per week. Wait and try again.
- **Network problem**: check mobile data or Wi-Fi.
- **Rejected / timeout**: the message says why. Try again in a few minutes.
- **Name does not open in the car**: some resolvers block names that point to link-local addresses ("DNS rebinding protection"). See Troubleshooting below.

### How it works

- The name `<your-name>.duckdns.org` points to the phone's virtual address (`169.254.77.1` by default).
- Let's Encrypt proves you own the name through a DNS record (DNS-01). No open ports and no server are needed.
- The certificate and its key stay on the phone. They are never put in the APK.

---

# Advanced: make the certificate on a computer

Use this if you prefer to do it by hand, or want a name that is not on DuckDNS. It takes about 20 minutes. Then import the file with **Import certificate (.p12)** under "Advanced". Placeholders look like `<this>`.

---

### 0. What you need

- The WebMirror app on the phone, with the virtual address switched on. Note the address shown in the app. The default is `169.254.77.1`. If you changed it (Settings > virtual address), use your address everywhere below.
- A computer with a terminal.
  - macOS: Terminal.
  - Linux: any terminal.
  - Windows: use WSL (Windows Subsystem for Linux). See the note in step 2.
- A free DuckDNS account (step 1). You can sign in with a Google, GitHub, Reddit or similar account.

---

### 1. Get a free name from DuckDNS

1. Open <https://www.duckdns.org> and sign in.
2. Copy your **token** (a long code shown at the top of the page). Keep it private.
3. In the "domains" box type a name you like, for example `mycar-mirror`. Click **add domain**.
4. In the row of your new domain, find the **current ip** field. Replace what is there with `169.254.77.1` (or your address from the app). Click **update ip**.

Check that the name resolves.

macOS / Linux / WSL:

```bash
dig +short <your-name>.duckdns.org
```

Windows (Command Prompt or PowerShell):

```
nslookup <your-name>.duckdns.org
```

You must see `169.254.77.1` as the answer. If you see another address or nothing, go back to step 4 and try again. Some home routers hide answers like this. See "Troubleshooting" below.

You can also set the address with a link, if you prefer (put your real values in):

```
https://www.duckdns.org/update?domains=<your-name>&token=<your-duckdns-token>&ip=169.254.77.1
```

It answers `OK` when it worked and `KO` when it failed.

---

### 2. Issue the certificate with acme.sh

acme.sh is a free, small script. Do this on your computer, not on the phone.

**Windows users:** install WSL first. Open PowerShell as administrator and run `wsl --install`, then restart and open the "Ubuntu" app. Run all commands below inside Ubuntu. Your Windows files are under `/mnt/c/Users/<you>/`.

#### 2.1 Install acme.sh

```bash
curl https://get.acme.sh | sh -s email=<your-email>
```

Close the terminal and open a new one, so the `acme.sh` command is found. (If it still is not found, use `~/.acme.sh/acme.sh` instead.)

#### 2.2 Tell acme.sh to use Let's Encrypt

acme.sh uses another certificate authority (ZeroSSL) by default. Use Let's Encrypt on purpose. Set it once:

```bash
acme.sh --set-default-ca --server letsencrypt
```

#### 2.3 Issue the certificate

Put your DuckDNS token in a variable, then issue:

```bash
export DuckDNS_Token="<your-duckdns-token>"
acme.sh --issue --dns dns_duckdns -d <your-name>.duckdns.org --server letsencrypt
```

acme.sh adds a temporary TXT record through the DuckDNS API, waits, lets Let's Encrypt check it, and removes it. It saves the token for later renewals.

When it finishes you see "Cert success". The files are in `~/.acme.sh/<your-name>.duckdns.org/`.

Notes:

- Use only the name you will open in the car browser. A certificate for `<your-name>.duckdns.org` does not work for another name.
- Wildcard names are not used by the app. A plain name is best.

---

### 3. Make the .p12 file and import it

The app needs a PKCS#12 file (`.p12`) with **a private key and the certificate**. Include the full chain (your certificate plus the Let's Encrypt intermediate). Both commands below do this.

#### 3.1 Choose a password

Pick any password you can remember. The app needs it at import time. It must not be empty.

#### 3.2 Create the file

Option A, with acme.sh:

```bash
acme.sh --to-pkcs12 -d <your-name>.duckdns.org --password '<choose-a-password>'
```

The file is saved next to the certificate, in `~/.acme.sh/<your-name>.duckdns.org/`. Its name ends in `.pfx`. A `.pfx` file is the same thing as a `.p12` file. You can rename it to `webmirror.p12`.

Option B, with openssl (works anywhere openssl is installed):

```bash
cd ~/.acme.sh/<your-name>.duckdns.org
openssl pkcs12 -export -out webmirror.p12 \
  -inkey <your-name>.duckdns.org.key \
  -in fullchain.cer \
  -password 'pass:<choose-a-password>'
```

Here `fullchain.cer` holds your certificate and the intermediate certificate. If a file name differs, run `ls` in that folder and use what you see.

#### 3.3 Copy the file to the phone

Use any way you like: USB cable (put it in Downloads), cloud drive, or messaging to yourself. Do not put it on a public place. Delete the copies when done (see "Security notes").

#### 3.4 Import in the app

1. Open WebMirror on the phone and scroll to the section **HTTPS certificate**.
2. Tap **Import certificate (.p12)**.
3. Pick your `.p12` file in the file picker.
4. In the dialog **Certificate password** type the password and tap **Import**.
5. The section now shows the subject, the **DNS names** (your `<your-name>.duckdns.org`) and **Expires on** with a date. Check that the name is right.

What the app checks:

- The file must be a PKCS#12 file with a private key and a certificate, up to 1 MB.
- A wrong password or another file type shows "Cannot open the file: wrong password, or it is not a PKCS#12 (.p12) file." Nothing is saved then.
- The app lists the DNS names in the certificate. Wildcard names (`*.example.com`) are not used to build links. Only normal names are shown as https links.
- It takes effect at once. You do not need to restart the server.

The https link now appears in the app's address list under **https (certificate)**.

To remove the certificate, tap **Remove certificate**.

---

### 4. Use it in the car

1. Start the WebMirror server in the app, with the virtual address on.
2. Turn on the phone's hotspot. Join the Tesla Wi-Fi to the phone hotspot.
3. In the car: Settings > Wi-Fi, and enable **Remain connected in Drive** for that network. Otherwise the car drops the Wi-Fi while driving.
4. Open the Tesla browser and type this exactly:

```
https://<your-name>.duckdns.org:8443
```

Type `https://` (not `http://`) and the port `:8443`. Port 8080 is plain HTTP and port 8443 is HTTPS. They are not interchangeable.

You can test the same address first in a browser on a laptop that is on the phone's hotspot. If you see the lock icon and no warning, the car will work too.

---

### 5. Renewal (important)

Let's Encrypt certificates are short-lived.

- Today (November 2026) Let's Encrypt certificates are valid for **90 days**.
- Let's Encrypt has announced shorter lifetimes: **64 days by default from February 2027**, and **45 days from February 2028**. Check <https://letsencrypt.org/2025/12/02/from-90-to-45.html> for current dates.
- The app shows the exact expiry date under **Expires on**. It shows "Expired on ..." when it is too late.

**A browser refuses an expired certificate.** The page will not open (or shows a big warning). Renew before the date.

How to renew:

1. On the computer, run:

```bash
export DuckDNS_Token="<your-duckdns-token>"
acme.sh --renew -d <your-name>.duckdns.org --force
```

   (acme.sh also renews by itself through a daily job on a computer that stays on. Then you only need the next steps. `--force` makes it renew now even if it is not yet due.)

2. Create the new `.p12` again, with step 3.2 (same password or a new one).
3. Copy it to the phone and import it again (step 3.4). The new file replaces the old one.
4. Check the new **Expires on** date in the app.

Tip: set a calendar reminder for about 2-3 weeks before the date shown in the app.

Everything else (the DuckDNS name and the address) stays the same.

---

### 6. Troubleshooting

**The name does not resolve (nslookup / dig shows nothing or an error).**
- Wait a minute and try again. Check that you pressed **update ip** on DuckDNS.
- Some routers and DNS resolvers block answers that point to private or link-local addresses. This is called "DNS rebinding protection". If your laptop is on home Wi-Fi, this may be the cause. Test with another resolver: `dig +short <your-name>.duckdns.org @1.1.1.1`.
- On the phone hotspot, the car uses the phone's mobile-data DNS. This normally works. If it fails, test the name on a laptop that is joined to the phone hotspot.
- If a resolver always blocks it, set it to allow `duckdns.org` (many routers have an "allow list" for rebinding protection).

**"This site can't be reached" or the page does not load.**
- Check the scheme and port: `https://` and `:8443`. Using `http://...:8443` or `https://...:8080` fails.
- Check that the WebMirror server is running, the virtual address is on, and the car is joined to the hotspot.

**Certificate error: "name mismatch" (or similar).**
- The name in the address bar must be one of the **DNS names** shown in the app. You must not use the IP address (`https://169.254.77.1:8443` always fails, because a certificate cannot be issued for it). Use `<your-name>.duckdns.org`.

**The car says the connection is not private.**
- The certificate may be a test certificate or expired. Open the app's **HTTPS certificate** section: if it shows "This is a TEST certificate", tap **Get a real certificate**; if it is expired, tap **Renew now**.

**The browser says the certificate is expired.**
- See step 5. Renew and import again.

**I changed the virtual address in the app.**
- Go to the DuckDNS page and set **current ip** to the new address, then **update ip**. Wait for `nslookup` to show it. The certificate does not need to change: it belongs to the name, not to the address.

**Import says "wrong password, or it is not a PKCS#12 file".**
- Check the password (no extra spaces) and that you picked the `.p12` file, not the `.key` or `.cer` file.
- If the password is surely right and you made the file with a very new openssl (version 3), create it again with the extra flag `-legacy` (only for Option B), or use Option A.

**acme.sh says it uses ZeroSSL or asks for an account.**
- Run `acme.sh --set-default-ca --server letsencrypt` and add `--server letsencrypt` to the issue command.

**acme.sh fails to add the TXT record.**
- Check the token (copy it again from the DuckDNS page). Run the issue command again with `--debug 2` to see the cause.

**Let's Encrypt says "too many certificates" (rate limit).**
- Wait and try again later. Do not repeat `--issue --force` many times. DuckDNS is on the Public Suffix List, so each `<your-name>.duckdns.org` has its own limits and other DuckDNS users do not use up yours.

---

### 7. Security notes

- The `.p12` file contains your private key. Anyone who has it and its password can pretend to be your name. Do not share it. Do not upload it to a public place. Do not commit it to Git.
- Your DuckDNS **token** controls your names. Keep it private. Never paste it in public chats or screenshots. If it leaked, generate a new one on the DuckDNS page.
- acme.sh stores the token in `~/.acme.sh/account.conf`. Protect your computer account.
- After you import the certificate on the phone, delete the copies of the `.p12` from Downloads, chat and cloud storage. The app keeps its own copy in private storage. The password is stored encrypted with a key in the Android Keystore.
- A DuckDNS name pointing to `169.254.77.1` is public information. It gives nobody access: that address only exists on your phone's own network.

---

### 8. Already own a domain?

You can use a name of your own, for example `mirror.example.com`.

1. At your DNS provider, add an **A record** for `mirror.example.com` with the value `169.254.77.1` (or your address from the app).
2. Issue the certificate with acme.sh. If your DNS provider has an acme.sh plugin (many do, for example Cloudflare: `dns_cf`), you can do it automatically. Find the plugin name and its settings at <https://github.com/acmesh-official/acme.sh/wiki/dnsapi>. For example:

```bash
export CF_Token="<your-api-token>"
acme.sh --issue --dns dns_cf -d mirror.example.com --server letsencrypt
```

3. If there is no plugin, use manual mode:

```bash
acme.sh --issue --dns -d mirror.example.com --server letsencrypt \
  --yes-I-know-dns-manual-mode-enough-go-ahead-please
```

   acme.sh prints a TXT record to add. Add it at your DNS provider, wait a minute, then run the same command again with `--renew`:

```bash
acme.sh --renew -d mirror.example.com --server letsencrypt \
  --yes-I-know-dns-manual-mode-enough-go-ahead-please
```

   **Manual mode cannot renew by itself.** You must repeat the TXT step every time the certificate expires (see step 5).

4. Continue with step 3 (make the `.p12`, import it) and use `https://mirror.example.com:8443`.
