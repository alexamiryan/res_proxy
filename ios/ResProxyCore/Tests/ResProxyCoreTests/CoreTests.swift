import Foundation
import XCTest
@testable import ResProxyCore

/// shared/test-vectors, relative to this file.
let VEC = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../shared/test-vectors").standardized
let PASS = "correct horse battery staple"

func vec(_ name: String) throws -> String { try String(contentsOf: VEC.appendingPathComponent(name), encoding: .utf8) }
func jsonObject(_ data: Data) throws -> NSDictionary { try JSONSerialization.jsonObject(with: data) as! NSDictionary }
func payload() throws -> BackupPayload { try JSONDecoder().decode(BackupPayload.self, from: Data(try vec("backup-payload.json").utf8)) }

final class BackupTests: XCTestCase {
    func testOpensAndroidAndChromeVectors() throws {
        let want = try payload()
        XCTAssertEqual(try Backup.open(try vec("backup-android.txt"), passphrase: PASS), want)
        XCTAssertEqual(try Backup.open(try vec("backup-chrome.txt"), passphrase: PASS), want)
    }

    func testEncodingMatchesSharedPayloadFieldForField() throws {
        let encoded = try jsonObject(try JSONEncoder.resproxy.encode(try payload()))
        let shared = try jsonObject(Data(try vec("backup-payload.json").utf8))
        XCTAssertEqual(encoded, shared)
    }

    func testRoundTripToleratesWrappingAndWritesIosVector() throws {
        let p = try payload()
        let text = try Backup.seal(p, passphrase: PASS)
        XCTAssertTrue(Backup.looksLikeBackup(text))
        for secret in ["ippass", "gpass", "abuse-key", "PrivateKey"] { XCTAssertFalse(text.contains(secret)) }
        var wrapped = ""
        for (i, ch) in text.enumerated() { if i > 0 && i % 60 == 0 { wrapped += "\n" }; wrapped.append(ch) }
        XCTAssertEqual(try Backup.open(wrapped, passphrase: PASS), p)
        let path = VEC.appendingPathComponent("backup-ios.txt")
        if ProcessInfo.processInfo.environment["WRITE_VECTORS"] == "1" || !FileManager.default.fileExists(atPath: path.path) {
            try (text + "\n").write(to: path, atomically: true, encoding: .utf8)
        }
    }

    func testOpensCommittedIosVector() throws {
        XCTAssertEqual(try Backup.open(try vec("backup-ios.txt"), passphrase: PASS), try payload())
    }

    func testSharedMullvadDeviceOnlyWhenComplete() throws {
        var p = try payload()
        let d = MullvadDevice(id: "d1", name: "Calm Fox", privateKey: "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", publicKey: "pk", ipv4: "10.1.2.2/32", ipv6: "")
        p.mullvadDevice = d
        XCTAssertEqual(try Backup.open(try Backup.seal(p, passphrase: PASS), passphrase: PASS).mullvadDevice, d)
        let partial = try JSONDecoder().decode(BackupPayload.self, from: Data(#"{"mullvadDevice":{"id":"x"}}"#.utf8))
        XCTAssertNil(partial.mullvadDevice)
    }

    func testWrongPassphrase() throws {
        let text = try Backup.seal(BackupPayload(), passphrase: "right")
        XCTAssertThrowsError(try Backup.open(text, passphrase: "wrong")) { XCTAssertEqual($0 as? BackupError, .wrongPassphrase) }
    }

    func testIgnoresUnknownFieldsAndMigratesLegacyChain() throws {
        var p = try JSONDecoder().decode(BackupPayload.self, from: Data(#"{"futureTop":1,"settings":{"futureSetting":"x","connection":{"futureConn":true}},"chain":{"name":"Old","config":"[Interface]"}}"#.utf8))
        XCTAssertEqual(p.settings.schema, 3)
        p.migrateLegacyChain()
        XCTAssertEqual(p.vpnProfiles.count, 1)
        XCTAssertEqual(p.settings.vpnExit, "wg:" + p.vpnProfiles[0].id)
    }
}

final class PresetTests: XCTestCase {
    struct Case: Decodable {
        struct P: Decodable {
            var provider: Provider; var `protocol`: ProxyProtocol; var host: String; var port: Int; var username: String; var password: String
            var country: String; var state: String; var city: String; var sticky: Bool; var lifetimeMinutes: Int; var sessionId: String
        }
        var name: String
        var profile: P
        var endpoint: Endpoint
    }

    func testSharedVectors() throws {
        struct F: Decodable { var cases: [Case] }
        let cases = try JSONDecoder().decode(F.self, from: Data(try vec("presets.json").utf8)).cases
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            var s = AppSettings()
            s.connection.protocol = c.profile.protocol
            s.connection.sticky = c.profile.sticky
            s.connection.lifetimeMinutes = c.profile.lifetimeMinutes
            s.connection.gateways = [c.profile.provider.rawValue: Gateway(host: c.profile.host, port: c.profile.port)]
            s.location = Location(country: c.profile.country, state: c.profile.state, city: c.profile.city)
            let a = Account(provider: c.profile.provider, username: c.profile.username, password: c.profile.password)
            XCTAssertEqual(Presets.build(Profile(account: a, settings: s, sessionId: c.profile.sessionId)), c.endpoint, c.name)
        }
    }

    func testParsesPastedProxyLines() {
        XCTAssertEqual(Presets.parseProxyLine("geo.iproyal.com:12321:user:pass_country-us"),
                       .init(host: "geo.iproyal.com", port: 12321, username: "user", password: "pass_country-us", protocol: nil))
        XCTAssertEqual(Presets.parseProxyLine("user:pass@proxy.geonode.io:9000"),
                       .init(host: "proxy.geonode.io", port: 9000, username: "user", password: "pass", protocol: nil))
        XCTAssertEqual(Presets.parseProxyLine("socks5://u:p@h.example:1080")?.protocol, .SOCKS5)
        XCTAssertNil(Presets.parseProxyLine("just-a-username"))
        XCTAssertEqual(Presets.baseUsername(.GEONODE, "geonode_abc-type-residential-country-us"), "geonode_abc")
    }
}

final class SettingsTests: XCTestCase {
    func decode(_ s: String) throws -> AppSettings { try JSONDecoder().decode(AppSettings.self, from: Data(s.utf8)) }

    func testUpgradesOldSchemas() throws {
        let s = try decode(#"{"schema":1,"killSwitch":false,"connection":{"lifetimeMinutes":1440}}"#)
        XCTAssertTrue(s.killSwitch)
        XCTAssertEqual(s.connection.lifetimeMinutes, MAX_LIFETIME_MINUTES)
        XCTAssertEqual(s.schema, 3)
        XCTAssertEqual(try decode(#"{"schema":3,"connection":{"lifetimeMinutes":1440}}"#).connection.lifetimeMinutes, 1440)
    }

    func testStickySessionsPerProviderAndLocation() {
        let now: Int64 = 1_000_000_000
        var base = AppSettings()
        base.location.country = "us"
        var s = base.withSession("aaa", now: now)
        let usKey = s.sessionKey
        s.location.country = "de"
        s = s.restoreSession(now: now)
        XCTAssertEqual(s.sessionId, "")
        s = s.withSession("bbb", now: now)
        s.location.country = "us"
        s = s.restoreSession(now: now + 1000)
        XCTAssertEqual(s.sessionId, "aaa")
        XCTAssertEqual(s.sessions[usKey]?.createdAt, now)
        var g = AppSettings()
        g.provider = .GEONODE
        g = g.withSession("ccc", now: now)
        XCTAssertEqual(g.restoreSession(now: now + 1440 * 60_000).sessionId, "")
    }
}

final class ReputationTests: XCTestCase {
    func r(_ s: SourceId, _ f: (inout SourceResult) -> Void) -> SourceResult { var x = SourceResult(source: s, ok: true); f(&x); return x }

    func testProxyFlagsAloneStayClean() {
        let rep = Reputation.aggregate(ip: "1.2.3.4", results: [r(.IPAPI_IS) { $0.proxy = true; $0.datacenter = false }, r(.PROXYCHECK) { $0.proxy = true; $0.score = 33 }])
        XCTAssertEqual(rep.verdict, .CLEAN)
        XCTAssertEqual(rep.proxyFlags, 2)
    }

    func testThresholds() {
        XCTAssertEqual(Reputation.aggregate(ip: "x", results: [r(.ABUSEIPDB) { $0.score = 60; $0.reports = 3 }]).verdict, .BAD)
        XCTAssertEqual(Reputation.aggregate(ip: "x", results: [r(.ABUSEIPDB) { $0.score = 5 }]).verdict, .SUSPICIOUS)
        XCTAssertEqual(Reputation.aggregate(ip: "x", results: [r(.IPQS) { $0.score = 80 }]).verdict, .SUSPICIOUS)
        XCTAssertEqual(Reputation.aggregate(ip: "x", results: [r(.SCAMALYTICS) { $0.riskLabel = "very high" }]).verdict, .BAD)
        XCTAssertEqual(Reputation.aggregate(ip: "x", results: [r(.IPAPI_IS) { $0.vpn = true }, r(.IPQS) { $0.vpn = true }]).verdict, .SUSPICIOUS)
        XCTAssertEqual(Reputation.aggregate(ip: "x", results: [.failed(.IPAPI_IS, "x")]).verdict, .UNKNOWN)
    }

    func testParsers() throws {
        let a = try Parsers.ipapiIs(#"{"is_proxy":true,"is_datacenter":false,"asn":{"asn":7922,"org":"Comcast"},"location":{"country":"United States","country_code":"US","city":"Austin"},"company":{"name":"Comcast","type":"isp"}}"#)
        XCTAssertEqual(a.proxy, true); XCTAssertEqual(a.datacenter, false); XCTAssertEqual(a.asn, "AS7922"); XCTAssertEqual(a.isp, "Comcast"); XCTAssertEqual(a.city, "Austin")
        let p = try Parsers.proxycheck(#"{"status":"ok","1.2.3.4":{"proxy":"yes","type":"Residential","risk":70}}"#, ip: "1.2.3.4")
        XCTAssertEqual(p.proxy, true); XCTAssertEqual(p.vpn, false); XCTAssertEqual(p.score, 70)
        let f = try Parsers.ipApi(#"{"status":"fail","message":"quota"}"#)
        XCTAssertFalse(f.ok); XCTAssertEqual(f.error, "quota")
        let ab = try Parsers.abuseIpDb(#"{"data":{"abuseConfidenceScore":0,"totalReports":0,"usageType":"Data Center/Web Hosting/Transit"}}"#)
        XCTAssertEqual(ab.datacenter, true); XCTAssertEqual(ab.score, 0)
        let sc = try Parsers.scamalytics(#"{"scamalytics":{"status":"ok","scamalytics_score":12,"scamalytics_risk":"Low"}}"#)
        XCTAssertEqual(sc.score, 12); XCTAssertEqual(sc.riskLabel, "low")
        let q = try Parsers.ipqs(#"{"success":true,"fraud_score":"90","ASN":123}"#)
        XCTAssertEqual(q.score, 90); XCTAssertEqual(q.asn, "AS123")
    }

    func testDnsbl() {
        XCTAssertEqual(Dnsbl.name("1.2.3.4", zone: "zen.spamhaus.org"), "4.3.2.1.zen.spamhaus.org")
        XCTAssertNil(Dnsbl.name("::1", zone: "z"))
        XCTAssertEqual(Dnsbl.interpret(.DNSBL_SPAMHAUS, ["127.0.0.10"]).listed, false)
        let x = Dnsbl.interpret(.DNSBL_SPAMHAUS, ["127.0.0.4"])
        XCTAssertEqual(x.listed, true); XCTAssertEqual(x.note, "Listed in XBL")
        XCTAssertFalse(Dnsbl.interpret(.DNSBL_SPAMHAUS, ["127.255.255.254"]).ok)
        XCTAssertEqual(Dnsbl.interpret(.DNSBL_SPAMCOP, []).listed, false)
    }

    func testEcho() {
        XCTAssertEqual(Reputation.parseEcho(#"{"ip":"5.6.7.8"}"#), "5.6.7.8")
        XCTAssertEqual(Reputation.parseEcho("2001:db8::1\n"), "2001:db8::1")
        XCTAssertEqual(Reputation.parseEcho("<html>"), "")
    }

    func testSourceUrlsEncodeKeys() {
        var k = ApiKeys()
        k.proxycheck = "a+b"
        let reqs = Reputation.sourceRequests(ip: "1.2.3.4", keys: k)
        XCTAssertEqual(reqs.map(\.id), [.IPAPI_IS, .PROXYCHECK, .IP_API])
        XCTAssertEqual(reqs[1].url, "https://proxycheck.io/v2/1.2.3.4?vpn=1&asn=1&risk=1&key=a%2Bb")
    }
}

final class VpnTests: XCTestCase {
    func testParsesWgQuick() throws {
        let cfg = try WireGuardConfig.parse(try payload().vpnProfiles[0].config)
        XCTAssertEqual(cfg.addresses, ["10.2.0.2/32"])
        XCTAssertEqual(cfg.dns, ["10.2.0.1"])
        XCTAssertEqual(cfg.peers[0].endpointHost, "nl.example.net")
        XCTAssertEqual(cfg.peers[0].endpointPort, 51820)
        XCTAssertEqual(cfg.peers[0].keepalive, 25)
        XCTAssertThrowsError(try WireGuardConfig.parse("[Interface]\nPrivateKey = short\n")) { XCTAssertEqual(($0 as? UserError)?.message, "Invalid WireGuard key") }
    }

    func testMullvadDevicesVector() throws {
        let v = try JSONSerialization.jsonObject(with: Data(try vec("mullvad-devices.json").utf8)) as! [String: Any]
        let body = String(data: try JSONSerialization.data(withJSONObject: v["body"]!), encoding: .utf8)!
        let want = (v["devices"] as! [[String: String]]).map { MullvadDeviceInfo(id: $0["id"]!, name: $0["name"]!, created: $0["created"]!) }
        XCTAssertEqual(Mullvad.parseDevices(body), want)
    }

    func testRelays() throws {
        let body = #"{"locations":{"se-got":{"country":"Sweden","city":"Gothenburg"}},"wireguard":{"relays":[{"hostname":"se-got-wg-001","location":"se-got","active":true,"ipv4_addr_in":"1.1.1.1","public_key":"k1","weight":100},{"hostname":"se-got-wg-002","location":"se-got","active":false,"ipv4_addr_in":"1.1.1.2","public_key":"k2"}]}}"#
        let relays = try Mullvad.parseRelays(body)
        XCTAssertEqual(relays.count, 1)
        XCTAssertEqual(Mullvad.pickRelay("se", relays)?.hostname, "se-got-wg-001")
        XCTAssertNil(Mullvad.pickRelay("de", relays))
        XCTAssertEqual(Mullvad.relayLabel("se-got", relays), "Sweden, Gothenburg")
        var relay = relays[0]
        relay.publicKey = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
        let conf = Mullvad.wgQuick(MullvadDevice(id: "d", name: "n", privateKey: "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", publicKey: "", ipv4: "10.1.2.3/32", ipv6: ""), relay)
        XCTAssertEqual(try WireGuardConfig.parse(conf).peers[0].endpointHost, "1.1.1.1")
    }

    func testKeyPair() {
        let kp = Mullvad.newKeyPair()
        XCTAssertEqual(Mullvad.publicKey(ofPrivate: kp.privateKey), kp.publicKey)
    }
}

final class LocationTests: XCTestCase {
    func testCatalogs() throws {
        let ipr = try Locations.parseIpRoyal(#"{"countries":[{"code":"us","name":"United States","states":{"options":[{"code":"newyork","name":"New York","cities":{"options":[{"code":"NYC","name":"New York"}]}}]},"cities":{"options":[]}}]}"#)
        XCTAssertEqual(ipr[0].states?[0].cities?[0].code, "nyc")
        let geo = try Locations.parseGeonode(#"{"countries":[{"code":"US","name":"United States","states":[{"code":"NY","name":"New York","cities":[{"code":"nyc","name":"NYC"}]},{"code":"CA","name":"California","cities":[{"code":"la","name":"LA"},{"code":"nyc","name":"NYC"}]}]}]}"#)
        XCTAssertEqual(geo[0].code, "us")
        XCTAssertEqual(geo[0].cities?.map(\.code), ["la", "nyc"])
    }

    func testBuiltInAndGeonodeAuth() {
        XCTAssertEqual(Locations.builtIn().countries.first { $0.code == "us" }?.states?.count, 51)
        let a = Account(provider: .GEONODE, username: "geonode_abc-type-residential", password: "p")
        XCTAssertEqual(Locations.geonodeAuth(a)?.0, "geonode_abc")
        XCTAssertEqual(Locations.flag("us"), "🇺🇸")
    }
}
