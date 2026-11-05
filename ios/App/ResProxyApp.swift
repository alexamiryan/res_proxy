import ResProxyCore
import SwiftUI

@main
struct ResProxyApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onChange(of: phase) { p in
                    if p == .active { model.reload(); model.startPolling() } else { model.stopPolling() }
                }
        }
    }
}

struct RootView: View {
    var body: some View {
        // First run shows the welcome card inline on Home, like Android.
        NavigationStack { start }
            .tint(Brand.green)
    }

    @ViewBuilder var start: some View {
        #if DEBUG
        // Simulator screenshots: RP_SCREEN opens a page directly.
        switch ProcessInfo.processInfo.environment["RP_SCREEN"] ?? "" {
        case "settings": SettingsView()
        case "vpn": VpnSettingsView()
        case "protection": ProtectionView()
        case "reputation": ReputationView()
        case "advanced": AdvancedView()
        case "backup": BackupView()
        case "account": AccountView(provider: .GEONODE)
        case "vpnpicker": VpnPicker()
        default: HomeView()
        }
        #else
        HomeView()
        #endif
    }
}
