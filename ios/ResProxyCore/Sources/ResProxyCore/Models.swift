// Data model shared with the Android app and the Chrome extension. Field names, enum values and defaults mirror
// android/app/src/main/java/dev/resproxy/data/Models.kt and chrome/src/core/models.ts, so backups move between
// platforms unchanged. See shared/FORMAT.md before changing anything here.

import Foundation

public enum Provider: String, Codable, CaseIterable, Identifiable, Sendable {
    case IPROYAL, GEONODE, GENERIC
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .IPROYAL: return "IPRoyal"
        case .GEONODE: return "Geonode"
        case .GENERIC: return "Custom"
        }
    }
}

public enum ProxyProtocol: String, Codable, CaseIterable, Sendable { case SOCKS5, HTTP }
public enum TunnelMode: String, Codable, CaseIterable, Sendable { case FULL, PROXY_ONLY }
public enum SplitMode: String, Codable, CaseIterable, Sendable { case ALL, ONLY_SELECTED, EXCEPT_SELECTED }

public enum Verdict: String, Codable, CaseIterable, Sendable {
    case UNKNOWN, CLEAN, SUSPICIOUS, BAD
    public var severity: Int {
        switch self {
        case .UNKNOWN: return -1
        case .CLEAN: return 0
        case .SUSPICIOUS: return 1
        case .BAD: return 2
        }
    }
    public var word: String { rawValue.lowercased() }
}

public let MAX_LIFETIME_MINUTES = 7 * 1440
public let SETTINGS_SCHEMA = 3

// MARK: - decoding helpers (missing or wrong-typed fields fall back to defaults, unknown fields are ignored)

struct AnyKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ s: String) { stringValue = s }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

extension KeyedDecodingContainer where K == AnyKey {
    func v<T: Decodable>(_ key: String, _ d: T) -> T {
        (try? decodeIfPresent(T.self, forKey: AnyKey(key))) ?? d
    }
    func int(_ key: String, _ d: Int) -> Int {
        if let i = try? decodeIfPresent(Int.self, forKey: AnyKey(key)) { return i }
        if let f = try? decodeIfPresent(Double.self, forKey: AnyKey(key)), f.isFinite { return Int(f) }
        return d
    }
    func int64(_ key: String, _ d: Int64) -> Int64 {
        if let i = try? decodeIfPresent(Int64.self, forKey: AnyKey(key)) { return i }
        if let f = try? decodeIfPresent(Double.self, forKey: AnyKey(key)), f.isFinite { return Int64(f) }
        return d
    }
    func e<T: RawRepresentable>(_ key: String, _ d: T) -> T where T.RawValue == String {
        guard let s = try? decodeIfPresent(String.self, forKey: AnyKey(key)) else { return d }
        return T(rawValue: s) ?? d
    }
    func strList(_ key: String) -> [String] {
        guard let a = try? decodeIfPresent([String?].self, forKey: AnyKey(key)) else { return [] }
        var seen = Set<String>()
        return a.compactMap { $0 }.filter { seen.insert($0).inserted }
    }
    /// Map keyed by provider name; entries with unknown providers or bad values are dropped.
    func providerMap<T: Decodable>(_ key: String, _ type: T.Type) -> [String: T] {
        guard let nested = try? nestedContainer(keyedBy: AnyKey.self, forKey: AnyKey(key)) else { return [:] }
        var out: [String: T] = [:]
        for k in nested.allKeys where Provider(rawValue: k.stringValue) != nil {
            if let val = try? nested.decode(T.self, forKey: k) { out[k.stringValue] = val }
        }
        return out
    }
}

// MARK: - model

public struct Account: Codable, Equatable, Sendable {
    public var provider: Provider
    public var username = ""
    public var password = ""
    public var apiToken = ""
    public var apiLogin = ""
    public var apiPassword = ""

    public init(provider: Provider, username: String = "", password: String = "", apiToken: String = "", apiLogin: String = "", apiPassword: String = "") {
        self.provider = provider; self.username = username; self.password = password
        self.apiToken = apiToken; self.apiLogin = apiLogin; self.apiPassword = apiPassword
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        provider = c.e("provider", .IPROYAL)
        username = c.v("username", ""); password = c.v("password", "")
        apiToken = c.v("apiToken", ""); apiLogin = c.v("apiLogin", ""); apiPassword = c.v("apiPassword", "")
    }
}

public struct Location: Codable, Equatable, Hashable, Sendable {
    public var country = "", countryName = ""
    public var state = "", stateName = ""
    public var city = "", cityName = ""

    public init(country: String = "", countryName: String = "", state: String = "", stateName: String = "", city: String = "", cityName: String = "") {
        self.country = country; self.countryName = countryName; self.state = state
        self.stateName = stateName; self.city = city; self.cityName = cityName
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        country = c.v("country", ""); countryName = c.v("countryName", "")
        state = c.v("state", ""); stateName = c.v("stateName", "")
        city = c.v("city", ""); cityName = c.v("cityName", "")
    }

    public var label: String {
        let parts = [cityName, stateName, countryName].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        return parts.isEmpty ? "Any location" : parts.joined(separator: ", ")
    }
}

public struct Gateway: Codable, Equatable, Sendable {
    public var host = ""
    public var port = 0
    public init(host: String = "", port: Int = 0) { self.host = host; self.port = port }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        host = c.v("host", ""); port = c.int("port", 0)
    }
}

public struct ConnectionSettings: Codable, Equatable, Sendable {
    public var `protocol`: ProxyProtocol = .SOCKS5
    public var sticky = true
    public var lifetimeMinutes = MAX_LIFETIME_MINUTES
    /// Keyed by provider name.
    public var gateways: [String: Gateway] = [:]
    /// Android only (kept for round trips).
    public var tunnelMode: TunnelMode = .FULL
    public var splitMode: SplitMode = .ALL
    public var apps: [String] = []
    /// Chrome only (kept for round trips).
    public var siteSplitMode: SplitMode = .ALL
    public var sites: [String] = []

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        `protocol` = c.e("protocol", .SOCKS5)
        sticky = c.v("sticky", true)
        lifetimeMinutes = c.int("lifetimeMinutes", MAX_LIFETIME_MINUTES)
        gateways = c.providerMap("gateways", Gateway.self)
        tunnelMode = c.e("tunnelMode", .FULL)
        splitMode = c.e("splitMode", .ALL)
        apps = c.strList("apps")
        siteSplitMode = c.e("siteSplitMode", .ALL)
        sites = c.strList("sites")
    }

    public func gateway(_ p: Provider) -> Gateway? { gateways[p.rawValue] }
}

public struct SavedSession: Codable, Equatable, Sendable {
    public var id: String
    public var createdAt: Int64
    public var lifetimeMinutes: Int
    public init(id: String, createdAt: Int64, lifetimeMinutes: Int) { self.id = id; self.createdAt = createdAt; self.lifetimeMinutes = lifetimeMinutes }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let id = try? c.decode(String.self, forKey: AnyKey("id")) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "session without id"))
        }
        self.id = id
        createdAt = c.int64("createdAt", 0)
        lifetimeMinutes = c.int("lifetimeMinutes", 1)
    }

    /// A minute of margin so a session the provider is about to drop is never reused.
    public func expired(_ now: Int64) -> Bool { now >= createdAt + Int64(lifetimeMinutes - 1) * 60_000 }
}

public struct AppSettings: Codable, Equatable, Sendable {
    public var schema = SETTINGS_SCHEMA
    public var provider: Provider = .IPROYAL
    public var location = Location()
    public var sessionId = ""
    public var connection = ConnectionSettings()
    public var killSwitch = true
    public var autoConnect = true
    public var wasConnected = false
    public var recheckOnConnect = true
    public var autoRotate = true
    public var rotateThreshold: Verdict = .SUSPICIOUS
    public var maxRotations = 10
    public var healthIntervalSec = 30
    /// Keyed by provider name.
    public var savedLocations: [String: Location] = [:]
    public var chainEnabled = false
    public var vpnExit = ""
    public var bypassLan = true
    public var sessions: [String: SavedSession] = [:]

    public init() {}

    /// Fills defaults and applies the same schema upgrades as Android's SettingsRepository.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        schema = c.int("schema", 0)
        provider = c.e("provider", .IPROYAL)
        location = c.v("location", Location())
        sessionId = c.v("sessionId", "")
        connection = c.v("connection", ConnectionSettings())
        killSwitch = c.v("killSwitch", true)
        autoConnect = c.v("autoConnect", true)
        wasConnected = c.v("wasConnected", false)
        recheckOnConnect = c.v("recheckOnConnect", true)
        autoRotate = c.v("autoRotate", true)
        rotateThreshold = c.e("rotateThreshold", .SUSPICIOUS)
        maxRotations = c.int("maxRotations", 10)
        healthIntervalSec = c.int("healthIntervalSec", 30)
        savedLocations = c.providerMap("savedLocations", Location.self)
        chainEnabled = c.v("chainEnabled", false)
        vpnExit = c.v("vpnExit", "")
        bypassLan = c.v("bypassLan", true)
        if let nested = try? c.nestedContainer(keyedBy: AnyKey.self, forKey: AnyKey("sessions")) {
            for k in nested.allKeys {
                if let s = try? nested.decode(SavedSession.self, forKey: k) { sessions[k.stringValue] = s }
            }
        }
        if schema < 2 {
            killSwitch = true; autoConnect = true; autoRotate = true; rotateThreshold = .SUSPICIOUS; maxRotations = 10
        }
        if schema < 3 && connection.lifetimeMinutes == 1440 { connection.lifetimeMinutes = MAX_LIFETIME_MINUTES }
        schema = max(schema, SETTINGS_SCHEMA)
    }

    // MARK: sticky sessions per provider + location

    /// Lifetime the provider actually applies (Geonode caps sticky sessions at 24 h).
    public var effectiveLifetime: Int {
        clamp(connection.lifetimeMinutes, 1, provider == .GEONODE ? 1440 : MAX_LIFETIME_MINUTES)
    }

    public var sessionKey: String { [provider.rawValue, location.country, location.state, location.city].joined(separator: "|") }

    /// Sets sessionId and remembers it for the current provider + location.
    public func withSession(_ id: String, now: Int64 = nowMs()) -> AppSettings {
        var s = self
        let key = sessionKey
        let prev = sessions[key]
        let same = (prev != nil && prev!.id == id && !prev!.expired(now)) ? prev : nil
        var kept: [String: SavedSession] = [:]
        for (k, v) in sessions where k != key && !v.expired(now) { kept[k] = v }
        if !id.isEmpty { kept[key] = same ?? SavedSession(id: id, createdAt: now, lifetimeMinutes: effectiveLifetime) }
        s.sessionId = id
        s.sessions = kept
        return s
    }

    /// Picks up the remembered session for the current provider + location, or none (a new one gets made).
    public func restoreSession(now: Int64 = nowMs()) -> AppSettings {
        var s = self
        if let x = sessions[sessionKey], !x.expired(now) { s.sessionId = x.id } else { s.sessionId = "" }
        return s
    }

    public func savedLocation(_ p: Provider) -> Location? { savedLocations[p.rawValue] }

    /// Switches provider, saving the current location and restoring the new provider's.
    public func switchingProvider(to p: Provider) -> AppSettings {
        guard p != provider else { return self }
        var s = self
        s.savedLocations[provider.rawValue] = location
        s.provider = p
        s.location = savedLocations[p.rawValue] ?? Location()
        return s.restoreSession()
    }
}

public struct ApiKeys: Codable, Equatable, Sendable {
    public var abuseIpDb = "", ipqs = "", proxycheck = "", ipapiIs = "", scamalyticsUser = "", scamalyticsKey = ""
    public init() {}
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        abuseIpDb = c.v("abuseIpDb", ""); ipqs = c.v("ipqs", ""); proxycheck = c.v("proxycheck", "")
        ipapiIs = c.v("ipapiIs", ""); scamalyticsUser = c.v("scamalyticsUser", ""); scamalyticsKey = c.v("scamalyticsKey", "")
    }
}

public struct VpnProfile: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var config: String
    public init(id: String = randomId(8), name: String, config: String) { self.id = id; self.name = name; self.config = config }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        let id: String = c.v("id", "")
        self.id = id.isEmpty ? randomId(8) : id
        name = c.v("name", "")
        config = c.v("config", "")
        if config.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "empty VPN config"))
        }
    }
}

public struct MullvadDevice: Codable, Equatable, Sendable {
    public var id = "", name = "", privateKey = "", publicKey = "", ipv4 = "", ipv6 = ""
    public init(id: String, name: String, privateKey: String, publicKey: String, ipv4: String, ipv6: String) {
        self.id = id; self.name = name; self.privateKey = privateKey; self.publicKey = publicKey; self.ipv4 = ipv4; self.ipv6 = ipv6
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        id = c.v("id", ""); name = c.v("name", ""); privateKey = c.v("privateKey", "")
        publicKey = c.v("publicKey", ""); ipv4 = c.v("ipv4", ""); ipv6 = c.v("ipv6", "")
        if id.isEmpty || privateKey.isEmpty {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "incomplete device"))
        }
    }
}

/// signedOut: signed out in this install, or its device was removed from the account (mullvad.net / another
/// install). Then nothing signs in again by itself (that would take a device slot): the user taps Log in.
public struct MullvadAccount: Codable, Equatable, Sendable {
    public var number: String
    public var expiry: String
    public var device: MullvadDevice?
    public var signedOut: Bool
    public init(number: String, expiry: String = "", device: MullvadDevice? = nil, signedOut: Bool = false) {
        self.number = number; self.expiry = expiry; self.device = device; self.signedOut = signedOut
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        number = c.v("number", ""); expiry = c.v("expiry", "")
        device = try? c.decodeIfPresent(MullvadDevice.self, forKey: AnyKey("device"))
        signedOut = c.v("signedOut", false)
    }
}

// MARK: - VPN exit selection

public enum VpnExit: Equatable, Sendable {
    case imported(id: String)
    case mullvad(code: String)

    public static func parse(_ s: String) -> VpnExit? {
        if s.hasPrefix("wg:") { return .imported(id: String(s.dropFirst(3))) }
        if s.hasPrefix("mullvad:") { return .mullvad(code: String(s.dropFirst(8))) }
        return nil
    }

    public var stringValue: String {
        switch self {
        case .imported(let id): return "wg:\(id)"
        case .mullvad(let code): return "mullvad:\(code)"
        }
    }
}

// MARK: - helpers

public func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(hi, max(lo, v)) }

public func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

public func randomId(_ len: Int, alphabet: String = "abcdefghijklmnopqrstuvwxyz0123456789") -> String {
    let chars = Array(alphabet)
    var out = ""
    var rng = SystemRandomNumberGenerator()
    while out.count < len { out.append(chars[Int.random(in: 0..<chars.count, using: &rng)]) }
    return out
}

public extension JSONEncoder {
    static var resproxy: JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }
}
