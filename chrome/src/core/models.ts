// Data model shared with the Android app. Field names, enum values and defaults mirror
// android/app/src/main/java/dev/resproxy/data/Models.kt so backups move between platforms unchanged.
// See shared/FORMAT.md before changing anything here.

export type Provider = 'IPROYAL' | 'GEONODE' | 'GENERIC'
export const PROVIDERS: Provider[] = ['IPROYAL', 'GEONODE', 'GENERIC']
export const PROVIDER_LABEL: Record<Provider, string> = { IPROYAL: 'IPRoyal', GEONODE: 'Geonode', GENERIC: 'Custom' }

export type Protocol = 'SOCKS5' | 'HTTP'
export type TunnelMode = 'FULL' | 'PROXY_ONLY'
export type SplitMode = 'ALL' | 'ONLY_SELECTED' | 'EXCEPT_SELECTED'
export type Verdict = 'UNKNOWN' | 'CLEAN' | 'SUSPICIOUS' | 'BAD'
export const SEVERITY: Record<Verdict, number> = { UNKNOWN: -1, CLEAN: 0, SUSPICIOUS: 1, BAD: 2 }

export interface Account {
  provider: Provider
  username: string
  password: string
  apiToken: string
  apiLogin: string
  apiPassword: string
}

export interface Location {
  country: string
  countryName: string
  state: string
  stateName: string
  city: string
  cityName: string
}

export interface Gateway { host: string; port: number }

export const MAX_LIFETIME_MINUTES = 7 * 1440

export interface ConnectionSettings {
  protocol: Protocol
  sticky: boolean
  lifetimeMinutes: number
  gateways: Partial<Record<Provider, Gateway>>
  /** Android only (kept for round trips). */
  tunnelMode: TunnelMode
  splitMode: SplitMode
  apps: string[]
  /** Split tunnelling by site, used by the Chrome extension. */
  siteSplitMode: SplitMode
  sites: string[]
}

export interface SavedSession { id: string; createdAt: number; lifetimeMinutes: number }

export interface AppSettings {
  schema: number
  provider: Provider
  location: Location
  sessionId: string
  connection: ConnectionSettings
  killSwitch: boolean
  autoConnect: boolean
  wasConnected: boolean
  recheckOnConnect: boolean
  autoRotate: boolean
  rotateThreshold: Verdict
  maxRotations: number
  healthIntervalSec: number
  savedLocations: Partial<Record<Provider, Location>>
  chainEnabled: boolean
  vpnExit: string
  bypassLan: boolean
  sessions: Record<string, SavedSession>
  /** Unknown fields from newer app versions are kept so they survive an import/export round trip. */
  [extra: string]: unknown
}

export interface ApiKeys {
  abuseIpDb: string
  ipqs: string
  proxycheck: string
  ipapiIs: string
  scamalyticsUser: string
  scamalyticsKey: string
}

export interface VpnProfile { id: string; name: string; config: string }

export interface MullvadDevice {
  id: string
  name: string
  privateKey: string
  publicKey: string
  ipv4: string
  ipv6: string
}

/**
 * signedOut: signed out in this install, or its device was removed from the account (mullvad.net / another install).
 * Then nothing signs in again by itself (that would take a device slot): the user clicks Log in.
 */
export interface MullvadAccount { number: string; expiry: string; device: MullvadDevice | null; signedOut?: boolean }

export const SCHEMA = 3

export const emptyLocation = (): Location => ({ country: '', countryName: '', state: '', stateName: '', city: '', cityName: '' })

export const emptyAccount = (provider: Provider): Account =>
  ({ provider, username: '', password: '', apiToken: '', apiLogin: '', apiPassword: '' })

export const emptyApiKeys = (): ApiKeys =>
  ({ abuseIpDb: '', ipqs: '', proxycheck: '', ipapiIs: '', scamalyticsUser: '', scamalyticsKey: '' })

export const defaultConnection = (): ConnectionSettings => ({
  protocol: 'SOCKS5',
  sticky: true,
  lifetimeMinutes: MAX_LIFETIME_MINUTES,
  gateways: {},
  tunnelMode: 'FULL',
  splitMode: 'ALL',
  apps: [],
  siteSplitMode: 'ALL',
  sites: [],
})

export const defaultSettings = (): AppSettings => ({
  schema: SCHEMA,
  provider: 'IPROYAL',
  location: emptyLocation(),
  sessionId: '',
  connection: defaultConnection(),
  killSwitch: true,
  autoConnect: true,
  wasConnected: false,
  recheckOnConnect: true,
  autoRotate: true,
  rotateThreshold: 'SUSPICIOUS',
  maxRotations: 10,
  healthIntervalSec: 30,
  savedLocations: {},
  chainEnabled: false,
  vpnExit: '',
  bypassLan: true,
  sessions: {},
})

export function locationLabel(l: Location): string {
  return [l.cityName, l.stateName, l.countryName].filter((s) => s.trim()).join(', ') || 'Any location'
}

// ---------- normalization (JSON from storage or a backup → typed values with defaults) ----------

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const str = (v: unknown, d = ''): string => (typeof v === 'string' ? v : d)
const num = (v: unknown, d: number): number => (typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : d)
const bool = (v: unknown, d: boolean): boolean => (typeof v === 'boolean' ? v : d)
const oneOf = <T extends string>(v: unknown, all: readonly T[], d: T): T => (all.includes(v as T) ? (v as T) : d)
const strList = (v: unknown): string[] => (Array.isArray(v) ? [...new Set(v.filter((x): x is string => typeof x === 'string'))] : [])

const PROTOCOLS = ['SOCKS5', 'HTTP'] as const
const SPLITS = ['ALL', 'ONLY_SELECTED', 'EXCEPT_SELECTED'] as const
const VERDICTS = ['UNKNOWN', 'CLEAN', 'SUSPICIOUS', 'BAD'] as const

export function normLocation(v: unknown): Location {
  const o = isObj(v) ? v : {}
  return {
    country: str(o.country), countryName: str(o.countryName),
    state: str(o.state), stateName: str(o.stateName),
    city: str(o.city), cityName: str(o.cityName),
  }
}

export function normAccount(v: unknown): Account | null {
  if (!isObj(v)) return null
  return {
    provider: oneOf(v.provider, PROVIDERS, 'IPROYAL'),
    username: str(v.username), password: str(v.password),
    apiToken: str(v.apiToken), apiLogin: str(v.apiLogin), apiPassword: str(v.apiPassword),
  }
}

export function normApiKeys(v: unknown): ApiKeys {
  const o = isObj(v) ? v : {}
  const d = emptyApiKeys()
  for (const k of Object.keys(d) as (keyof ApiKeys)[]) d[k] = str(o[k])
  return d
}

export function normVpnProfile(v: unknown): VpnProfile | null {
  if (!isObj(v)) return null
  const config = str(v.config)
  if (!config.trim()) return null
  return { id: str(v.id) || randomId(8), name: str(v.name), config }
}

function normConnection(v: unknown): ConnectionSettings {
  const o = isObj(v) ? v : {}
  const d = defaultConnection()
  const gateways: ConnectionSettings['gateways'] = {}
  if (isObj(o.gateways)) {
    for (const [k, g] of Object.entries(o.gateways)) {
      if (PROVIDERS.includes(k as Provider) && isObj(g)) gateways[k as Provider] = { host: str(g.host), port: num(g.port, 0) }
    }
  }
  return {
    ...(o as Partial<ConnectionSettings>),
    protocol: oneOf(o.protocol, PROTOCOLS, d.protocol),
    sticky: bool(o.sticky, d.sticky),
    lifetimeMinutes: num(o.lifetimeMinutes, d.lifetimeMinutes),
    gateways,
    tunnelMode: oneOf(o.tunnelMode, ['FULL', 'PROXY_ONLY'] as const, d.tunnelMode),
    splitMode: oneOf(o.splitMode, SPLITS, d.splitMode),
    apps: strList(o.apps),
    siteSplitMode: oneOf(o.siteSplitMode, SPLITS, d.siteSplitMode),
    sites: strList(o.sites),
  }
}

/** Fills defaults and applies the same schema upgrades as Android's SettingsRepository. */
export function normSettings(v: unknown): AppSettings {
  const o = isObj(v) ? v : {}
  const d = defaultSettings()
  const savedLocations: AppSettings['savedLocations'] = {}
  if (isObj(o.savedLocations)) {
    for (const [k, l] of Object.entries(o.savedLocations)) if (PROVIDERS.includes(k as Provider)) savedLocations[k as Provider] = normLocation(l)
  }
  const sessions: Record<string, SavedSession> = {}
  if (isObj(o.sessions)) {
    for (const [k, s] of Object.entries(o.sessions)) {
      if (isObj(s) && typeof s.id === 'string') sessions[k] = { id: s.id, createdAt: num(s.createdAt, 0), lifetimeMinutes: num(s.lifetimeMinutes, 1) }
    }
  }
  let s: AppSettings = {
    ...o,
    schema: num(o.schema, 0),
    provider: oneOf(o.provider, PROVIDERS, d.provider),
    location: normLocation(o.location),
    sessionId: str(o.sessionId),
    connection: normConnection(o.connection),
    killSwitch: bool(o.killSwitch, d.killSwitch),
    autoConnect: bool(o.autoConnect, d.autoConnect),
    wasConnected: bool(o.wasConnected, d.wasConnected),
    recheckOnConnect: bool(o.recheckOnConnect, d.recheckOnConnect),
    autoRotate: bool(o.autoRotate, d.autoRotate),
    rotateThreshold: oneOf(o.rotateThreshold, VERDICTS, d.rotateThreshold),
    maxRotations: num(o.maxRotations, d.maxRotations),
    healthIntervalSec: num(o.healthIntervalSec, d.healthIntervalSec),
    savedLocations,
    chainEnabled: bool(o.chainEnabled, d.chainEnabled),
    vpnExit: str(o.vpnExit),
    bypassLan: bool(o.bypassLan, d.bypassLan),
    sessions,
  }
  // A missing object (fresh install) is already current; only stored/imported data is upgraded.
  if (!isObj(v)) return d
  if (s.schema < 2) s = { ...s, killSwitch: true, autoConnect: true, autoRotate: true, rotateThreshold: 'SUSPICIOUS', maxRotations: 10 }
  if (s.schema < 3 && s.connection.lifetimeMinutes === 1440) s = { ...s, connection: { ...s.connection, lifetimeMinutes: MAX_LIFETIME_MINUTES } }
  return { ...s, schema: Math.max(s.schema, SCHEMA) }
}

// ---------- sticky sessions per provider + location ----------

/** Lifetime the provider actually applies (Geonode caps sticky sessions at 24 h). */
export function effectiveLifetime(s: AppSettings): number {
  return clamp(s.connection.lifetimeMinutes, 1, s.provider === 'GEONODE' ? 1440 : MAX_LIFETIME_MINUTES)
}

export function sessionKey(s: AppSettings): string {
  return [s.provider, s.location.country, s.location.state, s.location.city].join('|')
}

/** A minute of margin so a session the provider is about to drop is never reused. */
export const sessionExpired = (x: SavedSession, now: number) => now >= x.createdAt + (x.lifetimeMinutes - 1) * 60_000

/** Sets sessionId and remembers it for the current provider + location. */
export function withSession(s: AppSettings, id: string, now = Date.now()): AppSettings {
  const key = sessionKey(s)
  const prev = s.sessions[key]
  const same = prev && prev.id === id && !sessionExpired(prev, now) ? prev : null
  const kept: Record<string, SavedSession> = {}
  for (const [k, v] of Object.entries(s.sessions)) if (k !== key && !sessionExpired(v, now)) kept[k] = v
  if (id) kept[key] = same ?? { id, createdAt: now, lifetimeMinutes: effectiveLifetime(s) }
  return { ...s, sessionId: id, sessions: kept }
}

/** Picks up the remembered session for the current provider + location, or none (a new one gets made). */
export function restoreSession(s: AppSettings, now = Date.now()): AppSettings {
  const x = s.sessions[sessionKey(s)]
  return { ...s, sessionId: x && !sessionExpired(x, now) ? x.id : '' }
}

// ---------- VPN exit selection ----------

export type VpnExit = { kind: 'wg'; id: string } | { kind: 'mullvad'; code: string }

export function parseVpnExit(s: string): VpnExit | null {
  if (s.startsWith('wg:')) return { kind: 'wg', id: s.slice(3) }
  if (s.startsWith('mullvad:')) return { kind: 'mullvad', code: s.slice(8) }
  return null
}
export const vpnExitImported = (id: string) => `wg:${id}`
export const vpnExitMullvad = (code: string) => `mullvad:${code}`

// ---------- helpers ----------

export function clamp(v: number, lo: number, hi: number) { return Math.min(hi, Math.max(lo, v)) }

export function randomId(len: number, alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'): string {
  const out: string[] = []
  const buf = new Uint8Array(len * 2)
  while (out.length < len) {
    crypto.getRandomValues(buf)
    for (const b of buf) {
      // Rejection sampling keeps the distribution uniform.
      if (b < 256 - (256 % alphabet.length) && out.length < len) out.push(alphabet[b % alphabet.length])
    }
  }
  return out.join('')
}
