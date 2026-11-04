// WireGuard config parsing and the Mullvad API — ports of android/.../net/{WireGuard,Mullvad}.kt.

import type { MullvadAccount, MullvadDevice } from './models'
import { b64, unb64 } from './backup'

// ---------- WireGuard (.conf) ----------

export interface WgPeer {
  publicKey: string
  presharedKey?: string
  endpointHost: string
  endpointPort: number
  allowedIps: string[]
  keepalive: number
}

export interface WireGuardConfig {
  privateKey: string
  addresses: string[]
  dns: string[]
  mtu: number
  peers: WgPeer[]
}

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/

function wgKey(v: string): string {
  let k: Uint8Array | null = null
  try { k = unb64(v.trim()) } catch { /* invalid */ }
  if (!k || k.length !== 32) throw new Error('Invalid WireGuard key')
  return v.trim()
}

/** Parses a wg-quick `.conf` (the format VPN providers such as Proton export). Throws with a readable message. */
export function parseWireGuard(text: string): WireGuardConfig {
  let section = ''
  let privateKey: string | null = null
  const addresses: string[] = []
  const dns: string[] = []
  let mtu = 1280
  const peers: WgPeer[] = []
  let peer: Record<string, string> | null = null
  const list = (v: string) => v.split(',').map((x) => x.trim()).filter(Boolean)

  const flush = () => {
    if (!peer) return
    const pub = peer.publickey
    if (!pub) throw new Error('[Peer] has no PublicKey')
    const ep = peer.endpoint
    if (!ep) throw new Error('[Peer] has no Endpoint')
    const i = ep.lastIndexOf(':')
    if (i <= 0) throw new Error(`Bad Endpoint: ${ep}`)
    const port = Number(ep.slice(i + 1))
    if (!Number.isInteger(port)) throw new Error(`Bad Endpoint port: ${ep}`)
    peers.push({
      publicKey: wgKey(pub),
      presharedKey: peer.presharedkey ? wgKey(peer.presharedkey) : undefined,
      endpointHost: ep.slice(0, i).replace(/^\[|\]$/g, ''),
      endpointPort: port,
      allowedIps: list(peer.allowedips ?? '0.0.0.0/0, ::/0'),
      keepalive: Number(peer.persistentkeepalive) || 25,
    })
    peer = null
  }

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.split('#')[0].trim()
    if (!line) continue
    if (line.startsWith('[')) {
      flush()
      section = line.toLowerCase()
      if (section === '[peer]') peer = {}
      continue
    }
    const eq = line.indexOf('=')
    const k = (eq < 0 ? line : line.slice(0, eq)).trim().toLowerCase()
    const v = eq < 0 ? '' : line.slice(eq + 1).trim()
    if (section === '[interface]') {
      if (k === 'privatekey') privateKey = wgKey(v)
      else if (k === 'address') addresses.push(...list(v))
      else if (k === 'dns') dns.push(...list(v).filter((x) => IPV4.test(x) || x.includes(':')))
      else if (k === 'mtu') mtu = Number(v) || mtu
    } else if (section === '[peer]' && peer) {
      peer[k] = v
    }
  }
  flush()
  if (!privateKey) throw new Error('[Interface] has no PrivateKey')
  if (addresses.length === 0) throw new Error('[Interface] has no Address')
  if (peers.length === 0) throw new Error('Config has no [Peer]')
  return { privateKey, addresses, dns, mtu: Math.min(1420, Math.max(1280, mtu)), peers }
}

export const wgEndpoint = (c: WireGuardConfig) => (c.peers[0] ? `${c.peers[0].endpointHost}:${c.peers[0].endpointPort}` : '')

// ---------- Mullvad ----------

export interface MullvadRelay {
  hostname: string
  /** `se-got` */
  location: string
  country: string
  city: string
  ipv4: string
  publicKey: string
  owned: boolean
  weight: number
}

export const MULLVAD_API = 'https://api.mullvad.net'
export const MULLVAD_DNS = '10.64.0.1'
export const relayCountry = (r: MullvadRelay) => r.location.split('-')[0]
export const normalizeMullvad = (n: string) => n.replace(/\D/g, '')

export function parseRelays(body: string): MullvadRelay[] {
  type J = Record<string, unknown>
  const root = JSON.parse(body) as J
  const locations = (root.locations ?? {}) as Record<string, J>
  const relays = ((root.wireguard as J | undefined)?.relays ?? []) as J[]
  const out: MullvadRelay[] = []
  for (const o of relays) {
    if (typeof o !== 'object' || o === null || o.active === false) continue
    const loc = o.location, host = o.hostname, ip = o.ipv4_addr_in, pk = o.public_key
    if (typeof loc !== 'string' || typeof host !== 'string' || typeof ip !== 'string' || typeof pk !== 'string') continue
    const place = locations[loc]
    out.push({
      hostname: host, location: loc,
      country: typeof place?.country === 'string' ? place.country : loc.split('-')[0].toUpperCase(),
      city: typeof place?.city === 'string' ? place.city : loc,
      ipv4: ip, publicKey: pk,
      owned: o.owned === true,
      weight: typeof o.weight === 'number' ? o.weight : 100,
    })
  }
  return out
}

/** [code] is a hostname, a `country-city` location or a country code; picks by weight. */
export function pickRelay(code: string, relays: MullvadRelay[], rnd = Math.random): MullvadRelay | null {
  const exact = relays.find((r) => r.hostname === code)
  if (exact) return exact
  let matches = relays.filter((r) => r.location === code)
  if (matches.length === 0) matches = relays.filter((r) => relayCountry(r) === code)
  if (matches.length === 0) return null
  const total = matches.reduce((s, m) => s + Math.max(1, m.weight), 0)
  let r = Math.floor(rnd() * total)
  for (const m of matches) { r -= Math.max(1, m.weight); if (r < 0) return m }
  return matches[matches.length - 1]
}

/** Human label for an exit code, e.g. "Sweden", "Sweden, Gothenburg", "Gothenburg · se-got-wg-001". */
export function relayLabel(code: string, relays: MullvadRelay[]): string {
  const h = relays.find((r) => r.hostname === code)
  if (h) return `${h.city} · ${h.hostname}`
  const l = relays.find((r) => r.location === code)
  if (l) return `${l.country}, ${l.city}`
  const c = relays.find((r) => relayCountry(r) === code)
  return c ? c.country : code
}

export function wgQuick(device: MullvadDevice, relay: MullvadRelay): string {
  return [
    '[Interface]',
    `PrivateKey = ${device.privateKey}`,
    `Address = ${[device.ipv4, device.ipv6].filter((x) => x.trim()).join(', ')}`,
    `DNS = ${MULLVAD_DNS}`,
    '[Peer]',
    `PublicKey = ${relay.publicKey}`,
    'AllowedIPs = 0.0.0.0/0, ::/0',
    `Endpoint = ${relay.ipv4}:51820`,
    '',
  ].join('\n')
}

/** Mullvad API error body → user-facing message. */
export function mullvadError(status: number, body: string): string {
  let code: string | undefined
  try { code = (JSON.parse(body) as { code?: string }).code } catch { /* not JSON */ }
  switch (code) {
    case 'INVALID_ACCOUNT': case 'INVALID_ACCOUNT_NUMBER': return "Mullvad doesn't recognise this account number"
    case 'MAX_DEVICES_REACHED': return 'This Mullvad account already has 5 devices. Remove one at mullvad.net → Devices, then try again.'
    case 'ACCOUNT_EXPIRED': return 'This Mullvad account has no time left'
    default: return `Mullvad API error ${status}${code ? ` (${code})` : ''}`
  }
}

/** New X25519 key pair as base64 (WireGuard format). */
export async function newWgKeyPair(): Promise<{ privateKey: string; publicKey: string }> {
  const kp = (await crypto.subtle.generateKey({ name: 'X25519' }, true, ['deriveBits'])) as CryptoKeyPair
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', kp.privateKey))
  const pub = new Uint8Array(await crypto.subtle.exportKey('raw', kp.publicKey))
  // PKCS#8 for X25519 ends with the 32-byte private scalar.
  return { privateKey: b64(pkcs8.slice(pkcs8.length - 32)), publicKey: b64(pub) }
}

export class MullvadClient {
  constructor(private readonly fetcher: (url: string, init?: RequestInit) => Promise<Response> = (u, i) => fetch(u, i)) {}

  private async call(url: string, init: RequestInit = {}, allow404 = false): Promise<string> {
    const r = await this.fetcher(url, { ...init, cache: 'no-store' })
    const text = await r.text()
    if (r.ok) return text
    if (allow404 && r.status === 404) return ''
    throw new Error(mullvadError(r.status, text))
  }

  async relays(): Promise<MullvadRelay[]> {
    const list = parseRelays(await this.call(`${MULLVAD_API}/app/v1/relays`))
    if (list.length === 0) throw new Error('Mullvad returned no WireGuard servers')
    return list
  }

  private async token(number: string): Promise<string> {
    const body = await this.call(`${MULLVAD_API}/auth/v1/token`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ account_number: number }),
    })
    const t = (JSON.parse(body) as { access_token?: string }).access_token
    if (!t) throw new Error('Mullvad login failed')
    return t
  }

  /** Logs in, returns the account with its expiry and this install's device (registered if missing). */
  async login(number: string, existing: MullvadDevice | null): Promise<MullvadAccount> {
    const n = normalizeMullvad(number)
    const token = await this.token(n)
    const auth = { Authorization: `Bearer ${token}` }
    let expiry = ''
    try { expiry = (JSON.parse(await this.call(`${MULLVAD_API}/accounts/v1/accounts/me`, { headers: auth })) as { expiry?: string }).expiry ?? '' } catch { /* optional */ }
    let device = existing
    if (device?.id) {
      const found = await this.call(`${MULLVAD_API}/accounts/v1/devices/${device.id}`, { headers: auth }, true)
      if (!found) device = null
    } else device = null
    if (!device) {
      const { privateKey, publicKey } = await newWgKeyPair()
      const o = JSON.parse(await this.call(`${MULLVAD_API}/accounts/v1/devices`, {
        method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify({ pubkey: publicKey, hijack_dns: false }),
      })) as Record<string, string>
      if (!o.id) throw new Error("Mullvad didn't return a device")
      device = { id: o.id, name: o.name ?? '', privateKey, publicKey, ipv4: o.ipv4_address ?? '', ipv6: o.ipv6_address ?? '' }
    }
    return { number: n, expiry, device }
  }

  async removeDevice(number: string, deviceId: string): Promise<void> {
    const token = await this.token(normalizeMullvad(number))
    await this.call(`${MULLVAD_API}/accounts/v1/devices/${deviceId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } }, true)
  }
}
