// IP reputation: per-source parsers, DNSBL interpretation and the verdict rules.
// Port of android/.../reputation/{Model,Parsers,Dnsbl,ReputationAggregator}.kt — keep the rules identical.

import { type ApiKeys, type Verdict, SEVERITY } from './models'

export type SourceId =
  | 'IPAPI_IS' | 'PROXYCHECK' | 'IP_API' | 'ABUSEIPDB' | 'IPQS' | 'SCAMALYTICS'
  | 'DNSBL_SPAMHAUS' | 'DNSBL_SPAMCOP' | 'DNSBL_BARRACUDA' | 'DNSBL_UCEPROTECT'

export const SOURCE_LABEL: Record<SourceId, string> = {
  IPAPI_IS: 'ipapi.is', PROXYCHECK: 'proxycheck.io', IP_API: 'ip-api.com', ABUSEIPDB: 'AbuseIPDB',
  IPQS: 'IPQualityScore', SCAMALYTICS: 'Scamalytics', DNSBL_SPAMHAUS: 'Spamhaus ZEN', DNSBL_SPAMCOP: 'SpamCop',
  DNSBL_BARRACUDA: 'Barracuda', DNSBL_UCEPROTECT: 'UCEPROTECT L1',
}

/** One source's answer, normalized. Missing field = the source doesn't report that signal. */
export interface SourceResult {
  source: SourceId
  ok: boolean
  error?: string
  proxy?: boolean
  vpn?: boolean
  tor?: boolean
  datacenter?: boolean
  abuser?: boolean
  listed?: boolean
  score?: number
  riskLabel?: string
  recentAbuse?: boolean
  reports?: number
  country?: string
  countryCode?: string
  region?: string
  city?: string
  isp?: string
  asn?: string
  connectionType?: string
  note?: string
}

export interface Reason { verdict: Verdict; text: string }

export interface GeoInfo {
  country?: string
  countryCode?: string
  region?: string
  city?: string
  isp?: string
  asn?: string
  connectionType?: string
}

export interface ReputationReport {
  ip: string
  verdict: Verdict
  reasons: Reason[]
  results: SourceResult[]
  geo: GeoInfo | null
  proxyFlags: number
  checkedAt: number
}

export const failed = (source: SourceId, error: string): SourceResult => ({ source, ok: false, error })

// ---------- parsers ----------

type J = Record<string, unknown>
const obj = (body: string): J => {
  const v = JSON.parse(body)
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new Error('not a JSON object')
  return v as J
}
const sub = (o: J | undefined, k: string): J | undefined => {
  const v = o?.[k]
  return typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as J) : undefined
}
const str = (o: J | undefined, k: string): string | undefined => {
  const v = o?.[k]
  if (v === null || v === undefined || typeof v === 'object') return undefined
  const t = String(v)
  return t && t !== 'null' ? t : undefined
}
const bool = (o: J | undefined, k: string): boolean | undefined => {
  const v = o?.[k]
  if (typeof v === 'boolean') return v
  switch (typeof v === 'string' || typeof v === 'number' ? String(v).toLowerCase() : '') {
    case 'yes': case 'true': case '1': return true
    case 'no': case 'false': case '0': return false
    default: return undefined
  }
}
const int = (o: J | undefined, k: string): number | undefined => {
  const v = o?.[k]
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN
  return Number.isFinite(n) ? Math.trunc(n) : undefined
}
const clean = (r: SourceResult): SourceResult =>
  Object.fromEntries(Object.entries(r).filter(([, v]) => v !== undefined)) as unknown as SourceResult

export const Parsers = {
  ipapiIs(body: string): SourceResult {
    const o = obj(body)
    const err = str(o, 'error')
    if (err) return failed('IPAPI_IS', err)
    const company = sub(o, 'company'), asn = sub(o, 'asn'), loc = sub(o, 'location')
    const asnNo = str(asn, 'asn')
    const abuser = str(company, 'abuser_score')
    return clean({
      source: 'IPAPI_IS', ok: true,
      proxy: bool(o, 'is_proxy'), vpn: bool(o, 'is_vpn'), tor: bool(o, 'is_tor'),
      datacenter: bool(o, 'is_datacenter'), abuser: bool(o, 'is_abuser'),
      country: str(loc, 'country'), countryCode: str(loc, 'country_code'), region: str(loc, 'state'), city: str(loc, 'city'),
      isp: str(company, 'name') ?? str(asn, 'org'),
      asn: asnNo ? `AS${asnNo}` : undefined,
      connectionType: str(company, 'type'),
      note: abuser ? `Abuser score ${abuser}` : undefined,
    })
  },

  proxycheck(body: string, ip: string): SourceResult {
    const o = obj(body)
    const status = str(o, 'status')
    if (status && status !== 'ok' && status !== 'warning') return failed('PROXYCHECK', str(o, 'message') ?? status)
    const r = sub(o, ip)
    if (!r) return failed('PROXYCHECK', str(o, 'message') ?? 'no data')
    const type = str(r, 'type')
    const eq = (a: string | undefined, b: string) => (a === undefined ? undefined : a.toLowerCase() === b.toLowerCase())
    return clean({
      source: 'PROXYCHECK', ok: true,
      proxy: eq(str(r, 'proxy'), 'yes'), vpn: eq(type, 'VPN'), tor: eq(type, 'TOR'), datacenter: eq(type, 'Hosting'),
      score: int(r, 'risk'),
      country: str(r, 'country'), countryCode: str(r, 'isocode'), region: str(r, 'region'), city: str(r, 'city'),
      isp: str(r, 'provider') ?? str(r, 'organisation'), asn: str(r, 'asn'), connectionType: type,
    })
  },

  ipApi(body: string): SourceResult {
    const o = obj(body)
    if (str(o, 'status') !== 'success') return failed('IP_API', str(o, 'message') ?? 'failed')
    return clean({
      source: 'IP_API', ok: true,
      proxy: bool(o, 'proxy'), datacenter: bool(o, 'hosting'),
      country: str(o, 'country'), countryCode: str(o, 'countryCode'), region: str(o, 'regionName'), city: str(o, 'city'),
      isp: str(o, 'isp') ?? str(o, 'org'), asn: str(o, 'as')?.split(' ')[0],
      connectionType: bool(o, 'mobile') === true ? 'mobile' : undefined,
    })
  },

  abuseIpDb(body: string): SourceResult {
    const o = obj(body)
    if (o.errors !== undefined) return failed('ABUSEIPDB', JSON.stringify(o.errors).slice(0, 120))
    const d = sub(o, 'data')
    if (!d) return failed('ABUSEIPDB', 'no data')
    const usage = str(d, 'usageType')
    const last = str(d, 'lastReportedAt')
    return clean({
      source: 'ABUSEIPDB', ok: true,
      tor: bool(d, 'isTor'), datacenter: usage === undefined ? undefined : usage.toLowerCase().includes('data center'),
      score: int(d, 'abuseConfidenceScore'), reports: int(d, 'totalReports'),
      countryCode: str(d, 'countryCode'), isp: str(d, 'isp'), connectionType: usage,
      note: last ? `Last reported ${last}` : undefined,
    })
  },

  ipqs(body: string): SourceResult {
    const o = obj(body)
    if (bool(o, 'success') === false) return failed('IPQS', str(o, 'message') ?? 'failed')
    const asn = int(o, 'ASN')
    return clean({
      source: 'IPQS', ok: true,
      proxy: bool(o, 'proxy'), vpn: bool(o, 'vpn'), tor: bool(o, 'tor'),
      score: int(o, 'fraud_score'), recentAbuse: bool(o, 'recent_abuse'),
      countryCode: str(o, 'country_code'), region: str(o, 'region'), city: str(o, 'city'),
      isp: str(o, 'ISP'), asn: asn === undefined ? undefined : `AS${asn}`,
      connectionType: str(o, 'connection_type'),
      note: bool(o, 'bot_status') === true ? 'Recent bot activity' : undefined,
    })
  },

  /** Accepts both the v3 (`{"scamalytics":{...}}`) and legacy flat response shapes. */
  scamalytics(body: string): SourceResult {
    const root = obj(body)
    const o = sub(root, 'scamalytics') ?? root
    const status = str(o, 'status')
    if (status && status !== 'ok') return failed('SCAMALYTICS', str(o, 'error') ?? status)
    const score = int(o, 'scamalytics_score') ?? int(o, 'score')
    const risk = str(o, 'scamalytics_risk') ?? str(o, 'risk')
    if (score === undefined && risk === undefined) return failed('SCAMALYTICS', str(o, 'error') ?? 'no data')
    return clean({ source: 'SCAMALYTICS', ok: true, score, riskLabel: risk?.toLowerCase() })
  },
}

// ---------- DNS blacklists ----------

export const DNSBL_ZONES: [SourceId, string][] = [
  ['DNSBL_SPAMHAUS', 'zen.spamhaus.org'],
  ['DNSBL_SPAMCOP', 'bl.spamcop.net'],
  ['DNSBL_BARRACUDA', 'b.barracudacentral.org'],
  ['DNSBL_UCEPROTECT', 'dnsbl-1.uceprotect.net'],
]

export function dnsblName(ip: string, zone: string): string | null {
  const o = ip.split('.')
  return o.length === 4 ? `${o.reverse().join('.')}.${zone}` : null
}

/** [answers]: A records for the reversed-octet name; empty = not listed (NXDOMAIN). */
export function interpretDnsbl(source: SourceId, answers: string[]): SourceResult {
  if (answers.length === 0) return { source, ok: true, listed: false }
  // 127.255.255.x: Spamhaus refusing the query (e.g. via a public resolver) — not a listing.
  if (answers.every((a) => a.startsWith('127.255.255.'))) return failed(source, 'Query refused by blacklist (resolver blocked)')
  if (source === 'DNSBL_SPAMHAUS') {
    const codes = answers.map((a) => Number(a.split('.').pop())).filter(Number.isFinite)
    const abuse = codes.filter((c) => c >= 2 && c <= 9)
    const pbl = codes.some((c) => c === 10 || c === 11)
    if (abuse.length === 0) {
      return clean({ source, ok: true, listed: false, note: pbl ? 'In PBL (end-user/residential range) — expected for residential IPs' : undefined })
    }
    const lists = [...new Set(abuse.map((c) => (c === 2 || c === 3 || c === 9 ? 'SBL' : 'XBL')))]
    return { source, ok: true, listed: true, note: `Listed in ${lists.join('/')}` }
  }
  return { source, ok: true, listed: answers.some((a) => a.startsWith('127.')), note: answers.join(', ') }
}

// ---------- verdict ----------

/**
 * "Detected as a proxy" is deliberately *not* negative on its own: residential proxy IPs are routinely
 * flagged as such. What matters is abuse history, blacklists, Tor, and whether the IP is really residential.
 */
export function aggregate(ip: string, results: SourceResult[], now = Date.now()): ReputationReport {
  const ok = results.filter((r) => r.ok)
  const reasons: Reason[] = []
  const bad = (text: string) => reasons.push({ verdict: 'BAD', text })
  const sus = (text: string) => reasons.push({ verdict: 'SUSPICIOUS', text })

  for (const r of ok) {
    const src = SOURCE_LABEL[r.source]
    if (r.tor === true) bad(`Tor exit node (${src})`)
    if (r.listed === true) bad(`Listed on ${src} blacklist`)
    if (r.abuser === true) bad(`Flagged as abuser by ${src}`)
    if (r.recentAbuse === true) bad(`Recent abuse reported by ${src}`)
    if (r.datacenter === true) sus(`Datacenter/hosting IP, not residential (${src})`)
    const score = r.score
    switch (r.source) {
      case 'ABUSEIPDB':
        if (score !== undefined) {
          const txt = `AbuseIPDB confidence ${score}%` + (r.reports !== undefined ? ` (${r.reports} reports)` : '')
          if (score >= 50) bad(txt); else if (score >= 1) sus(txt)
        }
        break
      case 'IPQS':
        if (score !== undefined) { if (score >= 85) bad(`IPQS fraud score ${score}`); else if (score >= 75) sus(`IPQS fraud score ${score}`) }
        break
      case 'SCAMALYTICS': {
        const sfx = score !== undefined ? ` (${score})` : ''
        if (r.riskLabel === 'high' || r.riskLabel === 'very high') bad(`Scamalytics risk ${r.riskLabel}${sfx}`)
        else if (r.riskLabel === 'medium') sus(`Scamalytics risk medium${sfx}`)
        break
      }
      case 'PROXYCHECK':
        if (score !== undefined && score >= 67) sus(`proxycheck.io risk ${score}`)
        break
    }
  }
  const vpnCount = ok.filter((r) => r.vpn === true).length
  if (vpnCount >= 2) sus(`Flagged as VPN by ${vpnCount} sources`)

  const verdict: Verdict = ok.length === 0 ? 'UNKNOWN'
    : reasons.reduce<Verdict>((m, r) => (SEVERITY[r.verdict] > SEVERITY[m] ? r.verdict : m), 'CLEAN')
  const seen = new Set<string>()
  const distinct = reasons.filter((r) => { const k = r.verdict + r.text; return seen.has(k) ? false : (seen.add(k), true) })
  return {
    ip, verdict,
    reasons: distinct.sort((a, b) => SEVERITY[b.verdict] - SEVERITY[a.verdict]),
    results, geo: geo(ok), proxyFlags: ok.filter((r) => r.proxy === true).length, checkedAt: now,
  }
}

const GEO_PRIORITY: SourceId[] = ['IPAPI_IS', 'IP_API', 'PROXYCHECK', 'IPQS', 'ABUSEIPDB']

function geo(ok: SourceResult[]): GeoInfo | null {
  const ordered = GEO_PRIORITY.map((id) => ok.find((r) => r.source === id)).filter((r): r is SourceResult => !!r)
  if (ordered.length === 0) return null
  const pick = (f: (r: SourceResult) => string | undefined) => ordered.map(f).find((v) => v !== undefined)
  return {
    country: pick((r) => r.country), countryCode: pick((r) => r.countryCode)?.toUpperCase(), region: pick((r) => r.region),
    city: pick((r) => r.city), isp: pick((r) => r.isp), asn: pick((r) => r.asn), connectionType: pick((r) => r.connectionType),
  }
}

// ---------- sources (HTTP requests; executed by the caller) ----------

export interface SourceRequest {
  id: SourceId
  url: string
  headers?: Record<string, string>
  parse: (body: string) => SourceResult
}

export function sourceRequests(ip: string, k: ApiKeys): SourceRequest[] {
  const q = (base: string, params: Record<string, string>) => {
    const u = new URL(base)
    for (const [key, v] of Object.entries(params)) if (v) u.searchParams.set(key, v)
    return u.toString()
  }
  const list: SourceRequest[] = [
    { id: 'IPAPI_IS', url: q('https://api.ipapi.is/', { q: ip, key: k.ipapiIs.trim() }), parse: Parsers.ipapiIs },
    {
      id: 'PROXYCHECK', url: q(`https://proxycheck.io/v2/${ip}`, { vpn: '1', asn: '1', risk: '1', key: k.proxycheck.trim() }),
      parse: (b) => Parsers.proxycheck(b, ip),
    },
    // ip-api.com's free tier is plain HTTP only; it goes through the proxy like everything else.
    {
      id: 'IP_API', url: `http://ip-api.com/json/${ip}?fields=status,message,country,countryCode,regionName,city,isp,org,as,mobile,proxy,hosting,query`,
      parse: Parsers.ipApi,
    },
  ]
  if (k.abuseIpDb.trim()) {
    list.push({
      id: 'ABUSEIPDB', url: `https://api.abuseipdb.com/api/v2/check?ipAddress=${ip}&maxAgeInDays=90`,
      headers: { Key: k.abuseIpDb.trim() }, parse: Parsers.abuseIpDb,
    })
  }
  if (k.ipqs.trim()) {
    list.push({
      id: 'IPQS', url: `https://ipqualityscore.com/api/json/ip/${encodeURIComponent(k.ipqs.trim())}/${ip}?strictness=1&allow_public_access_points=true`,
      parse: Parsers.ipqs,
    })
  }
  if (k.scamalyticsUser.trim() && k.scamalyticsKey.trim()) {
    list.push({
      id: 'SCAMALYTICS', url: q(`https://api11.scamalytics.com/v3/${encodeURIComponent(k.scamalyticsUser.trim())}/`, { key: k.scamalyticsKey.trim(), ip }),
      parse: Parsers.scamalytics,
    })
  }
  return list
}

export const IP_ECHO = ['https://api.ipify.org?format=json', 'https://ifconfig.me/ip', 'https://icanhazip.com']
export const PROBE_URL = 'http://connectivitycheck.gstatic.com/generate_204'
export const REPORT_CACHE_MS = 3600_000

/** Body of an IP echo service → the IP, or '' when it isn't one. */
export function parseEcho(body: string): string {
  const t = body.trim()
  const ip = t.startsWith('{') ? String((JSON.parse(t) as J).ip ?? '') : t
  return /^[0-9a-f.:]+$/i.test(ip) ? ip : ''
}
