// App state: the VPN configuration (NETunnelProviderManager), the tunnel's live status, and the actions the screens
// call. Settings and secrets live in SharedStore (App Group + Keychain), which the tunnel reads too.

import Foundation
import NetworkExtension
import ResProxyCore
import SwiftUI
import UserNotifications

let tunnelBundleId = (Bundle.main.bundleIdentifier ?? "dev.resproxy") + ".tunnel"

/// URLSession-based fetcher for the app's own API calls (Mullvad, location catalogs). While the tunnel is up these go
/// through it like every other app's traffic.
let appFetch: Fetcher = { r in
    guard let url = URL(string: r.url) else { throw UserError("Bad URL") }
    var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
    req.httpMethod = r.method
    r.headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
    req.httpBody = r.body?.data(using: .utf8)
    let (data, resp) = try await URLSession.shared.data(for: req)
    return HTTPResponse(status: (resp as? HTTPURLResponse)?.statusCode ?? 0, body: String(decoding: data, as: UTF8.self))
}

@MainActor
final class AppModel: ObservableObject {
    let store = SharedStore.shared

    @Published var settings: AppSettings
    @Published var accounts: [Account]
    @Published var apiKeys: ApiKeys
    @Published var vpnProfiles: [VpnProfile]
    @Published var mullvad: MullvadAccount?
    @Published var status = TunnelStatus()
    @Published var vpnState: NEVPNStatus = .invalid
    @Published var error: String?
    @Published var relays: [MullvadRelay]
    @Published var pings: [String: Int] = [:]
    @Published var catalogs: [Provider: LocationCatalog] = [:]

    private var manager: NETunnelProviderManager?
    private var poller: Task<Void, Never>?
    private var observer: NSObjectProtocol?

    init() {
        settings = store.settings()
        accounts = store.accounts()
        apiKeys = store.apiKeys()
        vpnProfiles = store.vpnProfiles()
        mullvad = store.mullvad()
        relays = store.cachedRelays()
        for p in [Provider.IPROYAL, .GEONODE] { catalogs[p] = store.cachedCatalog(p) ?? Locations.builtIn() }
        #if DEBUG
        if ProcessInfo.processInfo.environment["RP_DEMO"] != nil { loadDemo(); return }
        #endif
        Task { await loadManager() }
    }

    #if DEBUG
    /// Simulator screenshots: sample account and a connected, checked status, in memory only (nothing is saved).
    var demo = false
    private func loadDemo() {
        demo = true
        accounts = [Account(provider: .GEONODE, username: "geonode_demo", password: "x")]
        settings.provider = .GEONODE
        settings.location = Location(country: "FR", countryName: "France", city: "paris", cityName: "Paris")
        settings.chainEnabled = true
        settings.vpnExit = VpnExit.mullvad(code: "bg-sof-wg-003").stringValue
        mullvad = MullvadAccount(number: "1234567890123456", expiry: "2027-01-31T00:00:00Z", device: nil, signedOut: false)
        vpnState = .connected
        var st = TunnelStatus(phase: .CONNECTED)
        st.exitIp = "78.242.143.169"; st.trafficBlocked = false; st.chainName = "Mullvad Sofia · bg-sof-wg-003"
        st.downBps = 182_000; st.upBps = 12_400; st.sessionDown = 48_300_000; st.sessionUp = 3_100_000
        st.vpnPingMs = 41; st.proxyPingMs = 118; st.repairs = 1
        var geo = SourceResult(source: .IP_API, ok: true)
        geo.country = "France"; geo.countryCode = "FR"; geo.region = "Île-de-France"; geo.city = "Paris"
        geo.isp = "Free SAS"; geo.asn = "AS12322"; geo.connectionType = "residential"
        var pc = SourceResult(source: .PROXYCHECK, ok: true); pc.proxy = true; pc.score = 0
        var bad = SourceResult(source: .IPQS, ok: false); bad.error = "insufficient credits"
        st.report = Reputation.aggregate(ip: "78.242.143.169", results: [geo, pc, bad, SourceResult(source: .DNSBL_SPAMCOP, ok: true)])
        status = st
    }
    #endif

    var hasAccount: Bool { accounts.contains { !$0.username.trimmingCharacters(in: .whitespaces).isEmpty } }
    func account(_ p: Provider) -> Account { accounts.first { $0.provider == p } ?? Account(provider: p) }

    // MARK: VPN manager

    func loadManager() async {
        let list = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = list.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == tunnelBundleId } ?? list.first
        if let m = manager {
            vpnState = m.connection.status
            observe(m)
        }
        refreshStatus()
    }

    private func observe(_ m: NETunnelProviderManager) {
        if let o = observer { NotificationCenter.default.removeObserver(o) }
        observer = NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: m.connection, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.vpnState = m.connection.status
                self.refreshStatus()
            }
        }
    }

    /// Creates or updates the VPN configuration from the current settings.
    @discardableResult
    func saveManager(onDemand: Bool? = nil) async throws -> NETunnelProviderManager {
        let m = manager ?? NETunnelProviderManager()
        let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = tunnelBundleId
        proto.serverAddress = "ResProxy"
        // Kill switch: with includeAllNetworks iOS sends nothing outside the tunnel, even while it (re)connects.
        proto.includeAllNetworks = settings.killSwitch
        proto.excludeLocalNetworks = settings.bypassLan
        proto.disconnectOnSleep = false
        m.protocolConfiguration = proto
        m.localizedDescription = "ResProxy"
        m.isEnabled = true
        let wantOnDemand = onDemand ?? (settings.autoConnect && m.isOnDemandEnabled)
        let rule = NEOnDemandRuleConnect()
        rule.interfaceTypeMatch = .any
        m.onDemandRules = [rule]
        m.isOnDemandEnabled = wantOnDemand
        try await m.saveToPreferences()
        try await m.loadFromPreferences()
        if manager == nil { observe(m) }
        manager = m
        return m
    }

    var isActive: Bool { [.connected, .connecting, .reasserting].contains(vpnState) }

    func connect() async {
        error = nil
        guard hasAccount, !account(settings.provider).username.isEmpty else {
            error = "Add your \(settings.provider.label) account first (Settings → Proxy accounts)"
            return
        }
        do {
            let m = try await saveManager(onDemand: settings.autoConnect)
            try m.connection.startVPNTunnel()
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) { _, _ in }
            startPolling()
        } catch {
            self.error = "Couldn't start the VPN: \(error.localizedDescription)"
        }
    }

    func disconnect() async {
        updateSettings { $0.wasConnected = false }
        guard let m = manager else { return }
        // On-demand would bring it right back.
        if m.isOnDemandEnabled {
            m.isOnDemandEnabled = false
            try? await m.saveToPreferences()
        }
        m.connection.stopVPNTunnel()
    }

    // MARK: tunnel messages

    func send(_ msg: TunnelMessage) async -> TunnelReply? {
        guard let session = manager?.connection as? NETunnelProviderSession, isActive,
              let data = try? JSONEncoder().encode(msg) else { return nil }
        return await withCheckedContinuation { cont in
            do {
                try session.sendProviderMessage(data) { reply in
                    cont.resume(returning: reply.flatMap { try? JSONDecoder().decode(TunnelReply.self, from: $0) })
                }
            } catch {
                cont.resume(returning: nil)
            }
        }
    }

    #if DEBUG
    private var lastMirror = Date.distantPast
    #endif

    /// Debug builds copy the App Group's log and state into the app's own container, where
    /// `xcrun devicectl device copy from --domain-type appDataContainer` can read them (it can't read the group).
    func mirrorDiagnostics() {
        #if DEBUG
        guard Date().timeIntervalSince(lastMirror) > 3 else { return }
        lastMirror = Date()
        let fm = FileManager.default
        let dst = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("diag")
        try? fm.createDirectory(at: dst, withIntermediateDirectories: true)
        for name in ["diag.log", "status.json", "settings.json"] {
            let to = dst.appendingPathComponent(name)
            try? fm.removeItem(at: to)
            try? fm.copyItem(at: store.dir.appendingPathComponent(name), to: to)
        }
        #endif
    }

    func refreshStatus() {
        #if DEBUG
        if demo { return }
        #endif
        mirrorDiagnostics()
        Task {
            if isActive, let r = await send(TunnelMessage(.status)), let s = r.status {
                status = s
            } else if !isActive {
                // Show why it stopped (e.g. an error the tunnel saved), otherwise disconnected.
                let last = store.lastStatus()
                status = last?.phase == .ERROR ? last! : TunnelStatus()
            }
        }
    }

    func startPolling() {
        poller?.cancel()
        poller = Task {
            while !Task.isCancelled {
                refreshStatus()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    func stopPolling() { poller?.cancel(); poller = nil }

    func rotate() { Task { _ = await send(TunnelMessage(.rotate)); refreshStatus() } }
    func recheck() { Task { _ = await send(TunnelMessage(.recheck)); refreshStatus() } }

    // MARK: settings

    func updateSettings(_ f: (inout AppSettings) -> Void) {
        let before = settings
        settings = store.updateSettings(f)
        // Kill switch / LAN bypass / auto-connect live on the VPN configuration too. Saving it can restart a running
        // tunnel, so only when one of those changed.
        if manager != nil && (before.killSwitch != settings.killSwitch || before.bypassLan != settings.bypassLan || before.autoConnect != settings.autoConnect) {
            let onDemand = settings.autoConnect && isActive
            Task { try? await saveManager(onDemand: onDemand) }
        }
    }

    func setProvider(_ p: Provider) {
        updateSettings { $0 = $0.switchingProvider(to: p) }
        Task { _ = await send(TunnelMessage(.applyLocation)) }
    }

    func setLocation(_ l: Location) {
        updateSettings {
            $0.location = l
            $0 = $0.restoreSession()
        }
        Task { _ = await send(TunnelMessage(.applyLocation)) }
    }

    func saveAccount(_ a: Account) {
        store.saveAccount(a)
        accounts = store.accounts()
        Task { await refreshCatalog(a.provider, force: false) }
    }

    func saveApiKeys(_ k: ApiKeys) {
        store.saveApiKeys(k)
        apiKeys = k
    }

    func saveVpnProfiles(_ list: [VpnProfile]) {
        store.saveVpnProfiles(list)
        vpnProfiles = list
    }

    // MARK: locations

    func refreshCatalog(_ p: Provider, force: Bool) async {
        guard p != .GENERIC else { return }
        let acc = account(p)
        guard Locations.canFetch(p, acc) else { return }
        if !force, let c = catalogs[p], c.source != .BUILT_IN, nowMs() - c.fetchedAt < Locations.maxAgeMs { return }
        do {
            let r = try await appFetch(Locations.request(p, acc))
            if r.status == 401 || r.status == 403 { throw UserError(Locations.authError(p, acc)) }
            guard r.ok else { throw UserError("HTTP \(r.status)") }
            let list = try Locations.parse(p, r.body)
            guard !list.isEmpty else { throw UserError("Empty location list") }
            let c = LocationCatalog(countries: list, source: .LIVE, fetchedAt: nowMs())
            catalogs[p] = c
            store.saveCatalog(p, c)
        } catch {
            var c = catalogs[p] ?? Locations.builtIn()
            c.error = error.localizedDescription
            catalogs[p] = c
        }
    }

    // MARK: Mullvad

    var mullvadClient: MullvadClient { MullvadClient(fetch: appFetch) }

    func refreshRelays() async {
        if let r = try? await mullvadClient.relays() {
            relays = r
            store.saveRelays(r)
        }
    }

    func mullvadLogin(_ number: String) async throws {
        let n = Mullvad.normalize(number)
        guard n.count >= 10 else { throw UserError("Enter your 16-digit Mullvad account number") }
        let cur = store.mullvad()
        let acc = try await mullvadClient.login(n, existing: cur?.number == n ? cur?.device : nil)
        store.saveMullvad(acc)
        mullvad = acc
    }

    /// Signs out on this install only: forgets its device key; the device stays on the account for anyone sharing it.
    func mullvadSignOutHere() {
        guard var m = store.mullvad() else { return }
        m.device = nil
        m.signedOut = true
        store.saveMullvad(m)
        mullvad = m
    }

    func mullvadRemoveAccount() {
        store.saveMullvad(nil)
        mullvad = nil
    }

    /// Detects a device removed on mullvad.net or by another install: this install is then signed out.
    func verifyMullvadDevice() async {
        guard let acc = store.mullvad(), let d = acc.device else { return }
        guard let ok = try? await mullvadClient.isDeviceRegistered(acc.number, d.id), !ok else { return }
        mullvadSignOutHere()
    }

    // MARK: backup

    func exportBackup(passphrase: String, shareDevice: Bool) throws -> String {
        try Backup.seal(store.exportPayload(shareMullvadDevice: shareDevice), passphrase: passphrase)
    }

    /// Replaces accounts, settings, API keys and VPN profiles (shared/FORMAT.md import rules).
    func importBackup(_ text: String, passphrase: String) async throws {
        var b = try Backup.open(text, passphrase: passphrase)
        b.migrateLegacyChain()
        store.saveVpnProfiles(b.vpnProfiles)
        // Keep this install's Mullvad device when the number is unchanged; a new number logs in again on connect.
        let n = Mullvad.normalize(b.mullvadNumber)
        let cur = store.mullvad()
        if n.isEmpty { store.saveMullvad(nil) } else if cur?.number != n { store.saveMullvad(MullvadAccount(number: n)) }
        if let d = b.mullvadDevice, !n.isEmpty, !d.privateKey.isEmpty {
            // Uses the shared device; this install's own different device is removed from the account (frees a slot).
            let mine = store.mullvad().flatMap { $0.number == n ? $0 : nil }
            if let old = mine?.device, old.id != d.id { try? await mullvadClient.removeDevice(n, old.id) }
            store.saveMullvad(MullvadAccount(number: n, expiry: mine?.expiry ?? "", device: d))
        }
        store.saveApiKeys(b.apiKeys)
        _ = store.updateSettings { s in
            var x = b.settings
            x.wasConnected = false
            x.sessionId = ""
            s = x
        }
        store.saveAccounts(b.accounts)
        reload()
        for a in b.accounts { Task { await refreshCatalog(a.provider, force: true) } }
    }

    func reload() {
        #if DEBUG
        if demo { return }
        #endif
        settings = store.settings()
        accounts = store.accounts()
        apiKeys = store.apiKeys()
        vpnProfiles = store.vpnProfiles()
        mullvad = store.mullvad()
    }

    // MARK: latency (VPN list sorting)

    func measurePings() async {
        var targets = Set<String>()
        for r in relays { targets.insert(r.ipv4) }
        for p in vpnProfiles {
            if let c = try? WireGuardConfig.parse(p.config), let h = c.peers.first?.endpointHost, isIPv4(h) { targets.insert(h) }
        }
        guard !targets.isEmpty else { return }
        // While connected, the app's own pings would go through the tunnel: ask the tunnel (it sits outside).
        if isActive, let r = await send(TunnelMessage(.ping, targets: Array(targets))), let p = r.pings {
            pings = p
        } else if !isActive {
            pings = await Pinger.pingAll(Array(targets))
        }
    }
}
