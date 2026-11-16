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
