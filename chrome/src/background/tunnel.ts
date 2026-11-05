// The connection controller — Chrome counterpart of android/.../vpn/ProxyVpnService.kt.
//
//   tabs → Chrome proxy (PAC) → [native helper 127.0.0.1 → [WireGuard] →] residential proxy
//
// Invariants (same as Android, see AGENTS.md):
// - No leaks: the PAC points at the proxy (or a dead local port) before any upstream I/O, and stays set
//   across rotations, settings changes, repairs and outages. Only `disconnect` (or kill switch off) clears it.
// - Tabs only get internet through a checked IP: the gate (DNR session rules) is closed from connect, every
//   new session and upstream rebuild until the exit IP's verdict is below the rotate threshold.
// - Never fall back to an unchained connection when the selected VPN can't be set up.
// - Background failures never leave things half-done: errors are caught, logged and turn into PROXY_DOWN / retry.

import {
  type AppSettings, type Provider, type Verdict, PROVIDER_LABEL, SEVERITY, clamp, locationLabel, parseVpnExit,
  withSession, randomId,
} from '../core/models'
import { type Endpoint, buildEndpoint, newSessionId, profileFrom } from '../core/presets'
import { buildPac } from '../core/pac'
import {
  type ReputationReport, type SourceResult, DNSBL_ZONES, IP_ECHO, PROBE_URL, isDeadExitStatus, REPORT_CACHE_MS, aggregate, dnsblName,
  failed, interpretDnsbl, parseEcho, sourceRequests,
} from '../core/reputation'
import { MULLVAD_DNS, MullvadClient, parseWireGuard, pickRelay, relayLabel, wgQuick, type MullvadRelay } from '../core/vpn'
import { type TunnelStatus, idleStatus, isActive } from '../shared/protocol'
import { Helper, HelperError, type HelperStatus } from './helper'
import { applyPac, clearProxy, closeGate, notify, openGate, protectWebRtc, proxyControlledByOther, setBadge } from './browser'
import { log } from './log'
import { addUsage, loadPrefs, loadSecrets, loadSettings, updateSecrets, updateSettings, type Secrets } from './store'

const TICK_MS = 5000
/** Rotations in a row without a pause; after that one every ROTATION_PAUSE_MS (under the worker's 30 s idle limit). */
const QUICK_ROTATIONS = 10
const ROTATION_PAUSE_MS = 20_000
/** Probes in a row where the proxy answered but couldn't reach the target: the sticky exit node is gone. */
const DEAD_EXIT_PROBES = 2
/** PAC target while nothing is listening yet: every proxied request fails, nothing goes direct. */
const BLACKHOLE = 'PROXY 127.0.0.1:9'

class ProxyAuthError extends Error {}
/** The proxy works but couldn't reach the probe target: the sticky session's exit node is gone. */
class ExitNodeError extends Error {}

/** Survives service-worker restarts (chrome.storage.session lives as long as the browser session). */
interface Runtime {
  status: TunnelStatus
  via: 'direct' | 'helper' | null
  endpoint: Endpoint | null
  /** Direct mode: the proxy host as written in the PAC. Alternates with a trailing-dot spelling on every
   * credential change, so Chrome's proxy auth cache (keyed by host) can't keep sending the old session. */
  proxyHost: string
  helperPort: number
  helperToken: string
  cleanIp: string | null
  waitingForUser: boolean
  builtWith: Keys | null
  provider: Provider | null
}

interface Keys { routing: string; upstream: string }

const emptyRuntime = (): Runtime => ({
  status: idleStatus(), via: null, endpoint: null, proxyHost: '', helperPort: 0, helperToken: '',
  cleanIp: null, waitingForUser: false, builtWith: null, provider: null,
})

export class Tunnel {
  private rt: Runtime = emptyRuntime()
  private loaded: Promise<void>
  private queue: Promise<unknown> = Promise.resolve()
  private helper: Helper | null = null
  private checkToken = 0
  private checkRunning = false
  private lastCheckKick = 0
  private healthTimer: ReturnType<typeof setInterval> | null = null
  private reports = new Map<string, ReputationReport>()
  private relays: MullvadRelay[] = []
  private chainDns: string | null = null
  private chainStartedAt = 0
  private chainBroken = false
  /** Direct mode: requests whose proxy auth we already answered once (a second challenge = rejected). */
  private authTries = new Map<string, number>()
  private authRejected = false
  // health
  private fails = 0
  /** Probes in a row that failed with a dead exit node. */
  private deadExitProbes = 0
  private lastProbe = 0
  private lastPing = 0
  private lastRepairAt = 0
  private failedRepairs = 0
  private seenOk = 0
  private seenFail = 0
  private failingSince = 0
  private failsInStreak = 0
  private lastCounters: { down: number; up: number } | null = null
  private unsavedUsage = { rx: 0, tx: 0 }
  private directFailures: { at: number; host: string }[] = []
  private directOk = 0

  constructor() {
    this.loaded = this.restore()
  }

  // ---------- state ----------

  get status(): TunnelStatus { return this.rt.status }

  private async restore() {
    const { runtime, relays } = await chrome.storage.session.get(['runtime', 'relays'])
    if (runtime) this.rt = { ...emptyRuntime(), ...(runtime as Runtime) }
    if (Array.isArray(relays)) this.relays = relays as MullvadRelay[]
  }

  private persist() {
    void chrome.storage.session.set({ runtime: this.rt }).catch(() => undefined)
  }

  private update(fn: (s: TunnelStatus) => TunnelStatus) {
    this.rt.status = fn(this.rt.status)
    this.persist()
    const s = this.rt.status
    void setBadge(
      s.phase === 'CONNECTED' && !s.trafficBlocked ? 'ok'
        : s.phase === 'ERROR' ? 'error'
          : s.phase === 'DISCONNECTED' ? 'off'
            : s.phase === 'PROXY_DOWN' || s.trafficBlocked ? (s.checking ? 'busy' : 'blocked') : 'busy',
    ).catch(() => undefined)
  }

  /** Runs [fn] after every earlier locked operation (the Android service's Mutex). */
  private locked<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.queue.then(fn)
    this.queue = run.catch(() => undefined)
    return run
  }

  /** Background jobs never throw out: log, and reconnect if the job was part of a live connection. */
  private guard(what: string, p: Promise<unknown>) {
    p.catch(async (e) => {
      log.error(`${what} failed, reconnecting`, e)
      await new Promise((r) => setTimeout(r, 3000))
      if ((await loadSettings()).wasConnected && isActive(this.rt.status.phase)) this.guard('reconnect', this.connect())
    })
  }

  // ---------- lifecycle ----------

  /** Service worker (re)started. A direct connection resumes; a helper connection died with the worker, so it reconnects. */
  async resume(browserStartup: boolean) {
    await this.loaded
    const s = await loadSettings()
    if (isActive(this.rt.status.phase)) {
      if (this.rt.via === 'direct' && !browserStartup) {
        this.startHealth()
        return
      }
      this.guard('resume', this.connect())
    } else if (browserStartup && s.autoConnect && s.wasConnected) {
      this.guard('auto-connect', this.connect())
    } else {
      // Not connected: make sure nothing of ours is left in Chrome's settings (they persist across restarts).
      if (browserStartup) await updateSettings((x) => ({ ...x, wasConnected: false }))
      await clearProxy().catch(() => undefined)
      await openGate().catch(() => undefined)
      await protectWebRtc(false)
      this.update((x) => x)
    }
  }

  connect(): Promise<void> {
    return this.locked(async () => {
      await this.loaded
      // Block tabs and point Chrome at a dead proxy first: nothing goes out directly from here on.
      let s = await loadSettings()
      await closeGate(gateScope(s))
      if (await proxyControlledByOther()) return this.fail('Another extension controls Chrome\'s proxy settings. Turn it off, then connect again.')
      await applyPac(pac(s, BLACKHOLE))
      const prefs = await loadPrefs()
      await protectWebRtc(prefs.webRtcProtection)
      await this.stopUpstream()
      this.resetHealth()

      if (s.connection.sticky) s = await updateSettings((x) => withSession(x, x.sessionId || newSessionId()))
      const secrets = await loadSecrets()
      const account = secrets.accounts[s.provider]
      if (!account?.username.trim()) return this.fail(`Add your ${PROVIDER_LABEL[s.provider]} account first`)
      s = await updateSettings((x) => ({ ...x, wasConnected: true }))
      this.rt = {
        ...emptyRuntime(),
        provider: s.provider,
        builtWith: keys(s, secrets),
        status: {
          ...idleStatus(), phase: 'CONNECTING', trafficBlocked: true, sessionId: s.sessionId,
          profileName: `${PROVIDER_LABEL[s.provider]} · ${locationLabel(s.location)}`,
        },
      }
      this.update((x) => x)
      this.startHealth()
      try {
        await this.startUpstream(s, secrets)
      } catch (e) {
        if (e instanceof ConfigError) { await this.teardown(); return this.fail(e.message) }
        log.warn('start failed', e)
        if (!(await this.onProxyFailure(e))) return
      }
      this.update((x) => (x.phase === 'PROXY_DOWN' ? x : { ...x, phase: 'CONNECTED', connectedAt: Date.now(), message: undefined }))
      log.info(`connected via ${this.rt.via ?? 'nothing yet'} (${this.rt.status.phase})`)
      this.launchCheck(false)
    })
  }

  disconnect(): Promise<void> {
    return this.locked(async () => {
      await updateSettings((x) => ({ ...x, wasConnected: false }))
      await this.teardown()
      this.update(() => idleStatus())
    })
  }

  private async teardown() {
    this.checkToken++
    this.stopHealth()
    await this.flushUsage()
    await this.stopUpstream()
    this.helper?.disconnect()
    this.helper = null
    await clearProxy().catch(() => undefined)
    await openGate().catch(() => undefined)
    await protectWebRtc(false)
    this.rt = { ...emptyRuntime(), status: this.rt.status }
    this.chainBroken = false
    this.chainDns = null
    this.persist()
  }

  private async fail(message: string) {
    await this.teardown()
    this.update(() => ({ ...idleStatus(), phase: 'ERROR', message }))
  }

  // ---------- upstream ----------

  /** Starts the path to the proxy for the current settings and points Chrome at it. */
  private async startUpstream(s: AppSettings, secrets: Secrets, prevProxyHost = '') {
    const account = secrets.accounts[s.provider]!
    const prefs = await loadPrefs()
    let profile = profileFrom(account, s)
    if (!profile.host.trim() && s.provider === 'GENERIC') throw new ConfigError('Proxy host is empty (Settings → Accounts → Custom)')
    const vpn = s.chainEnabled ? await this.resolveVpn(s, secrets) : null
    const needsHelper = !!vpn || (profile.protocol === 'SOCKS5' && !!profile.username.trim() && s.provider === 'GENERIC')
    const helperOk = prefs.useHelper && (await this.ensureHelper())
    if (needsHelper && !helperOk) {
      const why = vpn ? 'VPN chaining' : 'SOCKS5 with a password'
      // Never fall back to an unchained connection: that would show the real IP to the proxy.
      if (vpn) this.chainBroken = true
      throw new ConfigError(`${why} needs the ResProxy helper app. Install it from Settings → Helper, then connect again.`)
    }
    if (!helperOk && profile.protocol === 'SOCKS5' && s.provider !== 'GENERIC') {
      // Chrome can't send a password to a SOCKS5 proxy; the providers' HTTP gateways take the same credentials.
      profile = { ...profile, protocol: 'HTTP', port: 0 }
    }
    const ep = buildEndpoint(profile)
    if (!ep.host.trim()) throw new ConfigError('Proxy host is empty')
    if (!ep.username.trim() && s.provider !== 'GENERIC') throw new ConfigError('Username is empty')
    this.rt.endpoint = ep
    this.authRejected = false
    this.authTries.clear()

    if (helperOk) {
      this.rt.via = 'helper'
      this.rt.helperToken ||= randomId(24)
      if (vpn) this.update((x) => ({ ...x, chainName: vpn.name, vpnPingMs: undefined }))
      this.chainStartedAt = Date.now()
      const port = await this.helper!.start(ep, vpn?.config ?? null, this.rt.helperToken)
      this.rt.helperPort = port
      // The helper's counters start at zero with each start.
      this.lastCounters = { down: 0, up: 0 }
      this.seenOk = 0
      this.seenFail = 0
      await applyPac(pac(s, `PROXY 127.0.0.1:${port}`))
    } else {
      this.rt.via = 'direct'
      // Chrome caches proxy credentials per proxy host and would keep sending the old session's: clear them.
      if (prevProxyHost) await clearProxyAuthCache(prevProxyHost)
      this.rt.proxyHost = ep.host
      await applyPac(pac(s, pacDirective(ep, this.rt.proxyHost)))
    }
    this.update((x) => ({ ...x, via: this.rt.via ?? undefined }))
    this.persist()
  }

  /** Switches the running upstream to new credentials (new session / location) without opening a gap. */
  private async swapUpstream(s: AppSettings, secrets: Secrets) {
    const account = secrets.accounts[s.provider]
    if (!account) throw new ConfigError(`Add your ${PROVIDER_LABEL[s.provider]} account first`)
    if (this.rt.via !== 'helper' || !this.helper?.connected) {
      const prev = this.rt.proxyHost
      await this.stopUpstream()
      await this.startUpstream(s, secrets, prev)
      return
    }
    const ep = buildEndpoint(profileFrom(account, s))
    this.rt.endpoint = ep
    await this.helper.setUpstream(ep)
    this.persist()
  }

  private async stopUpstream() {
    if (this.helper?.connected) await this.helper.stop()
    this.rt.endpoint = null
    this.rt.via = null
  }

  private async ensureHelper(): Promise<boolean> {
    if (this.helper?.connected) return true
    const h = new Helper()
    try {
      await h.hello()
    } catch {
      h.disconnect()
      return false
    }
    h.onDisconnect = (reason) => {
      if (this.helper !== h) return
      this.helper = null
      if (isActive(this.rt.status.phase) && this.rt.via === 'helper') {
        log.warn('helper exited:', reason)
        // The local proxy is gone: Chrome's requests fail (blocked), never direct. Bring it back.
        this.guard('helper restart', this.repair('helper exited', true))
      }
    }
    this.helper = h
    return true
  }

  /** The VPN to chain through: display name, wg-quick text and its DNS server (for in-tunnel ping). */
  private async resolveVpn(s: AppSettings, secrets: Secrets): Promise<{ name: string; config: string }> {
    const e = parseVpnExit(s.vpnExit)
    try {
      if (!e) throw new Error('Choose a VPN server in the popup')
      if (e.kind === 'wg') {
        const p = secrets.vpnProfiles.find((x) => x.id === e.id)
        if (!p) throw new Error('The selected VPN profile was removed')
        const cfg = parseWireGuard(p.config)
        this.chainDns = cfg.dns.find((d) => !d.includes(':')) ?? cfg.dns[0] ?? null
        return { name: p.name || 'WireGuard', config: p.config }
      }
      let acc = secrets.mullvad
      if (!acc) throw new Error('Add your Mullvad account number in Settings → VPN')
      if (!acc.device) {
        if (acc.signedOut) throw new Error('Signed out of Mullvad in this browser. Log in again in Settings → VPN.')
        acc = await this.mullvadLogin()
      }
      const relays = await this.mullvadRelays(false).catch((err) => { if (this.relays.length) return this.relays; throw err })
      const relay = pickRelay(e.code, relays)
      if (!relay) throw new Error(`No Mullvad server matches “${e.code}”`)
      this.chainDns = MULLVAD_DNS
      return { name: `Mullvad ${relay.city} · ${relay.hostname}`, config: wgQuick(acc.device!, relay) }
    } catch (err) {
      this.chainBroken = true
      this.update((x) => ({ ...x, chainName: undefined }))
      throw new Error(`VPN: ${(err as Error).message}`)
    }
  }

  // ---------- Mullvad (also used by the settings UI) ----------

  /** API calls go direct through the helper when it runs (like Android, outside the tunnel); otherwise plain fetch. */
  apiFetch = async (url: string, init: RequestInit = {}): Promise<Response> => {
    if (this.helper?.connected) {
      try {
        const r = await this.helper.call('fetch', {
          url, method: init.method ?? 'GET', headers: init.headers ?? {}, body: typeof init.body === 'string' ? init.body : undefined,
        }, 30_000)
        return new Response(String(r.body ?? ''), { status: Number(r.status) })
      } catch (e) {
        if (!(e instanceof HelperError) || e.code !== 'net') throw e
        // Network error on the direct path (e.g. a filtering DNS): fall through to Chrome, i.e. via the proxy.
      }
    }
    return fetch(url, { ...init, cache: 'no-store' })
  }

  private get mullvad() { return new MullvadClient(this.apiFetch) }

  async mullvadLogin() {
    const cur = (await loadSecrets()).mullvad
    if (!cur) throw new Error('Add your Mullvad account number in Settings → VPN')
    const acc = await this.mullvad.login(cur.number, cur.device)
    await updateSecrets((x) => ({ ...x, mullvad: acc }))
    return acc
  }

  async mullvadRelays(force: boolean): Promise<MullvadRelay[]> {
    const { mullvadRelays } = await chrome.storage.local.get('mullvadRelays')
    const cached = mullvadRelays as { fetchedAt: number; relays: MullvadRelay[] } | undefined
    if (!force && cached && Date.now() - cached.fetchedAt < 6 * 3600_000 && cached.relays.length) {
      this.relays = cached.relays
      return cached.relays
    }
    try {
      const relays = await this.mullvad.relays()
      this.relays = relays
      await chrome.storage.local.set({ mullvadRelays: { fetchedAt: Date.now(), relays } })
      void chrome.storage.session.set({ relays })
      return relays
    } catch (e) {
      if (cached?.relays.length) { this.relays = cached.relays; return cached.relays }
      throw e
    }
  }

  /**
   * Checks this install's device still exists on the account; if it was removed (mullvad.net / another install)
   * marks this install signed out. false = removed, null = nothing to check or no answer.
   */
  async verifyMullvadDevice(): Promise<boolean | null> {
    const m = (await loadSecrets()).mullvad
    if (!m?.device) return null
    const ok = await this.mullvad.isDeviceRegistered(m.number, m.device.id).catch(() => null)
    if (ok === false) {
      log.warn('Mullvad device was removed from the account; signed out here')
      await updateSecrets((x) => ({ ...x, mullvad: x.mullvad ? { ...x.mullvad, device: null, signedOut: true } : null }))
    }
    return ok
  }

  /** Signs out in this install only: forgets the device key; the device stays on the account for anyone sharing it. */
  async signOutMullvadHere() {
    await updateSecrets((x) => ({ ...x, mullvad: x.mullvad ? { ...x.mullvad, device: null, signedOut: true } : null }))
  }

  /** Devices on the account; [number] defaults to the saved account. */
  async mullvadDevices(number?: string) {
    const n = number || (await loadSecrets()).mullvad?.number
    if (!n) throw new Error('Add your Mullvad account number first')
    return this.mullvad.devices(n)
  }

  async removeMullvadDevice(id: string, number?: string) {
    const n = number || (await loadSecrets()).mullvad?.number
    if (!n) throw new Error('Add your Mullvad account number first')
    await this.mullvad.removeDevice(n, id)
  }

  async removeMullvad() {
    const m = (await loadSecrets()).mullvad
    if (m?.device?.id) await this.mullvad.removeDevice(m.number, m.device.id).catch((e) => log.warn('remove device', e))
    await updateSecrets((x) => ({ ...x, mullvad: null }))
  }

  relayLabel(code: string) { return relayLabel(code, this.relays) }

  /** ICMP latency to every Mullvad relay / imported profile endpoint (needs the helper). */
  async latency(): Promise<Record<string, number | null>> {
    const secrets = await loadSecrets()
    const relays = secrets.mullvad ? await this.mullvadRelays(false).catch(() => this.relays) : []
    const targets: Record<string, string> = {}
    for (const r of relays) targets[`mullvad:${r.hostname}`] = r.ipv4
    for (const p of secrets.vpnProfiles) {
      try { targets[`wg:${p.id}`] = parseWireGuard(p.config).peers[0].endpointHost } catch { /* skip invalid */ }
    }
    const ad = this.helper?.connected ? this.helper : new Helper()
    try {
      const res = await ad.ping([...new Set(Object.values(targets))])
      return Object.fromEntries(Object.entries(targets).map(([k, ip]) => [k, res[ip] ?? null]))
    } finally {
      if (ad !== this.helper) ad.disconnect()
    }
  }

  // ---------- proxy auth (direct mode and helper token) ----------

  /** onAuthRequired handler: answers challenges from our proxy only. */
  async authFor(details: chrome.webRequest.OnAuthRequiredDetails): Promise<chrome.webRequest.BlockingResponse> {
    await this.loaded
    if (!details.isProxy || !isActive(this.rt.status.phase)) return {}
    const host = details.challenger.host.toLowerCase().replace(/\.$/, '')
    const tries = (this.authTries.get(details.requestId) ?? 0) + 1
    this.authTries.set(details.requestId, tries)
    if (this.authTries.size > 500) this.authTries.clear()
    if (this.rt.via === 'helper' && (host === '127.0.0.1' || host === 'localhost') && details.challenger.port === this.rt.helperPort) {
      return tries > 2 ? { cancel: true } : { authCredentials: { username: 'resproxy', password: this.rt.helperToken } }
    }
    const ep = this.rt.endpoint
    if (this.rt.via === 'direct' && ep && host === ep.host.toLowerCase().replace(/\.$/, '')) {
      if (tries > 1) {
        // Same request challenged again: the proxy rejected these credentials.
        this.authRejected = true
        return { cancel: true }
      }
      return { authCredentials: { username: ep.username, password: ep.password } }
    }
    return {}
  }

  /** Direct mode: what tabs' requests through the proxy did, for the health loop. */
  noteRequest(ok: boolean, host: string) {
    if (this.rt.via !== 'direct') return
    if (ok) this.directOk++
    else this.directFailures.push({ at: Date.now(), host })
    if (this.directFailures.length > 200) this.directFailures.splice(0, 100)
  }

  noteBytes(n: number) {
    if (this.rt.via === 'direct' && n > 0) {
      this.unsavedUsage.rx += n
      this.rt.status.sessionDown += n
    }
  }

  // ---------- user actions ----------

  rotate(): Promise<void> {
    if (!isActive(this.rt.status.phase)) return Promise.resolve()
    return this.switchSession(newSessionId()).then(() => this.launchCheck(true))
  }

  /** Provider / location changed: reuse that place's remembered session (same IP) if it's still alive. */
  async applyLocation() {
    if (!isActive(this.rt.status.phase)) return
    const s = await loadSettings()
    await this.switchSession(s.sessionId || newSessionId())
    this.launchCheck(false)
  }

  recheck() {
    if (isActive(this.rt.status.phase)) this.launchCheck(true)
  }

  private switchSession(sessionId: string): Promise<void> {
    return this.locked(async () => {
      if (!this.rt.endpoint && this.rt.status.phase !== 'PROXY_DOWN') return
      await this.blockTraffic()
      const s = await updateSettings((x) => withSession(x, sessionId))
      const secrets = await loadSecrets()
      this.rt.provider = s.provider
      this.rt.builtWith = keys(s, secrets)
      this.update((x) => ({
        ...x, phase: 'ROTATING', sessionId, exitIp: undefined, report: undefined,
        profileName: `${PROVIDER_LABEL[s.provider]} · ${locationLabel(s.location)}`,
      }))
      try {
        if (this.chainBroken || !this.rt.via) await this.startUpstream(s, secrets)
        else await this.swapUpstream(s, secrets)
        this.update((x) => ({ ...x, phase: 'CONNECTED', message: undefined }))
      } catch (e) {
        await this.onProxyFailure(e)
      }
    })
  }

  /** Settings or accounts changed while connected: apply what changed. */
  async settingsChanged() {
    await this.loaded
    if (!isActive(this.rt.status.phase) || !this.rt.builtWith) return
    const s = await loadSettings()
    const secrets = await loadSecrets()
    const now = keys(s, secrets)
    const built = this.rt.builtWith
    if (now.upstream !== built.upstream) {
      log.info('connection settings changed, rebuilding upstream')
      await this.locked(async () => {
        this.rt.builtWith = now
        await this.blockTraffic()
        this.update((x) => ({ ...x, phase: 'CONNECTING', exitIp: undefined, report: undefined, message: 'Applying new settings…' }))
        try {
          this.chainBroken = false
          const prev = this.rt.proxyHost
          await this.stopUpstream()
          let cur = s
          if (cur.connection.sticky && !cur.sessionId) cur = await updateSettings((x) => withSession(x, newSessionId()))
          this.rt.provider = cur.provider
          await this.startUpstream(cur, secrets, prev)
          this.update((x) => ({ ...x, phase: 'CONNECTED', message: undefined }))
        } catch (e) {
          if (!(await this.onProxyFailure(e))) return
        }
      })
      this.launchCheck(false)
    } else if (now.routing !== built.routing) {
      log.info('routing settings changed')
      this.rt.builtWith = now
      const target = this.rt.via === 'helper' ? `PROXY 127.0.0.1:${this.rt.helperPort}` : this.rt.endpoint ? pacDirective(this.rt.endpoint, this.rt.proxyHost) : BLACKHOLE
      await applyPac(pac(s, target))
      if (this.rt.status.trafficBlocked) await closeGate(gateScope(s))
      this.persist()
    }
  }

  /** New or removed API keys change which sources a report has: re-check right away. */
  apiKeysChanged() {
    this.reports.clear()
    const st = this.rt.status
    if (st.phase === 'CONNECTED' && !st.checking) this.launchCheck(true)
  }

  // ---------- traffic gate ----------

  private async blockTraffic() {
    const s = await loadSettings()
    await closeGate(gateScope(s))
    this.rt.cleanIp = null
    this.rt.waitingForUser = false
    if (this.helper?.connected) await this.helper.closeConnections().catch(() => undefined)
    this.update((x) => ({ ...x, trafficBlocked: true }))
  }

  private async allowTraffic(ip: string | null) {
    this.rt.cleanIp = ip
    this.rt.waitingForUser = false
    await openGate()
    this.update((x) => ({ ...x, trafficBlocked: false }))
  }

  // ---------- failures ----------

  /** Kill switch on: stay up with traffic blocked and keep retrying. Off: disconnect. Returns true if still up. */
  private async onProxyFailure(e: unknown): Promise<boolean> {
    const msg = this.describe(e)
    log.warn('proxy failure:', msg)
    const s = await loadSettings()
    if (s.killSwitch) {
      this.update((x) => ({ ...x, phase: 'PROXY_DOWN', checking: false, message: `${msg} — traffic blocked` }))
      return true
    }
    await this.teardown()
    this.update(() => ({ ...idleStatus(), phase: 'ERROR', message: `${msg} — disconnected (kill switch is off)` }))
    return false
  }

  private describe(e: unknown): string {
    if (e instanceof ProxyAuthError || (e instanceof HelperError && e.code === 'auth')) {
      return `${this.rt.provider ? PROVIDER_LABEL[this.rt.provider] : 'Proxy'} rejected the proxy username/password (Settings → Accounts)`
    }
    return (e as Error)?.message || String(e)
  }

  // ---------- probes ----------

  /** End-to-end check of the proxy and its credentials. Returns latency in ms. */
  private async probe(): Promise<number> {
    if (this.rt.via === 'helper') {
      if (!this.helper?.connected) throw new Error('Helper is not running')
      return this.helper.probe()
    }
    if (!this.rt.endpoint) throw new Error('Proxy is not set up')
    const start = performance.now()
    this.authRejected = false
    try {
      const r = await fetchTimeout(PROBE_URL, 15_000)
      if (r.status === 407) throw new ProxyAuthError()
      if (isDeadExitStatus(r.status)) throw new ExitNodeError(`Proxy couldn't reach the probe site (${r.status})`)
      if (r.status !== 204 && !r.ok) throw new Error(`Unexpected response through proxy: ${r.status}`)
    } catch (e) {
      if (this.authRejected || e instanceof ProxyAuthError) throw new ProxyAuthError()
      if (e instanceof ExitNodeError) throw e
      throw new Error(`Proxy unreachable (${(e as Error).message})`)
    }
    return Math.round(performance.now() - start)
  }

  private async exitIp(): Promise<string> {
    let last: unknown
    for (const url of IP_ECHO) {
      try {
        const r = await fetchTimeout(url, 20_000)
        if (!r.ok) throw new Error(`${r.status}`)
        const ip = parseEcho(await r.text())
        if (ip) return ip
      } catch (e) { last = e }
    }
    throw new Error(`Could not determine exit IP: ${(last as Error)?.message ?? 'no answer'}`)
  }

  async checkReputation(ip: string, force: boolean): Promise<ReputationReport> {
    const hit = this.reports.get(ip)
    if (!force && hit && Date.now() - hit.checkedAt < REPORT_CACHE_MS) return hit
    const { apiKeys } = await loadSecrets()
    const http = sourceRequests(ip, apiKeys).map(async (req): Promise<SourceResult> => {
      try {
        const body = await this.sourceFetch(req.url, req.headers ?? {})
        return req.parse(body)
      } catch (e) {
        return failed(req.id, (e as Error).message || 'failed')
      }
    })
    const dns = DNSBL_ZONES.map(async ([id, zone]): Promise<SourceResult> => {
      const name = dnsblName(ip, zone)
      if (!name) return failed(id, 'IPv6 not supported')
      try { return interpretDnsbl(id, await this.lookupA(name)) } catch (e) { return failed(id, (e as Error).message || 'lookup failed') }
    })
    const report = aggregate(ip, await Promise.all([...http, ...dns]))
    this.reports.set(ip, report)
    return report
  }

  /** Reputation APIs: direct through the helper first (saves proxy GB), else / on failure through the proxy. */
  private async sourceFetch(url: string, headers: Record<string, string>): Promise<string> {
    const h = { Accept: 'application/json', ...headers }
    if (this.helper?.connected) {
      try {
        const r = await this.helper.call('fetch', { url, method: 'GET', headers: h }, 12_000)
        const body = String(r.body ?? '')
        if (Number(r.status) < 400 || body.trimStart().startsWith('{')) return body
      } catch { /* retry through the proxy */ }
    }
    const r = await fetchTimeout(url, 20_000, { headers: h })
    const body = await r.text()
    // Error bodies are parsed only when they're JSON (API error objects), not proxy/HTML errors.
    if (!r.ok && !body.trimStart().startsWith('{')) throw new Error(`HTTP ${r.status}`)
    return body
  }

  /** A records for a DNSBL name: the system resolver via the helper, else DNS-over-HTTPS through the proxy. */
  private async lookupA(name: string): Promise<string[]> {
    if (this.helper?.connected) {
      try { return ((await this.helper.call('lookup', { name }, 8000)).addrs as string[]) ?? [] } catch (e) {
        if (e instanceof HelperError && e.code === 'nxdomain') return []
      }
    }
    let last: unknown
    for (const base of ['https://cloudflare-dns.com/dns-query', 'https://dns.google/resolve']) {
      try {
        const r = await fetchTimeout(`${base}?name=${encodeURIComponent(name)}&type=A`, 8000, { headers: { Accept: 'application/dns-json' } })
        const j = (await r.json()) as { Status: number; Answer?: { type: number; data: string }[] }
        if (j.Status === 3) return []
        if (j.Status !== 0) throw new Error(`DNS status ${j.Status}`)
        return (j.Answer ?? []).filter((a) => a.type === 1).map((a) => a.data)
      } catch (e) { last = e }
    }
    throw last instanceof Error ? last : new Error('lookup failed')
  }

  // ---------- check + auto-rotate ----------

  /**
   * Verifies the proxy works, then exit IP + reputation. Tabs stay blocked until an IP passes (verdict below the
   * rotate threshold). With auto-rotate on, keeps switching sticky sessions until one does.
   */
  private launchCheck(force: boolean) {
    const token = ++this.checkToken
    this.lastCheckKick = Date.now()
    this.guard('check', this.runCheck(token, force))
  }

  private async runCheck(token: number, force: boolean) {
    const alive = () => token === this.checkToken && isActive(this.rt.status.phase)
    this.checkRunning = true
    try {
      for (let attempt = 0; alive(); attempt++) {
        if (!this.rt.endpoint && this.rt.via !== 'helper') return
        this.update((x) => ({ ...x, checking: true, rotationAttempt: attempt }))
        let latency: number
        try {
          latency = await this.probe()
        } catch (e) {
          if (!alive()) return
          this.update((x) => ({ ...x, checking: false }))
          await this.onProxyFailure(e)
          return
        }
        if (!alive()) return
        this.update((x) => (x.phase === 'PROXY_DOWN' ? { ...x, phase: 'CONNECTED', message: undefined, latencyMs: latency } : { ...x, latencyMs: latency }))
        const s = await loadSettings()
        if (!s.recheckOnConnect && !force) {
          // Checks are turned off: nothing to wait for.
          await this.allowTraffic(null)
          this.update((x) => ({ ...x, checking: false }))
          return
        }
        let report: ReputationReport
        try {
          const ip = await this.exitIp()
          if (!alive()) return
          if (ip !== this.rt.cleanIp) await this.blockTraffic() // new IP, or it changed under us (provider ended the session)
          this.update((x) => ({ ...x, exitIp: ip }))
          report = await this.checkReputation(ip, force)
        } catch (e) {
          if (alive()) this.update((x) => ({ ...x, checking: false, message: `Couldn't check the IP (${(e as Error).message}) — traffic blocked, retrying` }))
          return
        }
        if (!alive()) return
        this.update((x) => ({ ...x, report, checking: false }))
        if (report.verdict === 'UNKNOWN') {
          this.update((x) => ({ ...x, message: 'No reputation source answered — traffic blocked, retrying' }))
          return
        }
        log.info(`exit IP ${report.ip}: ${report.verdict}${report.reasons.length ? ` (${report.reasons.map((r) => r.text).join('; ')})` : ''}`)
        if (SEVERITY[report.verdict] < SEVERITY[s.rotateThreshold]) {
          await this.allowTraffic(report.ip)
          this.update((x) => ({ ...x, message: undefined }))
          if (attempt > 0) notify('IP reputation', `Found ${word(report.verdict)} IP ${report.ip} after ${attempt} rotation(s)`)
          return
        }
        await this.blockTraffic()
        if (!s.autoRotate || !s.connection.sticky) {
          this.rt.waitingForUser = true
          this.update((x) => ({ ...x, message: `IP is ${word(report.verdict)} — traffic blocked. Click New IP.` }))
          return
        }
        // No limit: keep rotating until an IP passes. After the first quick tries, pace them.
        if (attempt >= QUICK_ROTATIONS) {
          if (attempt === QUICK_ROTATIONS) notify('IP reputation', `No clean IP after ${attempt} tries — traffic blocked, still trying`)
          this.update((x) => ({ ...x, message: `No clean IP after ${attempt} tries — traffic blocked, trying again shortly` }))
          await new Promise((r) => setTimeout(r, ROTATION_PAUSE_MS))
          if (!alive()) return
        }
        await this.switchSession(newSessionId())
      }
    } finally {
      if (token === this.checkToken) {
        this.checkRunning = false
        if (this.rt.status.checking) this.update((x) => ({ ...x, checking: false }))
      }
    }
  }

  // ---------- health ----------

  private resetHealth() {
    this.fails = 0; this.deadExitProbes = 0; this.lastProbe = Date.now(); this.lastPing = 0; this.failedRepairs = 0
    this.seenOk = 0; this.seenFail = 0; this.failingSince = 0; this.failsInStreak = 0
    this.lastCounters = null; this.directFailures = []; this.directOk = 0
  }

  private startHealth() {
    if (this.healthTimer) return
    this.healthTimer = setInterval(() => this.guard('health', this.healthTick()), TICK_MS)
  }

  private stopHealth() {
    if (this.healthTimer) clearInterval(this.healthTimer)
    this.healthTimer = null
  }

  /** Alarm fallback: the worker may have been suspended (direct mode has nothing keeping it alive). */
  async alarm() {
    await this.loaded
    if (!isActive(this.rt.status.phase)) return
    this.startHealth()
    await this.healthTick()
  }

  private healthBusy = false

  /**
   * Every few seconds: are tabs' requests getting answers, is the VPN handshake fresh, latencies and traffic.
   * Every `healthIntervalSec`: an end-to-end probe. Proxy answers but traffic or VPN is broken → repair.
   */
  private async healthTick() {
    if (this.healthBusy || !isActive(this.rt.status.phase)) return
    this.healthBusy = true
    try {
      const now = Date.now()
      const s = await loadSettings()
      const interval = clamp(s.healthIntervalSec, 10, 600) * 1000
      const down = this.rt.status.phase === 'PROXY_DOWN'
      let suspect: string | null = null
      const gateOpen = !this.rt.status.trafficBlocked

      let st: HelperStatus | null = null
      if (this.rt.via === 'helper' && this.helper?.connected) st = await this.helper.status().catch((e) => { log.warn('helper status', e); return null })

      // 1. Real traffic: only failures (across ≥2 hosts) and no successes for 20 s means tabs have no internet.
      let newOk: number, newFail: number, failedHosts: number
      if (st) {
        newOk = st.conns - this.seenOk; newFail = st.connFails - this.seenFail
        this.seenOk = st.conns; this.seenFail = st.connFails
        failedHosts = st.failedHosts
        this.trackSpeed(st, s.provider)
      } else {
        newOk = this.directOk; this.directOk = 0
        const recent = this.directFailures.filter((f) => f.at > now - TICK_MS - 1000)
        newFail = recent.length
        failedHosts = new Set(this.directFailures.filter((f) => f.at > (this.failingSince || now) - 1000).map((f) => f.host)).size
        if (this.unsavedUsage.rx > 256 * 1024) void this.flushUsage()
      }
      if (newOk > 0) { this.failingSince = 0; this.failsInStreak = 0; this.failedRepairs = 0 }
      else if (newFail > 0) { if (!this.failingSince) this.failingSince = now; this.failsInStreak += newFail }
      if (!gateOpen) { this.failingSince = 0; this.failsInStreak = 0 }
      if (this.failingSince && now - this.failingSince > 20_000 && this.failsInStreak >= 3 && failedHosts >= 2) {
        suspect = "tabs' connections were failing"
      }

      // 2. VPN: WireGuard re-handshakes every 2 minutes; an old handshake means the VPN path is dead.
      if (st && this.rt.status.chainName) {
        const age = st.handshakeAgeSec
        if ((age === undefined && now - this.chainStartedAt > 20_000) || (age !== undefined && age > 200)) suspect = 'VPN handshake lost'
        if (this.chainDns && now - this.lastPing > 10_000) {
          this.lastPing = now
          const ms = await this.helper!.vpnPing(this.chainDns)
          if (ms !== null) this.update((x) => ({ ...x, vpnPingMs: ms }))
        }
      }
      if (st?.rttMs) this.update((x) => ({ ...x, proxyPingMs: Math.round(st!.rttMs!) }))

      // Blocked with no check running (it failed, or the proxy dropped mid-check): check again.
      if (!gateOpen && !this.rt.waitingForUser && !this.checkRunning && this.rt.status.phase === 'CONNECTED' && now - this.lastCheckKick > 30_000) {
        this.launchCheck(false)
      }

      const backoff = Math.min(5000 * (this.fails + 1), interval)
      if (!this.rt.via) {
        // Upstream never came up (no network / VPN down at connect): keep retrying.
        if (down && now - this.lastProbe >= backoff) { this.lastProbe = now; this.fails++; await this.repair('retrying', true) }
        return
      }
      if (!suspect && now - this.lastProbe < (down ? backoff : interval)) return
      this.lastProbe = now
      try {
        const ms = await this.probe()
        this.fails = 0; this.deadExitProbes = 0
        if (this.rt.status.phase === 'PROXY_DOWN') {
          this.update((x) => ({ ...x, phase: 'CONNECTED', message: 'Proxy reachable again' }))
          if ((!this.rt.status.exitIp || this.rt.status.trafficBlocked) && !this.checkRunning) this.launchCheck(false)
        }
        this.update((x) => ({ ...x, latencyMs: ms }))
        if (suspect) { this.failingSince = 0; this.failsInStreak = 0; await this.repair(suspect) }
      } catch (e) {
        this.fails++
        const dead = e instanceof ExitNodeError || (e instanceof HelperError && e.code === 'refused')
        this.deadExitProbes = dead ? this.deadExitProbes + 1 : 0
        if (this.deadExitProbes >= DEAD_EXIT_PROBES && s.connection.sticky) {
          // Repairs and reconnects reuse the same session, so they'd never get past a dead exit node.
          log.info(`exit node not answering (${(e as Error).message}), switching to a new session`)
          this.deadExitProbes = 0; this.fails = 0
          await this.switchSession(newSessionId())
          this.update((x) => ({ ...x, message: 'Exit IP stopped working — switched to a new one' }))
          this.launchCheck(false)
          return
        }
        const auth = e instanceof ProxyAuthError || (e instanceof HelperError && e.code === 'auth')
        if (this.fails >= 2 || auth || down || suspect) {
          if (!(await this.onProxyFailure(e))) return
          if (!auth && (this.fails % 3 === 0 || suspect)) await this.repair(suspect ?? 'proxy unreachable', this.fails % 3 === 0)
        }
      }
    } finally {
      this.healthBusy = false
    }
  }

  private trackSpeed(st: HelperStatus, provider: Provider) {
    const prev = this.lastCounters
    this.lastCounters = { down: st.down, up: st.up }
    if (!prev) return
    const dDown = Math.max(0, st.down - prev.down), dUp = Math.max(0, st.up - prev.up)
    this.unsavedUsage.rx += dDown; this.unsavedUsage.tx += dUp
    this.update((x) => ({
      ...x, downBps: Math.round(dDown / (TICK_MS / 1000)), upBps: Math.round(dUp / (TICK_MS / 1000)),
      sessionDown: x.sessionDown + dDown, sessionUp: x.sessionUp + dUp,
    }))
    if (this.unsavedUsage.rx + this.unsavedUsage.tx > 256 * 1024) void this.flushUsage(provider)
  }

  private async flushUsage(provider = this.rt.provider) {
    const { rx, tx } = this.unsavedUsage
    this.unsavedUsage = { rx: 0, tx: 0 }
    if (provider) await addUsage(provider, rx, tx).catch(() => undefined)
  }

  /**
   * Rebuilds the VPN and upstream while the PAC stays set. Rate-limited unless [force]; after three repairs that
   * didn't help, reconnects from scratch.
   */
  private async repair(reason: string, force = false) {
    if (!isActive(this.rt.status.phase)) return
    const now = Date.now()
    if (!force && now - this.lastRepairAt < 60_000) return
    this.lastRepairAt = now
    log.info('repairing connection:', reason)
    const retry = reason === 'retrying'
    if (!retry && this.failedRepairs >= 3) {
      this.failedRepairs = 0
      await this.connect()
      this.update((x) => ({ ...x, repairs: x.repairs + 1, message: `Reconnected (${reason})` }))
      return
    }
    if (!retry) this.failedRepairs++
    await this.locked(async () => {
      if (!isActive(this.rt.status.phase)) return
      const s = await loadSettings()
      let secrets = await loadSecrets()
      if (s.chainEnabled && parseVpnExit(s.vpnExit)?.kind === 'mullvad' && reason.startsWith('VPN')) {
        // The device may have been removed on the Mullvad website: then this install is signed out (no silent
        // re-registration, which would take a slot) and the chain reports it.
        await this.verifyMullvadDevice()
        secrets = await loadSecrets()
      }
      this.chainBroken = false
      try {
        const prev = this.rt.proxyHost
        await this.stopUpstream()
        await this.startUpstream(s, secrets, prev)
        this.update((x) => retry ? { ...x, phase: 'CONNECTED', message: undefined }
          : { ...x, phase: 'CONNECTED', repairs: x.repairs + 1, message: `Connection repaired (${reason})` })
      } catch (e) {
        await this.onProxyFailure(e)
      }
    })
    this.launchCheck(false)
  }
}

class ConfigError extends Error {}

// ---------- helpers ----------

function word(v: Verdict) { return v.toLowerCase() }

function isIp(host: string) { return /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':') }

function gateScope(s: AppSettings) {
  return { bypassLan: s.bypassLan, splitMode: s.connection.siteSplitMode, sites: s.connection.sites }
}

function pac(s: AppSettings, directive: string) {
  return buildPac({ proxy: directive, bypassLan: s.bypassLan, splitMode: s.connection.siteSplitMode, sites: s.connection.sites })
}

function pacDirective(ep: Endpoint, host: string) {
  const h = host.includes(':') ? `[${host}]` : host
  return `${ep.protocol === 'SOCKS5' ? 'SOCKS5' : 'PROXY'} ${h}:${ep.port}`
}

/** What the running connection was built from; a change while connected rebuilds the affected part. */
function keys(s: AppSettings, secrets: Secrets): Keys {
  const c = s.connection
  const acc = secrets.accounts[s.provider]
  const e = s.chainEnabled ? parseVpnExit(s.vpnExit) : null
  const vpn = !e ? '' : e.kind === 'wg' ? String(hash(secrets.vpnProfiles.find((p) => p.id === e.id)?.config ?? ''))
    : `${e.code}/${secrets.mullvad?.number ?? ''}`
  return {
    routing: JSON.stringify([s.bypassLan, c.siteSplitMode, [...c.sites].sort()]),
    upstream: JSON.stringify([s.provider, c.protocol, c.sticky, c.lifetimeMinutes, c.gateways, acc?.username, acc?.password, s.chainEnabled, s.vpnExit, vpn]),
  }
}

function hash(t: string) {
  let h = 0
  for (let i = 0; i < t.length; i++) h = (Math.imul(31, h) + t.charCodeAt(i)) | 0
  return h
}

async function fetchTimeout(url: string, ms: number, init: RequestInit = {}): Promise<Response> {
  const ctl = new AbortController()
  const t = setTimeout(() => ctl.abort(), ms)
  try {
    return await fetch(url, { ...init, cache: 'no-store', credentials: 'omit', signal: ctl.signal })
  } finally {
    clearTimeout(t)
  }
}

/**
 * Chrome caches proxy credentials per proxy host and keeps sending them, so a new session's credentials would
 * never be used. Clearing the proxy site's browsing data (cookies) also clears its HTTP auth cache entries.
 * Side effect: cookies of the provider's own site (e.g. a dashboard login on iproyal.com) are removed too.
 */
async function clearProxyAuthCache(host: string) {
  if (!chrome.browsingData) return
  const h = host.replace(/\.$/, '')
  const name = h.includes(':') ? `[${h}]` : h
  try {
    await chrome.browsingData.remove({ origins: [`https://${name}`, `http://${name}`] }, { cookies: true })
  } catch (e) {
    log.warn('clear auth cache', e)
  }
}
