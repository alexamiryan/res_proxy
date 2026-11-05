// Messages between the extension pages (popup, options) and the background service worker.

import type { Account, ApiKeys, AppSettings, MullvadAccount, Provider, VpnProfile } from '../core/models'
import type { ReputationReport } from '../core/reputation'
import type { MullvadRelay } from '../core/vpn'
import type { LocationCatalog } from '../core/locations'
import type { LocalPrefs, UsageTotals } from '../background/store'

export type Phase = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'ROTATING' | 'PROXY_DOWN' | 'ERROR'

export interface TunnelStatus {
  phase: Phase
  message?: string
  profileName?: string
  sessionId?: string
  exitIp?: string
  report?: ReputationReport
  checking: boolean
  rotationAttempt: number
  /** The exit IP hasn't passed the check (yet): tabs are blocked. */
  trafficBlocked: boolean
  latencyMs?: number
  connectedAt?: number
  /** VPN the proxy is reached through, when chaining. */
  chainName?: string
  vpnPingMs?: number
  proxyPingMs?: number
  repairs: number
  /** How Chrome reaches the proxy: directly (HTTP proxy + auth) or through the native helper. */
  via?: 'direct' | 'helper'
  downBps?: number
  upBps?: number
  sessionDown: number
  sessionUp: number
}

export const idleStatus = (): TunnelStatus => ({
  phase: 'DISCONNECTED', checking: false, rotationAttempt: 0, trafficBlocked: false, repairs: 0, sessionDown: 0, sessionUp: 0,
})

export const isActive = (p: Phase) => p === 'CONNECTING' || p === 'CONNECTED' || p === 'ROTATING' || p === 'PROXY_DOWN'

export interface HelperInfo { installed: boolean; version?: string; platform?: string; error?: string }

export interface LatencyResult { exit: string; ms: number | null }

export interface UiState {
  settings: AppSettings
  accounts: Partial<Record<Provider, Account>>
  apiKeys: ApiKeys
  vpnProfiles: VpnProfile[]
  /** Without the device private key. */
  mullvad: { number: string; expiry: string; deviceName: string; deviceId: string; signedOut: boolean } | null
  prefs: LocalPrefs
  usage: Partial<Record<Provider, UsageTotals>>
  helper: HelperInfo
  extensionId: string
}

export type Request =
  | { type: 'state' }
  | { type: 'connect' }
  | { type: 'disconnect' }
  | { type: 'rotate' }
  | { type: 'recheck' }
  | { type: 'applyLocation' }
  | { type: 'updateSettings'; patch: Partial<AppSettings> }
  | { type: 'selectProvider'; provider: Provider }
  | { type: 'saveAccount'; account: Account }
  | { type: 'deleteAccount'; provider: Provider }
  | { type: 'saveApiKeys'; keys: ApiKeys }
  | { type: 'saveVpnProfile'; profile: VpnProfile }
  | { type: 'deleteVpnProfile'; id: string }
  | { type: 'setMullvad'; number: string }
  | { type: 'mullvadLogin' }
  | { type: 'removeMullvad' }
  | { type: 'mullvadDevices'; number?: string }
  | { type: 'mullvadVerify' }
  | { type: 'mullvadSignOutHere' }
  | { type: 'removeMullvadDevice'; id: string; number?: string }
  | { type: 'mullvadRelays'; force?: boolean }
  | { type: 'catalog'; provider: Provider; force?: boolean }
  | { type: 'latency' }
  | { type: 'savePrefs'; prefs: Partial<LocalPrefs> }
  | { type: 'resetUsage' }
  | { type: 'helperInfo' }
  | { type: 'exportBackup'; passphrase: string; shareMullvadDevice?: boolean }
  | { type: 'openBackup'; text: string; passphrase: string }
  | { type: 'importBackup'; text: string; passphrase: string }

export type Response<T = unknown> = { ok: true; value: T } | { ok: false; error: string }

export interface BackupPreview {
  createdAt: number; accounts: number; vpnProfiles: number; mullvad: boolean; provider: Provider
  /** Name of the Mullvad device the backup shares, if any. */
  mullvadDevice?: string
}

export type { MullvadRelay, LocationCatalog, MullvadAccount }
