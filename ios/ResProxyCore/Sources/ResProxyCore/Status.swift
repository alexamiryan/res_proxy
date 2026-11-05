// Tunnel status (written by the packet tunnel, read by the app) and the app ↔ tunnel message API.
// Mirrors android/.../vpn/TunnelState.kt.

import Foundation

public enum Phase: String, Codable, Sendable { case DISCONNECTED, CONNECTING, CONNECTED, ROTATING, PROXY_DOWN, ERROR }

public struct TunnelStatus: Codable, Equatable, Sendable {
    public var phase: Phase = .DISCONNECTED
    public var profileId: String?
    public var profileName: String?
    public var sessionId: String?
    public var exitIp: String?
    public var latencyMs: Int?
    public var report: ReputationReport?
    public var checking = false
    public var rotationAttempt = 0
    public var message: String?
    public var downBps: Int64 = 0
    public var upBps: Int64 = 0
    public var sessionDown: Int64 = 0
    public var sessionUp: Int64 = 0
    public var connectedAt: Int64 = 0
    /// Name of the WireGuard VPN the proxy connection is chained through, if any.
    public var chainName: String?
    /// Round trip to the VPN server (in-tunnel ping), when chaining.
    public var vpnPingMs: Int?
    /// Round trip to the proxy gateway (through the VPN when chaining).
    public var proxyPingMs: Int?
    /// Times the connection was found not to be working and was rebuilt automatically.
    public var repairs = 0
    /// Apps' traffic is held back: the exit IP hasn't passed the reputation check (yet).
    public var trafficBlocked = true

    public init(phase: Phase = .DISCONNECTED, message: String? = nil) { self.phase = phase; self.message = message }

    public var isActive: Bool { phase != .DISCONNECTED && phase != .ERROR }
}

/// Messages from the app to the running tunnel (NETunnelProviderSession.sendProviderMessage), JSON-encoded.
public enum TunnelCommand: String, Codable, Sendable {
    case status
    /// New sticky session (new exit IP) while the TUN stays up.
    case rotate
    /// Provider / location changed in settings: reuse that place's remembered session if alive.
    case applyLocation
    /// Run the reputation check again.
    case recheck
    /// ICMP latency to the given targets, measured from outside the tunnel.
    case ping
    /// Settings were saved (also signalled with a Darwin notification).
    case settingsChanged
}

public struct TunnelMessage: Codable, Sendable {
    public var cmd: TunnelCommand
    public var targets: [String]?
    public init(_ cmd: TunnelCommand, targets: [String]? = nil) { self.cmd = cmd; self.targets = targets }
}

public struct TunnelReply: Codable, Sendable {
    public var status: TunnelStatus?
    public var pings: [String: Int]?
    public init(status: TunnelStatus? = nil, pings: [String: Int]? = nil) { self.status = status; self.pings = pings }
}

/// Darwin notification names (no payload) between app and tunnel.
public enum Notify {
    public static let settingsChanged = "dev.resproxy.settings-changed"
    public static let apiKeysChanged = "dev.resproxy.apikeys-changed"

    public static func post(_ name: String) {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(name as CFString), nil, nil, true)
    }

    /// Observes `name` for the life of the process; `block` runs on the main queue.
    public static func observe(_ name: String, _ block: @escaping () -> Void) {
        let box = Unmanaged.passRetained(BlockBox(block)).toOpaque()
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), box, { _, observer, _, _, _ in
            guard let observer else { return }
            let b = Unmanaged<BlockBox>.fromOpaque(observer).takeUnretainedValue()
            DispatchQueue.main.async { b.block() }
        }, name as CFString, nil, .deliverImmediately)
    }

    final class BlockBox {
        let block: () -> Void
        init(_ b: @escaping () -> Void) { block = b }
    }
}

public func formatBytes(_ b: Int64) -> String {
    let units = ["B", "KB", "MB", "GB", "TB"]
    var v = Double(b), i = 0
    while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
    return i == 0 ? "\(b) B" : String(format: v >= 100 ? "%.0f %@" : "%.1f %@", v, units[i])
}
