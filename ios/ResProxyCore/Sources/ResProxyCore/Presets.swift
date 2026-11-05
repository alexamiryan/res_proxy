// Provider credential presets — port of android/.../provider/Presets.kt and chrome/src/core/presets.ts.
// Shared vectors: shared/test-vectors/presets.json.

import Foundation

/// Everything the upstream needs, derived from Account + AppSettings at connect time.
public struct Profile: Codable, Equatable, Sendable {
    public var provider: Provider
    public var `protocol`: ProxyProtocol
    public var host: String
    public var port: Int
    public var username: String
    public var password: String
    public var country: String
    public var state: String
    public var city: String
    public var sticky: Bool
    public var lifetimeMinutes: Int
    public var sessionId: String

    public init(account: Account, settings s: AppSettings, sessionId: String? = nil) {
        let gw = s.connection.gateway(account.provider)
        provider = account.provider
        `protocol` = s.connection.protocol
        host = gw?.host ?? ""
        port = gw?.port ?? 0
        username = account.username
        password = account.password
        country = s.location.country
        state = s.location.state
        city = s.location.city
        sticky = s.connection.sticky
        lifetimeMinutes = s.connection.lifetimeMinutes
        self.sessionId = sessionId ?? s.sessionId
    }
}

/// Concrete upstream credentials after the provider's targeting/session syntax has been applied.
public struct Endpoint: Codable, Equatable, Sendable {
    public var host: String
    public var port: Int
    public var `protocol`: ProxyProtocol
    public var username: String
    public var password: String
    public init(host: String, port: Int, protocol: ProxyProtocol, username: String, password: String) {
        self.host = host; self.port = port; self.protocol = `protocol`; self.username = username; self.password = password
    }
}

public enum Presets {
    public static func defaultHost(_ p: Provider) -> String {
        switch p {
        case .IPROYAL: return "geo.iproyal.com"
        case .GEONODE: return "proxy.geonode.io"
        case .GENERIC: return ""
        }
    }

    public static func defaultPort(_ p: Provider, _ proto: ProxyProtocol, sticky: Bool) -> Int {
        switch p {
        case .IPROYAL: return proto == .SOCKS5 ? 32325 : 12321
        case .GEONODE: return proto == .HTTP ? (sticky ? 10000 : 9000) : (sticky ? 12000 : 11000)
        case .GENERIC: return proto == .SOCKS5 ? 1080 : 8080
        }
    }

    public static func formatHint(_ p: Provider) -> String {
        switch p {
        case .IPROYAL: return "password_country-xx_state-…_city-…_session-<id>_lifetime-30m"
        case .GEONODE: return "username-type-residential-country-xx-state-…-city-…-session-<id>-lifetime-<min>"
        case .GENERIC: return "Credentials are sent exactly as entered"
        }
    }

    /// Normalizes a targeting value ("New York" → "newyork"); nil when unset.
    public static func token(_ value: String) -> String? {
        let t = String(value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().unicodeScalars.filter {
            ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9")
        }.map(Character.init))
        return t.isEmpty ? nil : t
    }

    public static func ipRoyalLifetime(_ minutes: Int) -> String {
        let m = clamp(minutes, 1, MAX_LIFETIME_MINUTES)
        if m % 1440 == 0 { return "\(m / 1440)d" }
        if m % 60 == 0 { return "\(m / 60)h" }
        return "\(m)m"
    }

    static let ipRoyalParams = try! NSRegularExpression(pattern: "_(country|state|region|city|session|lifetime|streaming|skipispstatic)-.*$")
    static let geonodeParams = try! NSRegularExpression(pattern: "-(type|country|state|city|asn|session|lifetime)-.*$")

    static func strip(_ s: String, _ re: NSRegularExpression) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
    }

    public static func build(_ p: Profile) -> Endpoint {
        let host = p.host.trimmingCharacters(in: .whitespaces).isEmpty ? defaultHost(p.provider) : p.host.trimmingCharacters(in: .whitespaces)
        let port = p.port > 0 ? p.port : defaultPort(p.provider, p.protocol, sticky: p.sticky)
        let c = token(p.country), st = token(p.state), ci = token(p.city)
        switch p.provider {
        case .IPROYAL:
            // Targeting and session go on the *password*.
            var pass = strip(p.password.trimmingCharacters(in: .whitespacesAndNewlines), ipRoyalParams)
            if let c { pass += "_country-\(c)" }
            if let st { pass += "_state-\(st)" }
            if let ci { pass += "_city-\(ci)" }
            if p.sticky && !p.sessionId.isEmpty { pass += "_session-\(p.sessionId)_lifetime-\(ipRoyalLifetime(p.lifetimeMinutes))" }
            return Endpoint(host: host, port: port, protocol: p.protocol, username: p.username.trimmingCharacters(in: .whitespacesAndNewlines), password: pass)
        case .GEONODE:
            // Targeting and session go on the *username*.
            var user = strip(p.username.trimmingCharacters(in: .whitespacesAndNewlines), geonodeParams) + "-type-residential"
            if let c { user += "-country-\(c)" }
            if let st { user += "-state-\(st)" }
            if let ci { user += "-city-\(ci)" }
            if p.sticky && !p.sessionId.isEmpty { user += "-session-\(p.sessionId)-lifetime-\(clamp(p.lifetimeMinutes, 1, 1440))" }
            return Endpoint(host: host, port: port, protocol: p.protocol, username: user, password: p.password.trimmingCharacters(in: .whitespacesAndNewlines))
        case .GENERIC:
            return Endpoint(host: host, port: port, protocol: p.protocol, username: p.username.trimmingCharacters(in: .whitespacesAndNewlines), password: p.password)
        }
    }

    /// 8 lowercase alphanumerics: accepted by both IPRoyal and Geonode session syntax.
    public static func newSessionId() -> String { randomId(8) }

    /// The user-facing base username: targeting params pasted along with it are stripped.
    public static func baseUsername(_ provider: Provider, _ username: String) -> String {
        let t = username.trimmingCharacters(in: .whitespacesAndNewlines)
        return provider == .GEONODE ? strip(t, geonodeParams) : t
    }

    public struct ProxyLine: Equatable {
        public var host: String?
        public var port: Int?
        public var username: String
        public var password: String
        public var `protocol`: ProxyProtocol?
    }

    /// Users paste whole proxy lines (`host:port:user:pass`, `user:pass@host:port`, `socks5://user:pass@host:port`).
    public static func parseProxyLine(_ text: String) -> ProxyLine? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        func groups(_ pattern: String, _ opts: NSRegularExpression.Options = []) -> [String]? {
            let re = try! NSRegularExpression(pattern: pattern, options: opts)
            guard let m = re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) else { return nil }
            return (0..<m.numberOfRanges).map { i in Range(m.range(at: i), in: t).map { String(t[$0]) } ?? "" }
        }
        if let g = groups("^(socks5h?|socks|https?)://([^:@\\s]*):([^@\\s]*)@([^:\\s/]+):(\\d+)/?$", .caseInsensitive) {
            return ProxyLine(host: g[4], port: Int(g[5]), username: g[2].removingPercentEncoding ?? g[2],
                             password: g[3].removingPercentEncoding ?? g[3], protocol: g[1].lowercased().hasPrefix("socks") ? .SOCKS5 : .HTTP)
        }
        if let g = groups("^([^:@\\s]+):([^@\\s]+)@([^:\\s]+):(\\d+)$") {
            return ProxyLine(host: g[3], port: Int(g[4]), username: g[1], password: g[2], protocol: nil)
        }
        let colon = t.components(separatedBy: ":")
        if colon.count == 4, !colon[1].isEmpty, colon[1].allSatisfy(\.isNumber) {
            return ProxyLine(host: colon[0], port: Int(colon[1]), username: colon[2], password: colon[3], protocol: nil)
        }
        return nil
    }
}
