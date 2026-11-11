// The packet tunnel's brain — port of android/.../vpn/ProxyVpnService.kt:
//   apps → utun (NEPacketTunnelProvider) → hev-socks5-tunnel → Go relay (gate) → [WireGuard netstack →] proxy
//
// The tunnel's network settings are applied before anything touches the network and stay applied across rotations,
// location and settings changes, repairs and outages, so with the kill switch on traffic is held/dropped, never sent
// direct. Apps only get through once the exit IP passed the reputation check (the relay's gate).

import Foundation
import Network
import NetworkExtension
import ResProxyCore
import UserNotifications
import os

private let osLog = Logger(subsystem: "dev.resproxy.tunnel", category: "tunnel")

/// Logs to the unified log (public, so the device console shows it) and to the Diagnostics log in the App Group.
/// Never pass secrets.
enum log {
    static func info(_ s: String) { osLog.notice("\(s, privacy: .public)"); SharedStore.shared.appendLog(s) }
    static func warning(_ s: String) { osLog.warning("\(s, privacy: .public)"); SharedStore.shared.appendLog("WARN " + s) }
}

/// Serializes the critical sections (Kotlin's Mutex.withLock) across suspension points.
@MainActor
final class AsyncLock {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run<T>(_ body: () async throws -> T) async rethrows -> T {
        if locked {
            await withCheckedContinuation { waiters.append($0) }
        } else {
            locked = true
        }
        defer {
            if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
        }
        return try await body()
    }
}

@MainActor
final class TunnelController {
    unowned let provider: NEPacketTunnelProvider
    let store = SharedStore.shared
    private let lock = AsyncLock()
    private let hev = Hev()
    /// Repair reasons starting with this restart hev; no other repair does.
    private static let engineReason = "tunnel engine"

    private(set) var status = TunnelStatus()
    private var profile: Profile?
    /// wg-quick text of the VPN the proxy is reached through, when chaining.
    private var chainConfig: String?
    private var chainParsed: WireGuardConfig?
    /// The VPN couldn't be set up; the upstream must not start without it.
    private var chainBroken = false
    private var upstreamUp = false
    private var socksUser = randomId(16)
    private var socksPass = randomId(24)
    private var tunFd: Int32 = -1
    private var settingsApplied = false

    private var killSwitch = true
    private var bypassLan = true
    /// Exit IP that passed the check and that the gate was opened for.
    private var cleanIp: String?
    private var gateOpen = false
    /// Blocked with no clean IP found (bad IP, rotations used up): wait for "New IP" instead of re-checking.
    private var waitingForUser = false
    private var lastCheckKick: Date = .distantPast
    private var checkTask: Task<Void, Never>?
    private var workers: [Task<Void, Never>] = []
    private var chainStartedAt = Date()
    private var lastRepairAt: Date = .distantPast
    private var failedRepairs = 0
    private var pathMonitor: NWPathMonitor?
    private var reportCache: [String: ReputationReport] = [:]
    private var settingsDebounce: Task<Void, Never>?

    /// What the running tunnel was built from; a change while connected rebuilds the affected part.
    private struct Keys: Equatable { var tun: String; var upstream: String }
    private var builtWith: Keys?

    init(provider: NEPacketTunnelProvider) {
        self.provider = provider
        let s = store.settings()
        killSwitch = s.killSwitch
        bypassLan = s.bypassLan
        Notify.observe(Notify.settingsChanged) { [weak self] in self?.settingsChanged() }
        Notify.observe(Notify.apiKeysChanged) { [weak self] in self?.apiKeysChanged() }
    }

    // MARK: status

    private func update(_ f: (inout TunnelStatus) -> Void) {
        let before = status
        f(&status)
        if status.phase != before.phase || status.message != before.message || status.exitIp != before.exitIp ||
            status.report != before.report || status.trafficBlocked != before.trafficBlocked || status.checking != before.checking {
            store.saveStatus(status)
        }
        if status.phase != before.phase || status.message != before.message {
            log.info("status \(status.phase.rawValue)\(status.trafficBlocked ? " (blocked)" : "")\(status.message.map { ": " + $0 } ?? "")")
        }
        if status.exitIp != before.exitIp, let ip = status.exitIp { log.info("exit IP \(ip)") }
        if status.report != before.report, let r = status.report {
            log.info("reputation \(r.ip): \(r.verdict.rawValue) (\(r.results.count) sources)")
        }
    }

    private func describe(_ e: Error) -> String {
        if let b = e as? BridgeError, b.code == "auth" {
            return "\(profile?.provider.label ?? "Proxy") rejected the proxy username/password (Settings → Accounts)"
        }
        return e.localizedDescription
    }

    // MARK: lifecycle

    private func keys() -> Keys {
        let s = store.settings()
        let cs = s.connection
        let acc = store.account(s.provider)
        var vpn = ""
        if s.chainEnabled {
            switch VpnExit.parse(s.vpnExit) {
            case .imported(let id): vpn = String(store.vpnProfiles().first { $0.id == id }?.config.hashValue ?? 0)
            case .mullvad(let code): vpn = code + "/" + (store.mullvad()?.number ?? "") + "/" + (store.mullvad()?.device?.id ?? "")
            case nil: vpn = ""
            }
        }
        return Keys(
            tun: "\(s.bypassLan)",
            upstream: [s.provider.rawValue, cs.protocol.rawValue, "\(cs.sticky)", "\(cs.lifetimeMinutes)",
                       cs.gateway(s.provider).map { "\($0.host):\($0.port)" } ?? "", acc?.username ?? "", acc?.password ?? "",
                       "\(s.chainEnabled)", s.vpnExit, vpn].joined(separator: "\u{1}"))
    }

    /// Settings saved by the app (debounced, so typing in a field rebuilds once).
    private func settingsChanged() {
        let s = store.settings()
        killSwitch = s.killSwitch
        bypassLan = s.bypassLan
        settingsDebounce?.cancel()
        settingsDebounce = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, !Task.isCancelled else { return }
            await self.applySettings()
        }
    }

    private func applySettings() async {
        guard let built = builtWith, status.isActive else { return }
        let now = keys()
        if now.tun != built.tun {
            log.info("routing settings changed, reapplying")
            await connect()
        } else if now.upstream != built.upstream {
            log.info("connection settings changed, rebuilding upstream")
            await lock.run {
                guard profile != nil else { return }
                builtWith = now
                await blockTraffic()
                update { $0.phase = .CONNECTING; $0.exitIp = nil; $0.report = nil; $0.message = "Applying new settings…" }
                if await reloadChain(), await rebuild() { update { $0.phase = .CONNECTED; $0.message = nil } }
            }
            launchCheck(force: false)
        }
    }

    /// New or removed API keys change which sources a report has: re-check the current IP right away.
    private func apiKeysChanged() {
        reportCache.removeAll()
        if status.phase == .CONNECTED && !status.checking {
            log.info("API keys changed, re-checking reputation")
            launchCheck(force: true)
        }
    }

    /// Connects (or reconnects). `started` is the startTunnel completion: called once the tunnel's network settings
    /// are applied (traffic captured) or with the error that stopped it.
    func connect(started: ((Error?) -> Void)? = nil) async {
        await lock.run {
            // Network settings stay applied (same utun) across a reconnect, so nothing leaks meanwhile.
            await teardown(keepTun: true)
            if store.settings().connection.sticky {
                // Remember the session for this provider + location (new one if none), so switching back reuses it.
                store.updateSettings(notify: false) { $0 = $0.withSession($0.sessionId.isEmpty ? Presets.newSessionId() : $0.sessionId) }
            }
            let s = store.settings()
            killSwitch = s.killSwitch
            bypassLan = s.bypassLan
            guard let acc = store.account(s.provider), !acc.username.trimmingCharacters(in: .whitespaces).isEmpty else {
                return fail("Add your \(s.provider.label) account first", started)
            }
            let p = Profile(account: acc, settings: s)
            profile = p
            log.info("connect: \(s.provider.label) · \(s.location.label), VPN \(s.chainEnabled ? s.vpnExit : "off"), kill switch \(s.killSwitch ? "on" : "off")")
            builtWith = keys()
            status = TunnelStatus(phase: .CONNECTING)
            update {
                $0.profileId = p.provider.rawValue
                $0.profileName = "\(p.provider.label) · \(s.location.label)"
                $0.sessionId = p.sessionId
            }
            store.updateSettings(notify: false) { $0.wasConnected = true }
            // Capture all traffic before any upstream I/O.
            do {
                try await applyNetworkSettings(bypassLan: s.bypassLan)
            } catch {
                return fail("Couldn't set up the VPN interface: \(error.localizedDescription)", started)
            }
            started?(nil)
            workers = [Task { await self.statsLoop(p.provider.rawValue) }, Task { await self.healthLoop() }]
            startPathMonitor()
            guard await reloadChain() else { return }
            do {
                try await startUpstream(p)
                log.info("upstream up")
            } catch let e as UserError {
                await teardown(keepTun: false)
                return fail(e.message, nil)
            } catch {
                log.warning("tunnel start failed: \(error.localizedDescription)")
                if !(await onProxyFailure(error)) { return }
            }
            update {
                if $0.phase != .PROXY_DOWN { $0.phase = .CONNECTED; $0.connectedAt = nowMs(); $0.message = nil }
            }
        }
        launchCheck(force: false)
    }

    func disconnect() async {
        await lock.run { await teardown(keepTun: false) }
    }

    private func applyNetworkSettings(bypassLan: Bool) async throws {
        let ns = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        ns.mtu = NSNumber(value: Hev.mtu)
        let v4 = NEIPv4Settings(addresses: [Hev.tunV4], subnetMasks: ["255.255.255.255"])
        v4.includedRoutes = [NEIPv4Route.default()]
        let v6 = NEIPv6Settings(addresses: [Hev.tunV6], networkPrefixLengths: [128])
        v6.includedRoutes = [NEIPv6Route.default()]
        if bypassLan {
            // LAN bypass: private, link-local and multicast ranges stay off the VPN. 100.64.0.0/10 stays routed:
            // hev's fake-IP DNS uses it.
            v4.excludedRoutes = [("10.0.0.0", "255.0.0.0"), ("172.16.0.0", "255.240.0.0"), ("192.168.0.0", "255.255.0.0"),
                                 ("169.254.0.0", "255.255.0.0"), ("224.0.0.0", "240.0.0.0"), ("255.255.255.255", "255.255.255.255")]
                .map { NEIPv4Route(destinationAddress: $0.0, subnetMask: $0.1) }
            v6.excludedRoutes = [("fe80::", 10), ("fd00::", 8), ("ff00::", 8)]
                .map { NEIPv6Route(destinationAddress: $0.0, networkPrefixLength: NSNumber(value: $0.1)) }
        }
        ns.ipv4Settings = v4
        ns.ipv6Settings = v6
        // Fake-IP DNS: apps get 100.64/10 addresses, hev sends the hostname to the proxy. DNS can't leak.
        let dns = NEDNSSettings(servers: [Hev.mapDns])
        dns.matchDomains = [""]
        ns.dnsSettings = dns
        try await provider.setTunnelNetworkSettings(ns)
        settingsApplied = true
        tunFd = rp_find_tun_fd()
        if tunFd < 0 { throw UserError("Couldn't find the tunnel interface") }
    }

    /// (Re)resolves the VPN to chain through (imported profile or a Mullvad server). Returns false if torn down.
    private func reloadChain() async -> Bool {
        chainConfig = nil
        chainParsed = nil
        let s = store.settings()
        if !s.chainEnabled {
            update { $0.chainName = nil; $0.vpnPingMs = nil }
            return true
        }
        do {
            let (name, cfg) = try await resolveVpn(s)
            chainParsed = try WireGuardConfig.parse(cfg)
            chainConfig = cfg
            chainBroken = false
            update { $0.chainName = name; $0.vpnPingMs = nil }
            return true
        } catch {
            log.warning("VPN chain: \(error.localizedDescription)")
            // Never fall back to an unchained connection: that would show the real IP to the proxy.
            update { $0.chainName = nil }
            chainBroken = true
            return await onProxyFailure(UserError("VPN: \(error.localizedDescription)"))
        }
    }

    private var mullvadClient: MullvadClient { MullvadClient { try await Bridge.fetch($0) } }

    /// The VPN to chain through: a display name and its wg-quick config. Throws with a user-facing message.
    private func resolveVpn(_ s: AppSettings) async throws -> (String, String) {
        switch VpnExit.parse(s.vpnExit) {
        case .imported(let id):
            guard let p = store.vpnProfiles().first(where: { $0.id == id }) else { throw UserError("The selected VPN profile was removed") }
            return (p.name, p.config)
        case .mullvad(let code):
            let cur = store.mullvad()
            if let cur, cur.device == nil, cur.signedOut {
                throw UserError("Signed out of Mullvad on this iPhone. Log in again in Settings → VPN.")
            }
            var acc = cur
            if acc?.device == nil {
                guard let c = cur else { throw UserError("Add your Mullvad account number in Settings → VPN") }
                acc = try await mullvadClient.login(c.number, existing: c.device)
                store.saveMullvad(acc)
            }
            var relays: [MullvadRelay]
            do {
                relays = try await mullvadClient.relays()
                store.saveRelays(relays)
            } catch {
                relays = store.cachedRelays()
                if relays.isEmpty { throw error }
            }
            guard let relay = Mullvad.pickRelay(code, relays) else { throw UserError("No Mullvad server matches “\(code)”") }
            return ("Mullvad \(relay.city) · \(relay.hostname)", Mullvad.wgQuick(acc!.device!, relay))
        case nil:
            throw UserError("Choose a VPN server on the home screen")
        }
    }

    /// Checks that this install's Mullvad device still exists; a removed one signs this install out (no silent
    /// re-registration, which would take a slot).
    private func verifyMullvadDevice() async {
        guard let acc = store.mullvad(), let d = acc.device else { return }
        guard let ok = try? await mullvadClient.isDeviceRegistered(acc.number, d.id) else { return }
        if !ok {
            var a = acc
            a.device = nil
            a.signedOut = true
            store.saveMullvad(a)
        }
    }

    /// New sticky session while keeping the TUN up.
    private func switchSession(_ sessionId: String) async {
        await lock.run {
            guard profile != nil else { return }
            await blockTraffic()
            let s = store.updateSettings(notify: false) { $0 = $0.withSession(sessionId) }
            guard let acc = store.account(s.provider) else { return }
            let p = Profile(account: acc, settings: s, sessionId: sessionId)
            profile = p
            update {
                $0.phase = .ROTATING; $0.sessionId = sessionId; $0.exitIp = nil; $0.report = nil
                $0.profileName = "\(p.provider.label) · \(s.location.label)"
            }
            builtWith = keys()
            do {
                if upstreamUp {
                    // Same VPN and listeners; the relay drops connections of the old session.
                    _ = try await Bridge.call("upstream", ["upstream": Bridge.endpointJSON(try endpoint(p))])
                } else {
                    try await startUpstream(p)
                }
                update { $0.phase = .CONNECTED; $0.message = nil }
            } catch {
                _ = await onProxyFailure(error)
            }
        }
    }

    /// Re-reads the profile (credentials, gateway, protocol) and restarts the upstream. False if it couldn't.
    /// hev keeps running unless `restartEngine` (see `startUpstream`).
    private func rebuild(restartEngine: Bool = false) async -> Bool {
        let s = store.settings()
        guard let acc = store.account(s.provider), !acc.username.isEmpty else { return false }
        let p = Profile(account: acc, settings: s)
        profile = p
        update { $0.profileName = "\(p.provider.label) · \(s.location.label)" }
        do {
            try await startUpstream(p, restartEngine: restartEngine)
            return true
        } catch {
            log.warning("upstream restart failed: \(error.localizedDescription)")
            _ = await onProxyFailure(error)
            return false
        }
    }

    /// Kill switch on: stay up with traffic blocked and keep retrying. Off: disconnect. True if the tunnel stays up.
    private func onProxyFailure(_ e: Error) async -> Bool {
        let msg = describe(e)
        if killSwitch {
            update { $0.phase = .PROXY_DOWN; $0.checking = false; $0.message = "\(msg) — traffic blocked" }
            return true
        }
        await teardown(keepTun: false)
        fail("\(msg) — disconnected (kill switch is off)", nil)
        return false
    }

    private func teardown(keepTun: Bool) async {
        checkTask?.cancel(); checkTask = nil
        workers.forEach { $0.cancel() }; workers = []
        pathMonitor?.cancel(); pathMonitor = nil
        cleanIp = nil; waitingForUser = false; gateOpen = false
        hev.stop()
        _ = try? await Bridge.call("stop")
        upstreamUp = false
        chainConfig = nil; chainParsed = nil; chainBroken = false
        profile = nil
        builtWith = nil
        failedRepairs = 0
        hevBase = (0, 0); hevLast = (0, 0)
        if status.phase != .ERROR { status = TunnelStatus() }
        if !keepTun { store.saveStatus(status) }
    }

    private func fail(_ message: String, _ started: ((Error?) -> Void)?) {
        status = TunnelStatus(phase: .ERROR, message: message)
        store.saveStatus(status)
        let err = NSError(domain: "dev.resproxy", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        if let started { started(err) } else { provider.cancelTunnelWithError(err) }
    }

    // MARK: plumbing

    private func endpoint(_ p: Profile) throws -> Endpoint {
        let e = Presets.build(p)
        if e.host.trimmingCharacters(in: .whitespaces).isEmpty { throw UserError("Proxy host is empty") }
        if e.username.trimmingCharacters(in: .whitespaces).isEmpty { throw UserError("Username is empty") }
        return e
    }

    /// Relay + upstream (+ WireGuard when chaining) in the Go bridge, then hev on the utun. A running hev is kept
    /// (unless `restartEngine`): the relay's SOCKS front keeps its port and credentials, and only the upstream
    /// behind it is replaced. Restarting hev empties its fake-IP DNS table, and every address apps still have cached
    /// (browsers keep them for about a minute) then points nowhere or at a different site, while our own checks,
    /// which don't go through the TUN, stay green. While the upstream is down, the front refuses connections.
    private func startUpstream(_ p: Profile, restartEngine: Bool = false) async throws {
        if chainBroken { throw UserError("VPN is not available") }
        if restartEngine || hev.died { hev.stop() }
        upstreamUp = false
        let ep = try endpoint(p)
        var args: [String: Any] = ["upstream": Bridge.endpointJSON(ep), "token": randomId(24)]
        if let cfg = chainConfig { args["wg"] = cfg; chainStartedAt = Date() }
        _ = try await Bridge.call("start", args)
        if gateOpen { _ = try? await Bridge.call("gate", ["open": true]) }
        // Same credentials: the bridge keeps the running front (same port). New ones only for a new hev.
        if !hev.running { socksUser = randomId(16); socksPass = randomId(24) }
        let socks = try await Bridge.call("socks", ["user": socksUser, "pass": socksPass, "lanDirect": bypassLan])
        guard let port = socks["port"] as? Int else { throw UserError("Relay didn't start") }
        upstreamUp = true
        if hev.running && hev.socksPort == port { return }
        if tunFd < 0 { tunFd = rp_find_tun_fd() }
        guard tunFd >= 0 else { throw UserError("Couldn't find the tunnel interface") }
        if hev.running { log.info("relay port changed, restarting tunnel engine") }
        hev.start(fd: tunFd, socksPort: port, user: socksUser, pass: socksPass)
    }

    /// Closes the gate and drops apps' open connections: the exit IP is new or not (yet) known to be clean.
    private func blockTraffic() async {
        gateOpen = false
        cleanIp = nil
        waitingForUser = false
        _ = try? await Bridge.call("gate", ["open": false])
        if status.trafficBlocked != true { log.info("gate closed") }
        update { $0.trafficBlocked = true }
    }

    private func allowTraffic(_ ip: String?) async {
        cleanIp = ip
        waitingForUser = false
        gateOpen = true
        _ = try? await Bridge.call("gate", ["open": true])
        if status.trafficBlocked { log.info("gate open for \(ip ?? "unchecked IP")") }
        update { $0.trafficBlocked = false }
    }

    /// Wi-Fi ↔ cellular switches leave proxy and VPN sockets dead; rebuild them on the new network.
    private func startPathMonitor() {
        let m = NWPathMonitor()
        var lost = false
        var lastIfaces: [String] = []
        var pending: Task<Void, Never>?
        m.pathUpdateHandler = { [weak self] path in
            let ifaces = path.availableInterfaces.map(\.name)
            Task { @MainActor in
                guard let self else { return }
                if path.status != .satisfied { lost = true; return }
                // The first update just lists what's there; a different network after one, or after a loss, is a switch.
                if !lastIfaces.isEmpty && (lost || ifaces.first != lastIfaces.first) {
                    log.info("network switched to \(ifaces.first ?? "?")")
                    pending?.cancel()
                    pending = Task { try? await Task.sleep(nanoseconds: 2_000_000_000); if !Task.isCancelled { await self.repair("network changed", force: true) } }
                }
                lost = false
                lastIfaces = ifaces
            }
        }
        m.start(queue: .main)
        pathMonitor = m
    }

    /// Device woke up: sockets may be stale.
    func wake() {
        guard status.isActive else { return }
        Task { await self.repair("device woke up", force: false, probeFirst: true) }
    }

    /// Rebuilds VPN chain, relay and tunnel engine while keeping the TUN up. Rate-limited unless `force`; after
    /// repeated repairs that didn't help, reconnects from scratch.
    private func repair(_ reason: String, force: Bool = false, probeFirst: Bool = false) async {
        guard status.isActive else { return }
        if probeFirst, upstreamUp, (try? await Bridge.call("probe")) != nil { return }
        let now = Date()
        if !force && now.timeIntervalSince(lastRepairAt) < 60 { return }
        lastRepairAt = now
        log.info("repairing connection: \(reason)")
        let retry = reason == "retrying"
        if !retry && failedRepairs >= 3 {
            failedRepairs = 0
            await connect()
            update { $0.repairs += 1; $0.message = "Reconnected (\(reason))" }
            return
        }
        if !retry { failedRepairs += 1 }
        // A check still running against the upstream we're about to replace would report its aborted probe as
        // "proxy down"; the repair starts a fresh one.
        checkTask?.cancel()
        let mullvad: Bool = { if case .mullvad = VpnExit.parse(store.settings().vpnExit) { return true }; return false }()
        await lock.run {
            guard profile != nil else { return }
            if chainConfig != nil && mullvad && reason.hasPrefix("VPN") { await verifyMullvadDevice() }
            chainBroken = false
            guard await reloadChain() else { return }
            builtWith = keys()
            if await rebuild(restartEngine: reason.hasPrefix(Self.engineReason)) {
                update {
                    $0.phase = .CONNECTED
                    if retry { $0.message = nil } else { $0.repairs += 1; $0.message = "Connection repaired (\(reason))" }
                }
            }
        }
        launchCheck(force: false)
    }

    // MARK: background work

    /// Monotonic (down, up) counters; hev's reset on restart, so deltas are tracked across restarts.
    private var hevBase: (Int64, Int64) = (0, 0)
    private var hevLast: (Int64, Int64) = (0, 0)
    private func counters() -> (Int64, Int64) {
        guard hev.running else { return hevLast }
        let raw = hev.stats()
        if raw.down + hevBase.0 < hevLast.0 || raw.up + hevBase.1 < hevLast.1 { hevBase = hevLast }
        hevLast = (raw.down + hevBase.0, raw.up + hevBase.1)
        return hevLast
    }

    private func statsLoop(_ key: String) async {
        var last: (Int64, Int64) = (0, 0)
        var saved: (Int64, Int64) = (0, 0)
        var tick = 0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if Task.isCancelled { break }
            let (down, up) = counters()
            let dDown = max(0, down - last.0), dUp = max(0, up - last.1)
            last = (down, up)
            update { $0.downBps = dDown; $0.upBps = dUp; $0.sessionDown += dDown; $0.sessionUp += dUp }
            tick += 1
            if tick % 10 == 0 {
                store.addUsage(key, down: status.sessionDown - saved.0, up: status.sessionUp - saved.1)
                saved = (status.sessionDown, status.sessionUp)
                store.saveStatus(status)
            }
            if tick % 60 == 1 { log.info("memory \(memoryFootprintMB()) MB, session ↓\(formatBytes(status.sessionDown)) ↑\(formatBytes(status.sessionUp))") }
        }
        store.addUsage(key, down: status.sessionDown - saved.0, up: status.sessionUp - saved.1)
    }

    /// Every few seconds: are apps' connections through the relay getting answers, is the VPN handshake fresh,
    /// latencies. Every `healthIntervalSec`: an end-to-end probe. When the proxy answers our probe but app traffic or
    /// the VPN is broken, rebuilds the plumbing.
    private func healthLoop() async {
        var fails = 0
        var lastProbe = Date()
        var lastPing = Date.distantPast
        var seenOk = 0, seenFail = 0
        var failingSince: Date?
        var failsInStreak = 0
        var quietSince: Date?
        var appUpAtQuiet: Int64 = 0
        var acceptedAtQuiet = -1
        var engineDeadLogged = false
        var staleSeen = 0
        var staleLoggedAt = Date.distantPast
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if Task.isCancelled { return }
            let now = Date()
            let interval = TimeInterval(clamp(store.settings().healthIntervalSec, 10, 600))
            let down = status.phase == .PROXY_DOWN
            var suspect: String?

            let st = upstreamUp ? ((try? await Bridge.call("status")) ?? [:]) : [:]
            // 1. Real traffic: only failures and no successes for 20 s means apps have no internet.
            let ok = st["conns"] as? Int ?? seenOk, failed = st["connFails"] as? Int ?? seenFail
            let newOk = max(0, ok - seenOk), newFail = max(0, failed - seenFail)
            seenOk = ok; seenFail = failed
            if newOk > 0 {
                failingSince = nil; failsInStreak = 0; failedRepairs = 0
            } else if newFail > 0 {
                if failingSince == nil { failingSince = now }
                failsInStreak += newFail
            }
            if !gateOpen { failingSince = nil; failsInStreak = 0 }
            if let since = failingSince, now.timeIntervalSince(since) > 20, failsInStreak >= 3, (st["failedHosts"] as? Int ?? 0) >= 2 {
                suspect = "apps' connections were failing"
                log.info("relay: \(failsInStreak) failures, no successes; last: \(st["lastError"] as? String ?? "")")
            }

            // 2. Tunnel engine: its thread must be alive, and apps' packets must turn into relay connections. With no
            //    relay connection open or opened for 30 s, everything apps send is new connections that never arrive.
            if hev.died {
                if !engineDeadLogged { log.warning("tunnel engine stopped by itself") }
                engineDeadLogged = true
                suspect = "\(Self.engineReason) stopped"
            } else if hev.running, let accepted = st["socksAccepted"] as? Int {
                engineDeadLogged = false
                let open = st["socksOpen"] as? Int ?? 0
                let appUp = hevLast.1 // apps' upload: packets hev read from the TUN
                if accepted != acceptedAtQuiet || open > 0 || !gateOpen || quietSince == nil {
                    quietSince = now; acceptedAtQuiet = accepted; appUpAtQuiet = appUp
                } else if now.timeIntervalSince(quietSince!) > 30 {
                    if appUp - appUpAtQuiet > 8_000 {
                        log.info("tunnel engine: apps sent \(appUp - appUpAtQuiet) B in \(Int(now.timeIntervalSince(quietSince!))) s, no relay connection")
                        suspect = "\(Self.engineReason) stalled"
                    }
                    quietSince = nil
                }
            }
            if let stale = st["staleFakeIps"] as? Int, stale < staleSeen { staleSeen = stale } // new front, new count
            if let stale = st["staleFakeIps"] as? Int, stale > staleSeen, now.timeIntervalSince(staleLoggedAt) > 60 {
                log.info("relay: \(stale - staleSeen) connection(s) to fake IPs the tunnel engine no longer knows")
                staleSeen = stale; staleLoggedAt = now
            }

            // 3. VPN: WireGuard re-handshakes every 2 minutes; an old handshake means the VPN path is dead.
            if let chain = chainParsed, upstreamUp {
                let age = st["handshakeAgeSec"] as? Int
                if (age == nil && now.timeIntervalSince(chainStartedAt) > 20) || (age ?? 0) > 200 { suspect = "VPN handshake lost" }
                if now.timeIntervalSince(lastPing) > 10, let target = chain.pingTarget {
                    lastPing = now
                    if let r = try? await Bridge.call("vpnPing", ["ip": target]), let ms = r["ms"] as? Int { update { $0.vpnPingMs = ms } }
                }
            }
            if let rtt = st["rttMs"] as? Double { update { $0.proxyPingMs = Int(rtt) } }

            // Blocked and no check running (it failed, or the proxy dropped mid-check): check again.
            if !gateOpen && !waitingForUser && checkTask == nil && upstreamUp && status.phase == .CONNECTED &&
                now.timeIntervalSince(lastCheckKick) > 30 {
                launchCheck(force: false)
            }

            let backoff = min(5.0 * Double(fails + 1), interval)
            if !upstreamUp {
                // Upstream never came up (e.g. no network / VPN down at connect): keep retrying.
                if down && now.timeIntervalSince(lastProbe) >= backoff {
                    lastProbe = now; fails += 1
                    await repair("retrying", force: true)
                }
                continue
            }
            if suspect == nil && now.timeIntervalSince(lastProbe) < (down ? backoff : interval) { continue }
            lastProbe = now
            do {
                let r = try await Bridge.call("probe")
                fails = 0
                // Upstream up but hev never started (e.g. the utun wasn't found): start it.
                if !hev.running && !hev.died && profile != nil { await lock.run { _ = await rebuild() } }
                if status.phase == .PROXY_DOWN {
                    update { $0.phase = .CONNECTED; $0.message = "Proxy reachable again" }
                    if (status.exitIp == nil || !gateOpen) && checkTask == nil { launchCheck(force: false) }
                }
                update { $0.latencyMs = r["ms"] as? Int }
                if let s = suspect {
                    failingSince = nil; failsInStreak = 0
                    // A dead engine leaves apps with nothing at all: don't wait out the repair rate limit.
                    await repair(s, force: hev.died)
                }
            } catch {
                fails += 1
                let auth = (error as? BridgeError)?.code == "auth"
                if fails >= 2 || auth || down || suspect != nil {
                    if !(await onProxyFailure(error)) { return }
                    if !auth && (fails % 3 == 0 || suspect != nil) { await repair(suspect ?? "proxy unreachable", force: fails % 3 == 0) }
                }
            }
        }
    }

    // MARK: reputation check + auto-rotate

    func rotate() {
        guard status.isActive else { return }
        Task {
            await switchSession(Presets.newSessionId())
            launchCheck(force: true)
        }
    }

    func applyLocation() {
        guard status.isActive else { return }
        Task {
            let s = store.settings()
            await switchSession(s.sessionId.isEmpty ? Presets.newSessionId() : s.sessionId)
            launchCheck(force: false)
        }
    }

    func recheck() {
        guard status.isActive else { return }
        launchCheck(force: true)
    }

    /// Verifies the proxy works, then exit IP + reputation. Apps' traffic stays blocked until an IP passes
    /// (verdict below the rotate threshold). With auto-rotate on, keeps switching sticky sessions until one does.
    private func launchCheck(force: Bool) {
        checkTask?.cancel()
        lastCheckKick = Date()
        checkTask = Task { [weak self] in
            guard let self else { return }
            await self.runCheck(force: force)
            if !Task.isCancelled { self.checkTask = nil }
        }
    }

    private func runCheck(force: Bool) async {
        var attempt = 0
        while !Task.isCancelled {
            guard upstreamUp, let p = profile else { return }
            update { $0.checking = true; $0.rotationAttempt = attempt }
            let latency: Int
            do {
                latency = try await Bridge.call("probe")["ms"] as? Int ?? 0
            } catch {
                if Task.isCancelled { return }
                update { $0.checking = false }
                _ = await onProxyFailure(error)
                return
            }
            if Task.isCancelled { return }
            update {
                if $0.phase == .PROXY_DOWN { $0.phase = .CONNECTED; $0.message = nil }
                $0.latencyMs = latency
            }
            let s = store.settings()
            if !s.recheckOnConnect && !force {
                // Checks are turned off: nothing to wait for.
                await allowTraffic(nil)
                update { $0.checking = false }
                return
            }
            let report: ReputationReport
            do {
                let ip = try await exitIp()
                if Task.isCancelled { return }
                if ip != cleanIp { await blockTraffic() } // new IP, or it changed under us (provider ended the session)
                update { $0.exitIp = ip }
                report = await check(ip, force: force)
            } catch {
                if Task.isCancelled { return }
                // The health loop tries again.
                update { $0.checking = false; $0.message = "Couldn't check the IP (\(error.localizedDescription)) — traffic blocked, retrying" }
                return
            }
            if Task.isCancelled { return }
            update { $0.report = report; $0.checking = false }
            if report.verdict == .UNKNOWN {
                update { $0.message = "No reputation source answered — traffic blocked, retrying" }
                return
            }
            if report.verdict.severity < s.rotateThreshold.severity {
                await allowTraffic(report.ip)
                update { $0.message = nil }
                if attempt > 0 { notify("Found \(report.verdict.word) IP \(report.ip) after \(attempt) rotation(s)") }
                return
            }
            await blockTraffic()
            if !s.autoRotate || !p.sticky {
                waitingForUser = true
                update { $0.message = "IP is \(report.verdict.word) — traffic blocked. Tap New IP." }
                return
            }
            // No limit: keep rotating until an IP passes. After the first quick tries, pace them (20 s).
            if attempt >= 10 {
                if attempt == 10 { notify("No clean IP after \(attempt) tries — traffic blocked, still trying") }
                update { $0.message = "No clean IP after \(attempt) tries — traffic blocked, trying again shortly" }
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                if Task.isCancelled { return }
            }
            attempt += 1
            await switchSession(Presets.newSessionId())
        }
    }

    private func exitIp() async throws -> String {
        var last: Error = UserError("no answer")
        for url in Reputation.ipEcho {
            do {
                let r = try await Bridge.fetch(HTTPRequest(url: url), proxy: true)
                guard r.ok else { throw UserError("HTTP \(r.status)") }
                let ip = Reputation.parseEcho(r.body)
                if !ip.isEmpty { return ip }
            } catch {
                last = error
            }
        }
        throw UserError("Could not determine exit IP: \(last.localizedDescription)")
    }

    private func check(_ ip: String, force: Bool) async -> ReputationReport {
        if !force, let c = reportCache[ip], nowMs() - c.checkedAt < Reputation.reportCacheMs { return c }
        let keys = store.apiKeys()
        let results = await withTaskGroup(of: SourceResult.self) { group -> [SourceResult] in
            for src in Reputation.sourceRequests(ip: ip, keys: keys) {
                group.addTask { await Self.runSource(src) }
            }
            for (id, zone) in Dnsbl.zones {
                group.addTask {
                    guard let name = Dnsbl.name(ip, zone: zone) else { return .failed(id, "IPv6 not supported") }
                    do { return Dnsbl.interpret(id, try await Bridge.lookupA(name)) } catch { return .failed(id, error.localizedDescription) }
                }
            }
            var out: [SourceResult] = []
            for await r in group { out.append(r) }
            return out
        }
        let order = SourceId.allCases
        let sorted = results.sorted { order.firstIndex(of: $0.source)! < order.firstIndex(of: $1.source)! }
        let report = Reputation.aggregate(ip: ip, results: sorted)
        reportCache[ip] = report
        return report
    }

    /// Direct first (saves proxy traffic); if that fails (e.g. a filtering DNS sinkholes the API host), via the proxy.
    nonisolated static func runSource(_ src: Reputation.SourceRequest) async -> SourceResult {
        var h = src.headers
        if h["Accept"] == nil { h["Accept"] = "application/json" }
        let req = HTTPRequest(url: src.url, headers: h)
        func body(_ r: HTTPResponse) throws -> String {
            // Error bodies are parsed only when they're JSON (API error objects), not proxy/HTML errors.
            if !r.ok && !r.body.trimmingCharacters(in: .whitespaces).hasPrefix("{") { throw UserError("HTTP \(r.status)") }
            return r.body
        }
        do {
            let text: String
            do { text = try body(try await Bridge.fetch(req)) } catch { text = try body(try await Bridge.fetch(req, proxy: true)) }
            return try src.parse(text)
        } catch {
            return .failed(src.id, error.localizedDescription)
        }
    }

    private func notify(_ text: String) {
        let c = UNMutableNotificationContent()
        c.title = "IP reputation"
        c.body = text
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "rotation", content: c, trigger: nil))
    }

    // MARK: app messages

    func handle(_ msg: TunnelMessage) async -> TunnelReply {
        switch msg.cmd {
        case .status: break
        case .rotate: rotate()
        case .applyLocation: applyLocation()
        case .recheck: recheck()
        case .settingsChanged: settingsChanged()
        case .ping: return TunnelReply(status: status, pings: await Bridge.ping(msg.targets ?? []))
        }
        return TunnelReply(status: status)
    }
}

/// Physical footprint (what jetsam counts; network extensions are killed at about 50 MB).
func memoryFootprintMB() -> Int {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
    }
    return kr == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
}
