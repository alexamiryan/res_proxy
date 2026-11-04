// Provider location catalogs — port of android/.../provider/ProviderLocations.kt (parsers + built-in fallback).

import type { Account, Provider } from './models'

/** A selectable location; codes go into the proxy credentials (`country-us`, `state-iowa`, …). */
export interface Place {
  code: string
  name: string
  states?: Place[]
  cities?: Place[]
}

export type CatalogSource = 'BUILT_IN' | 'CACHED' | 'LIVE'

export interface LocationCatalog {
  countries: Place[]
  source: CatalogSource
  fetchedAt: number
  error?: string
}

export const IPROYAL_URL = 'https://resi-api.iproyal.com/v1/access/countries'
export const GEONODE_URL = 'https://monitor.geonode.com/services/RESIDENTIAL-PREMIUM/targeting-options'
export const CATALOG_MAX_AGE_MS = 24 * 3600_000

/** Basic-auth pair for Geonode's API: the dashboard login, or the base proxy username + password. */
export function geonodeAuth(a: Account): [string, string] | null {
  if (a.apiLogin.trim() && a.apiPassword.trim()) return [a.apiLogin.trim(), a.apiPassword.trim()]
  if (a.username.trim() && a.password.trim()) return [a.username.trim().split('-')[0], a.password.trim()]
  return null
}

export function canFetch(provider: Provider, a: Account | undefined): boolean {
  if (!a) return false
  if (provider === 'IPROYAL') return !!a.apiToken.trim()
  if (provider === 'GEONODE') return geonodeAuth(a) !== null
  return false
}

type J = Record<string, unknown>
const arr = (v: unknown): J[] => (Array.isArray(v) ? v.filter((x): x is J => typeof x === 'object' && x !== null) : [])
const s = (o: J | undefined, k: string) => (o && (typeof o[k] === 'string' || typeof o[k] === 'number') ? String(o[k]) : '')
const byName = (a: Place, b: Place) => a.name.localeCompare(b.name)
const places = (v: unknown): Place[] =>
  arr(v).map((o) => ({ code: s(o, 'code').toLowerCase(), name: s(o, 'name') })).filter((p) => p.code).sort(byName)

/** IPRoyal `/v1/access/countries`: option lists wrapped in `{prefix, options}`; ISPs dropped. */
export function parseIpRoyal(body: string): Place[] {
  const root = JSON.parse(body) as J
  return arr(root.countries).map((c) => ({
    code: s(c, 'code'),
    name: s(c, 'name'),
    states: arr((c.states as J | undefined)?.options).map((st) => ({
      code: s(st, 'code'), name: s(st, 'name'), cities: places((st.cities as J | undefined)?.options),
    })).sort(byName),
    cities: places((c.cities as J | undefined)?.options),
  })).filter((c) => c.code).sort(byName)
}

/** Geonode `targeting-options`: plain arrays, cities nested under states; ASNs dropped. */
export function parseGeonode(body: string): Place[] {
  const root = JSON.parse(body) as J
  return arr(root.countries).map((c) => {
    const states = arr(c.states).map((st) => ({ code: s(st, 'code').toLowerCase(), name: s(st, 'name'), cities: places(st.cities) }))
      .filter((x) => x.code).sort(byName)
    const seen = new Set<string>()
    // "Any state" + city: offer every city in the country.
    const cities = states.flatMap((x) => x.cities).filter((x) => (seen.has(x.code) ? false : (seen.add(x.code), true))).sort(byName)
    return { code: s(c, 'code').toLowerCase(), name: s(c, 'name'), states, cities }
  }).filter((c) => c.code).sort(byName)
}

export function parseCatalog(provider: Provider, body: string): Place[] {
  return provider === 'IPROYAL' ? parseIpRoyal(body) : parseGeonode(body)
}

export function catalogRequest(provider: Provider, a: Account): { url: string; headers: Record<string, string> } {
  if (provider === 'IPROYAL') return { url: IPROYAL_URL, headers: { Authorization: `Bearer ${a.apiToken.trim()}`, Accept: 'application/json' } }
  const [u, p] = geonodeAuth(a)!
  return { url: GEONODE_URL, headers: { Authorization: `Basic ${btoa(`${u}:${p}`)}`, Accept: 'application/json' } }
}

export function authError(provider: Provider, a: Account): string {
  if (provider === 'IPROYAL') return 'IPRoyal rejected the API token'
  return a.apiLogin.trim()
    ? 'Geonode rejected the dashboard login used for the location list'
    : "Geonode didn't accept the proxy login for the location list. Add your dashboard login in Settings → Accounts."
}

const ISO = ('AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF ' +
  'CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM ' +
  'GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI ' +
  'LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA ' +
  'PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG ' +
  'TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW').split(' ')

const US_STATES = ['Alabama', 'Alaska', 'Arizona', 'Arkansas', 'California', 'Colorado', 'Connecticut', 'Delaware',
  'District of Columbia', 'Florida', 'Georgia', 'Hawaii', 'Idaho', 'Illinois', 'Indiana', 'Iowa', 'Kansas',
  'Kentucky', 'Louisiana', 'Maine', 'Maryland', 'Massachusetts', 'Michigan', 'Minnesota', 'Mississippi',
  'Missouri', 'Montana', 'Nebraska', 'Nevada', 'New Hampshire', 'New Jersey', 'New Mexico', 'New York',
  'North Carolina', 'North Dakota', 'Ohio', 'Oklahoma', 'Oregon', 'Pennsylvania', 'Rhode Island',
  'South Carolina', 'South Dakota', 'Tennessee', 'Texas', 'Utah', 'Vermont', 'Virginia', 'Washington',
  'West Virginia', 'Wisconsin', 'Wyoming'].map((n) => ({ code: n.toLowerCase().replace(/ /g, ''), name: n }))

let names: Intl.DisplayNames | null = null
export function countryName(code: string): string {
  try {
    names ??= new Intl.DisplayNames(['en'], { type: 'region' })
    return names.of(code.toUpperCase()) ?? code.toUpperCase()
  } catch {
    return code.toUpperCase()
  }
}

/** ISO countries + US states; cities become free text. Used without provider API credentials. */
export function builtInCatalog(): LocationCatalog {
  const countries = ISO.map((cc) => {
    const code = cc.toLowerCase()
    return { code, name: countryName(cc), states: code === 'us' ? US_STATES : [], cities: [] }
  }).sort(byName)
  return { countries, source: 'BUILT_IN', fetchedAt: 0 }
}

export function flagEmoji(cc: string | null | undefined): string {
  if (!cc || !/^[a-z]{2}$/i.test(cc)) return ''
  return String.fromCodePoint(...[...cc.toUpperCase()].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65))
}
