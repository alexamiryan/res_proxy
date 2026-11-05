import ResProxyCore
import SwiftUI

/// Searchable list of countries, states or cities (Android's PlacePickerDialog), shown as a sheet.
/// `nil` = "Any …". With `allowCustom`, typed text that matches nothing can be used as is.
struct PlacePicker: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let any: String
    let options: [Place]
    let showFlags: Bool
    let allowCustom: Bool
    let onPick: (Place?) -> Void
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                let q = query.trimmingCharacters(in: .whitespaces)
                if q.isEmpty {
                    row(any, bold: true) { pick(nil) }
                }
                if allowCustom, !q.isEmpty, !options.contains(where: { $0.name.caseInsensitiveCompare(q) == .orderedSame }),
                   let code = Presets.token(q) {
                    row("Use “\(q)”", bold: true) { pick(Place(code: code, name: q)) }
                }
                ForEach(options.filter { q.isEmpty || $0.name.localizedCaseInsensitiveContains(q) || $0.code.caseInsensitiveCompare(q) == .orderedSame }) { p in
                    row(showFlags ? flagName(p.code, p.name) : p.name) { pick(p) }
                }
                if options.isEmpty && !allowCustom {
                    Text("No options available").foregroundStyle(.secondary)
                }
            }
            .listStyle(.plain)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    func pick(_ p: Place?) { onPick(p); dismiss() }

    func row(_ text: String, bold: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).fontWeight(bold ? .semibold : .regular).foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }
    }
}

/// VPN to reach the proxy through (Android's VpnPickerDialog): Off, imported WireGuard profiles, Mullvad countries →
/// cities, and servers when searching.
struct VpnPicker: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var open: Set<String> = []

    var body: some View {
        let s = model.settings
        let q = query.trimmingCharacters(in: .whitespaces)
        List {
            if q.isEmpty {
                Section {
                    PickRow(title: "Off", subtitle: "Connect to the proxy directly", bold: true, selected: !s.chainEnabled) {
                        model.updateSettings { $0.chainEnabled = false }; dismiss()
                    }
                }
                if !model.vpnProfiles.isEmpty {
                    Section("My WireGuard profiles") {
                        ForEach(model.vpnProfiles) { p in
                            PickRow(title: p.name, subtitle: ping(of: p).map { "\($0) ms" }, selected: isSel(.imported(id: p.id))) { choose(.imported(id: p.id)) }
                        }
                    }
                }
            }
            if model.mullvad == nil {
                Section {
                    Text("Add a Mullvad account or import WireGuard configs in Settings → VPN.").font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Section(model.relays.isEmpty ? "Mullvad (loading servers…)" : "Mullvad") {
                    ForEach(countries(q), id: \.code) { c in
                        PickRow(title: flagName(c.code, c.name), subtitle: countrySubtitle(c), selected: isSel(.mullvad(code: c.code)),
                                trailing: AnyView(expandButton(c.code))) { choose(.mullvad(code: c.code)) }
                        if open.contains(c.code) || q.count >= 3 {
                            ForEach(cities(c.code, q), id: \.loc) { city in
                                PickRow(title: city.name, subtitle: "\(city.servers) server\(city.servers == 1 ? "" : "s")" + (city.ping.map { " · \($0) ms" } ?? ""),
                                        indent: 1, selected: isSel(.mullvad(code: city.loc))) { choose(.mullvad(code: city.loc)) }
                                if q.count >= 3 {
                                    ForEach(servers(city.loc, q)) { r in
                                        PickRow(title: r.hostname, subtitle: r.city + (r.owned ? " · Mullvad-owned" : "") + (model.pings[r.ipv4].map { " · \($0) ms" } ?? ""),
                                                indent: 2, selected: isSel(.mullvad(code: r.hostname))) { choose(.mullvad(code: r.hostname)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .modifier(MullvadSearch(enabled: model.mullvad != nil, query: $query))
        .navigationTitle("VPN")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
            ToolbarItem(placement: .primaryAction) { NavigationLink("Manage") { VpnSettingsView() } }
        }
        .task {
            if model.mullvad != nil && model.relays.isEmpty { await model.refreshRelays() }
            await model.measurePings()
        }
    }

    func expandButton(_ code: String) -> some View {
        Button {
            withAnimation { if open.contains(code) { open.remove(code) } else { open.insert(code) } }
        } label: {
            Image(systemName: "chevron.down").rotationEffect(.degrees(open.contains(code) ? 180 : 0))
                .foregroundStyle(.secondary).frame(width: 36, height: 36).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Cities")
    }

    func isSel(_ e: VpnExit) -> Bool { model.settings.chainEnabled && model.settings.vpnExit == e.stringValue }

    func choose(_ e: VpnExit) {
        model.updateSettings { $0.chainEnabled = true; $0.vpnExit = e.stringValue }
        dismiss()
    }

    func ping(of p: VpnProfile) -> Int? {
        guard let c = try? WireGuardConfig.parse(p.config), let h = c.peers.first?.endpointHost else { return nil }
        return model.pings[h]
    }

    func matches(_ r: MullvadRelay, _ q: String) -> Bool {
        q.isEmpty || r.country.localizedCaseInsensitiveContains(q) || r.city.localizedCaseInsensitiveContains(q) || r.hostname.contains(q.lowercased())
    }

    struct Country { let code: String; let name: String; let cities: Int; let servers: Int; let ping: Int? }

    func countrySubtitle(_ c: Country) -> String {
        "\(c.cities) cit\(c.cities == 1 ? "y" : "ies") · \(c.servers) server\(c.servers == 1 ? "" : "s")" + (c.ping.map { " · \($0) ms" } ?? "")
    }

    /// Fastest first once measured, otherwise alphabetical.
    static func order(_ a: (Int?, String), _ b: (Int?, String)) -> Bool {
        switch (a.0, b.0) {
        case let (x?, y?) where x != y: return x < y
        case (_?, nil): return true
        case (nil, _?): return false
        default: return a.1 < b.1
        }
    }

    func countries(_ q: String) -> [Country] {
        let hits = model.relays.filter { matches($0, q) }
        return Dictionary(grouping: hits, by: \.countryCode).map { code, rs in
            Country(code: code, name: rs[0].country, cities: Set(rs.map(\.location)).count, servers: rs.count,
                    ping: rs.compactMap { model.pings[$0.ipv4] }.min())
        }
        .sorted { Self.order(($0.ping, $0.name), ($1.ping, $1.name)) }
    }

    func cities(_ cc: String, _ q: String) -> [(loc: String, name: String, servers: Int, ping: Int?)] {
        let hits = model.relays.filter { $0.countryCode == cc && matches($0, q) }
        return Dictionary(grouping: hits, by: \.location).map { loc, rs in
            (loc, rs[0].city, rs.count, rs.compactMap { model.pings[$0.ipv4] }.min())
        }
        .sorted { Self.order(($0.ping, $0.name), ($1.ping, $1.name)) }
    }

    func servers(_ loc: String, _ q: String) -> [MullvadRelay] {
        model.relays.filter { $0.location == loc && matches($0, q) }
            .sorted { Self.order((model.pings[$0.ipv4], $0.hostname), (model.pings[$1.ipv4], $1.hostname)) }
    }
}

private struct MullvadSearch: ViewModifier {
    let enabled: Bool
    @Binding var query: String
    func body(content: Content) -> some View {
        if enabled {
            content.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search Mullvad countries, cities, servers")
        } else {
            content
        }
    }
}

/// Selectable row: title, optional subtitle, check when selected, optional trailing control (Android's PickRow).
struct PickRow: View {
    let title: String
    var subtitle: String?
    var indent = 0
    var bold = false
    let selected: Bool
    var trailing: AnyView?
    let action: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: action) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).fontWeight(bold || selected ? .semibold : .regular).foregroundStyle(.primary)
                        if let subtitle, !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor).accessibilityLabel("Selected") }
                }
                .padding(.leading, CGFloat(indent) * 20)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if let trailing { trailing }
        }
    }
}
