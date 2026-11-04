// Passphrase-encrypted backup, byte-compatible with the Android app (data/Backup.kt):
// `resproxy-backup-v1:` + base64(JSON envelope {kdf, iter, salt, iv, data}); AES-256-GCM (128-bit tag,
// AAD = the prefix) with a PBKDF2-HMAC-SHA256 key. Spec and test vectors: shared/FORMAT.md, shared/test-vectors/.

import {
  type Account, type ApiKeys, type AppSettings, type VpnProfile,
  normAccount, normApiKeys, normSettings, normVpnProfile, defaultSettings, emptyApiKeys,
} from './models'

export const PREFIX = 'resproxy-backup-v1:'
const ITERATIONS = 210_000

export interface BackupPayload {
  version: number
  createdAt: number
  settings: AppSettings
  accounts: Account[]
  /** Legacy single VPN config, only in old Android backups. */
  chain?: { name: string; config: string } | null
  apiKeys: ApiKeys
  vpnProfiles: VpnProfile[]
  mullvadNumber: string
  [extra: string]: unknown
}

export class WrongPassphraseError extends Error {
  constructor() { super('Wrong passphrase or damaged backup') }
}

export const looksLikeBackup = (text: string) => text.trim().startsWith(PREFIX)

const enc = new TextEncoder()
const dec = new TextDecoder()

export function b64(bytes: Uint8Array): string {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(s)
}

export function unb64(s: string): Uint8Array {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

async function key(passphrase: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', enc.encode(passphrase), 'PBKDF2', false, ['deriveKey'])
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'],
  )
}

export async function seal(payload: BackupPayload, passphrase: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const k = await key(passphrase, salt, ITERATIONS)
  const ct = new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: enc.encode(PREFIX), tagLength: 128 }, k, enc.encode(JSON.stringify(payload)),
  ))
  const env = { kdf: 'pbkdf2-sha256', iter: ITERATIONS, salt: b64(salt), iv: b64(iv), data: b64(ct) }
  return PREFIX + b64(enc.encode(JSON.stringify(env)))
}

export async function open(text: string, passphrase: string): Promise<BackupPayload> {
  const t = text.trim()
  if (!t.startsWith(PREFIX)) throw new Error('Not a ResProxy backup')
  let env: { kdf?: string; iter?: number; salt?: string; iv?: string; data?: string }
  try {
    env = JSON.parse(dec.decode(unb64(t.slice(PREFIX.length).replace(/\s+/g, ''))))
  } catch {
    throw new Error('Damaged backup')
  }
  if (env.kdf !== 'pbkdf2-sha256' || !env.iter || !env.salt || !env.iv || !env.data) throw new Error('Unsupported backup format')
  let plain: ArrayBuffer
  try {
    const k = await key(passphrase, unb64(env.salt), env.iter)
    plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: unb64(env.iv) as BufferSource, additionalData: enc.encode(PREFIX), tagLength: 128 }, k, unb64(env.data) as BufferSource,
    )
  } catch {
    throw new WrongPassphraseError()
  }
  return normPayload(JSON.parse(dec.decode(plain)))
}

export function normPayload(v: unknown): BackupPayload {
  const o = (typeof v === 'object' && v !== null ? v : {}) as Record<string, unknown>
  const chain = o.chain as { name?: unknown; config?: unknown } | null | undefined
  return {
    ...o,
    version: typeof o.version === 'number' ? o.version : 1,
    createdAt: typeof o.createdAt === 'number' ? o.createdAt : 0,
    settings: o.settings ? normSettings(o.settings) : defaultSettings(),
    accounts: Array.isArray(o.accounts) ? o.accounts.map(normAccount).filter((a): a is Account => a !== null) : [],
    chain: chain && typeof chain.config === 'string' ? { name: String(chain.name ?? ''), config: chain.config } : null,
    apiKeys: o.apiKeys ? normApiKeys(o.apiKeys) : emptyApiKeys(),
    vpnProfiles: Array.isArray(o.vpnProfiles) ? o.vpnProfiles.map(normVpnProfile).filter((p): p is VpnProfile => p !== null) : [],
    mullvadNumber: typeof o.mullvadNumber === 'string' ? o.mullvadNumber : '',
  }
}
