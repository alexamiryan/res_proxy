// Persistent state in chrome.storage. Settings are plain JSON; accounts, API keys, VPN profiles and the
// Mullvad account are AES-GCM encrypted with a non-extractable key kept in IndexedDB (the Keystore analogue).

import {
  type Account, type ApiKeys, type AppSettings, type MullvadAccount, type Provider, type VpnProfile,
  normAccount, normApiKeys, normSettings, normVpnProfile, emptyApiKeys,
} from '../core/models'
import { b64, unb64 } from '../core/backup'

export interface Secrets {
  accounts: Partial<Record<Provider, Account>>
  apiKeys: ApiKeys
  vpnProfiles: VpnProfile[]
  mullvad: MullvadAccount | null
}

export interface UsageTotals { rxBytes: number; txBytes: number }

/** Chrome-only preferences; not part of backups. */
export interface LocalPrefs {
  /** Use the native helper when it's installed (needed for WireGuard and SOCKS5 with a password). */
  useHelper: boolean
  /** Block WebRTC from using non-proxied UDP while connected. */
  webRtcProtection: boolean
}

export const defaultPrefs = (): LocalPrefs => ({ useHelper: true, webRtcProtection: true })

// ---------- encryption key ----------

let keyPromise: Promise<CryptoKey> | null = null

function idb<T>(fn: (store: IDBObjectStore) => IDBRequest<T>, mode: IDBTransactionMode): Promise<T> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('resproxy', 1)
    req.onupgradeneeded = () => req.result.createObjectStore('keys')
    req.onerror = () => reject(req.error)
    req.onsuccess = () => {
      const db = req.result
      const tx = db.transaction('keys', mode)
      const r = fn(tx.objectStore('keys'))
      r.onsuccess = () => resolve(r.result)
      r.onerror = () => reject(r.error)
      tx.oncomplete = () => db.close()
    }
  })
}

function secretKey(): Promise<CryptoKey> {
  keyPromise ??= (async () => {
    const existing = await idb<CryptoKey | undefined>((s) => s.get('secrets') as IDBRequest<CryptoKey | undefined>, 'readonly')
    if (existing) return existing
    const k = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
    await idb((s) => s.put(k, 'secrets'), 'readwrite')
    return k
  })()
  return keyPromise
}

async function encrypt(text: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await secretKey(), new TextEncoder().encode(text))
  return `${b64(iv)}.${b64(new Uint8Array(ct))}`
}

async function decrypt(blob: string): Promise<string> {
  const [iv, ct] = blob.split('.')
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(iv) as BufferSource }, await secretKey(), unb64(ct) as BufferSource)
  return new TextDecoder().decode(plain)
}

// ---------- settings ----------

export async function loadSettings(): Promise<AppSettings> {
  const { settings } = await chrome.storage.local.get('settings')
  return normSettings(settings)
}

let settingsQueue: Promise<unknown> = Promise.resolve()

/** Serialized read-modify-write, so concurrent updates never drop each other's changes. */
export function updateSettings(fn: (s: AppSettings) => AppSettings): Promise<AppSettings> {
  const run = settingsQueue.then(async () => {
    const next = fn(await loadSettings())
    await chrome.storage.local.set({ settings: next })
    return next
  })
  settingsQueue = run.catch(() => undefined)
  return run
}

// ---------- secrets ----------

export async function loadSecrets(): Promise<Secrets> {
  const { secrets } = await chrome.storage.local.get('secrets')
  let raw: Record<string, unknown> = {}
  if (typeof secrets === 'string') {
    try { raw = JSON.parse(await decrypt(secrets)) } catch { raw = {} }
  }
  const accounts: Secrets['accounts'] = {}
  if (raw.accounts && typeof raw.accounts === 'object') {
    for (const v of Object.values(raw.accounts as Record<string, unknown>)) {
      const a = normAccount(v)
      if (a) accounts[a.provider] = a
    }
  }
  const m = raw.mullvad as MullvadAccount | null | undefined
  return {
    accounts,
    apiKeys: raw.apiKeys ? normApiKeys(raw.apiKeys) : emptyApiKeys(),
    vpnProfiles: Array.isArray(raw.vpnProfiles) ? raw.vpnProfiles.map(normVpnProfile).filter((p): p is VpnProfile => !!p) : [],
    mullvad: m && typeof m.number === 'string' && m.number ? { number: m.number, expiry: m.expiry ?? '', device: m.device ?? null, signedOut: !!m.signedOut } : null,
  }
}

let secretsQueue: Promise<unknown> = Promise.resolve()

export function updateSecrets(fn: (s: Secrets) => Secrets): Promise<Secrets> {
  const run = secretsQueue.then(async () => {
    const next = fn(await loadSecrets())
    // Bump a plain counter too: storage.onChanged then tells listeners *that* secrets changed without exposing them.
    await chrome.storage.local.set({ secrets: await encrypt(JSON.stringify(next)), secretsRev: Date.now() })
    return next
  })
  secretsQueue = run.catch(() => undefined)
  return run
}

// ---------- prefs, usage ----------

export async function loadPrefs(): Promise<LocalPrefs> {
  const { prefs } = await chrome.storage.local.get('prefs')
  return { ...defaultPrefs(), ...(prefs as Partial<LocalPrefs> | undefined) }
}

export async function savePrefs(p: Partial<LocalPrefs>) {
  await chrome.storage.local.set({ prefs: { ...(await loadPrefs()), ...p } })
}

export async function addUsage(provider: Provider, rx: number, tx: number) {
  if (rx <= 0 && tx <= 0) return
  const { usage } = await chrome.storage.local.get('usage')
  const u = (usage ?? {}) as Record<string, UsageTotals>
  const cur = u[provider] ?? { rxBytes: 0, txBytes: 0 }
  u[provider] = { rxBytes: cur.rxBytes + Math.max(0, rx), txBytes: cur.txBytes + Math.max(0, tx) }
  await chrome.storage.local.set({ usage: u })
}
