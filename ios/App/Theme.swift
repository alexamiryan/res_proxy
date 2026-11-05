// Look and building blocks shared by the screens. Mirrors android/.../ui/Theme.kt and Components.kt (same status and
// verdict colours, cards, picker rows), drawn with iOS conventions (grouped backgrounds, SF Symbols, sheets).

import ResProxyCore
import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

enum Brand {
    /// Launcher icon background; the app's tint (lighter in dark mode so it stays readable).
    static let green = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.36, green: 0.78, blue: 0.55, alpha: 1)
                                                                        : UIColor(red: 0x1E / 255, green: 0x6B / 255, blue: 0x45 / 255, alpha: 1) })
    static let connected = Color(hex: 0x2E9D5B)
    static let pending = Color(hex: 0xE0A030)
    static let down = Color(hex: 0xD64545)
    static let idle = Color(hex: 0x8A8F98)

    static func latency(_ ms: Int) -> Color { ms < 60 ? connected : ms < 150 ? pending : down }
}

extension Verdict {
    var color: Color {
        switch self {
        case .CLEAN: return Brand.connected
        case .SUSPICIOUS: return Brand.pending
        case .BAD: return Brand.down
        case .UNKNOWN: return Brand.idle
        }
    }
    var label: String {
        switch self {
        case .CLEAN: return "Clean"
        case .SUSPICIOUS: return "Suspicious"
        case .BAD: return "Bad reputation"
        case .UNKNOWN: return "Unknown"
        }
    }
}

/// Filled pill with the verdict's colour (Android's ReputationCard badge).
struct VerdictBadge: View {
    let verdict: Verdict
    var body: some View {
        Text(verdict.label).font(.subheadline.bold()).foregroundStyle(.white)
            .padding(.horizontal, 14).padding(.vertical, 6)
            .background(verdict.color, in: Capsule())
    }
}

/// Rounded card on the grouped background (Android's Material Card).
struct Card<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// Small caption title inside a card ("Exit IP", "Location").
struct CardLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.footnote.weight(.medium)).foregroundStyle(.secondary) }
}

/// Label over value with a chevron (Android's PickerRow).
struct PickerRow: View {
    let label: String
    let value: String
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.footnote).foregroundStyle(.secondary)
                    Text(value).font(.headline).foregroundStyle(enabled ? Color.primary : Color.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// Settings row: tinted SF Symbol, title, optional subtitle (Android's SettingRow).
struct SettingLabel: View {
    let title: String
    var subtitle: String?
    var icon: String?
    var body: some View {
        HStack(spacing: 14) {
            if let icon {
                Image(systemName: icon).font(.title3).foregroundStyle(Color.accentColor).frame(width: 28)
            }
            VStack(alignment: .leading, spacing: 2) {
                // Explicit colours: inside a Button the tint would otherwise colour the text.
                Text(title).foregroundColor(Color(.label))
                if let subtitle, !subtitle.isEmpty { Text(subtitle).font(.footnote).foregroundColor(Color(.secondaryLabel)) }
            }
        }
    }
}

/// Switch with title + subtitle (Android's SwitchRow).
struct SwitchRow: View {
    let title: String
    var subtitle: String?
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) { SettingLabel(title: title, subtitle: subtitle) }
    }
}

/// Digits-only field that keeps its own text while typing and saves valid numbers (Android's NumberField).
struct NumberField: View {
    let title: String
    let value: Int
    var range: ClosedRange<Int> = 0...99_999
    let onChange: (Int) -> Void
    @State private var text = ""
    var body: some View {
        LabeledContent(title) {
            TextField("", text: $text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 110)
        }
        .onAppear { text = "\(value)" }
        .onChange(of: text) { t in
            let digits = String(t.filter(\.isNumber).prefix(5))
            if digits != t { text = digits; return }
            if let n = Int(digits), range.contains(n), n != value { onChange(n) }
        }
    }
}

func flagName(_ code: String, _ name: String) -> String { "\(Locations.flag(code))  \(name)" }

extension View {
    /// Grouped background behind a scrolling card layout.
    func groupedBackground() -> some View { background(Color(.systemGroupedBackground).ignoresSafeArea()) }
}
