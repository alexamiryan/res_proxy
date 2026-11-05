import NetworkExtension
import ResProxyCore

/// Entry point iOS starts for the VPN. All work happens in TunnelController.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var controllerStorage: TunnelController?
    @MainActor private var controller: TunnelController {
        if let c = controllerStorage { return c }
        let c = TunnelController(provider: self)
        controllerStorage = c
        return c
    }

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        Task { @MainActor in
            var done = false
            await controller.connect { err in
                if !done { done = true; completionHandler(err) }
            }
            if !done { done = true; completionHandler(nil) }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        Task { @MainActor in
            if reason == .userInitiated || reason == .configurationDisabled || reason == .configurationRemoved {
                SharedStore.shared.updateSettings(notify: false) { $0.wasConnected = false }
            }
            await controller.disconnect()
            completionHandler()
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        Task { @MainActor in
            guard let msg = try? JSONDecoder().decode(TunnelMessage.self, from: messageData) else { completionHandler?(nil); return }
            let reply = await controller.handle(msg)
            completionHandler?(try? JSONEncoder().encode(reply))
        }
    }

    override func wake() {
        Task { @MainActor in controller.wake() }
    }
}
