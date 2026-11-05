# AGENTS.md — ResProxy

Notes for coding agents working in this repository. **Keep this file current.** If a change affects the
architecture, layout, build, test workflow, invariants or gotchas described here, update this file in the
same commit (a commit hook checks for this, see the end of this file).

## What this is

ResProxy sends traffic through residential proxies (IPRoyal, Geonode, custom), optionally reaching the proxy through
a WireGuard VPN first (imported `.conf` profiles or a Mullvad account). It checks the exit IP's reputation
(Clean / Suspicious / Bad) and can rotate the IP until the result is acceptable. One repo, several platforms:

| Folder | Platform |
|---|---|
| `android/` | Sideloaded Android VPN app. Package `dev.resproxy`, minSdk 26, target/compileSdk 36 |
| `chrome/` | Chrome MV3 extension (TypeScript + Preact, esbuild). Works in Edge/Brave/Chromium too |
| `native/wgbridge/` | Go: Android WireGuard JNI lib (root package), `cmd/resproxy-helper` (native-messaging helper for the extension), `cmd/wgtestserver` / `cmd/testproxy` (test peers) |
| `shared/` | `FORMAT.md` (backup, settings schema, presets, verdict rules) + `test-vectors/` that every platform's tests read |
| `ios/` (future) | Implements `shared/FORMAT.md`; WireGuard can come from `native/` via gomobile (wireguard-go is what WireGuard's own iOS app uses) |

**Cross-platform rule:** anything in `shared/FORMAT.md` (backup format, settings fields and defaults, preset
syntax, verdict rules) changes on every platform in the same commit, with the vectors regenerated. New settings
fields get defaults and are added to Android's `Models.kt` even when only one platform uses them, so backups
round-trip (e.g. `connection.sites` is Chrome-only, `connection.apps` Android-only).

## Android traffic path

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

## Chrome traffic path

```
tabs → Chrome proxy (PAC, chrome.proxy) → residential proxy (HTTP, auth via webRequest.onAuthRequired)          direct mode
tabs → Chrome proxy (PAC) → resproxy-helper 127.0.0.1 (token auth) → [WireGuard netstack →] proxy (SOCKS5/HTTP)  helper mode
```

- `background/tunnel.ts` is the `ProxyVpnService` counterpart (connect, gate, check + auto-rotate, health + repair,
  live settings). Runtime state lives in `chrome.storage.session` so it survives service-worker restarts; the UI reads
  the status from there.
- The PAC is set before any upstream I/O (first a dead `127.0.0.1:9` target) and only cleared on disconnect / kill
  switch off. It only does string matching (no `dnsResolve`), so evaluating it can't leak DNS.
- The traffic gate is declarativeNetRequest **session** rules: tab page loads redirect to `blocked.html` (which
  continues to the page once the gate opens), other tab requests are blocked; requests with `tabId -1` (the
  extension's own checks) pass. LAN and split-excluded sites get allow rules.
- Helper mode is used when the helper is installed (and `prefs.useHelper`), and is required for VPN chaining and
  SOCKS5 with a password. Without it IPRoyal/Geonode silently use their HTTP gateway. The helper also does the
  extension's direct API calls (`fetch`, like Android's sockets outside the VPN), system-resolver DNSBL lookups,
  ICMP pings and byte counting; it exits when Chrome closes the native port.
- Reputation and IP-echo requests from the worker go through Chrome's proxy (so through the exit IP); with the
  helper, reputation APIs go direct first.

## Invariants (don't break these)

1. **No leaks.** In full-tunnel mode the TUN is established before any upstream I/O, and it stays up across
   rotations, location and settings changes, repairs and outages. A reconnect keeps the old TUN fd open until
   the new one is established.
2. **Background jobs never crash the process.** The service scope has a `CoroutineExceptionHandler` that
   logs and reconnects; an uncaught exception would drop the VPN with nothing left to restore it.
3. **Apps only get internet through a checked IP.** `TrafficGate` (`net/TrafficGate.kt`) stays closed from
   connect, each new session and settings rebuilds until the exit IP's verdict is below the rotate threshold.
   While closed, the relay (and in proxy-only mode the system proxy) holds new connections for up to 8 s,
   then refuses them, and drops open ones when an IP turns out bad. The app's own checks use a separate,
   ungated `httpProxy`. Rotations used up, or a bad IP with auto-rotate off, leave traffic blocked until
   "New IP". Failed or UNKNOWN checks are retried by the health loop. Turning checks off opens the gate.
4. **Kill switch** on (default): failures put the tunnel into `PROXY_DOWN` with traffic blocked, and it keeps
   retrying. Off: failures disconnect.
5. **Never fall back to an unchained connection** when the selected VPN can't be set up (`chainBroken`).
   That would show the real IP to the proxy.
6. Mullvad device keys are **per install** unless the user explicitly shares one: `MullvadAccount.device` goes into a
   backup only when "Share Mullvad device" is ticked at export (`BackupPayload.mullvadDevice`); importing it removes
   the importer's own different device from the account. Otherwise only the account number travels. Never share it
   implicitly. Both apps list the account's devices (Settings → VPN) and can remove stale ones.
7. Secrets (accounts, VPN profiles, Mullvad, API keys) are stored encrypted with Keystore AES-GCM
   (`data/SecretStore.kt`). Backups are PBKDF2 + AES-GCM (`data/Backup.kt`, prefix `resproxy-backup-v1:`).
8. Never show the proxy password, the API keys or the backup passphrase in UI or logs (including the extension's
   Diagnostics log in `chrome.storage.session`).
9. Chrome: secrets are AES-GCM encrypted with a non-extractable key in IndexedDB (`background/store.ts`); only the
   service worker writes storage (pages go through the message API in `shared/protocol.ts`).

## Layout (Android, under `android/`)

| Path | What |
|---|---|
| `app/src/main/java/dev/resproxy/App.kt` | `AppContainer` (manual DI): repos, `resolveVpn`, Mullvad login, backup export/import, migrations |
| `data/` | `Models.kt` (Account, AppSettings, ConnectionSettings, VpnProfile, MullvadAccount, `VpnExit`), `Repositories.kt` (DataStore repos incl. `VpnRepository`), `SecretStore.kt`, `Backup.kt` |
| `vpn/ProxyVpnService.kt` | TUN lifecycle, `watchSettings` (live apply), `healthLoop` (stall/handshake/engine detection), `repair`, network-change callback, reputation check + auto-rotate |
| `vpn/` other | `HevConfig` (YAML), `Routes` (LAN-bypass route complement), `TunnelState` (status + controller), `SystemHooks` (boot receiver, QS tile), `TunnelNative` (JNI) |
| `net/` | `Upstream` (SOCKS5/HTTP clients, RTT samples), `LoopbackServers` (relay, HTTP proxy, `RelayHealth`), `Resolver` (system DNS + DoH), `WireGuard` (config parser + JNI), `Mullvad` (API, relay list, wg-quick builder), `X25519`, `ProxyProbe`, `LatencyProbe` (parallel ICMP ping to every Mullvad relay / profile endpoint; results sort the Settings → VPN list and the home VPN picker) |
| `provider/` | Credential presets per provider (`Presets.kt`), location catalogs (`ProviderLocations.kt`) |
| `reputation/` | Sources, parsers, `ReputationAggregator` verdict rules |
| `ui/` | Compose screens. Settings = hub (`SettingsScreen`) + pages (`SettingsPageScreen`, `SettingsPage` enum: VPN, Split tunnelling, Protection, …); building blocks `SettingsGroup` / `SettingRow` / `SwitchRow` / `Hint` in `Components.kt`; VPN page and home VPN picker in `VpnSection.kt` |
| `app/src/main/jni/` | ndk-build for vendored hev-socks5-tunnel 2.19.3 (symlinks replaced by file copies) |
| `app/src/test/` | JVM unit tests (no device needed). `SharedVectorsTest` reads `../shared/test-vectors` |

## Layout (Chrome, under `chrome/`)

| Path | What |
|---|---|
| `src/core/` | Platform-neutral ports of the Kotlin logic: `models` (settings, normalization, sessions), `backup`, `presets`, `locations`, `reputation` (parsers, DNSBL, verdict), `vpn` (wg-quick parser, Mullvad API/relays), `pac` |
| `src/background/` | `main.ts` (listeners, message API, catalogs, backup), `tunnel.ts` (controller), `browser.ts` (PAC, DNR gate, WebRTC, badge), `helper.ts` (native messaging client), `store.ts`, `log.ts` |
| `src/ui/` | `popup.tsx` (home), `options.tsx` (settings pages), `pickers.tsx` (location, VPN), `components.tsx`, `blocked.ts` |
| `src/shared/protocol.ts` | Status type and the page ↔ worker message API |
| `static/` | manifest (version and `key` stamped by `build.mjs`), HTML, `styles.css` |
| `test/` | vitest unit tests (`core.test.ts`, shared vectors) and `e2e.mjs` (real Chromium + `cmd/testproxy`) |
| `extension-key.pub`, `extension-id.txt` | Public key fixing the extension ID `hncfepeimcljgcafphgmcjhigjcfdemc` (the helper's `allowed_origins`); unpacked installs need no private key |

## Layout (native, under `native/wgbridge/`)

| Path | What |
|---|---|
| `main.go` | Android c-shared lib (wireguard-go + gVisor netstack): `nativeStart/Forward/Stop/Status/Ping` |
| `cmd/resproxy-helper/` | Extension helper: native messaging (`main.go`), local proxy (`proxy.go`), SOCKS5/HTTP CONNECT client (`upstream.go`), WireGuard (`wg.go`), fetch/DNS/ping (`misc.go`), self-registration (`install_*.go`) |
| `cmd/wgtestserver/` | WireGuard test peer (also used by `wg_chain_test.go`) |
| `cmd/testproxy/`, `internal/testproxy/` | HTTP-CONNECT + SOCKS5 test proxy with auth that logs each login |

## Build and test (Windows host)

- Android: JDK 21 is pinned in `android/gradle.properties`. `android/local.properties` (git-ignored) needs
  `sdk.dir=D:/android/AndroidSDK` and `go.dir=D:/android/go`. Use forward slashes in both.
  `cd android && ./gradlew.bat testDebugUnitTest assembleDebug`. The `buildWgBridge` task cross-compiles
  `../native/wgbridge` for 4 ABIs with the NDK clang (before `preBuild`). Without Go, the APK builds without WireGuard.
- Go tooling: `PATH=/d/android/go/bin:$PATH GOPATH=/d/android/gopath GOMODCACHE=/d/android/gopath/pkg/mod`.
  The root package needs cgo + jni.h (no host C compiler), so vet/test only `./cmd/... ./internal/...` with
  `CGO_ENABLED=0`. `wg_chain_test.go` is manual (needs a running `cmd/wgtestserver`, see the file).
- Chrome: `cd chrome && npm ci && npx tsc --noEmit && npm test && node build.mjs` (→ `dist/extension`;
  `--zip` adds the release zip, `--watch` rebuilds). End-to-end: build, then
  `CHROMIUM=$LOCALAPPDATA/ms-playwright/chromium-1228/chrome-win64/chrome.exe node test/e2e.mjs` (Playwright's
  open-source Chromium; branded Chrome ignores `--load-extension`). For `--helper`, build the helper and run
  `resproxy-helper install` first, `uninstall` afterwards.
- Regenerate backup vectors after a format change: `WRITE_VECTORS=1` for both `npm test` and
  `./gradlew testDebugUnitTest --tests '*writeAndroidVector'`.
- Add or extend unit tests for pure logic (parsers, presets, routes, crypto, relay behaviour) on every platform
  that has it. All suites must stay green.

## Git, push and releases

- Remote: `git@github.com:alexamiryan/res_proxy.git`. Always commit and push to `main`, no branches for now.
- Remote git operations (push, fetch) run in WSL, where the SSH key is unlocked; local git and builds stay on
  Windows. The Windows `gh` CLI is logged in and handles the GitHub API.
- **Every push is a release:** run `scripts/release.sh` (Git Bash) instead of a bare push. It needs a clean
  tree on `main`, runs all test suites, builds the debug APK, the extension zip and the helper for
  windows/amd64, linux/amd64, darwin/arm64 and darwin/amd64, pushes via WSL and creates release
  `v0.1.<commit count>` with all of them attached and the commit subjects as notes.
- `versionCode` is the commit count (`android/app/build.gradle.kts`), so each release installs over the previous
  one. The extension version is `0.1.<commit count>` too (stamped by `chrome/build.mjs`).
  The release ships the debug-signed APK (same key as the builds already on the phone; R8 release builds are
  untested).

## Device testing (Android)

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
- Sticky sessions are remembered per provider + location (`AppSettings.sessions`, `withSession` /
  `restoreSession`), so switching provider or location and back keeps the same exit IP until the session's
  lifetime ends (Geonode: 24 h). Only "New IP" and auto-rotate make a new session. Changing the VPN keeps the
  session too.
- Geonode's location API (`monitor.geonode.com/.../targeting-options`) takes Basic auth with the dashboard
  login or, if none is set, the base proxy username + password (`ProviderLocations.geonodeAuth`).
- LAN bypass: normally the LAN ranges are left out of the TUN routes. With Always-on + "Block connections
  without VPN" (`isLockdownEnabled`) Android drops anything routed outside the VPN, LAN included, so then the LAN
  stays in the TUN and the relay connects private IPs directly (`LanTargets`). In both modes, local names
  (single label, `.local`, `.lan`, `.internal`, …) arrive as hostnames via fake-IP DNS; the relay resolves only
  those with the network's DNS and goes direct if they're private. Public names are never resolved locally.
- A city can be targeted without a state (both providers); with "Any state" the picker lists every city in
  the country.
- `LoopbackServer.stop` iterates the concurrent socket set directly: Kotlin `toList()` on it threw
  `NoSuchElementException` when a connection closed mid-copy, which crashed the app during a repair.
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
  with defaults; bump `AppSettings.SCHEMA` only when old values must be overridden (and mirror the upgrade in
  `chrome/src/core/models.ts` `normSettings`).

### Chrome extension

- **Chrome caches proxy credentials per proxy host** and keeps sending them preemptively: a new session's
  password/username would never be used, and a wrong password goes unnoticed while the cached one works. Clearing the
  proxy origin's cookies (`browsingData.remove({origins}, {cookies})`) also clears its HTTP auth cache and makes
  Chrome open new connections; that's done on every credential change in direct mode. Side effect: cookies of the
  provider's site (e.g. an iproyal.com dashboard login) go too. Helper mode avoids all of this (constant token).
- declarativeNetRequest rejects a `regexFilter` whose compiled form exceeds 2 KB: keep LAN patterns as several small
  rules. Write them with `String.raw` (in a plain string `'\.'` is just `.`).
- Native messaging replies carry `ok` (success flag); never use `ok` as a field name in a reply payload (the helper's
  status uses `conns` / `connFails`).
- Count relay bytes as they flow (`countWriter`), not when a tunnel closes: Chrome keeps tunnels open for minutes.
- MV3 workers are suspended after ~30 s idle. Direct mode resumes from `chrome.storage.session` on the next alarm /
  auth event; a helper connection dies with the worker, so the worker reconnects. The open native port keeps the
  worker alive while connected through the helper.
- When testing, page loads can come from the HTTP cache (no proxy login at all): cache-bust URLs. In the agent
  sandbox the test proxy can only reach allow-listed sites (example.com works, api.ipify.org doesn't).
- `go run` leaves its child process running when killed: tests build `cmd/testproxy` and run the binary.
- Unpacked installs (`installType: development`) reload themselves when `build.json` (written by `build.mjs`) changes
  on disk; the worker checks on every `health` alarm (30 s). Reload closes open extension pages. Alex's Brave loads
  `C:\Users\Alex\ResProxy\extension`: copy `chrome/dist/extension` there to update it. The settings sidebar shows
  the loaded build.
- The helper page shows the browser's exact native-messaging error ("host not found" = not registered for this
  browser, "forbidden" = extension ID not in `allowed_origins`, "failed to start" = binary missing/blocked).
  A Brave that was already running when the helper got registered kept answering "host not found" (a fresh Brave
  found it): restart the browser after `resproxy-helper install`. The helper logs every start to
  `%LOCALAPPDATA%/ResProxy/helper.log` (`os.UserCacheDir()/ResProxy`), which tells whether the browser launches it.
- An unpacked extension's pages load fresh from disk but its service worker keeps running the old code until the
  extension is reloaded: the sidebar's version (from the manifest the worker loaded) shows which one runs.

## Conventions

- Kotlin, Compose Material3, coroutines/Flow, manual DI. No Hilt, no new heavy dependencies without reason.
- User-facing text is plain, short and specific. Error messages say what to do next.
- Line endings LF (`.gitattributes`), except `*.bat`.
- Chrome: Preact function components, no state library. Core logic stays in `src/core` (no `chrome.*` there) so it
  is unit-testable and mirrors the Kotlin. TypeScript strict; `npx tsc --noEmit` must be clean.

## Keeping this file updated

`.claude/settings.json` registers a `PreToolUse` hook (`.claude/hooks/agents-md-check.sh`) on `git commit`.
When staged changes touch `android/`, `chrome/` sources, `native/`, `shared/` or `scripts/` but not `AGENTS.md`, the commit is blocked with a
reminder to review this file. If nothing here needs to change, the commit can go through with
`AGENTS_MD_OK=1 git commit …`.
