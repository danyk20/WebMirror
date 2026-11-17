# Car test results

Fill in after each car test. Save every probe JSON (Share button in the app) next to this file in `docs/`.

## Car test 1 (M1.4): address matrix

Setup: phone hotspot on, mobile data on, car Wi-Fi joined to the hotspot with "Remain connected in Drive" enabled, app started, virtual IP on.

Date: ____  Car software version: ____  Phone: ____

| # | URL typed in the car | Tests | Loads? | Probe JSON file | Notes |
|---|---|---|---|---|---|
| 1 | `http://<hotspot-ip>:8080` | private IP directly | | | |
| 2 | `http://<name-for-private-ip>:8080` | DNS name -> private IP | | | |
| 3 | `http://198.51.100.1:8080` | virtual IP, reserved range | | | |
| 4 | `http://3.3.3.3:8080` | virtual IP, public-looking | | | |
| 5 | `http://<name>:8080` | DNS name -> virtual IP | | | |
| 6 | `https://<name>:8443` | HTTPS -> secure context -> WebCodecs | | | |

Rows that worked, repeated with a passenger watching in Drive (page stays up?): ____

### Decision gate after M1

- Official URL form for the car: ____
- `VideoDecoder` present and H.264 supported on the HTTPS row? ____
- Car mode (H.264 + mandatory HTTPS, or JPEG with HTTPS optional): ____
- If no row loaded: stop and review.

## Car test 1b: hostnames and IPv6

Setup as car test 1. The app lists "Addresses" and "Names (for the car test)" (public service sslip.io; the car needs internet through the phone to resolve them).

Date: ____  Car software version: ____  Phone: ____

| # | URL typed in the car | Tests | Loads? | Probe JSON file | Notes |
|---|---|---|---|---|---|
| 1 | `http://<hotspot-ipv4-with-dashes>.sslip.io:8080` | hostname -> hotspot IPv4 | | | |
| 2 | `http://[<phone-ipv6>]:8080` | raw IPv6 | | | |
| 3 | `http://<ipv6-with-dashes>.sslip.io:8080` | hostname -> IPv6 | | | |

`http://test-ipv6.com` in the car browser reports IPv6 connectivity? ____

## Desk test M1.1 — virtual address over the phone hotspot (2026-11-16)

Phone: Galaxy S22 (SM-S901U1), Android 16. Mac joined to the phone's hotspot (phone `swlan0` 10.15.23.199).

| Test from the Mac | Result |
|---|---|
| `http://10.15.23.199:8080/probe.html` (hotspot address) | 200 — works |
| `ping 198.51.100.1` (virtual address) | answered by the phone (ttl 64) |
| `http://198.51.100.1:8099` (closed port) | refused immediately (reaches the phone) |
| `http://198.51.100.1:8080/probe.html` | **times out** |

**Cause (confirmed):** Android's connectivity module installs an "ingress discard" rule for every address owned by a `VpnService` interface. `adb shell dumpsys connectivity trafficcontroller` shows `sIngressDiscardMap: [/198.51.100.1]: tun0, tun0`: packets for that address are delivered to an app socket only if they arrive on `tun0`. Packets from hotspot clients arrive on `swlan0` and are dropped. Routing itself is correct. `Builder.allowBypass()` does not remove the rule (tried).

**Conclusion:** the virtual-address approach of plan M1.1 does not work on current Android. The code stays in the repo but must not be relied on. Decision on the replacement is open.

## Desk findings 2026-11-17 (phone on mobile data, hotspot on)

- **IPv6 is not shared with hotspot clients on this phone/carrier.** The hotspot's upstream is a separate IPv4-only interface (`rmnet_data7`, 10.204.153.240); the phone's public IPv6 address lives on a different one (`rmnet_data8`). `dumpsys tethering` shows `IPv6TetheringCoordinator ... UpstreamNetworkState{null}` and `swlan0` has only a link-local IPv6 address. So the car gets no IPv6 from the phone, and the raw-IPv6 and IPv6-name rows of car test 1b cannot work here.
- **A link-local virtual address avoids Android's VPN filter.** Android skips its ingress-discard rule for link-local addresses. With the virtual address set to `169.254.77.1`, `sIngressDiscardMap` is empty, the phone routes hotspot packets for that address to itself (`ip route get 169.254.77.1 from <client> iif swlan0` → `local`), and `arp_ignore` is 0, so the phone also answers ARP for it on the hotspot. Not yet confirmed from a real hotspot client; whether the Tesla browser accepts 169.254.x.x is unknown.

## Car test 1c — to do

| # | URL typed in the car | Loads? |
|---|---|---|
| 1 | `http://169.254.77.1:8080/probe.html` (virtual address, link-local) | |
| 2 | `http://10-15-23-199.sslip.io:8080/probe.html` (name → hotspot address) | |
