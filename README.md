# ResProxy

An Android app that works like a VPN client and sends your phone's traffic through a residential proxy (IPRoyal, Geonode, or any SOCKS5/HTTP proxy). Each time you connect, it detects the exit IP and checks its reputation.

## How traffic flows

```
apps → TUN (VpnService) → hev-socks5-tunnel (native) → SOCKS5 proxy
                                                    ↘ LocalSocksServer → HTTP CONNECT proxy
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
- **Kill switch**: a health probe runs every 30 s by default. While the proxy is down, the TUN stays up and traffic is dropped. The tunnel reconnects automatically when the proxy comes back. For full protection, also turn on Always-on VPN and "Block connections without VPN" in system settings.
- **Split tunnelling, connect on boot, Quick Settings tile, live speed, and data usage per profile.**

## Build

You need JDK 21 (set in `gradle.properties`), the Android SDK and NDK 29. The SDK location goes in `local.properties`.

```bash
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                     # minified, signed with the debug key for sideloading
```

`app/src/main/jni/hev-socks5-tunnel` contains a vendored copy of hev-socks5-tunnel 2.19.3 (MIT). On Windows, symlinks in that repository were replaced with copies of the files they point to.
