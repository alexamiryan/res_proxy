// Home: same arrangement as android/.../ui/HomeScreen.kt — power button, status, provider, location card, VPN card,
// then while connected the connection and reputation cards and New IP / Re-check.

import ResProxyCore
import SwiftUI

enum PlaceKind: String, Identifiable { case country, state, city; var id: String { rawValue } }

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @State private var picking: PlaceKind?
    @State private var showVpn = false
    @State private var accountFor: Provider?
    @State private var showAccount = false

    private var s: AppSettings { model.settings }
    private var st: TunnelStatus { model.status }

    /// Tunnel phase, with the system's own VPN state filling in before the tunnel answers.
    private var phase: Phase {
        switch model.vpnState {
        case .connecting, .reasserting: return st.phase == .DISCONNECTED ? .CONNECTING : st.phase
        case .disconnecting, .disconnected, .invalid: return st.phase == .ERROR ? .ERROR : .DISCONNECTED
        default: return st.phase
        }
    }
    private var active: Bool { phase != .DISCONNECTED && phase != .ERROR }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(spacing: 0) {
                Spacer().frame(height: 16)
                PowerButton(color: stateColor, busy: phase == .CONNECTING || phase == .ROTATING) { tapPower() }
                Spacer().frame(height: 16)
                Text(phaseLabel).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                if let m = st.message ?? model.error {
                    Text(m).font(.subheadline).multilineTextAlignment(.center)
                        .foregroundStyle(phase == .CONNECTED && model.error == nil ? Color.accentColor : Brand.down)
                        .padding(.top, 4)
                }
                Spacer().frame(height: 20)

                if !model.hasAccount {
                    WelcomeCard { open(account: $0) }
                } else {
                    VStack(spacing: 12) {
                        Picker("Provider", selection: Binding(get: { s.provider }, set: { switchProvider($0) })) {
                            ForEach([Provider.IPROYAL, .GEONODE]) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        locationCard
                        vpnCard
                        if active {
                            ConnectionCard(status: st)
                            ReputationCard(status: st)
                            HStack(spacing: 12) {
                                Button { model.rotate() } label: { Label("New IP", systemImage: "shuffle").frame(maxWidth: .infinity) }
                                    .disabled(!(phase == .CONNECTED || phase == .PROXY_DOWN) || !s.connection.sticky)
                                Button { model.recheck() } label: { Label("Re-check", systemImage: "arrow.clockwise").frame(maxWidth: .infinity) }
                                    .disabled(st.checking || phase != .CONNECTED)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.large)
                        }
                    }
                }
                Spacer().frame(height: 24).id("bottom")
            }
            .padding(.horizontal, 16)
            .animation(.default, value: active)
        }
        #if DEBUG
        .onAppear { if ProcessInfo.processInfo.environment["RP_SCROLL"] != nil { proxy.scrollTo("bottom", anchor: .bottom) } }
        #endif
        }
        .groupedBackground()
        .navigationTitle("ResProxy")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
            }
        }
        .navigationDestination(isPresented: $showAccount) { AccountView(provider: accountFor ?? s.provider) }
        .sheet(item: $picking) { kind in placePicker(kind) }
        .sheet(isPresented: $showVpn) { NavigationStack { VpnPicker() } }
        .refreshable { model.refreshStatus() }
        .onAppear { model.startPolling() }
        .task { await model.refreshCatalog(s.provider, force: false) }
    }

    // MARK: power

    private var stateColor: Color {
        switch phase {
        case .CONNECTED: return st.trafficBlocked ? Brand.pending : Brand.connected
        case .CONNECTING, .ROTATING: return Brand.pending
        case .PROXY_DOWN, .ERROR: return Brand.down
        case .DISCONNECTED: return Brand.idle
        }
    }

    private var phaseLabel: String {
        switch phase {
        case .DISCONNECTED: return "Not connected"
        case .CONNECTING: return "Connecting…"
        case .CONNECTED: return !st.trafficBlocked ? "Connected" : st.checking ? "Checking IP · traffic blocked" : "Traffic blocked"
        case .ROTATING: return "Getting a new IP…"
        case .PROXY_DOWN: return "Proxy down · traffic blocked"
        case .ERROR: return "Connection failed"
        }
    }

    private func tapPower() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        if active {
            Task { await model.disconnect() }
        } else if model.account(s.provider).username.trimmingCharacters(in: .whitespaces).isEmpty {
            open(account: s.provider)
        } else {
            Task { await model.connect() }
        }
    }

    private func open(account p: Provider) { accountFor = p; showAccount = true }

    private func switchProvider(_ p: Provider) {
        if model.account(p).username.trimmingCharacters(in: .whitespaces).isEmpty {
            if active { Task { await model.disconnect() } }
            model.setProvider(p)
            open(account: p)
        } else {
            model.setProvider(p)
            Task { await model.refreshCatalog(p, force: false) }
        }
    }

    // MARK: location

    private var catalog: LocationCatalog { model.catalogs[s.provider] ?? Locations.builtIn() }
    private var country: Place? { catalog.countries.first { $0.code == s.location.country } }
    private var stateOptions: [Place] { country?.states ?? [] }
    /// Geonode without its location API: any state can be typed.
    private var freeStates: Bool { s.provider == .GEONODE && catalog.source == .BUILT_IN }
    private var cityOptions: [Place] {
        if !s.location.state.isEmpty, let st = stateOptions.first(where: { $0.code == s.location.state }) { return st.cities ?? [] }
        // No state chosen: every city in the country.
        var seen = Set<String>()
        return ((country?.cities ?? []) + stateOptions.flatMap { $0.cities ?? [] })
            .filter { seen.insert($0.code).inserted }
            .sorted { $0.name < $1.name }
    }

    private var locationCard: some View {
        let l = s.location
        let showState = !l.country.isEmpty && (!stateOptions.isEmpty || !l.state.isEmpty || freeStates)
        return Card(spacing: 0) {
            CardLabel("Location").padding(.bottom, 2)
            PickerRow(label: "Country", value: l.country.isEmpty ? "Any country" : flagName(l.country, l.countryName.isEmpty ? Locations.countryName(l.country) : l.countryName)) { picking = .country }
            if showState {
                Divider()
                PickerRow(label: "State", value: l.state.isEmpty ? "Any state" : l.stateName) { picking = .state }
            }
            if !l.country.isEmpty {
                Divider()
                PickerRow(label: "City", value: l.city.isEmpty ? "Any city" : l.cityName) { picking = .city }
            }
            if let hint = locationHint {
                Text(hint).font(.caption).foregroundStyle(catalog.error != nil ? Brand.down : .secondary).padding(.top, 6)
            }
        }
    }

    private var locationHint: String? {
        if let e = catalog.error { return e }
        let a = model.account(s.provider)
        if a.username.isEmpty { return "Set up your \(s.provider.label) account to connect." }
        if s.provider == .IPROYAL && a.apiToken.isEmpty {
            return "Add your IPRoyal API token in Settings → Proxy accounts to choose from the list of available cities."
        }
        if s.provider == .GEONODE && catalog.source == .BUILT_IN && a.apiLogin.isEmpty {
            return "Add your Geonode login in Settings → Proxy accounts to choose from the list of states and cities."
        }
        return nil
    }

    @ViewBuilder private func placePicker(_ kind: PlaceKind) -> some View {
        let l = s.location
        switch kind {
        case .country:
            PlacePicker(title: "Country", any: "Any country", options: catalog.countries, showFlags: true, allowCustom: false) { p in
                model.setLocation(p.map { Location(country: $0.code, countryName: $0.name) } ?? Location())
            }
        case .state:
            PlacePicker(title: "State", any: "Any state", options: stateOptions, showFlags: false, allowCustom: freeStates) { p in
                var n = Location(country: l.country, countryName: l.countryName)
                if let p { n.state = p.code; n.stateName = p.name }
                model.setLocation(n)
            }
        case .city:
            PlacePicker(title: "City", any: "Any city", options: cityOptions, showFlags: false, allowCustom: true) { p in
                var n = l
                n.city = p?.code ?? ""; n.cityName = p?.name ?? ""
                model.setLocation(n)
            }
        }
    }

    // MARK: VPN

    private var vpnCard: some View {
        Card(spacing: 0) {
            PickerRow(label: "VPN before proxy", value: vpnLabel) { showVpn = true }
            if s.chainEnabled, case .mullvad = VpnExit.parse(s.vpnExit), let m = model.mullvad {
                Text(m.device.map { "Mullvad device: \($0.name)" } ?? (m.signedOut ? "Signed out of Mullvad — log in in Settings → VPN" : "Mullvad: signs in on first connect"))
                    .font(.caption).foregroundStyle(.secondary).padding(.bottom, 4)
            }
        }
    }

    private var vpnLabel: String {
        guard s.chainEnabled else { return "Off" }
        switch VpnExit.parse(s.vpnExit) {
        case .imported(let id): return model.vpnProfiles.first { $0.id == id }?.name ?? "Removed profile"
        case .mullvad(let code): return "Mullvad · " + Mullvad.relayLabel(code, model.relays)
        case nil: return "Not chosen"
        }
    }
}

/// 200 pt ring + 150 pt disc with the power glyph; colour animates with the state, spinner while connecting.
struct PowerButton: View {
    let color: Color
    let busy: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(color.opacity(0.15)).frame(width: 200, height: 200)
                Circle().fill(color).frame(width: 150, height: 150)
                    .shadow(color: color.opacity(0.35), radius: 10, y: 4)
                Image(systemName: "power").font(.system(size: 64, weight: .semibold)).foregroundStyle(.white)
                if busy {
                    ProgressView().progressViewStyle(SpinningRing(color: color)).frame(width: 200, height: 200)
                }
            }
        }
        .buttonStyle(.plain)
        .animation(.easeInOut(duration: 0.3), value: color)
        .accessibilityLabel("Connect")
    }
}

/// Indeterminate ring drawn over the power button's outer circle.
struct SpinningRing: ProgressViewStyle {
    let color: Color
    @State private var spin = false
    func makeBody(configuration: Configuration) -> some View {
        Circle().trim(from: 0, to: 0.28)
            .stroke(color, style: StrokeStyle(lineWidth: 4, lineCap: .round))
            .rotationEffect(.degrees(spin ? 360 : 0))
            .animation(.linear(duration: 1).repeatForever(autoreverses: false), value: spin)
            .onAppear { spin = true }
    }
}

/// First run, inline on Home (Android's WelcomeCard).
struct WelcomeCard: View {
    let setUp: (Provider) -> Void
    var body: some View {
        Card(spacing: 12) {
            Text("Get started").font(.headline)
            Text("Add your residential proxy account:").font(.subheadline)
            HStack(spacing: 8) {
                ForEach([Provider.IPROYAL, .GEONODE]) { p in
                    Button { setUp(p) } label: { Text("Set up \(p.label)").frame(maxWidth: .infinity) }
                }
            }
            .buttonStyle(.bordered).controlSize(.large)
            Divider()
            Text("Or import a backup from another phone or browser:").font(.subheadline)
            NavigationLink { BackupView(startWithImport: true) } label: { Text("Import backup").frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).controlSize(.large)
        }
    }
}

struct ConnectionCard: View {
    let status: TunnelStatus
    var body: some View {
        let st = status
        Card(spacing: 4) {
            CardLabel("Exit IP")
            Text(st.exitIp ?? (st.checking ? "Detecting…" : "—"))
                .font(.title2.monospaced().weight(.medium)).textSelection(.enabled)
            if let g = st.report?.geo {
                let place = [g.city, g.region, g.country].compactMap { $0 }.filter { !$0.isEmpty }
                Text("\(Locations.flag(g.countryCode))  \(NSOrderedSet(array: place).array.map { "\($0)" }.joined(separator: ", "))").font(.subheadline)
                let net = [g.isp, g.asn, g.connectionType].compactMap { $0 }.filter { !$0.isEmpty }
                if !net.isEmpty { Text(net.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
            }
            if let c = st.chainName { Text("Through VPN: \(c)").font(.caption).foregroundStyle(Color.accentColor) }
            Divider().padding(.vertical, 8)
            HStack {
                Stat(label: "↓ Down", value: "\(formatBytes(st.downBps))/s")
                Spacer()
                Stat(label: "↑ Up", value: "\(formatBytes(st.upBps))/s")
                Spacer()
                Stat(label: "Session", value: formatBytes(st.sessionDown + st.sessionUp))
            }
            HStack {
                if st.chainName != nil { Stat(label: "Ping to VPN", value: st.vpnPingMs.map { "\($0) ms" } ?? "—"); Spacer() }
                Stat(label: st.chainName != nil ? "Ping to proxy (via VPN)" : "Ping to proxy", value: st.proxyPingMs.map { "\($0) ms" } ?? "—")
                if st.repairs > 0 { Spacer(); Stat(label: "Auto-repairs", value: "\(st.repairs)") }
                if st.chainName == nil && st.repairs == 0 { Spacer() }
            }
            .padding(.top, 6)
        }
    }
}

struct Stat: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.subheadline.weight(.medium)).monospacedDigit()
        }
    }
}

struct ReputationCard: View {
    let status: TunnelStatus
    @State private var expanded = false

    var body: some View {
        Card(spacing: 8) {
            HStack {
                CardLabel("IP reputation")
                Spacer()
                if status.checking {
                    if status.rotationAttempt > 0 { Text("rotation \(status.rotationAttempt)").font(.caption2).foregroundStyle(.secondary) }
                    ProgressView().controlSize(.small)
                }
            }
            if let r = status.report {
                VerdictBadge(verdict: r.verdict)
                if r.reasons.isEmpty && r.verdict == .CLEAN {
                    Text("No abuse reports, blacklist hits or datacenter flags.").font(.subheadline)
                }
                ForEach(Array(r.reasons.enumerated()), id: \.offset) { _, reason in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle().fill(reason.verdict.color).frame(width: 8, height: 8)
                        Text(reason.text).font(.subheadline)
                    }
                }
                if r.proxyFlags > 0 {
                    Text("Detected as proxy by \(r.proxyFlags) source(s) — normal for residential proxies").font(.caption).foregroundStyle(.secondary)
                }
                Button { withAnimation { expanded.toggle() } } label: {
                    HStack {
                        Text("\(r.results.filter(\.ok).count)/\(r.results.count) sources answered").font(.footnote.weight(.medium))
                        Spacer()
                        Image(systemName: "chevron.down").rotationEffect(.degrees(expanded ? 180 : 0))
                    }
                    .foregroundStyle(.secondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
                if expanded {
                    ForEach(r.results, id: \.source) { res in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(res.source.label).font(.subheadline.weight(.medium))
                            Text(describe(res)).font(.caption).foregroundStyle(res.ok ? Color.secondary : Brand.down)
                        }
                    }
                }
            } else {
                Text(status.checking ? "Checking…" : "Not checked yet").font(.headline)
            }
        }
    }

    func describe(_ r: SourceResult) -> String {
        if !r.ok { return "Unavailable: \(r.error ?? "failed")" }
        var p: [String] = []
        if let l = r.listed { p.append(l ? "LISTED" : "not listed") }
        if let s = r.score { p.append("score \(s)") }
        if let x = r.riskLabel { p.append("risk \(x)") }
        if let n = r.reports, n > 0 { p.append("\(n) reports") }
        if r.proxy == true { p.append("proxy") }
        if r.vpn == true { p.append("VPN") }
        if r.tor == true { p.append("Tor") }
        if r.datacenter == true { p.append("datacenter") }
        if r.abuser == true { p.append("abuser") }
        if r.recentAbuse == true { p.append("recent abuse") }
        if let t = r.connectionType { p.append(t) }
        if let n = r.note { p.append(n) }
        return p.isEmpty ? "no flags" : p.joined(separator: " · ")
    }
}
