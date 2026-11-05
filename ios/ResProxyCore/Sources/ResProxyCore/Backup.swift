// Passphrase-encrypted backup, byte-compatible with Android (data/Backup.kt) and Chrome (core/backup.ts):
// `resproxy-backup-v1:` + base64(JSON envelope {kdf, iter, salt, iv, data}); AES-256-GCM (128-bit tag appended,
// AAD = the prefix) with a PBKDF2-HMAC-SHA256 key. Spec and test vectors: shared/FORMAT.md, shared/test-vectors/.

import CommonCrypto
import CryptoKit
import Foundation

public struct LegacyChain: Codable, Equatable, Sendable {
    public var name: String
    public var config: String
}

public struct BackupPayload: Codable, Equatable, Sendable {
    public var version = 1
    public var createdAt: Int64 = 0
    public var settings = AppSettings()
    public var accounts: [Account] = []
    /// Legacy single VPN config, only in old Android backups.
    public var chain: LegacyChain?
    public var apiKeys = ApiKeys()
    public var vpnProfiles: [VpnProfile] = []
    public var mullvadNumber = ""
    /// Only when the user opts in at export (see shared/FORMAT.md).
    public var mullvadDevice: MullvadDevice?

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        version = c.int("version", 1)
        createdAt = c.int64("createdAt", 0)
        settings = c.v("settings", AppSettings())
        if var arr = try? c.nestedUnkeyedContainer(forKey: AnyKey("accounts")) {
            while !arr.isAtEnd {
                if let a = try? arr.decode(Account.self) { accounts.append(a) } else { _ = try? arr.decode(Skip.self) }
            }
        }
        if let ch = try? c.nestedContainer(keyedBy: AnyKey.self, forKey: AnyKey("chain")),
           let cfg = try? ch.decode(String.self, forKey: AnyKey("config")) {
            chain = LegacyChain(name: ch.v("name", ""), config: cfg)
        }
        apiKeys = c.v("apiKeys", ApiKeys())
        if var arr = try? c.nestedUnkeyedContainer(forKey: AnyKey("vpnProfiles")) {
            while !arr.isAtEnd {
                if let p = try? arr.decode(VpnProfile.self) { vpnProfiles.append(p) } else { _ = try? arr.decode(Skip.self) }
            }
        }
        mullvadNumber = c.v("mullvadNumber", "")
        mullvadDevice = try? c.decodeIfPresent(MullvadDevice.self, forKey: AnyKey("mullvadDevice"))
    }

    // Encode every field, nil as JSON null (writers include every field).
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: AnyKey.self)
        try c.encode(version, forKey: AnyKey("version"))
        try c.encode(createdAt, forKey: AnyKey("createdAt"))
        try c.encode(settings, forKey: AnyKey("settings"))
        try c.encode(accounts, forKey: AnyKey("accounts"))
        try c.encode(chain, forKey: AnyKey("chain"))
        try c.encode(apiKeys, forKey: AnyKey("apiKeys"))
        try c.encode(vpnProfiles, forKey: AnyKey("vpnProfiles"))
        try c.encode(mullvadNumber, forKey: AnyKey("mullvadNumber"))
        try c.encode(mullvadDevice, forKey: AnyKey("mullvadDevice"))
    }

    /// Legacy `chain` becomes a profile (and the selected VPN) when there are no profiles.
    public mutating func migrateLegacyChain() {
        guard vpnProfiles.isEmpty, let ch = chain, !ch.config.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let p = VpnProfile(name: ch.name.isEmpty ? "VPN" : ch.name, config: ch.config)
        vpnProfiles = [p]
        if settings.vpnExit.isEmpty { settings.vpnExit = VpnExit.imported(id: p.id).stringValue }
    }
}

private struct Skip: Decodable {}

public enum BackupError: LocalizedError, Equatable {
    case notABackup, damaged, unsupported, wrongPassphrase
    public var errorDescription: String? {
        switch self {
        case .notABackup: return "Not a ResProxy backup"
        case .damaged: return "Damaged backup"
        case .unsupported: return "Unsupported backup format"
        case .wrongPassphrase: return "Wrong passphrase or damaged backup"
        }
    }
}

public enum Backup {
    public static let prefix = "resproxy-backup-v1:"
    static let iterations = 210_000

    public static func looksLikeBackup(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix(prefix)
    }

    static func key(_ passphrase: String, salt: Data, iterations: Int) -> SymmetricKey {
        var out = [UInt8](repeating: 0, count: 32)
        let pw = Array(passphrase.utf8)
        _ = salt.withUnsafeBytes { s in
            pw.withUnsafeBufferPointer { p in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2),
                                     UnsafeRawPointer(p.baseAddress!).assumingMemoryBound(to: Int8.self), pw.count,
                                     s.bindMemory(to: UInt8.self).baseAddress!, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations), &out, out.count)
            }
        }
        return SymmetricKey(data: out)
    }

    struct Envelope: Codable {
        var kdf: String?
        var iter: Int?
        var salt: String?
        var iv: String?
        var data: String?
    }

    public static func seal(_ payload: BackupPayload, passphrase: String) throws -> String {
        let salt = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        let iv = Data((0..<12).map { _ in UInt8.random(in: 0...255) })
        let k = key(passphrase, salt: salt, iterations: iterations)
        let plain = try JSONEncoder.resproxy.encode(payload)
        let box = try AES.GCM.seal(plain, using: k, nonce: AES.GCM.Nonce(data: iv), authenticating: Data(prefix.utf8))
        let env = Envelope(kdf: "pbkdf2-sha256", iter: iterations, salt: salt.base64EncodedString(), iv: iv.base64EncodedString(),
                           data: (box.ciphertext + box.tag).base64EncodedString())
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return prefix + (try e.encode(env)).base64EncodedString()
    }

    public static func open(_ text: String, passphrase: String) throws -> BackupPayload {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(prefix) else { throw BackupError.notABackup }
        let body = String(t.dropFirst(prefix.count).filter { !$0.isWhitespace })
        guard let raw = Data(base64Encoded: body), let env = try? JSONDecoder().decode(Envelope.self, from: raw) else { throw BackupError.damaged }
        guard env.kdf == "pbkdf2-sha256", let iter = env.iter, iter > 0, let s = env.salt, let i = env.iv, let d = env.data,
              let salt = Data(base64Encoded: s), let iv = Data(base64Encoded: i), let data = Data(base64Encoded: d), data.count >= 16
        else { throw BackupError.unsupported }
        let plain: Data
        do {
            let k = key(passphrase, salt: salt, iterations: iter)
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv), ciphertext: data.dropLast(16), tag: data.suffix(16))
            plain = try AES.GCM.open(box, using: k, authenticating: Data(prefix.utf8))
        } catch {
            throw BackupError.wrongPassphrase
        }
        do {
            return try JSONDecoder().decode(BackupPayload.self, from: plain)
        } catch {
            throw BackupError.damaged
        }
    }
}
