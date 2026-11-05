import ResProxyCore
import SwiftUI
import UniformTypeIdentifiers

/// Settings → VPN, arranged like android/.../ui/VpnSection.kt: intro, Mullvad account and devices, WireGuard profiles,
/// fastest servers.
struct VpnSettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var adding = false
    @State private var importing = false
    @State private var name = ""
    @State private var config = ""
    @State private var err: String?
    @State private var addErr: String?
    @State private var number = ""
    @State private var busy = false
    @State private var devices: [MullvadDeviceInfo]?
    @State private var devicesOpen = false
    @State private var devicesErr: String?
    @State private var confirmRemoveDevice: MullvadDeviceInfo?
    @State private var confirmSignOut = false
    @State private var confirmRemoveAccount = false
    @State private var deleteProfile: VpnProfile?
    @State private var testing = false
    @State private var testedAt: Date?
    @State private var showAll = false

    var body: some View {
        Form {
            Section {
                Text("Chain the proxy through a VPN: apps → ResProxy → VPN → proxy. Your network only sees VPN traffic and the proxy never sees your real IP. Pick the VPN server on the home screen. Keep the VPN provider's own app disconnected — iOS runs one VPN at a time, so ResProxy runs WireGuard itself.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            mullvadSection
            profilesSection
            if let e = err { Section { Text(e).font(.footnote).foregroundStyle(Brand.down) } }
            if !model.vpnProfiles.isEmpty || model.mullvad != nil { fastestSection }
        }
        .navigationTitle("VPN")
        .task { await loadDevices() }
        .sheet(isPresented: $adding) { addSheet }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .plainText, .text]) { r in
            if case .success(let url) = r {
                let ok = url.startAccessingSecurityScopedResource()
                defer { if ok { url.stopAccessingSecurityScopedResource() } }
                config = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
                name = url.deletingPathExtension().lastPathComponent
                addErr = nil
                adding = true
            }
        }
        .alert("Delete \(deleteProfile?.name ?? "")?", isPresented: Binding(get: { deleteProfile != nil }, set: { if !$0 { deleteProfile = nil } })) {
            Button("Delete", role: .destructive) {
                if let p = deleteProfile { model.saveVpnProfiles(model.vpnProfiles.filter { $0.id != p.id }) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Remove “\(confirmRemoveDevice?.name ?? "")”?", isPresented: Binding(get: { confirmRemoveDevice != nil }, set: { if !$0 { confirmRemoveDevice = nil } })) {
            Button("Remove", role: .destructive) { if let d = confirmRemoveDevice { Task { await removeDevice(d) } } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Frees a slot on your Mullvad account. Installs using this device (other phones or browsers) are signed out; they can log in again as a new device.") }
        .alert("Sign out on this iPhone?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { model.mullvadSignOutHere(); Task { await loadDevices() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Forgets the Mullvad device “\(model.mullvad?.device?.name ?? "")” here. It stays on your account, so other installs sharing it keep working. Log in again to register this iPhone as a new device.")
        }
        .alert("Remove Mullvad account?", isPresented: $confirmRemoveAccount) {
            Button("Remove", role: .destructive) { model.mullvadRemoveAccount(); devices = nil }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This iPhone is removed from your Mullvad devices. Other installs keep theirs.") }
    }

    // MARK: Mullvad

    @ViewBuilder private var mullvadSection: some View {
        if let m = model.mullvad {
            Section("Mullvad") {
                let state = m.device.map { "Signed in as device \($0.name)" } ?? (m.signedOut ? "Signed out on this iPhone" : "Signs in on first connect")
                SettingLabel(title: "Account •••• \(m.number.suffix(4))",
                             subtitle: [state, m.expiry.isEmpty ? nil : "paid until \(m.expiry.prefix(10))"].compactMap { $0 }.joined(separator: " · "),
                             icon: "person.badge.key.fill")
                if m.signedOut {
                    Text("This device was removed from your Mullvad account (on mullvad.net or by another install). Log in to register a new one.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button { Task { await login(m.number) } } label: {
                    HStack {
                        SettingLabel(title: m.device == nil ? "Log in" : "Refresh account and server list",
                                     subtitle: m.device == nil ? "Registers this iPhone as a new device on the account" : nil, icon: "arrow.clockwise")
                        Spacer()
                        if busy { ProgressView() }
                    }
                }
                .disabled(busy)
                devicesRows(m)
                if m.device != nil {
                    Button { confirmSignOut = true } label: {
                        SettingLabel(title: "Sign out on this iPhone", subtitle: "Forgets this device here; it stays on your account. Then log in as a new device.",
                                     icon: "rectangle.portrait.and.arrow.right")
                    }
                }
                Button { confirmRemoveAccount = true } label: {
                    SettingLabel(title: "Remove Mullvad account", subtitle: "Removes this device from the account and forgets the account here", icon: "trash")
                }
            }
        } else {
            Section {
                TextField("Mullvad account number", text: $number).keyboardType(.numberPad)
                    .onChange(of: number) { v in let f = String(v.filter { $0.isNumber || $0 == " " }.prefix(24)); if f != v { number = f } }
                Button { Task { await login(number) } } label: {
                    Text(busy ? "Signing in…" : "Add Mullvad account").frame(maxWidth: .infinity).fontWeight(.semibold)
                }
                .disabled(busy || Mullvad.normalize(number).count < 10)
            } header: { Text("Mullvad") } footer: { Text("Registers this iPhone as one of your account's 5 devices.") }
        }
    }

    @ViewBuilder private func devicesRows(_ m: MullvadAccount) -> some View {
        Button { withAnimation { devicesOpen.toggle() } } label: {
            SettingLabel(title: "Devices on this account" + (devices.map { " (\($0.count)/5)" } ?? ""),
                         subtitle: devicesOpen ? nil : "See which installs use your 5 slots",
                         icon: devicesOpen ? "chevron.up" : "chevron.down")
        }
        if devicesOpen {
            if let e = devicesErr {
                Text(e).font(.footnote).foregroundStyle(.secondary)
            } else if let list = devices {
                ForEach(list) { d in
                    HStack {
                        Image(systemName: "checkmark").foregroundStyle(Color.accentColor).opacity(d.id == m.device?.id ? 1 : 0).frame(width: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.name)
                            Text((d.id == m.device?.id ? "This iPhone · " : "") + "added \(d.created.prefix(10))").font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if d.id != m.device?.id {
                            Button(role: .destructive) { confirmRemoveDevice = d } label: { Image(systemName: "trash") }
                                .buttonStyle(.borderless).accessibilityLabel("Remove \(d.name)")
                        }
                    }
                }
            } else {
                Text("Loading…").foregroundStyle(.secondary)
            }
        }
    }

    // MARK: WireGuard

    private var profilesSection: some View {
        Section("WireGuard profiles") {
            ForEach(model.vpnProfiles) { p in
                let selected = model.settings.chainEnabled && model.settings.vpnExit == VpnExit.imported(id: p.id).stringValue
                HStack {
                    Button {
                        model.updateSettings { $0.chainEnabled = true; $0.vpnExit = VpnExit.imported(id: p.id).stringValue }
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor).opacity(selected ? 1 : 0).frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name.isEmpty ? "Unnamed" : p.name).foregroundStyle(.primary)
                                Text((try? WireGuardConfig.parse(p.config).endpoint) ?? "Invalid config").font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Button(role: .destructive) { deleteProfile = p } label: { Image(systemName: "trash") }
                        .buttonStyle(.borderless).accessibilityLabel("Delete")
                }
            }
            Button { importing = true } label: {
                SettingLabel(title: "Import .conf file", subtitle: "From Proton, Mullvad, AirVPN, your own server…", icon: "doc.badge.plus")
            }
            Button { config = UIPasteboard.general.string ?? ""; name = ""; addErr = nil; adding = true } label: {
                SettingLabel(title: "Paste config", icon: "doc.on.clipboard")
            }
        }
    }

    var addSheet: some View {
        NavigationStack {
            Form {
                TextField("Name (e.g. Proton NL)", text: $name)
                TextEditor(text: $config).font(.caption.monospaced()).frame(minHeight: 220)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                if let e = addErr { Text(e).font(.footnote).foregroundStyle(Brand.down) }
            }
            .navigationTitle("Paste WireGuard config")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { adding = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        do {
                            _ = try WireGuardConfig.parse(config)
                            var list = model.vpnProfiles
                            list.append(VpnProfile(name: name.isEmpty ? "VPN \(list.count + 1)" : name, config: config))
                            model.saveVpnProfiles(list)
                            adding = false
                        } catch {
                            addErr = error.localizedDescription
                        }
                    }
                }
            }
        }
    }

    // MARK: fastest servers

    private struct Result: Identifiable { let id: String; let title: String; let subtitle: String; let exit: VpnExit; let ms: Int? }

    private var results: [Result] {
        var out: [Result] = []
        for r in model.relays {
            guard let ms = model.pings[r.ipv4] else { continue }
            out.append(Result(id: r.hostname, title: flagName(r.countryCode, "\(r.city), \(r.country)"), subtitle: "Mullvad · \(r.hostname)",
                              exit: .mullvad(code: r.hostname), ms: ms))
        }
        for p in model.vpnProfiles {
            let c = try? WireGuardConfig.parse(p.config)
            let ms = c?.peers.first.flatMap { model.pings[$0.endpointHost] }
            out.append(Result(id: p.id, title: p.name, subtitle: "My profile · \(c?.endpoint ?? "invalid")" + (ms == nil && testedAt != nil ? " · doesn't answer ping (may still work)" : ""),
                              exit: .imported(id: p.id), ms: ms))
        }
        return out.sorted { ($0.ms ?? Int.max) < ($1.ms ?? Int.max) }
    }

    private var fastestSection: some View {
        let list = results
        let answered = list.filter { $0.ms != nil }.count
        return Section("Fastest servers") {
            Button {
                Task { testing = true; await model.measurePings(); testedAt = Date(); testing = false }
            } label: {
                HStack {
                    SettingLabel(title: testing ? "Testing…" : "Test latency to all servers",
                                 subtitle: testedAt.map { "\(answered) of \(model.relays.count + model.vpnProfiles.count) answered · tested \($0.formatted(date: .omitted, time: .shortened))" }
                                     ?? "Pings \(model.relays.count + model.vpnProfiles.count) servers directly from this iPhone",
                                 icon: "speedometer")
                    Spacer()
                    if testing { ProgressView() } else { Image(systemName: "arrow.clockwise").foregroundStyle(.secondary) }
                }
            }
            .disabled(testing)
            ForEach(showAll ? list : Array(list.prefix(10))) { r in
                let selected = model.settings.chainEnabled && model.settings.vpnExit == r.exit.stringValue
                Button {
                    model.updateSettings { $0.chainEnabled = true; $0.vpnExit = r.exit.stringValue }
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "checkmark").foregroundStyle(Color.accentColor).opacity(selected ? 1 : 0).frame(width: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.title).foregroundStyle(.primary)
                            Text(r.subtitle).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(r.ms.map { "\($0) ms" } ?? "—").font(.subheadline.weight(.semibold))
                            .foregroundStyle(r.ms.map(Brand.latency) ?? Color.secondary)
                    }
                }
            }
            if list.count > 10 {
                Button(showAll ? "Show top 10" : "Show all \(list.count)") { withAnimation { showAll.toggle() } }
            }
        }
    }

    // MARK: actions

    func login(_ n: String) async {
        busy = true; err = nil
        defer { busy = false }
        do {
            try await model.mullvadLogin(n)
            await model.refreshRelays()
            await loadDevices()
        } catch {
            err = error.localizedDescription
            if err?.contains("5 devices") == true || err?.contains("too many") == true { devicesOpen = true }
        }
    }

    func loadDevices() async {
        guard let m = model.mullvad else { return }
        await model.verifyMullvadDevice()
        do {
            devices = try await model.mullvadClient.devices(m.number)
            devicesErr = nil
        } catch {
            devicesErr = error.localizedDescription
        }
    }

    func removeDevice(_ d: MullvadDeviceInfo) async {
        guard let m = model.mullvad else { return }
        do {
            try await model.mullvadClient.removeDevice(m.number, d.id)
            await loadDevices()
        } catch {
            err = error.localizedDescription
        }
    }
}
