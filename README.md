# ResProxy

An Android app that works like a VPN client and sends your phone's traffic through a residential proxy (IPRoyal, Geonode, or any SOCKS5/HTTP proxy). Each time you connect, it detects the exit IP and checks its reputation.

## How traffic flows

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
- **VPN chain (WireGuard)**: import any number of WireGuard `.conf` profiles (e.g. from Proton), or add a Mullvad account by account number. Each install registers its own WireGuard key as a Mullvad device. Pick the VPN on the home screen: a profile, or a Mullvad country, city or server. The home screen shows ping to the VPN (in-tunnel ICMP to the VPN's DNS) and ping to the proxy (SOCKS5 auth round trip). Import a `.conf` and the proxy connection goes apps → TUN → WireGuard → residential proxy. WireGuard runs in-process (`wgbridge/`, wireguard-go + gVisor netstack exposing local TCP forwarders), so no second Android VPN is needed; the proxy hostname is resolved by the VPN's DNS inside the tunnel.

## Build

You need JDK 21 (set in `gradle.properties`), the Android SDK and NDK 29, and Go (version per `wgbridge/go.mod`) for `wgbridge`. Put the SDK and Go locations in `local.properties` (`sdk.dir=…`, `go.dir=…`); without Go the APK builds but WireGuard chaining is unavailable.

```bash
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                     # minified, signed with the debug key for sideloading
```

`app/src/main/jni/hev-socks5-tunnel` contains a vendored copy of hev-socks5-tunnel 2.19.3 (MIT). On Windows, symlinks in that repository were replaced with copies of the files they point to.
