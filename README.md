# ResProxy

Sends your traffic through a residential proxy (IPRoyal, Geonode, or any SOCKS5/HTTP proxy), checks the exit IP's
reputation before anything goes out, and rotates the IP until it's clean. Optionally reaches the proxy through a
WireGuard VPN (imported configs or a Mullvad account), so the proxy provider never sees your real IP.

| Platform | Folder | What |
|---|---|---|
| Android | [`android/`](android) | VPN app (whole-device traffic) |
| Chrome / Edge / Brave | [`chrome/`](chrome) | Extension (browser traffic) |
| Desktop helper | [`native/wgbridge/cmd/resproxy-helper`](native/wgbridge/cmd/resproxy-helper) | Optional companion for the extension: WireGuard chaining, SOCKS5 with password, pings, exact data usage |
| Shared | [`shared/`](shared) | Backup format, settings schema and test vectors every platform is tested against |
| iOS | [`ios/`](ios) | Packet-tunnel VPN app (whole-device traffic), same features as Android |

Settings move between platforms with an encrypted backup (Settings → Backup & transfer): export on Android, import
in Chrome, and the other way round. Mullvad device keys stay per install; each install registers its own.

## Chrome extension

```
tabs → Chrome proxy (PAC) → residential proxy                                  (direct mode)
tabs → Chrome proxy (PAC) → resproxy-helper 127.0.0.1 → [WireGuard →] proxy     (helper mode)
```

- Same features as the app: provider presets and location targeting, sticky sessions remembered per location,
  New IP, reputation check with auto-rotate, traffic gate (tabs show a waiting page until the IP passes), kill
  switch, health checks and repair, live settings, LAN bypass, split tunnelling **by site** (instead of by app),
  data usage, backup import/export compatible with Android, WebRTC leak protection.
- Without the helper, Chrome talks to the proxy itself. That works for IPRoyal and Geonode (their HTTP gateways take
  the same credentials) and any HTTP proxy. Chrome caches proxy logins per host, so on New IP the extension clears
  the proxy site's browsing data (cookies of e.g. iproyal.com go too) to make Chrome use the new session.
- The helper (Go, ~10 MB, no admin rights) adds what an extension can't do: WireGuard/Mullvad chaining, SOCKS5 with a
  password, ICMP latency for the VPN list, exact byte counts, and dropping open connections on New IP. Install:
  download `resproxy-helper-<os>-<arch>` from the release and run `resproxy-helper install` once (registers itself
  for Chrome, Edge, Brave and Chromium for the current user; `uninstall` removes it).
- Install the extension: unzip `ResProxy-chrome-v….zip`, open `chrome://extensions`, turn on Developer mode, Load
  unpacked. The extension ID is fixed (`chrome/extension-key.pub`), so the helper and updates keep working.
- Limits: only browser traffic; Chrome drops the proxy if the extension is disabled; QUIC/UDP isn't proxied (Chrome
  uses TCP through proxies, WebRTC is blocked from non-proxied UDP).

## iOS

```
apps → utun (NEPacketTunnelProvider) → hev-socks5-tunnel → Go relay (gate) → [WireGuard →] SOCKS5 / HTTP proxy
```

- Same features as Android: presets and location targeting, sticky sessions per location, New IP, reputation check
  with auto-rotate and a traffic gate, kill switch, health checks and repair (network changes, stalled engine, lost
  VPN handshake), live settings, LAN bypass, WireGuard / Mullvad chaining, data usage, backup import/export.
- The data plane in the tunnel extension is the desktop helper (`native/wgbridge/cmd/resproxy-helper`) built as a C
  archive, plus hev-socks5-tunnel. Settings live in the App Group container, secrets in the Keychain.
- Kill switch on also sets iOS's "include all networks", so nothing leaves outside the tunnel even while it reconnects.
- Not on iOS: per-app split tunnelling (needs MDM) and proxy-only mode; their settings are kept for backups.
- Needs a paid Apple developer team (Network Extension entitlement) and a real device; packet tunnels don't run in the
  Simulator.

## Android: how traffic flows

```
apps → TUN (VpnService) → hev-socks5-tunnel (native) → LocalSocksServer relay → [WireGuard →] SOCKS5 / HTTP proxy
```

- **Full tunnel** (default): carries all TCP and UDP. DNS uses fake-IP mapping, so hostnames are resolved on the proxy side and DNS can't leak.
- **Proxy only** (Android 10+): sets a system HTTP proxy that points at a local proxy, which adds your credentials. Only apps that honor the system proxy work. Everything else is blocked.
- ResProxy leaves its own traffic out of the VPN. A local HTTP proxy on `127.0.0.1` adds your credentials. The app uses it for the exit-IP and health checks.

## Features

- **Provider presets.** IPRoyal adds targeting and session parameters to the password. Geonode adds them to the username. You can set country, state, city, sticky or rotating, and session lifetime. A preview shows the generated credentials. Host and port start from the presets' defaults and stay editable. Check them against your provider dashboard.
- **New IP**: rotates the sticky session while the TUN stays up, so no traffic goes out directly during the switch.
- **Reputation check**:
  - Free sources: ipapi.is, proxycheck.io, ip-api.com, and four DNS blacklists (DNSBLs).
  - Sources that need API keys: AbuseIPDB, IPQualityScore, Scamalytics.
  - The verdict is CLEAN, SUSPICIOUS or BAD. Being detected as a proxy alone counts as information, not as a bad result.
  - Lookups go direct first. If a lookup fails, for example because a DNS filter blocks the API, it is retried through the proxy.
- **Auto-rotate**: rotates until the verdict is below your threshold, up to N tries, then keeps the best IP it found.
- **Connection health and auto-repair**: every app connection passes through the in-app relay, so the app can tell whether apps actually get answers. The proxy address is resolved again for each connection. When connections fail across several hosts, the VPN handshake goes stale, the tunnel engine stops forwarding, or the network changes (Wi-Fi ↔ mobile), the app rebuilds the VPN, relay and engine while the TUN stays up. After three repairs that don't help, it reconnects.
- **Settings apply live**: changing routing, split apps or LAN bypass rebuilds the TUN. The old TUN stays up until the new one is established. Changing credentials, the gateway, the protocol or the VPN restarts the upstream.
- **LAN bypass** (on by default): private, link-local and multicast ranges are left out of the TUN routes.
- **Kill switch**: a health probe runs every 30 s by default. While the proxy is down, the TUN stays up and traffic is dropped. The tunnel reconnects automatically when the proxy comes back. For full protection, also turn on Always-on VPN and "Block connections without VPN" in system settings.
- **Backup & transfer**: export or import everything, encrypted with a passphrase you choose. Android 17 blocks app traffic between Android users, so profiles can't sync with each other directly. Move the file between profiles yourself.
- **Split tunnelling, connect on boot, Quick Settings tile, live speed, and data usage per profile.**
- **VPN chain (WireGuard)**: import any number of WireGuard `.conf` profiles (e.g. from Proton), or add a Mullvad account by account number. Each install registers its own WireGuard key as a Mullvad device. Pick the VPN on the home screen: a profile, or a Mullvad country, city or server. The home screen shows ping to the VPN (in-tunnel ICMP to the VPN's DNS) and ping to the proxy (SOCKS5 auth round trip). Import a `.conf` and the proxy connection goes apps → TUN → WireGuard → residential proxy. WireGuard runs in-process (`native/wgbridge/`, wireguard-go + gVisor netstack exposing local TCP forwarders), so no second Android VPN is needed; the proxy hostname is resolved by the VPN's DNS inside the tunnel.

## Build

**Android** (`android/`): JDK 21 (set in `gradle.properties`), the Android SDK and NDK 29, and Go (version per
`native/wgbridge/go.mod`). Put the SDK and Go locations in `android/local.properties` (`sdk.dir=…`, `go.dir=…`);
without Go the APK builds but WireGuard chaining is unavailable.

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

**Chrome** (`chrome/`): Node 20+.

```bash
cd chrome && npm ci && npm test && npm run build            # dist/extension (load unpacked)
CHROMIUM=…/chrome.exe node test/e2e.mjs [--helper]          # end-to-end in a real Chromium with a local test proxy
```

**iOS** (`ios/`): Xcode 16+ and Go. `python3 ios/scripts/gen-project.py` writes `ios/ResProxy.xcodeproj`; the
tunnel target runs `ios/scripts/build-native.sh` (hev + Go bridge) itself.

```bash
cd ios/ResProxyCore && swift test                          # shared vectors, presets, reputation, WireGuard parsing
cd ios && xcodebuild -scheme ResProxy -destination 'generic/platform=iOS' -allowProvisioningUpdates build
```

**Helper** (`native/wgbridge`):

```bash
CGO_ENABLED=0 go test ./cmd/resproxy-helper/ && CGO_ENABLED=0 go build ./cmd/resproxy-helper
```

`scripts/release.sh` builds and tests all of it and publishes one GitHub release with the APK, the extension zip and
the helper binaries.

`android/app/src/main/jni/hev-socks5-tunnel` contains a vendored copy of hev-socks5-tunnel 2.19.3 (MIT). On Windows, symlinks in that repository were replaced with copies of the files they point to.
