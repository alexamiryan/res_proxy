// Provider credential presets — port of android/.../provider/Presets.kt. Shared vectors: shared/test-vectors/presets.json.

import { type Account, type AppSettings, type Protocol, type Provider, MAX_LIFETIME_MINUTES, clamp, randomId } from './models'

/** Everything the upstream needs, derived from Account + AppSettings at connect time. */
export interface Profile {
  provider: Provider
  protocol: Protocol
  host: string
  port: number
  username: string
  password: string
  country: string
  state: string
  city: string
  sticky: boolean
  lifetimeMinutes: number
  sessionId: string
}

/** Concrete upstream credentials after the provider's targeting/session syntax has been applied. */
export interface Endpoint {
  host: string
  port: number
  protocol: Protocol
  username: string
  password: string
}

export function profileFrom(account: Account, s: AppSettings, sessionId = s.sessionId): Profile {
  const gw = s.connection.gateways[account.provider]
  return {
    provider: account.provider,
    protocol: s.connection.protocol,
    host: gw?.host ?? '',
    port: gw?.port ?? 0,
    username: account.username,
    password: account.password,
    country: s.location.country,
    state: s.location.state,
    city: s.location.city,
    sticky: s.connection.sticky,
    lifetimeMinutes: s.connection.lifetimeMinutes,
    sessionId,
  }
}

interface Preset {
  defaultHost: string
  defaultPort(protocol: Protocol, sticky: boolean): number
  formatHint: string
  build(p: Profile): Endpoint
}

const hostOf = (p: Profile, preset: Preset) => p.host.trim() || preset.defaultHost
const portOf = (p: Profile, preset: Preset) => (p.port > 0 ? p.port : preset.defaultPort(p.protocol, p.sticky))

/** Normalizes a targeting value ("New York" → "newyork"); null when unset. */
export function token(value: string): string | null {
  const t = value.trim().toLowerCase().replace(/[^a-z0-9]/g, '')
  return t || null
}

export function ipRoyalLifetime(minutes: number): string {
  const m = clamp(minutes, 1, MAX_LIFETIME_MINUTES)
  if (m % 1440 === 0) return `${m / 1440}d`
  if (m % 60 === 0) return `${m / 60}h`
  return `${m}m`
}

/** IPRoyal: targeting and session go on the *password*: `pass_country-us_state-…_city-…_session-<id>_lifetime-30m`. */
export const IpRoyal: Preset = {
  defaultHost: 'geo.iproyal.com',
  defaultPort: (protocol) => (protocol === 'SOCKS5' ? 32325 : 12321),
  formatHint: 'password_country-xx_state-…_city-…_session-<id>_lifetime-30m',
  build(p) {
    let pass = p.password.trim().replace(/_(country|state|region|city|session|lifetime|streaming|skipispstatic)-.*$/, '')
    const c = token(p.country), st = token(p.state), ci = token(p.city)
    if (c) pass += `_country-${c}`
    if (st) pass += `_state-${st}`
    if (ci) pass += `_city-${ci}`
    if (p.sticky && p.sessionId) pass += `_session-${p.sessionId}_lifetime-${ipRoyalLifetime(p.lifetimeMinutes)}`
    return { host: hostOf(p, this), port: portOf(p, this), protocol: p.protocol, username: p.username.trim(), password: pass }
  },
}

/** Geonode: targeting and session go on the *username*: `user-type-residential-country-xx-…-session-<id>-lifetime-<min>`. */
export const Geonode: Preset = {
  defaultHost: 'proxy.geonode.io',
  defaultPort: (protocol, sticky) => (protocol === 'HTTP' ? (sticky ? 10000 : 9000) : (sticky ? 12000 : 11000)),
  formatHint: 'username-type-residential-country-xx-state-…-city-…-session-<id>-lifetime-<min>',
  build(p) {
    let user = p.username.trim().replace(/-(type|country|state|city|asn|session|lifetime)-.*$/, '') + '-type-residential'
    const c = token(p.country), st = token(p.state), ci = token(p.city)
    if (c) user += `-country-${c}`
    if (st) user += `-state-${st}`
    if (ci) user += `-city-${ci}`
    if (p.sticky && p.sessionId) user += `-session-${p.sessionId}-lifetime-${clamp(p.lifetimeMinutes, 1, 1440)}`
    return { host: hostOf(p, this), port: portOf(p, this), protocol: p.protocol, username: user, password: p.password.trim() }
  },
}

/** Any SOCKS5/HTTP proxy; credentials are used verbatim. */
export const Generic: Preset = {
  defaultHost: '',
  defaultPort: (protocol) => (protocol === 'SOCKS5' ? 1080 : 8080),
  formatHint: 'Credentials are sent exactly as entered',
  build(p) {
    return { host: hostOf(p, this), port: portOf(p, this), protocol: p.protocol, username: p.username.trim(), password: p.password }
  },
}

export function presetOf(provider: Provider): Preset {
  return provider === 'IPROYAL' ? IpRoyal : provider === 'GEONODE' ? Geonode : Generic
}

export const buildEndpoint = (p: Profile): Endpoint => presetOf(p.provider).build(p)

/** 8 lowercase alphanumerics: accepted by both IPRoyal and Geonode session syntax. */
export const newSessionId = () => randomId(8)

/** The user-facing base username: targeting params pasted along with it are stripped. */
export function baseUsername(provider: Provider, username: string): string {
  return provider === 'GEONODE' ? username.trim().replace(/-(type|country|state|city|asn|session|lifetime)-.*$/, '') : username.trim()
}

/**
 * Users paste whole proxy lines (`host:port:user:pass`, `user:pass@host:port`, `socks5://user:pass@host:port`).
 * Returns the parts found, or null when the text doesn't look like one.
 */
export function parseProxyLine(text: string): { host?: string; port?: number; username: string; password: string; protocol?: Protocol } | null {
  const t = text.trim()
  const url = /^(socks5h?|socks|https?):\/\/([^:@\s]*):([^@\s]*)@([^:\s/]+):(\d+)\/?$/i.exec(t)
  if (url) {
    return { protocol: url[1].toLowerCase().startsWith('socks') ? 'SOCKS5' : 'HTTP', username: decodeURIComponent(url[2]), password: decodeURIComponent(url[3]), host: url[4], port: +url[5] }
  }
  const at = /^([^:@\s]+):([^@\s]+)@([^:\s]+):(\d+)$/.exec(t)
  if (at) return { username: at[1], password: at[2], host: at[3], port: +at[4] }
  const colon = t.split(':')
  if (colon.length === 4 && /^\d+$/.test(colon[1])) return { host: colon[0], port: +colon[1], username: colon[2], password: colon[3] }
  return null
}
