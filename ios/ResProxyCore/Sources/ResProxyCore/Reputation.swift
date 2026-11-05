// IP reputation: per-source parsers, DNSBL interpretation and the verdict rules.
// Port of android/.../reputation/*.kt and chrome/src/core/reputation.ts — keep the rules identical.

import Foundation

public enum SourceId: String, Codable, CaseIterable, Sendable {
    case IPAPI_IS, PROXYCHECK, IP_API, ABUSEIPDB, IPQS, SCAMALYTICS
    case DNSBL_SPAMHAUS, DNSBL_SPAMCOP, DNSBL_BARRACUDA, DNSBL_UCEPROTECT

    public var label: String {
        switch self {
        case .IPAPI_IS: return "ipapi.is"
        case .PROXYCHECK: return "proxycheck.io"
        case .IP_API: return "ip-api.com"
        case .ABUSEIPDB: return "AbuseIPDB"
        case .IPQS: return "IPQualityScore"
        case .SCAMALYTICS: return "Scamalytics"
        case .DNSBL_SPAMHAUS: return "Spamhaus ZEN"
        case .DNSBL_SPAMCOP: return "SpamCop"
        case .DNSBL_BARRACUDA: return "Barracuda"
        case .DNSBL_UCEPROTECT: return "UCEPROTECT L1"
        }
    }
}

/// One source's answer, normalized. nil field = the source doesn't report that signal.
public struct SourceResult: Codable, Equatable, Sendable {
    public var source: SourceId
    public var ok: Bool
    public var error: String?
    public var proxy: Bool?
    public var vpn: Bool?
    public var tor: Bool?
    public var datacenter: Bool?
    public var abuser: Bool?
    public var listed: Bool?
    public var score: Int?
    public var riskLabel: String?
    public var recentAbuse: Bool?
    public var reports: Int?
    public var country: String?
    public var countryCode: String?
    public var region: String?
    public var city: String?
    public var isp: String?
    public var asn: String?
    public var connectionType: String?
    public var note: String?

    public init(source: SourceId, ok: Bool) { self.source = source; self.ok = ok }

    public static func failed(_ source: SourceId, _ error: String) -> SourceResult {
        var r = SourceResult(source: source, ok: false)
        r.error = error
        return r
    }
}

public struct Reason: Codable, Equatable, Sendable {
    public var verdict: Verdict
    public var text: String
}

public struct GeoInfo: Codable, Equatable, Sendable {
    public var country, countryCode, region, city, isp, asn, connectionType: String?
}

public struct ReputationReport: Codable, Equatable, Sendable {
    public var ip: String
    public var verdict: Verdict
    public var reasons: [Reason]
    public var results: [SourceResult]
    public var geo: GeoInfo?
    public var proxyFlags: Int
    public var checkedAt: Int64
}

// MARK: - parsers

public enum ParseError: Error { case notObject }

typealias J = [String: Any]

private func obj(_ body: String) throws -> J {
    guard let d = body.data(using: .utf8), let v = try JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]) as? J else {
        throw ParseError.notObject
    }
    return v
}

private func sub(_ o: J?, _ k: String) -> J? { o?[k] as? J }

private func str(_ o: J?, _ k: String) -> String? {
    guard let v = o?[k], !(v is NSNull), !(v is J), !(v is [Any]) else { return nil }
    let t: String
    if let n = v as? NSNumber {
        if CFGetTypeID(n) == CFBooleanGetTypeID() { t = n.boolValue ? "true" : "false" }
        else if n.doubleValue == n.doubleValue.rounded(), abs(n.doubleValue) < 1e15 { t = String(n.int64Value) }
        else { t = n.stringValue }
    } else {
        t = "\(v)"
    }
    return t.isEmpty || t == "null" ? nil : t
}

private func bool(_ o: J?, _ k: String) -> Bool? {
    guard let v = o?[k] else { return nil }
    if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
    let s: String
    if let n = v as? NSNumber { s = n.stringValue } else if let x = v as? String { s = x.lowercased() } else { return nil }
    switch s {
    case "yes", "true", "1": return true
    case "no", "false", "0": return false
    default: return nil
    }
}

private func int(_ o: J?, _ k: String) -> Int? {
    guard let v = o?[k] else { return nil }
    if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() {
        let d = n.doubleValue
        return d.isFinite ? Int(d) : nil
    }
    if let s = v as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty, let d = Double(s.trimmingCharacters(in: .whitespaces)), d.isFinite {
        return Int(d)
    }
    return nil
}

public enum Parsers {
    public static func ipapiIs(_ body: String) throws -> SourceResult {
        let o = try obj(body)
        if let err = str(o, "error") { return .failed(.IPAPI_IS, err) }
        let company = sub(o, "company"), asn = sub(o, "asn"), loc = sub(o, "location")
        var r = SourceResult(source: .IPAPI_IS, ok: true)
        r.proxy = bool(o, "is_proxy"); r.vpn = bool(o, "is_vpn"); r.tor = bool(o, "is_tor")
        r.datacenter = bool(o, "is_datacenter"); r.abuser = bool(o, "is_abuser")
        r.country = str(loc, "country"); r.countryCode = str(loc, "country_code"); r.region = str(loc, "state"); r.city = str(loc, "city")
        r.isp = str(company, "name") ?? str(asn, "org")
        r.asn = str(asn, "asn").map { "AS\($0)" }
        r.connectionType = str(company, "type")
        r.note = str(company, "abuser_score").map { "Abuser score \($0)" }
        return r
    }

    public static func proxycheck(_ body: String, ip: String) throws -> SourceResult {
        let o = try obj(body)
        if let status = str(o, "status"), status != "ok", status != "warning" { return .failed(.PROXYCHECK, str(o, "message") ?? status) }
        guard let d = sub(o, ip) else { return .failed(.PROXYCHECK, str(o, "message") ?? "no data") }
        let type = str(d, "type")
        func eq(_ a: String?, _ b: String) -> Bool? { a.map { $0.lowercased() == b.lowercased() } }
        var r = SourceResult(source: .PROXYCHECK, ok: true)
        r.proxy = eq(str(d, "proxy"), "yes"); r.vpn = eq(type, "VPN"); r.tor = eq(type, "TOR"); r.datacenter = eq(type, "Hosting")
        r.score = int(d, "risk")
        r.country = str(d, "country"); r.countryCode = str(d, "isocode"); r.region = str(d, "region"); r.city = str(d, "city")
        r.isp = str(d, "provider") ?? str(d, "organisation"); r.asn = str(d, "asn"); r.connectionType = type
        return r
    }

    public static func ipApi(_ body: String) throws -> SourceResult {
        let o = try obj(body)
        if str(o, "status") != "success" { return .failed(.IP_API, str(o, "message") ?? "failed") }
        var r = SourceResult(source: .IP_API, ok: true)
        r.proxy = bool(o, "proxy"); r.datacenter = bool(o, "hosting")
        r.country = str(o, "country"); r.countryCode = str(o, "countryCode"); r.region = str(o, "regionName"); r.city = str(o, "city")
        r.isp = str(o, "isp") ?? str(o, "org")
        r.asn = str(o, "as").map { String($0.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false).first ?? "") }
        r.connectionType = bool(o, "mobile") == true ? "mobile" : nil
        return r
    }

    public static func abuseIpDb(_ body: String) throws -> SourceResult {
        let o = try obj(body)
        if let errs = o["errors"] {
            let txt = (try? JSONSerialization.data(withJSONObject: errs, options: [.fragmentsAllowed])).flatMap { String(data: $0, encoding: .utf8) } ?? "error"
            return .failed(.ABUSEIPDB, String(txt.prefix(120)))
        }
        guard let d = sub(o, "data") else { return .failed(.ABUSEIPDB, "no data") }
        let usage = str(d, "usageType")
        var r = SourceResult(source: .ABUSEIPDB, ok: true)
        r.tor = bool(d, "isTor")
        r.datacenter = usage.map { $0.lowercased().contains("data center") }
        r.score = int(d, "abuseConfidenceScore"); r.reports = int(d, "totalReports")
        r.countryCode = str(d, "countryCode"); r.isp = str(d, "isp"); r.connectionType = usage
        r.note = str(d, "lastReportedAt").map { "Last reported \($0)" }
        return r
    }

    public static func ipqs(_ body: String) throws -> SourceResult {
        let o = try obj(body)
        if bool(o, "success") == false { return .failed(.IPQS, str(o, "message") ?? "failed") }
        var r = SourceResult(source: .IPQS, ok: true)
        r.proxy = bool(o, "proxy"); r.vpn = bool(o, "vpn"); r.tor = bool(o, "tor")
        r.score = int(o, "fraud_score"); r.recentAbuse = bool(o, "recent_abuse")
        r.countryCode = str(o, "country_code"); r.region = str(o, "region"); r.city = str(o, "city")
        r.isp = str(o, "ISP"); r.asn = int(o, "ASN").map { "AS\($0)" }
        r.connectionType = str(o, "connection_type")
        r.note = bool(o, "bot_status") == true ? "Recent bot activity" : nil
        return r
    }

    /// Accepts both the v3 (`{"scamalytics":{...}}`) and legacy flat response shapes.
    public static func scamalytics(_ body: String) throws -> SourceResult {
        let root = try obj(body)
        let o = sub(root, "scamalytics") ?? root
        if let status = str(o, "status"), status != "ok" { return .failed(.SCAMALYTICS, str(o, "error") ?? status) }
        let score = int(o, "scamalytics_score") ?? int(o, "score")
        let risk = str(o, "scamalytics_risk") ?? str(o, "risk")
        if score == nil && risk == nil { return .failed(.SCAMALYTICS, str(o, "error") ?? "no data") }
        var r = SourceResult(source: .SCAMALYTICS, ok: true)
        r.score = score; r.riskLabel = risk?.lowercased()
        return r
    }
}

// MARK: - DNS blacklists

public enum Dnsbl {
    public static let zones: [(SourceId, String)] = [
        (.DNSBL_SPAMHAUS, "zen.spamhaus.org"),
        (.DNSBL_SPAMCOP, "bl.spamcop.net"),
        (.DNSBL_BARRACUDA, "b.barracudacentral.org"),
        (.DNSBL_UCEPROTECT, "dnsbl-1.uceprotect.net"),
    ]

    public static func name(_ ip: String, zone: String) -> String? {
        let o = ip.split(separator: ".", omittingEmptySubsequences: false)
        return o.count == 4 ? o.reversed().joined(separator: ".") + "." + zone : nil
    }

    /// answers: A records for the reversed-octet name; empty = not listed (NXDOMAIN).
    public static func interpret(_ source: SourceId, _ answers: [String]) -> SourceResult {
        var r = SourceResult(source: source, ok: true)
        if answers.isEmpty { r.listed = false; return r }
        // 127.255.255.x: Spamhaus refusing the query (e.g. via a public resolver) — not a listing.
        if answers.allSatisfy({ $0.hasPrefix("127.255.255.") }) { return .failed(source, "Query refused by blacklist (resolver blocked)") }
        if source == .DNSBL_SPAMHAUS {
            let codes = answers.compactMap { a in a.split(separator: ".").last.flatMap { Int($0) } }
            let abuse = codes.filter { $0 >= 2 && $0 <= 9 }
            let pbl = codes.contains { $0 == 10 || $0 == 11 }
            if abuse.isEmpty {
                r.listed = false
                r.note = pbl ? "In PBL (end-user/residential range) — expected for residential IPs" : nil
                return r
            }
            var lists: [String] = []
            for c in abuse {
                let l = (c == 2 || c == 3 || c == 9) ? "SBL" : "XBL"
                if !lists.contains(l) { lists.append(l) }
            }
            r.listed = true
            r.note = "Listed in \(lists.joined(separator: "/"))"
            return r
        }
        r.listed = answers.contains { $0.hasPrefix("127.") }
        r.note = answers.joined(separator: ", ")
        return r
    }
}

// MARK: - verdict

public enum Reputation {
    /// "Detected as a proxy" is deliberately *not* negative on its own: residential proxy IPs are routinely
    /// flagged as such. What matters is abuse history, blacklists, Tor, and whether the IP is really residential.
    public static func aggregate(ip: String, results: [SourceResult], now: Int64 = nowMs()) -> ReputationReport {
        let ok = results.filter(\.ok)
        var reasons: [Reason] = []
        func bad(_ t: String) { reasons.append(Reason(verdict: .BAD, text: t)) }
        func sus(_ t: String) { reasons.append(Reason(verdict: .SUSPICIOUS, text: t)) }

        for r in ok {
            let src = r.source.label
            if r.tor == true { bad("Tor exit node (\(src))") }
            if r.listed == true { bad("Listed on \(src) blacklist") }
            if r.abuser == true { bad("Flagged as abuser by \(src)") }
            if r.recentAbuse == true { bad("Recent abuse reported by \(src)") }
            if r.datacenter == true { sus("Datacenter/hosting IP, not residential (\(src))") }
            switch r.source {
            case .ABUSEIPDB:
                if let s = r.score {
                    let txt = "AbuseIPDB confidence \(s)%" + (r.reports.map { " (\($0) reports)" } ?? "")
                    if s >= 50 { bad(txt) } else if s >= 1 { sus(txt) }
                }
            case .IPQS:
                if let s = r.score { if s >= 85 { bad("IPQS fraud score \(s)") } else if s >= 75 { sus("IPQS fraud score \(s)") } }
            case .SCAMALYTICS:
                let sfx = r.score.map { " (\($0))" } ?? ""
                if r.riskLabel == "high" || r.riskLabel == "very high" { bad("Scamalytics risk \(r.riskLabel!)\(sfx)") }
                else if r.riskLabel == "medium" { sus("Scamalytics risk medium\(sfx)") }
            case .PROXYCHECK:
                if let s = r.score, s >= 67 { sus("proxycheck.io risk \(s)") }
            default: break
            }
        }
        let vpnCount = ok.filter { $0.vpn == true }.count
        if vpnCount >= 2 { sus("Flagged as VPN by \(vpnCount) sources") }

        let verdict: Verdict = ok.isEmpty ? .UNKNOWN : reasons.reduce(Verdict.CLEAN) { $1.verdict.severity > $0.severity ? $1.verdict : $0 }
        var seen = Set<String>()
        let distinct = reasons.filter { seen.insert($0.verdict.rawValue + $0.text).inserted }
        // Stable sort by severity, worst first.
        let sorted = distinct.enumerated().sorted { a, b in
            a.element.verdict.severity != b.element.verdict.severity ? a.element.verdict.severity > b.element.verdict.severity : a.offset < b.offset
        }.map(\.element)
        return ReputationReport(ip: ip, verdict: verdict, reasons: sorted, results: results, geo: geo(ok),
                                proxyFlags: ok.filter { $0.proxy == true }.count, checkedAt: now)
    }

    static let geoPriority: [SourceId] = [.IPAPI_IS, .IP_API, .PROXYCHECK, .IPQS, .ABUSEIPDB]

    static func geo(_ ok: [SourceResult]) -> GeoInfo? {
        let ordered = geoPriority.compactMap { id in ok.first { $0.source == id } }
        if ordered.isEmpty { return nil }
        func pick(_ f: (SourceResult) -> String?) -> String? { ordered.lazy.compactMap(f).first }
        return GeoInfo(country: pick(\.country), countryCode: pick(\.countryCode)?.uppercased(), region: pick(\.region),
                       city: pick(\.city), isp: pick(\.isp), asn: pick(\.asn), connectionType: pick(\.connectionType))
    }

    // MARK: sources (HTTP requests; executed by the caller)

    public struct SourceRequest {
        public var id: SourceId
        public var url: String
        public var headers: [String: String] = [:]
        public var parse: (String) throws -> SourceResult
    }

    static func q(_ base: String, _ params: [(String, String)]) -> String {
        var u = URLComponents(string: base)!
        let items = params.filter { !$0.1.isEmpty }.map { URLQueryItem(name: $0.0, value: $0.1) }
        if !items.isEmpty { u.queryItems = (u.queryItems ?? []) + items }
        // URLSearchParams encodes '+' etc.; URLComponents leaves '+' alone, so encode it explicitly.
        u.percentEncodedQuery = u.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return u.string!
    }

    static func pathEnc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.!~*'()")) ?? s
    }

    public static func sourceRequests(ip: String, keys k: ApiKeys) -> [SourceRequest] {
        func t(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }
        var list: [SourceRequest] = [
            SourceRequest(id: .IPAPI_IS, url: q("https://api.ipapi.is/", [("q", ip), ("key", t(k.ipapiIs))]), parse: Parsers.ipapiIs),
            SourceRequest(id: .PROXYCHECK, url: q("https://proxycheck.io/v2/\(ip)", [("vpn", "1"), ("asn", "1"), ("risk", "1"), ("key", t(k.proxycheck))]),
                          parse: { try Parsers.proxycheck($0, ip: ip) }),
            // ip-api.com's free tier is plain HTTP only; it goes through the proxy like everything else.
            SourceRequest(id: .IP_API, url: "http://ip-api.com/json/\(ip)?fields=status,message,country,countryCode,regionName,city,isp,org,as,mobile,proxy,hosting,query",
                          parse: Parsers.ipApi),
        ]
        if !t(k.abuseIpDb).isEmpty {
            list.append(SourceRequest(id: .ABUSEIPDB, url: "https://api.abuseipdb.com/api/v2/check?ipAddress=\(ip)&maxAgeInDays=90",
                                      headers: ["Key": t(k.abuseIpDb), "Accept": "application/json"], parse: Parsers.abuseIpDb))
        }
        if !t(k.ipqs).isEmpty {
            list.append(SourceRequest(id: .IPQS, url: "https://ipqualityscore.com/api/json/ip/\(pathEnc(t(k.ipqs)))/\(ip)?strictness=1&allow_public_access_points=true",
                                      parse: Parsers.ipqs))
        }
        if !t(k.scamalyticsUser).isEmpty && !t(k.scamalyticsKey).isEmpty {
            list.append(SourceRequest(id: .SCAMALYTICS, url: q("https://api11.scamalytics.com/v3/\(pathEnc(t(k.scamalyticsUser)))/", [("key", t(k.scamalyticsKey)), ("ip", ip)]),
                                      parse: Parsers.scamalytics))
        }
        return list
    }

    public static let ipEcho = ["https://api.ipify.org?format=json", "https://ifconfig.me/ip", "https://icanhazip.com"]
    public static let reportCacheMs: Int64 = 3_600_000

    /// Body of an IP echo service → the IP, or "" when it isn't one.
    public static func parseEcho(_ body: String) -> String {
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        var ip = t
        if t.hasPrefix("{") { ip = (try? obj(t)).flatMap { str($0, "ip") } ?? "" }
        let ok = !ip.isEmpty && ip.unicodeScalars.allSatisfy { CharacterSet(charactersIn: "0123456789abcdefABCDEF.:").contains($0) }
        return ok ? ip : ""
    }
}
