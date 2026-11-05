// Provider location catalogs — port of android/.../provider/ProviderLocations.kt and chrome/src/core/locations.ts.

import Foundation

/// A selectable location; codes go into the proxy credentials (`country-us`, `state-iowa`, …).
public struct Place: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var code: String
    public var name: String
    public var states: [Place]?
    public var cities: [Place]?
    public var id: String { code }
    public init(code: String, name: String, states: [Place]? = nil, cities: [Place]? = nil) {
        self.code = code; self.name = name; self.states = states; self.cities = cities
    }
}

public enum CatalogSource: String, Codable, Sendable { case BUILT_IN, CACHED, LIVE }

public struct LocationCatalog: Codable, Equatable, Sendable {
    public var countries: [Place]
    public var source: CatalogSource
    public var fetchedAt: Int64
    public var error: String?
    public init(countries: [Place], source: CatalogSource, fetchedAt: Int64, error: String? = nil) {
        self.countries = countries; self.source = source; self.fetchedAt = fetchedAt; self.error = error
    }
}

public enum Locations {
    public static let ipRoyalURL = "https://resi-api.iproyal.com/v1/access/countries"
    public static let geonodeURL = "https://monitor.geonode.com/services/RESIDENTIAL-PREMIUM/targeting-options"
    public static let maxAgeMs: Int64 = 24 * 3_600_000

    /// Basic-auth pair for Geonode's API: the dashboard login, or the base proxy username + password.
    public static func geonodeAuth(_ a: Account) -> (String, String)? {
        let l = a.apiLogin.trimmingCharacters(in: .whitespaces), p = a.apiPassword.trimmingCharacters(in: .whitespaces)
        if !l.isEmpty && !p.isEmpty { return (l, p) }
        let u = a.username.trimmingCharacters(in: .whitespaces), pw = a.password.trimmingCharacters(in: .whitespaces)
        if !u.isEmpty && !pw.isEmpty { return (String(u.split(separator: "-", omittingEmptySubsequences: false).first ?? ""), pw) }
        return nil
    }

    public static func canFetch(_ provider: Provider, _ a: Account?) -> Bool {
        guard let a else { return false }
        switch provider {
        case .IPROYAL: return !a.apiToken.trimmingCharacters(in: .whitespaces).isEmpty
        case .GEONODE: return geonodeAuth(a) != nil
        case .GENERIC: return false
        }
    }

    typealias O = [String: Any]
    static func arr(_ v: Any?) -> [O] { (v as? [Any])?.compactMap { $0 as? O } ?? [] }
    static func s(_ o: O?, _ k: String) -> String {
        if let x = o?[k] as? String { return x }
        if let n = o?[k] as? NSNumber { return n.stringValue }
        return ""
    }
    static func byName(_ a: Place, _ b: Place) -> Bool { a.name.localizedStandardCompare(b.name) == .orderedAscending }
    static func places(_ v: Any?) -> [Place] {
        arr(v).map { Place(code: s($0, "code").lowercased(), name: s($0, "name")) }.filter { !$0.code.isEmpty }.sorted(by: byName)
    }

    static func root(_ body: String) throws -> O {
        guard let o = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? O else { throw UserError("Unexpected location list") }
        return o
    }

    /// IPRoyal `/v1/access/countries`: option lists wrapped in `{prefix, options}`; ISPs dropped.
    public static func parseIpRoyal(_ body: String) throws -> [Place] {
        try arr(root(body)["countries"]).map { c in
            Place(code: s(c, "code"), name: s(c, "name"),
                  states: arr((c["states"] as? O)?["options"]).map { st in
                      Place(code: s(st, "code"), name: s(st, "name"), cities: places((st["cities"] as? O)?["options"]))
                  }.sorted(by: byName),
                  cities: places((c["cities"] as? O)?["options"]))
        }.filter { !$0.code.isEmpty }.sorted(by: byName)
    }

    /// Geonode `targeting-options`: plain arrays, cities nested under states; ASNs dropped.
    public static func parseGeonode(_ body: String) throws -> [Place] {
        try arr(root(body)["countries"]).map { c in
            let states = arr(c["states"]).map { st in Place(code: s(st, "code").lowercased(), name: s(st, "name"), cities: places(st["cities"])) }
                .filter { !$0.code.isEmpty }.sorted(by: byName)
            var seen = Set<String>()
            // "Any state" + city: offer every city in the country.
            let cities = states.flatMap { $0.cities ?? [] }.filter { seen.insert($0.code).inserted }.sorted(by: byName)
            return Place(code: s(c, "code").lowercased(), name: s(c, "name"), states: states, cities: cities)
        }.filter { !$0.code.isEmpty }.sorted(by: byName)
    }

    public static func parse(_ provider: Provider, _ body: String) throws -> [Place] {
        provider == .IPROYAL ? try parseIpRoyal(body) : try parseGeonode(body)
    }

    public static func request(_ provider: Provider, _ a: Account) -> HTTPRequest {
        if provider == .IPROYAL {
            return HTTPRequest(url: ipRoyalURL, headers: ["Authorization": "Bearer \(a.apiToken.trimmingCharacters(in: .whitespaces))", "Accept": "application/json"])
        }
        let (u, p) = geonodeAuth(a) ?? ("", "")
        return HTTPRequest(url: geonodeURL, headers: ["Authorization": "Basic \(Data("\(u):\(p)".utf8).base64EncodedString())", "Accept": "application/json"])
    }

    public static func authError(_ provider: Provider, _ a: Account) -> String {
        if provider == .IPROYAL { return "IPRoyal rejected the API token" }
        return a.apiLogin.trimmingCharacters(in: .whitespaces).isEmpty
            ? "Geonode didn't accept the proxy login for the location list. Add your dashboard login in Settings → Accounts."
            : "Geonode rejected the dashboard login used for the location list"
    }

    static let iso = ("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF " +
        "CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM " +
        "GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI " +
        "LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA " +
        "PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG " +
        "TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW").split(separator: " ").map(String.init)

    static let usStates = ["Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut", "Delaware",
        "District of Columbia", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas",
        "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota", "Mississippi",
        "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire", "New Jersey", "New Mexico", "New York",
        "North Carolina", "North Dakota", "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island",
        "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont", "Virginia", "Washington",
        "West Virginia", "Wisconsin", "Wyoming"].map { Place(code: $0.lowercased().replacingOccurrences(of: " ", with: ""), name: $0) }

    public static func countryName(_ code: String) -> String {
        Foundation.Locale(identifier: "en_US").localizedString(forRegionCode: code.uppercased()) ?? code.uppercased()
    }

    /// ISO countries + US states; cities become free text. Used without provider API credentials.
    public static func builtIn() -> LocationCatalog {
        let countries = iso.map { cc in
            Place(code: cc.lowercased(), name: countryName(cc), states: cc == "US" ? usStates : [], cities: [])
        }.sorted(by: byName)
        return LocationCatalog(countries: countries, source: .BUILT_IN, fetchedAt: 0)
    }

    public static func flag(_ cc: String?) -> String {
        guard let cc, cc.count == 2, cc.allSatisfy({ $0.isASCII && $0.isLetter }) else { return "" }
        return String(String.UnicodeScalarView(cc.uppercased().unicodeScalars.compactMap { Unicode.Scalar(0x1F1E6 + $0.value - 65) }))
    }
}
