# AGENTS.md — ResProxy

Notes for coding agents working in this repository. **Keep this file current.** If a change affects the
architecture, layout, build, test workflow, invariants or gotchas described here, update this file in the
same commit (a commit hook checks for this, see the end of this file).

## What this is

ResProxy is a sideloaded Android app that works like a VPN client. It sends phone traffic through residential
proxies (IPRoyal, Geonode, custom), optionally reaching the proxy through a WireGuard VPN first (imported `.conf`
profiles or a Mullvad account). It checks the exit IP's reputation (Clean / Suspicious / Bad) and can rotate
the IP until the result is acceptable. The package is `dev.resproxy`, minSdk 26, target/compileSdk 36.

## Traffic path

```
apps → TUN (VpnService) → hev-socks5-tunnel (native, JNI) → LocalSocksServer relay (127.0.0.1)
     → [WireGuard forwarder 127.0.0.1 → wireguard-go/netstack] → residential proxy (SOCKS5 / HTTP CONNECT)
```

- hev **always** talks to our Kotlin relay (`net/LoopbackServers.kt`), never directly to the proxy. Because
  of that, upstream hostnames are resolved for each connection (with DoH fallback), and `RelayHealth` sees
  whether apps' connections actually get answers. UDP ASSOCIATE is passed through only for a SOCKS5 upstream
  reached directly, without a chain.
- The app excludes itself from the VPN (`addDisallowedApplication(packageName)`), so relay, WireGuard,
  reputation and Mullvad API sockets go direct. With `ONLY_SELECTED` split mode the app simply isn't in the
  allowed list.
- Proxy-only mode: a system HTTP proxy points at `LocalHttpProxy`; apps that ignore it hit an unread TUN and
  are blocked.
- `LocalHttpProxy` is also how the app does its own exit-IP checks and probes through the proxy.

## Invariants (don't break these)

1. **No leaks.** In full-tunnel mode the TUN is established before any upstream I/O, and it stays up across
   rotations, location and settings changes, repairs and outages. A reconnect keeps the old TUN fd open until
   the new one is established.
2. **Kill switch** on (default): failures put the tunnel into `PROXY_DOWN` with traffic blocked, and it keeps
   retrying. Off: failures disconnect.
3. **Never fall back to an unchained connection** when the selected VPN can't be set up (`chainBroken`).
   That would show the real IP to the proxy.
4. Mullvad device keys are **per install**: `MullvadAccount.device` is never put in a backup. Only the account
   number travels.
5. Secrets (accounts, VPN profiles, Mullvad, API keys) are stored encrypted with Keystore AES-GCM
   (`data/SecretStore.kt`). Backups are PBKDF2 + AES-GCM (`data/Backup.kt`, prefix `resproxy-backup-v1:`).
6. Never show the proxy password, the API keys or the backup passphrase in UI or logs.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/dev/resproxy/App.kt` | `AppContainer` (manual DI): repos, `resolveVpn`, Mullvad login, backup export/import, migrations |
| `data/` | `Models.kt` (Account, AppSettings, ConnectionSettings, VpnProfile, MullvadAccount, `VpnExit`), `Repositories.kt` (DataStore repos incl. `VpnRepository`), `SecretStore.kt`, `Backup.kt` |
| `vpn/ProxyVpnService.kt` | TUN lifecycle, `watchSettings` (live apply), `healthLoop` (stall/handshake/engine detection), `repair`, network-change callback, reputation check + auto-rotate |
| `vpn/` other | `HevConfig` (YAML), `Routes` (LAN-bypass route complement), `TunnelState` (status + controller), `SystemHooks` (boot receiver, QS tile), `TunnelNative` (JNI) |
| `net/` | `Upstream` (SOCKS5/HTTP clients, RTT samples), `LoopbackServers` (relay, HTTP proxy, `RelayHealth`), `Resolver` (system DNS + DoH), `WireGuard` (config parser + JNI), `Mullvad` (API, relay list, wg-quick builder), `X25519`, `ProxyProbe`, `LatencyProbe` (parallel ICMP ping to every Mullvad relay / profile endpoint; results sort the Settings → VPN list and the home VPN picker) |
| `provider/` | Credential presets per provider (`Presets.kt`), location catalogs (`ProviderLocations.kt`) |
| `reputation/` | Sources, parsers, `ReputationAggregator` verdict rules |
| `ui/` | Compose screens. Settings = hub (`SettingsScreen`) + pages (`SettingsPageScreen`, `SettingsPage` enum); building blocks `SettingsGroup` / `SettingRow` / `SwitchRow` / `Hint` in `Components.kt`; VPN page and home VPN picker in `VpnSection.kt` |
| `app/src/main/jni/` | ndk-build for vendored hev-socks5-tunnel 2.19.3 (symlinks replaced by file copies) |
| `wgbridge/` | Go c-shared lib (wireguard-go + gVisor netstack): `nativeStart/Forward/Stop/Status/Ping`. `cmd/wgtestserver` is a test peer |
| `app/src/test/` | JVM unit tests (no device needed) |

## Build and test (Windows host)

- JDK 21 is pinned in `gradle.properties`. `local.properties` (git-ignored) needs `sdk.dir=D:/android/AndroidSDK`
  and `go.dir=D:/android/go`. Use forward slashes in both.
- `./gradlew.bat testDebugUnitTest assembleDebug`. The `buildWgBridge` task cross-compiles Go for 4 ABIs with
  the NDK clang (it runs before `preBuild`). Without Go, the APK builds without WireGuard.
- Go tooling: `PATH=/d/android/go/bin:$PATH GOPATH=/d/android/gopath GOMODCACHE=/d/android/gopath/pkg/mod`.
  `go vet` needs `CGO_ENABLED=1`, which has no host C compiler, so let Gradle build it.
- Add or extend unit tests for pure logic (parsers, presets, routes, crypto, relay behaviour). The suite must
  stay green.

## Git, push and releases

- Remote: `git@github.com:alexamiryan/res_proxy.git`. Always commit and push to `main`, no branches for now.
- Remote git operations (push, fetch) run in WSL, where the SSH key is unlocked; local git and builds stay on
  Windows. The Windows `gh` CLI is logged in and handles the GitHub API.
- **Every push is a release:** run `scripts/release.sh` (Git Bash) instead of a bare push. It needs a clean
  tree on `main`, runs the tests, builds the debug APK, pushes via WSL and creates release `v0.1.<commit
  count>` with `ResProxy-v….apk` attached and the commit subjects as notes.
- `versionCode` is the commit count (`app/build.gradle.kts`), so each release installs over the previous one.
  The release ships the debug-signed APK (same key as the builds already on the phone; R8 release builds are
  untested).

## Device testing

- adb: `D:/android/AndroidSDK/platform-tools/adb.exe`. In Git Bash, prefix `adb shell` commands with
  `MSYS_NO_PATHCONV=1`, otherwise `/sdcard/...` paths get rewritten.
- Wait on the device (`adb shell sleep N`), not with a host-side `sleep`.
- The phone has several Android users (main user 0 plus secondary users). Installing the APK updates every
  user, and each user's VPN reconnects on its own (`MY_PACKAGE_REPLACED`). Logcat mixes all users; tell them
  apart with `ps -A -o USER,PID,NAME | grep resproxy`. Only drive the UI of the current foreground user; don't
  switch users or touch other users' data without being asked.
- Useful checks:
  - `uiautomator dump` + grep for reading screen text.
  - `dumpsys connectivity` for TUN routes.
  - The browser to `https://ipinfo.io/ip` for real traffic through the tunnel.
  - `svc wifi disable/enable` for network-change repair.
- `run-as dev.resproxy [--user N]` works on debug builds.

## Gotchas learned the hard way

- **Android 17 blocks app traffic between Android users** (loopback and the device's own IP both time out).
  Cross-profile sync over local sockets is impossible. Moving data between profiles is manual (encrypted
  backup file).
- Some networks sinkhole provider and reputation domains (e.g. `iproyal.com`). Every host lookup goes through
  `Resolver` (DoH fallback to 1.1.1.1 / 8.8.8.8 by IP).
- IPRoyal SOCKS5 has no UDP, so apps fall back to TCP. The default session lifetime is the maximum
  (`MAX_LIFETIME_MINUTES`, 7 days, IPRoyal's limit). The Geonode preset caps it at 1440 min. Schema 3
  migrated the old 1440 default to 7 days.
- Android's `/system/bin/ping` (iputils) has no `-4`/`-6` flags; IPv6 uses `ping6`. It works from the app
  sandbox, and because the app is excluded from its own VPN, it measures the real path even while connected.
- Users paste whole proxy lines or usernames with targeting params. Presets strip existing params, and the UI
  shows the base username.
- SOCKS reply codes 1–8 (`SocksRefusedException`) mean the target failed, not the proxy; they don't count as
  relay failures. Auto-repair needs failures across ≥2 distinct hosts with no successes for 20 s.
- Network changes: `registerDefaultNetworkCallback` didn't fire usefully for the excluded VPN app; use a
  `NOT_VPN` + `INTERNET` `NetworkRequest` and treat "new network after a loss" as a switch.
- Reputation reports are cached per IP for 1 h, and a sticky session keeps the same IP. When API keys change
  (saved as you type), the service clears the cache and re-checks, otherwise new sources never show up.
- Compose settings text fields keep local state (`TextSetting` / `NumberField`), because DataStore writes are
  async and binding straight to them drops typed characters.
- Backup import must run in `appScope` and write accounts last: saving accounts swaps the first-run UI out
  and cancels composition-scoped coroutines.
- `kotlinx.serialization` with `encodeDefaults = true`, `ignoreUnknownKeys = true`. Add new settings fields
  with defaults; bump `AppSettings.SCHEMA` only when old values must be overridden.

## Conventions

- Kotlin, Compose Material3, coroutines/Flow, manual DI. No Hilt, no new heavy dependencies without reason.
- User-facing text is plain, short and specific. Error messages say what to do next.
- Line endings LF (`.gitattributes`), except `*.bat`.

## Keeping this file updated

`.claude/settings.json` registers a `PreToolUse` hook (`.claude/hooks/agents-md-check.sh`) on `git commit`.
When staged changes touch source, build or native files but not `AGENTS.md`, the commit is blocked with a
reminder to review this file. If nothing here needs to change, the commit can go through with
`AGENTS_MD_OK=1 git commit …`.
