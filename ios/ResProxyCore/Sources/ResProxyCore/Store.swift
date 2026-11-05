// Storage shared by the app and the packet tunnel: plain settings as JSON in the App Group container, secrets
// (accounts, API keys, VPN profiles, Mullvad) in the Keychain with a shared access group. The iOS counterpart of
// Android's DataStore repos + SecretStore (Keystore AES-GCM) and Chrome's store.ts.

import Foundation
import Security

public struct StoreConfig: Sendable {
    public var appGroup: String
    public var keychainGroup: String?
    public init(appGroup: String, keychainGroup: String?) { self.appGroup = appGroup; self.keychainGroup = keychainGroup }

    /// Values baked into Info.plist by the build (RPAppGroup / RPKeychainGroup).
    public static var fromBundle: StoreConfig {
        let info = Bundle.main.infoDictionary ?? [:]
        let group = info["RPAppGroup"] as? String ?? "group.dev.resproxy"
        let kc = info["RPKeychainGroup"] as? String
        return StoreConfig(appGroup: group, keychainGroup: kc?.isEmpty == false ? kc : nil)
    }
}

public final class SharedStore: @unchecked Sendable {
    public static let shared = SharedStore(.fromBundle)

    let config: StoreConfig
    public let dir: URL
    private let lock = NSLock()

    public init(_ config: StoreConfig) {
        self.config = config
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: config.appGroup)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("ResProxy")
        dir = base
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    // MARK: files (App Group)

    func file(_ name: String) -> URL { dir.appendingPathComponent(name) }

    public func readJSON<T: Decodable>(_ name: String, _ type: T.Type) -> T? {
        guard let d = try? Data(contentsOf: file(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }

    public func writeJSON<T: Encodable>(_ name: String, _ value: T) {
        guard let d = try? JSONEncoder.resproxy.encode(value) else { return }
        try? d.write(to: file(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    // MARK: settings

    public func settings() -> AppSettings {
        lock.lock(); defer { lock.unlock() }
        return readJSON("settings.json", AppSettings.self) ?? AppSettings()
    }

    /// Read-modify-write; tells the tunnel unless `notify` is false.
    @discardableResult
    public func updateSettings(notify: Bool = true, _ f: (inout AppSettings) -> Void) -> AppSettings {
        lock.lock()
        var s = readJSON("settings.json", AppSettings.self) ?? AppSettings()
        let before = s
        f(&s)
        if s != before { writeJSON("settings.json", s) }
        lock.unlock()
        if s != before && notify { Notify.post(Notify.settingsChanged) }
        return s
    }

    // MARK: secrets (Keychain)

    public func accounts() -> [Account] { secret("accounts", [Account].self) ?? [] }
    public func account(_ p: Provider) -> Account? { accounts().first { $0.provider == p } }
    public func saveAccount(_ a: Account) {
        var list = accounts().filter { $0.provider != a.provider }
        list.append(a)
        saveAccounts(list)
    }
    public func saveAccounts(_ list: [Account]) { setSecret("accounts", list); Notify.post(Notify.settingsChanged) }

    public func apiKeys() -> ApiKeys { secret("apiKeys", ApiKeys.self) ?? ApiKeys() }
    public func saveApiKeys(_ k: ApiKeys) { setSecret("apiKeys", k); Notify.post(Notify.apiKeysChanged) }

    public func vpnProfiles() -> [VpnProfile] { secret("vpnProfiles", [VpnProfile].self) ?? [] }
    public func saveVpnProfiles(_ p: [VpnProfile]) { setSecret("vpnProfiles", p); Notify.post(Notify.settingsChanged) }

    public func mullvad() -> MullvadAccount? { secret("mullvad", MullvadAccount.self) }
    public func saveMullvad(_ m: MullvadAccount?) {
        if let m { setSecret("mullvad", m) } else { deleteSecret("mullvad") }
        Notify.post(Notify.settingsChanged)
    }

    func query(_ key: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.resproxy",
            kSecAttrAccount as String: key,
        ]
        if let g = config.keychainGroup { q[kSecAttrAccessGroup as String] = g }
        #if os(macOS)
        q[kSecUseDataProtectionKeychain as String] = true
        #endif
        return q
    }

    public func secret<T: Decodable>(_ key: String, _ type: T.Type) -> T? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }

    public func setSecret<T: Encodable>(_ key: String, _ value: T) {
        guard let d = try? JSONEncoder.resproxy.encode(value) else { return }
        let q = query(key)
        // After first unlock: the tunnel must be able to start (on demand) while the phone is locked.
        let attrs: [String: Any] = [kSecValueData as String: d, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        if SecItemUpdate(q as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            var add = q
            attrs.forEach { add[$0.key] = $0.value }
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    public func deleteSecret(_ key: String) { SecItemDelete(query(key) as CFDictionary) }

    // MARK: caches and stats

    public func cachedCatalog(_ p: Provider) -> LocationCatalog? { readJSON("catalog-\(p.rawValue).json", LocationCatalog.self) }
    public func saveCatalog(_ p: Provider, _ c: LocationCatalog) { writeJSON("catalog-\(p.rawValue).json", c) }

    public func cachedRelays() -> [MullvadRelay] { readJSON("mullvad-relays.json", [MullvadRelay].self) ?? [] }
    public func saveRelays(_ r: [MullvadRelay]) { writeJSON("mullvad-relays.json", r) }

    public struct Usage: Codable, Sendable { public var down: Int64 = 0; public var up: Int64 = 0; public init() {} }

    public func usage() -> [String: Usage] { readJSON("usage.json", [String: Usage].self) ?? [:] }
    public func addUsage(_ key: String, down: Int64, up: Int64) {
        guard down > 0 || up > 0 else { return }
        lock.lock(); defer { lock.unlock() }
        var u = usage()
        var x = u[key] ?? Usage()
        x.down += down; x.up += up
        u[key] = x
        writeJSON("usage.json", u)
    }
    public func resetUsage() { writeJSON("usage.json", [String: Usage]()) }
    public func resetUsage(_ key: String) {
        lock.lock(); defer { lock.unlock() }
        var u = usage()
        u[key] = nil
        writeJSON("usage.json", u)
    }

    /// Last status the tunnel wrote (the app reads it when the tunnel isn't answering messages).
    public func lastStatus() -> TunnelStatus? { readJSON("status.json", TunnelStatus.self) }
    public func saveStatus(_ s: TunnelStatus) { writeJSON("status.json", s) }

    // MARK: diagnostics log

    /// The tunnel's Diagnostics log (Settings → Diagnostics), like the extension's. Never put secrets in it.
    public func appendLog(_ line: String) {
        let df = ISO8601DateFormatter()
        df.formatOptions = [.withFullDate, .withFullTime, .withFractionalSeconds]
        let text = "\(df.string(from: Date())) \(line)\n"
        lock.lock(); defer { lock.unlock() }
        let url = file("diag.log")
        if let h = try? FileHandle(forWritingTo: url) {
            defer { try? h.close() }
            let size = (try? h.seekToEnd()) ?? 0
            if size > 256_000, let d = try? Data(contentsOf: url) {
                // Keep the newer half.
                let tail = d.suffix(128_000)
                let cut = tail.firstIndex(of: 10).map { tail.index(after: $0) } ?? tail.startIndex
                try? h.truncate(atOffset: 0)
                try? h.write(contentsOf: tail[cut...])
            }
            try? h.write(contentsOf: Data(text.utf8))
        } else {
            try? Data(text.utf8).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    public func readLog() -> String { (try? String(contentsOf: file("diag.log"), encoding: .utf8)) ?? "" }
    public func clearLog() { try? FileManager.default.removeItem(at: file("diag.log")) }

    // MARK: backup

    /// Everything that goes into a backup. `wasConnected` and `sessionId` are cleared (the importer starts disconnected).
    public func exportPayload(shareMullvadDevice: Bool) -> BackupPayload {
        var p = BackupPayload()
        p.createdAt = nowMs()
        var s = settings()
        s.wasConnected = false
        s.sessionId = ""
        p.settings = s
        p.accounts = accounts()
        p.apiKeys = apiKeys()
        p.vpnProfiles = vpnProfiles()
        let m = mullvad()
        p.mullvadNumber = m?.number ?? ""
        p.mullvadDevice = shareMullvadDevice ? m?.device : nil
        return p
    }
}
