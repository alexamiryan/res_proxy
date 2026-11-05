// WireGuard config parsing and the Mullvad API — ports of android/.../net/{WireGuard,Mullvad}.kt and
// chrome/src/core/vpn.ts.

import CryptoKit
import Foundation

// MARK: - HTTP abstraction (the app uses URLSession, the tunnel the Go bridge)

public struct HTTPRequest: Sendable {
    public var url: String
    public var method = "GET"
    public var headers: [String: String] = [:]
    public var body: String?
    public init(url: String, method: String = "GET", headers: [String: String] = [:], body: String? = nil) {
        self.url = url; self.method = method; self.headers = headers; self.body = body
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    public var body: String
    public init(status: Int, body: String) { self.status = status; self.body = body }
    public var ok: Bool { (200..<300).contains(status) }
}

public typealias Fetcher = @Sendable (HTTPRequest) async throws -> HTTPResponse

public struct UserError: LocalizedError, Equatable {
    public var message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

// MARK: - WireGuard (.conf)

public struct WgPeer: Equatable, Sendable {
    public var publicKey: String
    public var presharedKey: String?
    public var endpointHost: String
    public var endpointPort: Int
    public var allowedIps: [String]
    public var keepalive: Int
}

public struct WireGuardConfig: Equatable, Sendable {
    public var privateKey: String
    public var addresses: [String]
    public var dns: [String]
    public var mtu: Int
    public var peers: [WgPeer]

    public var endpoint: String { peers.first.map { "\($0.endpointHost):\($0.endpointPort)" } ?? "" }
    /// Target for the in-tunnel latency ping: the VPN's DNS (IPv4 preferred).
    public var pingTarget: String? { dns.first { !$0.contains(":") } ?? dns.first }

    static func key(_ v: String) throws -> String {
        let t = v.trimmingCharacters(in: .whitespaces)
        guard let d = Data(base64Encoded: t), d.count == 32 else { throw UserError("Invalid WireGuard key") }
        return t
    }

    /// Parses a wg-quick `.conf` (the format VPN providers such as Proton export). Throws with a readable message.
    public static func parse(_ text: String) throws -> WireGuardConfig {
        var section = ""
        var privateKey: String?
        var addresses: [String] = [], dns: [String] = []
        var mtu = 1280
        var peers: [WgPeer] = []
        var peer: [String: String]?
        func list(_ v: String) -> [String] { v.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
        func flush() throws {
            guard let p = peer else { return }
            peer = nil
            guard let pub = p["publickey"], !pub.isEmpty else { throw UserError("[Peer] has no PublicKey") }
            guard let ep = p["endpoint"], !ep.isEmpty else { throw UserError("[Peer] has no Endpoint") }
            guard let i = ep.lastIndex(of: ":"), i > ep.startIndex else { throw UserError("Bad Endpoint: \(ep)") }
            guard let port = Int(ep[ep.index(after: i)...]) else { throw UserError("Bad Endpoint port: \(ep)") }
            var host = String(ep[..<i])
            if host.hasPrefix("[") { host.removeFirst() }
            if host.hasSuffix("]") { host.removeLast() }
            peers.append(WgPeer(
                publicKey: try key(pub),
                presharedKey: try p["presharedkey"].flatMap { $0.isEmpty ? nil : try key($0) },
                endpointHost: host, endpointPort: port,
                allowedIps: list(p["allowedips"] ?? "0.0.0.0/0, ::/0"),
                keepalive: Int(p["persistentkeepalive"] ?? "").flatMap { $0 == 0 ? nil : $0 } ?? 25))
        }
        for raw in text.components(separatedBy: "\n") {
            let line = String(raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            if line.hasPrefix("[") {
                try flush()
                section = line.lowercased()
                if section == "[peer]" { peer = [:] }
                continue
            }
            let parts = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let k = parts[0].trimmingCharacters(in: .whitespaces).lowercased()
            let v = parts.count > 1 ? parts[1].trimmingCharacters(in: .whitespaces) : ""
            if section == "[interface]" {
                switch k {
                case "privatekey": privateKey = try key(v)
                case "address": addresses += list(v)
                case "dns": dns += list(v).filter { isIPv4($0) || $0.contains(":") }
                case "mtu": mtu = Int(v).flatMap { $0 == 0 ? nil : $0 } ?? mtu
                default: break
                }
            } else if section == "[peer]", peer != nil {
                peer![k] = v
            }
        }
        try flush()
        guard let pk = privateKey else { throw UserError("[Interface] has no PrivateKey") }
        if addresses.isEmpty { throw UserError("[Interface] has no Address") }
        if peers.isEmpty { throw UserError("Config has no [Peer]") }
        return WireGuardConfig(privateKey: pk, addresses: addresses, dns: dns, mtu: min(1420, max(1280, mtu)), peers: peers)
    }
}

public func isIPv4(_ s: String) -> Bool {
    let o = s.split(separator: ".", omittingEmptySubsequences: false)
    return o.count == 4 && o.allSatisfy { !$0.isEmpty && $0.count <= 3 && $0.allSatisfy(\.isNumber) }
}

// MARK: - Mullvad

public struct MullvadRelay: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String { hostname }
    public var hostname: String
    /// `se-got`
    public var location: String
    public var country: String
    public var city: String
    public var ipv4: String
    public var publicKey: String
    public var owned: Bool
    public var weight: Int
    public var countryCode: String { String(location.split(separator: "-").first ?? "") }
}

/// A device (WireGuard key) registered on the account. Every install registers its own.
public struct MullvadDeviceInfo: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var created: String
}

public enum Mullvad {
    public static let api = "https://api.mullvad.net"
    public static let dns = "10.64.0.1"

    public static func normalize(_ n: String) -> String { n.filter(\.isNumber) }

    public static func parseDevices(_ body: String) -> [MullvadDeviceInfo] {
        guard let d = body.data(using: .utf8), let list = (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let id = o["id"] as? String else { return nil }
            return MullvadDeviceInfo(id: id, name: o["name"] as? String ?? "", created: o["created"] as? String ?? "")
        }.sorted { $0.created < $1.created }
    }

    public static func parseRelays(_ body: String) throws -> [MullvadRelay] {
        guard let d = body.data(using: .utf8), let root = (try JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return [] }
        let locations = root["locations"] as? [String: [String: Any]] ?? [:]
        let relays = (root["wireguard"] as? [String: Any])?["relays"] as? [[String: Any]] ?? []
        var out: [MullvadRelay] = []
        for o in relays {
            if (o["active"] as? Bool) == false { continue }
            guard let loc = o["location"] as? String, let host = o["hostname"] as? String,
                  let ip = o["ipv4_addr_in"] as? String, let pk = o["public_key"] as? String else { continue }
            let place = locations[loc]
            out.append(MullvadRelay(
                hostname: host, location: loc,
                country: place?["country"] as? String ?? String(loc.split(separator: "-").first ?? "").uppercased(),
                city: place?["city"] as? String ?? loc,
                ipv4: ip, publicKey: pk, owned: (o["owned"] as? Bool) == true,
                weight: (o["weight"] as? NSNumber)?.intValue ?? 100))
        }
        return out
    }

    /// `code` is a hostname, a `country-city` location or a country code; picks by weight.
    public static func pickRelay(_ code: String, _ relays: [MullvadRelay], rnd: () -> Double = { Double.random(in: 0..<1) }) -> MullvadRelay? {
        if let exact = relays.first(where: { $0.hostname == code }) { return exact }
        var matches = relays.filter { $0.location == code }
        if matches.isEmpty { matches = relays.filter { $0.countryCode == code } }
        if matches.isEmpty { return nil }
        let total = matches.reduce(0) { $0 + max(1, $1.weight) }
        var r = Int(floor(rnd() * Double(total)))
        for m in matches {
            r -= max(1, m.weight)
            if r < 0 { return m }
        }
        return matches.last
    }

    /// Human label for an exit code, e.g. "Sweden", "Sweden, Gothenburg", "Gothenburg · se-got-wg-001".
    public static func relayLabel(_ code: String, _ relays: [MullvadRelay]) -> String {
        if let h = relays.first(where: { $0.hostname == code }) { return "\(h.city) · \(h.hostname)" }
        if let l = relays.first(where: { $0.location == code }) { return "\(l.country), \(l.city)" }
        if let c = relays.first(where: { $0.countryCode == code }) { return c.country }
        return code
    }

    public static func wgQuick(_ device: MullvadDevice, _ relay: MullvadRelay) -> String {
        [
            "[Interface]",
            "PrivateKey = \(device.privateKey)",
            "Address = \([device.ipv4, device.ipv6].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: ", "))",
            "DNS = \(dns)",
            "[Peer]",
            "PublicKey = \(relay.publicKey)",
            "AllowedIPs = 0.0.0.0/0, ::/0",
            "Endpoint = \(relay.ipv4):51820",
            "",
        ].joined(separator: "\n")
    }

    /// Mullvad API error body → user-facing message.
    public static func errorMessage(_ status: Int, _ body: String) -> String {
        let code = body.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }?["code"] as? String
        switch code {
        case "INVALID_ACCOUNT", "INVALID_ACCOUNT_NUMBER": return "Mullvad doesn't recognise this account number"
        case "MAX_DEVICES_REACHED": return "This Mullvad account already has 5 devices. Remove one in Settings → VPN or at mullvad.net, then try again."
        case "ACCOUNT_EXPIRED": return "This Mullvad account has no time left"
        default: return "Mullvad API error \(status)\(code.map { " (\($0))" } ?? "")"
        }
    }

    /// New X25519 key pair as base64 (WireGuard format).
    public static func newKeyPair() -> (privateKey: String, publicKey: String) {
        let k = Curve25519.KeyAgreement.PrivateKey()
        return (k.rawRepresentation.base64EncodedString(), k.publicKey.rawRepresentation.base64EncodedString())
    }

    public static func publicKey(ofPrivate b64: String) -> String? {
        guard let d = Data(base64Encoded: b64), let k = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: d) else { return nil }
        return k.publicKey.rawRepresentation.base64EncodedString()
    }
}

public struct MullvadClient: Sendable {
    let fetch: Fetcher
    public init(fetch: @escaping Fetcher) { self.fetch = fetch }

    func call(_ req: HTTPRequest, allow404: Bool = false) async throws -> String {
        let r = try await fetch(req)
        if r.ok { return r.body }
        if allow404 && r.status == 404 { return "" }
        throw UserError(Mullvad.errorMessage(r.status, r.body))
    }

    public func relays() async throws -> [MullvadRelay] {
        let list = try Mullvad.parseRelays(try await call(HTTPRequest(url: "\(Mullvad.api)/app/v1/relays")))
        if list.isEmpty { throw UserError("Mullvad returned no WireGuard servers") }
        return list
    }

    func token(_ number: String) async throws -> String {
        let body = try await call(HTTPRequest(url: "\(Mullvad.api)/auth/v1/token", method: "POST",
                                              headers: ["Content-Type": "application/json"],
                                              body: "{\"account_number\":\"\(Mullvad.normalize(number))\"}"))
        guard let t = (try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any])?["access_token"] as? String, !t.isEmpty else {
            throw UserError("Mullvad login failed")
        }
        return t
    }

    /// Logs in, returns the account with its expiry and this install's device (registered if missing).
    public func login(_ number: String, existing: MullvadDevice?) async throws -> MullvadAccount {
        let n = Mullvad.normalize(number)
        let auth = ["Authorization": "Bearer \(try await token(n))"]
        var expiry = ""
        if let b = try? await call(HTTPRequest(url: "\(Mullvad.api)/accounts/v1/accounts/me", headers: auth)),
           let e = (try? JSONSerialization.jsonObject(with: Data(b.utf8)) as? [String: Any])?["expiry"] as? String {
            expiry = e
        }
        var device = existing
        if let d = device, !d.id.isEmpty {
            let found = try await call(HTTPRequest(url: "\(Mullvad.api)/accounts/v1/devices/\(d.id)", headers: auth), allow404: true)
            if found.isEmpty { device = nil }
        } else {
            device = nil
        }
        if device == nil {
            let kp = Mullvad.newKeyPair()
            var h = auth
            h["Content-Type"] = "application/json"
            let body = try await call(HTTPRequest(url: "\(Mullvad.api)/accounts/v1/devices", method: "POST", headers: h,
                                                  body: "{\"pubkey\":\"\(kp.publicKey)\",\"hijack_dns\":false}"))
            guard let o = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any], let id = o["id"] as? String else {
                throw UserError("Mullvad didn't return a device")
            }
            device = MullvadDevice(id: id, name: o["name"] as? String ?? "", privateKey: kp.privateKey, publicKey: kp.publicKey,
                                   ipv4: o["ipv4_address"] as? String ?? "", ipv6: o["ipv6_address"] as? String ?? "")
        }
        return MullvadAccount(number: n, expiry: expiry, device: device)
    }

    /// Whether `deviceId` is still on the account (false once removed on mullvad.net or by another install).
    public func isDeviceRegistered(_ number: String, _ deviceId: String) async throws -> Bool {
        let t = try await token(number)
        return !(try await call(HTTPRequest(url: "\(Mullvad.api)/accounts/v1/devices/\(deviceId)", headers: ["Authorization": "Bearer \(t)"]), allow404: true)).isEmpty
    }

    public func devices(_ number: String) async throws -> [MullvadDeviceInfo] {
        let t = try await token(number)
        return Mullvad.parseDevices(try await call(HTTPRequest(url: "\(Mullvad.api)/accounts/v1/devices", headers: ["Authorization": "Bearer \(t)"])))
    }

    public func removeDevice(_ number: String, _ deviceId: String) async throws {
        let t = try await token(number)
        _ = try await call(HTTPRequest(url: "\(Mullvad.api)/accounts/v1/devices/\(deviceId)", method: "DELETE", headers: ["Authorization": "Bearer \(t)"]), allow404: true)
    }
}
