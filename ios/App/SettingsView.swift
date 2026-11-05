import ResProxyCore
import SwiftUI
import UniformTypeIdentifiers

/// Settings hub, arranged like android/.../ui/SettingsScreen.kt: proxy accounts, then one row per page with an icon
/// and a live summary.
struct SettingsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let s = model.settings
        List {
            Section("Proxy accounts") {
                ForEach([Provider.IPROYAL, .GEONODE]) { p in
                    let a = model.account(p)
                    NavigationLink {
                        AccountView(provider: p)
                    } label: {
                        SettingLabel(title: p.label + (p == s.provider ? " · in use" : ""),
                                     subtitle: a.username.isEmpty ? "Not set up" : Presets.baseUsername(p, a.username),
                                     icon: "person.crop.circle.fill")
                    }
                }
            }
            Section {
                NavigationLink { VpnSettingsView() } label: { SettingLabel(title: "VPN", subtitle: vpnSummary, icon: "lock.shield.fill") }
                NavigationLink { ProtectionView() } label: {
                    SettingLabel(title: "Protection", subtitle: (s.killSwitch ? "Kill switch on" : "Kill switch off") + (s.bypassLan ? " · LAN bypassed" : ""), icon: "shield.fill")
                }
                NavigationLink { ReputationView() } label: {
                    SettingLabel(title: "IP reputation",
                                 subtitle: !s.autoRotate ? "Check only" : s.rotateThreshold == .SUSPICIOUS ? "Auto-rotate until clean" : "Auto-rotate until not bad",
                                 icon: "checkmark.shield.fill")
                }
                NavigationLink { BackupView() } label: {
                    SettingLabel(title: "Backup & transfer", subtitle: "Copy this setup to other phones or browsers", icon: "arrow.up.doc.fill")
                }
                NavigationLink { UsageView() } label: { SettingLabel(title: "Data usage", subtitle: "Traffic per provider", icon: "chart.pie.fill") }
                NavigationLink { AdvancedView() } label: {
                    SettingLabel(title: "Advanced", subtitle: "Protocol, session, gateway", icon: "slider.horizontal.3")
                }
                NavigationLink { DiagnosticsView() } label: {
                    SettingLabel(title: "Diagnostics", subtitle: "Tunnel log", icon: "waveform.path.ecg")
                }
            } footer: {
                Text("ResProxy \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                    .frame(maxWidth: .infinity).padding(.top, 12)
            }
        }
        .navigationTitle("Settings")
    }

    var vpnSummary: String {
        var parts: [String] = []
        if model.mullvad != nil { parts.append("Mullvad") }
        let n = model.vpnProfiles.count
        if n > 0 { parts.append("\(n) profile\(n == 1 ? "" : "s")") }
        return parts.isEmpty ? "Not set up" : parts.joined(separator: " · ")
    }
}

/// Binding into AppSettings that saves through the model.
@MainActor
func settingBinding<T>(_ model: AppModel, _ kp: WritableKeyPath<AppSettings, T>) -> Binding<T> {
    Binding(get: { model.settings[keyPath: kp] }, set: { v in model.updateSettings { $0[keyPath: kp] = v } })
}

/// Text field that keeps its own text while typing; optional masking with a show/hide toggle.
struct SecretField: View {
    let title: String
    @Binding var text: String
    var secure = false
    @State private var reveal = false
    var body: some View {
        HStack {
            if secure && !reveal { SecureField(title, text: $text) } else { TextField(title, text: $text) }
            if secure {
                Button { reveal.toggle() } label: { Image(systemName: reveal ? "eye.slash" : "eye") }
                    .buttonStyle(.borderless).accessibilityLabel("Show password")
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }
}

// MARK: account

/// Like Android's AccountScreen: intro, username/password (a pasted proxy line fills both), optional location-list
/// credentials, Save.
struct AccountView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let provider: Provider
    @State private var a = Account(provider: .IPROYAL)
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                Text(intro).font(.subheadline)
            }
            Section {
                TextField("Proxy username", text: $a.username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .onChange(of: a.username) { v in fillFromLine(v) }
                SecretField(title: "Proxy password", text: $a.password, secure: true)
            } footer: {
                if a.username.contains("@") {
                    Text("This looks like your website login email. The proxy username is a different, generated value.").foregroundStyle(Brand.down)
                }
            }
            Section {
                if provider == .IPROYAL {
                    SecretField(title: "API token", text: $a.apiToken, secure: true)
                } else {
                    SecretField(title: "Dashboard login (email)", text: $a.apiLogin)
                    SecretField(title: "Dashboard password", text: $a.apiPassword, secure: true)
                }
            } header: { Text("Location lists (optional)") } footer: {
                Text(provider == .IPROYAL
                     ? "IPRoyal only shares its list of available states and cities with an API token (dashboard → Settings → API). Without it you can still pick any country and US state, and type a city."
                     : "Geonode's API for the list of available states and cities uses your dashboard login (the email and password for app.geonode.com). Without it you can still pick any country and type a state or city.")
            }
            Section {
                Button {
                    model.saveAccount(a)
                    if model.settings.provider != provider && model.account(model.settings.provider).username.isEmpty { model.setProvider(provider) }
                    dismiss()
                } label: { Text("Save").frame(maxWidth: .infinity).fontWeight(.semibold) }
                .disabled(a.username.trimmingCharacters(in: .whitespaces).isEmpty || a.password.isEmpty)
            } footer: { Text("Stored encrypted on this device only.") }
        }
        .navigationTitle("\(provider.label) account")
        .onAppear { if !loaded { a = model.account(provider); loaded = true } }
    }

    var intro: String {
        (provider == .IPROYAL
            ? "Use the proxy username and password from your IPRoyal dashboard (Residential → Proxy access) — not your website login."
            : "Use the proxy username and password from your Geonode dashboard (Proxies → Residential), usually a username like geonode_abc123.")
            + " You can also paste the whole proxy line (host:port:username:password)."
    }

    /// A whole proxy line pasted into the username field fills username, password and gateway.
    func fillFromLine(_ v: String) {
        guard v.contains(":"), let line = Presets.parseProxyLine(v) else { return }
        a.username = line.username
        a.password = line.password
        if let h = line.host, let p = line.port {
            model.updateSettings {
                var gw = $0.connection.gateways
                gw[provider.rawValue] = Gateway(host: h, port: p)
                $0.connection.gateways = gw
                if let pr = line.protocol { $0.connection.protocol = pr }
            }
        }
    }
}

struct GeneratedCredentials: View {
    @EnvironmentObject var model: AppModel
    let account: Account
    var body: some View {
        let ep = Presets.build(Profile(account: account, settings: model.settings, sessionId: model.settings.sessionId.isEmpty ? "<session>" : model.settings.sessionId))
        VStack(alignment: .leading, spacing: 4) {
            Text("Generated credentials").font(.footnote.weight(.medium)).foregroundStyle(.secondary)
            Group {
                Text(verbatim: "\(ep.protocol.rawValue.lowercased())://\(ep.host):\(ep.port)")
                Text(verbatim: "user: \(ep.username)")
                Text(verbatim: "pass: \(String(repeating: "•", count: min(8, ep.password.count)))\(ep.password.split(separator: "_", maxSplits: 1).dropFirst().first.map { "_" + $0 } ?? "")")
            }
            .font(.caption.monospaced())
        }
        .textSelection(.enabled)
    }
}

// MARK: pages

struct ProtectionView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section {
                SwitchRow(title: "Kill switch", subtitle: "Block traffic while connecting, repairing or when the proxy is down. iOS sends nothing outside the VPN.",
                          isOn: settingBinding(model, \.killSwitch))
                SwitchRow(title: "Reconnect automatically", subtitle: "Restore the connection after a restart or network change",
                          isOn: settingBinding(model, \.autoConnect))
                SwitchRow(title: "Bypass for local network", subtitle: "Printers, casting, router pages and other LAN devices connect directly",
                          isOn: settingBinding(model, \.bypassLan))
            }
            Section {
                NumberField(title: "Proxy probe interval (s)", value: model.settings.healthIntervalSec, range: 10...3600) { v in
                    model.updateSettings { $0.healthIntervalSec = v }
                }
            } header: { Text("Connection health") } footer: {
                Text("ResProxy watches whether apps' connections actually get answers, the VPN handshake and the proxy. When something breaks while it still looks connected, it rebuilds the connection on its own; the kill switch keeps traffic blocked meanwhile.")
            }
        }
        .navigationTitle("Protection")
    }
}

struct ReputationView: View {
    @EnvironmentObject var model: AppModel
    @State private var k = ApiKeys()
    @State private var loaded = false
    @State private var save: Task<Void, Never>?

    var body: some View {
        Form {
            Section {
                SwitchRow(title: "Check on connect", subtitle: "Look up the exit IP every time you connect or change IP",
                          isOn: settingBinding(model, \.recheckOnConnect))
                SwitchRow(title: "Auto-rotate bad IPs", subtitle: "Switch to a new IP until the reputation is acceptable",
                          isOn: settingBinding(model, \.autoRotate))
                if model.settings.autoRotate {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Accept an IP only if it is").font(.subheadline)
                        Picker("Accept", selection: settingBinding(model, \.rotateThreshold)) {
                            Text("Clean").tag(Verdict.SUSPICIOUS)
                            Text("Not bad").tag(Verdict.BAD)
                        }
                        .pickerStyle(.segmented).labelsHidden()
                    }
                    .padding(.vertical, 4)
                }
            }
            Section {
                SecretField(title: "AbuseIPDB", text: $k.abuseIpDb, secure: true)
                SecretField(title: "IPQualityScore", text: $k.ipqs, secure: true)
                SecretField(title: "proxycheck.io", text: $k.proxycheck, secure: true)
                SecretField(title: "ipapi.is", text: $k.ipapiIs, secure: true)
                SecretField(title: "Scamalytics username", text: $k.scamalyticsUser)
                SecretField(title: "Scamalytics key", text: $k.scamalyticsKey, secure: true)
            } header: { Text("API keys") } footer: {
                Text("ipapi.is, proxycheck.io, ip-api.com and DNS blacklists work without keys. Keys raise limits and add AbuseIPDB, IPQualityScore and Scamalytics.\n\nSaved automatically. While connected, the IP is re-checked with the new keys.")
            }
        }
        .navigationTitle("IP reputation")
        .onAppear { if !loaded { k = model.apiKeys; loaded = true } }
        .onChange(of: k) { v in
            // Like Android: save after a short pause in typing, so the tunnel re-checks once.
            save?.cancel()
            save = Task { try? await Task.sleep(nanoseconds: 800_000_000); if !Task.isCancelled { model.saveApiKeys(v) } }
        }
        .onDisappear { if k != model.apiKeys { save?.cancel(); model.saveApiKeys(k) } }
    }
}

struct AdvancedView: View {
    @EnvironmentObject var model: AppModel
    @State private var host = ""
    @State private var port = ""

    var body: some View {
        let s = model.settings
        let p = s.provider
        Form {
            Section("Protocol") {
                Picker("Protocol", selection: settingBinding(model, \.connection.protocol)) {
                    Text("SOCKS5").tag(ProxyProtocol.SOCKS5)
                    Text("HTTP").tag(ProxyProtocol.HTTP)
                }
                .pickerStyle(.segmented).labelsHidden()
            }
            Section {
                SwitchRow(title: "Sticky IP", subtitle: "Keep the same IP until you tap New IP or the lifetime ends", isOn: settingBinding(model, \.connection.sticky))
                if s.connection.sticky {
                    NumberField(title: "Session lifetime (minutes)", value: s.connection.lifetimeMinutes, range: 1...MAX_LIFETIME_MINUTES) { v in
                        model.updateSettings { $0.connection.lifetimeMinutes = v }
                    }
                }
            } header: { Text("Session") } footer: {
                if s.connection.sticky { Text("Max: IPRoyal 7 days (10080), Geonode 24 h (1440, applied automatically).") }
            }
            Section("Gateway · \(p.label)") {
                if p == .GEONODE {
                    HStack(spacing: 8) {
                        chip("France", "")
                        chip("US", "us.proxy.geonode.io")
                        chip("Singapore", "sg.proxy.geonode.io")
                    }
                }
                LabeledContent("Host") {
                    TextField(Presets.defaultHost(p), text: $host).multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                LabeledContent("Port (0 = default " + String(Presets.defaultPort(p, s.connection.protocol, sticky: s.connection.sticky)) + ")") {
                    TextField("0", text: $port).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(maxWidth: 80)
                }
                if !model.account(p).username.isEmpty { GeneratedCredentials(account: model.account(p)) }
            }
            Section {
                Text("All TCP traffic and DNS go through the proxy. There is no UDP, so apps fall back to TCP.").font(.footnote)
            } header: { Text("Routing") } footer: { Text("Changes apply to a running connection automatically.") }
        }
        .navigationTitle("Advanced")
        .onAppear { loadGateway() }
        .onChange(of: model.settings.provider) { _ in loadGateway() }
        .onChange(of: host) { _ in saveGateway() }
        .onChange(of: port) { _ in saveGateway() }
    }

    func chip(_ label: String, _ h: String) -> some View {
        let on = host.trimmingCharacters(in: .whitespaces) == h
        return Button { host = h } label: {
            Text(label).font(.subheadline).padding(.horizontal, 12).padding(.vertical, 6)
                .background(on ? Color.accentColor.opacity(0.18) : Color(.tertiarySystemFill), in: Capsule())
                .foregroundStyle(on ? Color.accentColor : Color.primary)
        }
        .buttonStyle(.plain)
    }

    func loadGateway() {
        let g = model.settings.connection.gateway(model.settings.provider)
        host = g?.host ?? ""
        port = (g?.port ?? 0) > 0 ? "\(g!.port)" : ""
    }

    func saveGateway() {
        let p = model.settings.provider
        let digits = String(port.filter(\.isNumber).prefix(5))
        if digits != port { port = digits; return }
        let g = Gateway(host: host.trimmingCharacters(in: .whitespaces), port: Int(digits) ?? 0)
        guard model.settings.connection.gateway(p) ?? Gateway() != g else { return }
        model.updateSettings {
            var gw = $0.connection.gateways
            gw[p.rawValue] = g
            $0.connection.gateways = gw
        }
    }
}

struct UsageView: View {
    @EnvironmentObject var model: AppModel
    @State private var usage: [String: SharedStore.Usage] = [:]

    var body: some View {
        let rows = Provider.allCases.filter { !model.account($0).username.isEmpty || usage[$0.rawValue] != nil }
        Form {
            Section {
                if rows.isEmpty { Text("No traffic yet.").foregroundStyle(.secondary) }
                ForEach(rows) { p in
                    let u = usage[p.rawValue] ?? SharedStore.Usage()
                    HStack {
                        SettingLabel(title: p.label, subtitle: "\(formatBytes(u.down + u.up)) total · ↓ \(formatBytes(u.down)) ↑ \(formatBytes(u.up))")
                        Spacer()
                        Button("Reset") { model.store.resetUsage(p.rawValue); usage = model.store.usage() }.buttonStyle(.borderless)
                    }
                }
            } footer: { Text("Measured on this device; providers' billing may count protocol overhead differently.") }
        }
        .navigationTitle("Data usage")
        .onAppear { usage = model.store.usage() }
    }
}

/// The tunnel's log (connects, status changes, exit IPs, verdicts, repairs). No secrets in it.
struct DiagnosticsView: View {
    @EnvironmentObject var model: AppModel
    @State private var text = ""

    var body: some View {
        ScrollView {
            Text(text.isEmpty ? "Nothing logged yet." : text)
                .font(.system(.caption2, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle("Diagnostics")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button("Copy") { UIPasteboard.general.string = text }
                Button("Clear", role: .destructive) { model.store.clearLog(); text = "" }
            }
        }
        .task {
            while !Task.isCancelled {
                // Newest first.
                text = model.store.readLog().split(separator: "\n").suffix(500).reversed().joined(separator: "\n")
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }
}
