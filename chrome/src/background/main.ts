// Service worker entry: event listeners (registered synchronously so they wake the worker) and the message API
// for the popup and options pages.

import { Tunnel } from './tunnel'
import {
  type AppSettings, type Provider, emptyLocation, restoreSession, vpnExitImported,
} from '../core/models'
import { normalizeMullvad as normMv } from '../core/vpn'
import { open as openBackup, seal } from '../core/backup'
import { type LocationCatalog, CATALOG_MAX_AGE_MS, authError, builtInCatalog, canFetch, catalogRequest, parseCatalog } from '../core/locations'
import type { BackupPreview, HelperInfo, Request, Response, UiState } from '../shared/protocol'
import { loadPrefs, loadSecrets, loadSettings, savePrefs, updateSecrets, updateSettings } from './store'
import { probeHelper } from './helper'
import { log } from './log'

const tunnel = new Tunnel()
/** Last known helper state for the UI; probing starts the helper process briefly, so it isn't done per popup. */
let helperInfo: HelperInfo = { installed: false }
let helperCheckedAt = 0
let helperCheck: Promise<HelperInfo> | null = null
const refreshHelper = () => (helperCheck ??= probeHelper().then((h) => {
  helperInfo = h
  helperCheckedAt = Date.now()
  if (!h.installed) log.warn('helper check:', h.error ?? 'not registered')
  return h
}).finally(() => { helperCheck = null }))
void refreshHelper()

// ---------- lifecycle ----------

chrome.runtime.onStartup.addListener(() => { void tunnel.resume(true) })
chrome.runtime.onInstalled.addListener((d) => {
  void chrome.alarms.create('health', { periodInMinutes: 0.5 })
  if (d.reason === 'install') void chrome.runtime.openOptionsPage()
  // After an update the worker restarts: a running connection carries on (or reconnects through the helper).
  void tunnel.resume(false)
})
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'health') void tunnel.alarm() })
void chrome.alarms.get('health').then((a) => { if (!a) void chrome.alarms.create('health', { periodInMinutes: 0.5 }) })
// A plain worker restart (suspended and woken): carry on.
void tunnel.resume(false)

// ---------- proxy auth + traffic observation ----------

chrome.webRequest.onAuthRequired.addListener(
  (details, callback) => { void tunnel.authFor(details).then((r) => callback?.(r)) },
  { urls: ['<all_urls>'] },
  ['asyncBlocking'],
)

const PROXY_ERRORS = /ERR_PROXY|ERR_TUNNEL_CONNECTION_FAILED|ERR_EMPTY_RESPONSE|ERR_CONNECTION_(RESET|CLOSED|TIMED_OUT)|ERR_TIMED_OUT/
chrome.webRequest.onErrorOccurred.addListener((d) => {
  if (d.tabId >= 0 && PROXY_ERRORS.test(d.error)) tunnel.noteRequest(false, safeHost(d.url))
}, { urls: ['<all_urls>'] })
chrome.webRequest.onCompleted.addListener((d) => {
  if (d.tabId < 0 || d.fromCache) return
  tunnel.noteRequest(true, safeHost(d.url))
  const len = Number(d.responseHeaders?.find((h) => h.name.toLowerCase() === 'content-length')?.value)
  if (Number.isFinite(len)) tunnel.noteBytes(len)
}, { urls: ['<all_urls>'] }, ['responseHeaders'])

function safeHost(url: string) { try { return new URL(url).hostname } catch { return '' } }

// ---------- live settings ----------

let settingsTimer: ReturnType<typeof setTimeout> | null = null
let keysTimer: ReturnType<typeof setTimeout> | null = null
let lastKeys = ''
void loadSecrets().then((s) => { lastKeys = JSON.stringify(s.apiKeys) })

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local') return
  if (changes.settings || changes.secretsRev) {
    // Debounced, so typing in a field rebuilds once.
    if (settingsTimer) clearTimeout(settingsTimer)
    settingsTimer = setTimeout(() => { void tunnel.settingsChanged().catch((e) => log.error('apply settings', e)) }, 1500)
  }
  if (changes.secretsRev) {
    if (keysTimer) clearTimeout(keysTimer)
    keysTimer = setTimeout(async () => {
      const k = JSON.stringify((await loadSecrets()).apiKeys)
      if (k !== lastKeys) { lastKeys = k; tunnel.apiKeysChanged() }
    }, 1000)
  }
  if (changes.prefs) void tunnel.settingsChanged()
})

// ---------- location catalogs ----------

async function catalog(provider: Provider, force: boolean): Promise<LocationCatalog> {
  const key = `catalog_${provider}`
  const cached = (await chrome.storage.local.get(key))[key] as LocationCatalog | undefined
  const account = (await loadSecrets()).accounts[provider]
  if (!canFetch(provider, account)) return builtInCatalog()
  if (!force && cached && Date.now() - cached.fetchedAt < CATALOG_MAX_AGE_MS) return { ...cached, source: 'CACHED' }
  try {
    const { url, headers } = catalogRequest(provider, account!)
    const r = await tunnel.apiFetch(url, { headers })
    if (r.status === 401 || r.status === 403) throw new Error(authError(provider, account!))
    if (!r.ok) throw new Error(`${provider === 'IPROYAL' ? 'IPRoyal' : 'Geonode'} API error ${r.status}`)
    const countries = parseCatalog(provider, await r.text())
    if (countries.length === 0) throw new Error('The provider returned no locations')
    const c: LocationCatalog = { countries, source: 'LIVE', fetchedAt: Date.now() }
    await chrome.storage.local.set({ [key]: c })
    return c
  } catch (e) {
    return { ...(cached ?? builtInCatalog()), error: (e as Error).message || 'Could not load locations' }
  }
}

// ---------- backup ----------

async function exportBackup(passphrase: string, shareMullvadDevice: boolean): Promise<string> {
  if (passphrase.length < 8) throw new Error('Use a passphrase of at least 8 characters')
  const s = await loadSettings()
  const secrets = await loadSecrets()
  return seal({
    version: 1,
    createdAt: Date.now(),
    // Connection state is left out so the other side starts disconnected.
    settings: { ...s, wasConnected: false, sessionId: '' },
    accounts: Object.values(secrets.accounts),
    chain: null,
    apiKeys: secrets.apiKeys,
    vpnProfiles: secrets.vpnProfiles,
    // The Mullvad device key stays per install; the other side registers its own.
    mullvadNumber: secrets.mullvad?.number ?? '',
    mullvadDevice: shareMullvadDevice ? secrets.mullvad?.device ?? null : null,
  }, passphrase)
}

async function importBackup(text: string, passphrase: string) {
  const b = await openBackup(text, passphrase)
  // Backups from before VPN profiles carry a single `chain`.
  const legacy = b.vpnProfiles.length === 0 && b.chain?.config.trim()
    ? { id: Math.random().toString(36).slice(2, 10), name: b.chain.name, config: b.chain.config } : null
  const mullvadNumber = normMv(b.mullvadNumber)
  const shared = mullvadNumber ? b.mullvadDevice : null
  // Adopting a shared device: this install's own one (if different) is removed to free its slot.
  const own = (await loadSecrets()).mullvad
  if (shared && own?.number === mullvadNumber && own.device && own.device.id !== shared.id) {
    await tunnel.removeMullvadDevice(own.device.id, mullvadNumber).catch((e) => log.warn('remove old Mullvad device', e))
  }
  await updateSecrets((x) => ({
    accounts: Object.fromEntries(b.accounts.map((a) => [a.provider, a])),
    apiKeys: b.apiKeys,
    vpnProfiles: b.vpnProfiles.length ? b.vpnProfiles : legacy ? [legacy] : [],
    // Keeps this install's Mullvad device when the number is unchanged.
    mullvad: !mullvadNumber ? null
      : shared ? { number: mullvadNumber, expiry: x.mullvad?.number === mullvadNumber ? x.mullvad.expiry : '', device: shared }
        : x.mullvad?.number === mullvadNumber ? x.mullvad : { number: mullvadNumber, expiry: '', device: null },
  }))
  await updateSettings((cur) => {
    const exit = legacy && !b.settings.vpnExit ? vpnExitImported(legacy.id) : b.settings.vpnExit
    // Keep whether this browser is connected right now.
    return { ...b.settings, wasConnected: cur.wasConnected, sessionId: '', vpnExit: exit }
  })
  for (const a of b.accounts) if (a.provider !== 'GENERIC') void catalog(a.provider, true)
}

// ---------- state for the UI ----------

async function uiState(): Promise<UiState> {
  // A failed check isn't final (installed later, or the first start was slow): re-check when the UI opens.
  if (!helperInfo.installed && tunnel.status.via !== 'helper' && Date.now() - helperCheckedAt > 10_000) await refreshHelper()
  const [settings, secrets, prefs, { usage }] = await Promise.all([loadSettings(), loadSecrets(), loadPrefs(), chrome.storage.local.get('usage')])
  const m = secrets.mullvad
  return {
    settings, prefs,
    accounts: secrets.accounts,
    apiKeys: secrets.apiKeys,
    vpnProfiles: secrets.vpnProfiles,
    mullvad: m ? { number: m.number, expiry: m.expiry, deviceName: m.device?.name ?? '', deviceId: m.device?.id ?? '' } : null,
    usage: (usage ?? {}) as UiState['usage'],
    helper: tunnel.status.via === 'helper' ? { ...helperInfo, installed: true } : helperInfo,
    extensionId: chrome.runtime.id,
  }
}

async function selectProvider(provider: Provider) {
  await updateSettings((s): AppSettings => {
    if (s.provider === provider) return s
    const saved = { ...s.savedLocations, [s.provider]: s.location }
    return restoreSession({ ...s, provider, savedLocations: saved, location: saved[provider] ?? emptyLocation() })
  })
  await tunnel.applyLocation()
}

// ---------- message API ----------

async function handle(req: Request): Promise<unknown> {
  switch (req.type) {
    case 'state': return uiState()
    case 'connect': await tunnel.connect(); return null
    case 'disconnect': await tunnel.disconnect(); return null
    case 'rotate': await tunnel.rotate(); return null
    case 'recheck': tunnel.recheck(); return null
    case 'applyLocation':
      await updateSettings((s) => restoreSession({ ...s, savedLocations: { ...s.savedLocations, [s.provider]: s.location } }))
      await tunnel.applyLocation()
      return null
    case 'updateSettings': await updateSettings((s) => ({ ...s, ...req.patch })); return null
    case 'selectProvider': await selectProvider(req.provider); return null
    case 'saveAccount': await updateSecrets((x) => ({ ...x, accounts: { ...x.accounts, [req.account.provider]: req.account } })); return null
    case 'deleteAccount':
      await updateSecrets((x) => { const a = { ...x.accounts }; delete a[req.provider]; return { ...x, accounts: a } })
      return null
    case 'saveApiKeys': await updateSecrets((x) => ({ ...x, apiKeys: req.keys })); return null
    case 'saveVpnProfile':
      await updateSecrets((x) => ({ ...x, vpnProfiles: [...x.vpnProfiles.filter((p) => p.id !== req.profile.id), req.profile] }))
      return null
    case 'deleteVpnProfile':
      await updateSecrets((x) => ({ ...x, vpnProfiles: x.vpnProfiles.filter((p) => p.id !== req.id) }))
      await updateSettings((s) => (s.vpnExit === vpnExitImported(req.id) ? { ...s, vpnExit: '', chainEnabled: false } : s))
      return null
    case 'setMullvad': {
      const n = normMv(req.number)
      await updateSecrets((x) => ({ ...x, mullvad: !n ? null : x.mullvad?.number === n ? x.mullvad : { number: n, expiry: '', device: null } }))
      return null
    }
    case 'mullvadLogin': { const a = await tunnel.mullvadLogin(); return { expiry: a.expiry, deviceName: a.device?.name ?? '' } }
    case 'removeMullvad': await tunnel.removeMullvad(); return null
    case 'mullvadDevices': return tunnel.mullvadDevices(req.number)
    case 'removeMullvadDevice': await tunnel.removeMullvadDevice(req.id, req.number); return null
    case 'mullvadRelays': return tunnel.mullvadRelays(!!req.force)
    case 'catalog': return catalog(req.provider, !!req.force)
    case 'latency': return tunnel.latency()
    case 'savePrefs': await savePrefs(req.prefs); return null
    case 'resetUsage': await chrome.storage.local.remove('usage'); return null
    case 'helperInfo': return refreshHelper()
    case 'exportBackup': return exportBackup(req.passphrase, !!req.shareMullvadDevice)
    case 'openBackup': {
      const b = await openBackup(req.text, req.passphrase)
      const preview: BackupPreview = {
        createdAt: b.createdAt, accounts: b.accounts.length, vpnProfiles: b.vpnProfiles.length + (b.chain?.config ? 1 : 0),
        mullvad: !!b.mullvadNumber, provider: b.settings.provider, mullvadDevice: b.mullvadDevice?.name || undefined,
      }
      return preview
    }
    case 'importBackup': await importBackup(req.text, req.passphrase); return null
  }
}

chrome.runtime.onMessage.addListener((req: Request, sender, sendResponse) => {
  // Only our own pages talk to the worker.
  if (sender.id !== chrome.runtime.id || (sender.url && !sender.url.startsWith(chrome.runtime.getURL('')))) return false
  handle(req).then(
    (value) => sendResponse({ ok: true, value } satisfies Response),
    (e: unknown) => sendResponse({ ok: false, error: (e as Error)?.message || String(e) } satisfies Response),
  )
  return true
})
