# Shared formats

Everything the platforms exchange or must compute identically. The Android app (Kotlin), the Chrome extension
(TypeScript) and the iOS app (Swift, `ios/ResProxyCore`) each implement these, and each runs its tests against the vectors in
`test-vectors/`. A change here needs all implementations (and the vectors) updated in the same commit.

| File | Checked by |
|---|---|
| `test-vectors/backup-payload.json` | the decrypted payload both backups below must equal |
| `test-vectors/backup-android.txt` | made by Android (`WRITE_VECTORS=1 ./gradlew testDebugUnitTest --tests '*writeAndroidVector'`); Chrome and iOS open it |
| `test-vectors/backup-chrome.txt` | made by Chrome (`WRITE_VECTORS=1 npm test`); Android and iOS open it |
| `test-vectors/backup-ios.txt` | made by iOS (`cd ios/ResProxyCore && WRITE_VECTORS=1 swift test`); Android and Chrome open it |
| `test-vectors/presets.json` | provider credential building (input profile → expected endpoint) |

Passphrase of both backup vectors: `correct horse battery staple`.

## Backup (`resproxy-backup-v1:`)

Text: `resproxy-backup-v1:` + base64(UTF-8 JSON envelope). Readers ignore whitespace inside the base64 (messengers wrap
long lines).

```json
{ "kdf": "pbkdf2-sha256", "iter": 210000, "salt": "<b64 16 bytes>", "iv": "<b64 12 bytes>", "data": "<b64 ciphertext+tag>" }
```

- Key: PBKDF2-HMAC-SHA256(passphrase as UTF-8, salt, iter) → 256-bit AES key. Readers use `iter` from the envelope.
- Cipher: AES-256-GCM, 128-bit tag appended to the ciphertext (Java / WebCrypto layout), AAD = the ASCII prefix
  `resproxy-backup-v1:`.
- Plaintext: the payload JSON below. A wrong passphrase and a damaged backup are indistinguishable (tag check fails).
- Base64 is standard with padding.

### Payload

```json
{
  "version": 1,
  "createdAt": 1762300000000,
  "settings": { …AppSettings… },
  "accounts": [ { "provider": "IPROYAL", "username": "", "password": "", "apiToken": "", "apiLogin": "", "apiPassword": "" } ],
  "chain": null,
  "apiKeys": { "abuseIpDb": "", "ipqs": "", "proxycheck": "", "ipapiIs": "", "scamalyticsUser": "", "scamalyticsKey": "" },
  "vpnProfiles": [ { "id": "a1b2c3d4", "name": "Proton NL", "config": "<wg-quick .conf text>" } ],
  "mullvadNumber": "1234567890123456",
  "mullvadDevice": null
}
```

Rules every implementation follows:

- **Unknown fields are kept or ignored, never an error**, at every level (kotlinx `ignoreUnknownKeys`; the extension
  keeps them so they survive a round trip). New fields always get a default.
- Writers include every field (kotlinx `encodeDefaults = true`), enums as their names, sets as arrays, maps with enum
  keys as JSON objects keyed by the enum name.
- `settings.wasConnected` is `false` and `settings.sessionId` is `""` in exports: the importing side starts disconnected.
- `chain` is the legacy single VPN config (old Android backups). On import, when `vpnProfiles` is empty and
  `chain.config` is set, it becomes a profile and, if `settings.vpnExit` is empty, the selected VPN.
- The Mullvad device key is per install by default: only `mullvadNumber` travels and each install registers its own
  WireGuard key as a Mullvad device when it first connects. An import keeps the local device if the number is unchanged.
- `mullvadDevice` (`{id, name, privateKey, publicKey, ipv4, ipv6}` or `null`) is set only when the user opts in at
  export ("Share Mullvad device"). The importing install then uses that device; if it had a different one of its own
  on the same account, it removes that one from the account (frees a slot). Installs sharing a device must never be
  connected at the same time.
- Import replaces accounts, settings, API keys and VPN profiles.

### AppSettings (schema 3)

| Field | Type / default | Notes |
|---|---|---|
| `schema` | int, 3 | below 2: kill switch, auto-connect, auto-rotate on, threshold SUSPICIOUS, 10 rotations. Below 3: lifetime 1440 → 10080 |
| `provider` | `IPROYAL` \| `GEONODE` \| `GENERIC` | `GENERIC` is "Custom" in the UI |
| `location` | `{country, countryName, state, stateName, city, cityName}` | codes are provider targeting tokens, names for display |
| `sessionId` | string | current sticky session |
| `connection.protocol` | `SOCKS5` \| `HTTP` | Chrome without the helper uses the provider's HTTP gateway for IPRoyal/Geonode |
| `connection.sticky` | bool, true | |
| `connection.lifetimeMinutes` | int, 10080 | Geonode caps at 1440 |
| `connection.gateways` | `{PROVIDER: {host, port}}` | empty host / port 0 = preset default |
| `connection.tunnelMode`, `splitMode`, `apps` | Android only | per-app split; Chrome keeps them for round trips |
| `connection.siteSplitMode`, `sites` | Chrome only | `ALL` \| `ONLY_SELECTED` \| `EXCEPT_SELECTED`, domains (subdomains included); Android keeps them |
| `killSwitch` | bool, true | failures block traffic instead of disconnecting |
| `autoConnect` | bool, true | reconnect on boot / browser start if `wasConnected` |
| `wasConnected` | bool | |
| `recheckOnConnect` | bool, true | gate traffic on the reputation check |
| `autoRotate` | bool, true | |
| `rotateThreshold` | `SUSPICIOUS` \| `BAD` | accept an IP whose verdict severity is below this (SUSPICIOUS = only clean) |
| `maxRotations` | int, 10 | 1–50 |
| `healthIntervalSec` | int, 30 | 10–600 |
| `savedLocations` | `{PROVIDER: Location}` | restored when switching provider |
| `chainEnabled` | bool | reach the proxy through the VPN in `vpnExit` |
| `vpnExit` | string | `wg:<profile id>` or `mullvad:<country \| country-city \| relay hostname>` |
| `bypassLan` | bool, true | |
| `sessions` | `{"PROVIDER\|country\|state\|city": {id, createdAt, lifetimeMinutes}}` | sticky session per place; expired one minute early |

## Provider presets

Given a profile (account + settings + session), each preset builds the upstream endpoint. Targeting values are
normalized to lowercase `[a-z0-9]` ("New York" → `newyork`); empty means "any".

- **IPRoyal** (`geo.iproyal.com`, SOCKS5 32325 / HTTP 12321): parameters go on the password:
  `pass_country-us_state-newyork_city-newyork_session-<id>_lifetime-7d`. Existing `_country-…` etc. suffixes are
  stripped first. Lifetime: `Nd` if whole days, `Nh` if whole hours, else `Nm`; 1 min – 7 days.
- **Geonode** (`proxy.geonode.io`; HTTP 9000 rotating / 10000 sticky, SOCKS5 11000 / 12000): parameters go on the
  username: `user-type-residential-country-fr-city-paris-session-<id>-lifetime-<min>`; lifetime capped at 1440.
- **Custom**: credentials verbatim (password not trimmed); SOCKS5 1080 / HTTP 8080 defaults.
- Session IDs: 8 chars `[a-z0-9]`. Session parameters only with sticky on and a session ID set.

## Reputation verdict

Sources: ipapi.is, proxycheck.io, ip-api.com, four DNSBLs (Spamhaus ZEN, SpamCop, Barracuda, UCEPROTECT L1) without
keys; AbuseIPDB, IPQualityScore, Scamalytics with keys. Verdict = worst reason, UNKNOWN if no source answered:

- BAD: Tor exit; listed on a DNSBL; flagged abuser (ipapi.is); recent abuse (IPQS); AbuseIPDB ≥ 50; IPQS ≥ 85;
  Scamalytics high / very high.
- SUSPICIOUS: datacenter/hosting; AbuseIPDB 1–49; IPQS 75–84; Scamalytics medium; proxycheck risk ≥ 67; VPN flag
  from ≥ 2 sources.
- Being detected as a proxy is informational only (residential proxies always are).
- Spamhaus answers 127.0.0.10/11 (PBL) are not a listing; 127.255.255.x means the query was refused (source failed).

## WireGuard configs

wg-quick text. `[Interface]`: `PrivateKey` (required), `Address` (required, comma list), `DNS` (IP literals only),
`MTU` (clamped 1280–1420, default 1280). `[Peer]`: `PublicKey` and `Endpoint` required, `PresharedKey`,
`AllowedIPs` (default `0.0.0.0/0, ::/0`), `PersistentKeepalive` (default 25). `#` starts a comment.
Mullvad configs are generated from the device key and a relay: DNS `10.64.0.1`, endpoint `<relay ipv4>:51820`.
