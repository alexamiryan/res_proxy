// Swift side of the native pieces: the Go bridge (relay, gate, upstream, WireGuard — the same helper the Chrome
// extension uses, see native/wgbridge/cmd/resproxy-helper/bridge_ios.go) and hev-socks5-tunnel (TUN → SOCKS5).

import Foundation
import ResProxyCore

struct BridgeError: LocalizedError {
    var message: String
    /// "auth" (proxy rejected the login), "vpn" (WireGuard setup failed), "net", "nxdomain" or "".
    var code: String
    var errorDescription: String? { message }
}

enum Bridge {
    private static let queue = DispatchQueue(label: "dev.resproxy.bridge", qos: .userInitiated, attributes: .concurrent)

    /// Runs one helper command off the main thread. Throws BridgeError when the reply has ok:false.
    static func call(_ cmd: String, _ args: [String: Any] = [:]) async throws -> [String: Any] {
        var req = args
        req["cmd"] = cmd
        req["id"] = 0
        let data = try JSONSerialization.data(withJSONObject: req)
        let text = String(data: data, encoding: .utf8)!
        let reply: [String: Any] = await withCheckedContinuation { cont in
            queue.async {
                let out = text.withCString { ResProxyCall(UnsafeMutablePointer(mutating: $0)) }
                defer { ResProxyFree(out) }
                let s = out.map { String(cString: $0) } ?? "{}"
                let obj = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] ?? ["ok": false, "error": "bad reply"]
                cont.resume(returning: obj)
            }
        }
        if reply["ok"] as? Bool == true { return reply }
        throw BridgeError(message: reply["error"] as? String ?? "failed", code: reply["code"] as? String ?? "")
    }

    static func endpointJSON(_ e: Endpoint) -> [String: Any] {
        ["host": e.host, "port": e.port, "protocol": e.protocol.rawValue, "username": e.username, "password": e.password]
    }

    /// Direct (outside the tunnel) or through the exit IP (`proxy`), never gated.
    static func fetch(_ r: HTTPRequest, proxy: Bool = false) async throws -> HTTPResponse {
        var a: [String: Any] = ["url": r.url, "method": r.method, "headers": r.headers, "proxy": proxy]
        if let b = r.body { a["body"] = b }
        let out = try await call("fetch", a)
        return HTTPResponse(status: out["status"] as? Int ?? 0, body: out["body"] as? String ?? "")
    }

    static func lookupA(_ name: String) async throws -> [String] {
        do {
            return try await call("lookup", ["name": name])["addrs"] as? [String] ?? []
        } catch let e as BridgeError where e.code == "nxdomain" {
            return []
        }
    }

    static func ping(_ targets: [String]) async -> [String: Int] {
        guard let out = try? await call("ping", ["targets": targets]), let r = out["results"] as? [String: Any] else { return [:] }
        var res: [String: Int] = [:]
        for (k, v) in r { if let ms = v as? Int { res[k] = ms } }
        return res
    }
}

/// hev-socks5-tunnel running on the utun descriptor, sending every TCP connection to the relay's SOCKS5 port.
/// Keep it running across upstream rebuilds: its fake-IP DNS table (100.64/10) lives inside it and starts over on
/// every restart, so addresses apps still have cached would point nowhere or at a different site.
final class Hev {
    static let tunV4 = "198.18.0.1"
    static let tunV6 = "fc00::1"
    static let mapDns = "198.18.0.2"
    static let mtu = 1500

    private var thread: Thread?
    private(set) var running = false
    /// The relay port this instance was started with (0 when stopped).
    private(set) var socksPort = 0
    /// Started and not stopped by us, but its thread has ended: hev itself is broken.
    var died: Bool { thread != nil && !running }
    private let done = DispatchSemaphore(value: 0)

    /// YAML single-quoted scalar.
    static func q(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "''") + "'" }

    static func config(port: Int, user: String, pass: String) -> String {
        """
        tunnel:
          mtu: \(mtu)
          ipv4: \(tunV4)
          ipv6: \(q(tunV6))
        socks5:
          address: '127.0.0.1'
          port: \(port)
          udp: 'udp'
          username: \(q(user))
          password: \(q(pass))
        mapdns:
          address: \(mapDns)
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          task-stack-size: 81920
          connect-timeout: 10000
          log-level: warn

        """
    }

    func start(fd: Int32, socksPort: Int, user: String, pass: String) {
        stop()
        let cfg = Array(Hev.config(port: socksPort, user: user, pass: pass).utf8)
        running = true
        self.socksPort = socksPort
        let t = Thread { [weak self] in
            _ = cfg.withUnsafeBufferPointer { hev_socks5_tunnel_main_from_str($0.baseAddress, UInt32(cfg.count), fd) }
            self?.running = false
            self?.done.signal()
        }
        t.name = "hev-socks5-tunnel"
        t.stackSize = 1 << 20
        thread = t
        t.start()
    }

    func stop() {
        guard thread != nil else { return }
        hev_socks5_tunnel_quit()
        _ = done.wait(timeout: .now() + 3)
        thread = nil
        running = false
        socksPort = 0
    }

    /// (down, up) bytes as apps see them, since hev started.
    func stats() -> (down: Int64, up: Int64) {
        guard running else { return (0, 0) }
        var txp = 0, txb = 0, rxp = 0, rxb = 0
        hev_socks5_tunnel_stats(&txp, &txb, &rxp, &rxb)
        // hev counts from its own side of the TUN: tx = read from it (apps' upload), rx = written to it (download).
        return (Int64(rxb), Int64(txb))
    }
}
