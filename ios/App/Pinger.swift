// ICMP echo over unprivileged (SOCK_DGRAM) sockets — iOS allows these without special entitlements. Used to sort
// the VPN server list by latency while the tunnel is down (when it's up, the tunnel pings from outside itself).

import Darwin
import Foundation

enum Pinger {
    static func pingAll(_ targets: [String], concurrency: Int = 32) async -> [String: Int] {
        await withTaskGroup(of: (String, Int?).self) { group in
            var out: [String: Int] = [:]
            var it = targets.makeIterator()
            var running = 0
            func next() -> Bool {
                guard let t = it.next() else { return false }
                group.addTask { (t, await ping(t)) }
                return true
            }
            while running < concurrency, next() { running += 1 }
            for await (t, ms) in group {
                if let ms { out[t] = ms }
                _ = next()
            }
            return out
        }
    }

    static func ping(_ ip: String, timeout: TimeInterval = 2) async -> Int? {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .utility).async { cont.resume(returning: pingSync(ip, timeout: timeout)) }
        }
    }

    static func checksum(_ data: [UInt8]) -> UInt16 {
        var sum: UInt32 = 0
        var i = 0
        while i + 1 < data.count { sum += UInt32(data[i]) << 8 | UInt32(data[i + 1]); i += 2 }
        if i < data.count { sum += UInt32(data[i]) << 8 }
        while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16) }
        return ~UInt16(sum)
    }

    static func pingSync(_ ip: String, timeout: TimeInterval) -> Int? {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        guard inet_pton(AF_INET, ip, &addr.sin_addr) == 1 else { return nil }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var tv = timeval(tv_sec: Int(timeout), tv_usec: Int32((timeout - floor(timeout)) * 1_000_000))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        let id = UInt16.random(in: 1...0xfffe), seq = UInt16.random(in: 1...0xfffe)
        var pkt: [UInt8] = [8, 0, 0, 0, UInt8(id >> 8), UInt8(id & 0xff), UInt8(seq >> 8), UInt8(seq & 0xff)] + Array("resproxy".utf8)
        let c = checksum(pkt)
        pkt[2] = UInt8(c >> 8); pkt[3] = UInt8(c & 0xff)
        let start = Date()
        let sent = withUnsafePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, pkt, pkt.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard sent == pkt.count else { return nil }
        var buf = [UInt8](repeating: 0, count: 1500)
        while Date().timeIntervalSince(start) < timeout {
            let n = recv(fd, &buf, buf.count, 0)
            if n <= 0 { return nil }
            // Darwin delivers the IP header with SOCK_DGRAM ICMP.
            var off = 0
            if n >= 20 && buf[0] >> 4 == 4 { off = Int(buf[0] & 0x0f) * 4 }
            if n >= off + 8 && buf[off] == 0 && buf[off + 6] == UInt8(seq >> 8) && buf[off + 7] == UInt8(seq & 0xff) {
                return Int(Date().timeIntervalSince(start) * 1000 + 0.5)
            }
        }
        return nil
    }
}
