// Chrome plumbing: proxy settings, the traffic gate (declarativeNetRequest), WebRTC protection, badge.

import type { SplitMode } from '../core/models'
import { normalizeSite } from '../core/pac'

// ---------- proxy ----------

/** Regular windows, plus incognito ones when the extension is allowed there. */
async function scopes(): Promise<chrome.types.ChromeSettingScope[]> {
  const incognito = await chrome.extension.isAllowedIncognitoAccess().catch(() => false)
  return incognito ? ['regular', 'incognito_persistent'] : ['regular']
}

export async function applyPac(script: string) {
  for (const scope of await scopes()) {
    await chrome.proxy.settings.set({ value: { mode: 'pac_script', pacScript: { data: script, mandatory: true } }, scope })
  }
}

export async function clearProxy() {
  for (const scope of await scopes()) await chrome.proxy.settings.clear({ scope })
}

/** Whether another extension controls the proxy (then ours can't apply). */
export async function proxyControlledByOther(): Promise<boolean> {
  const d = await chrome.proxy.settings.get({ incognito: false })
  return d.levelOfControl === 'controlled_by_other_extensions' || d.levelOfControl === 'not_controllable'
}

// ---------- traffic gate ----------

/**
 * While closed, tabs get no network: page loads show blocked.html (which reloads the page once the gate opens)
 * and every other tab request is blocked. Requests not from a tab (this extension's own checks) still pass,
 * like the Android app's ungated local proxy. LAN and split-excluded sites never use the proxy, so they stay open.
 */
const GATE_RULES = [1, 2, 3, 4, 5, 6, 7, 8]
// Several small patterns: DNR rejects a regex whose compiled form exceeds 2 KB.
const LAN_REGEXES = [
  String.raw`^[a-z]+://(localhost|127\.|10\.|192\.168\.|169\.254\.)`,
  String.raw`^[a-z]+://172\.(1[6-9]|2[0-9]|3[01])\.`,
  String.raw`^[a-z]+://\[(f[cd]|fe[89ab]|::1)`,
  String.raw`^[a-z]+://[^/.:]+(:|/|$)`,
  String.raw`^[a-z]+://[^/:]*\.(local|lan|home|internal|localdomain)(:|/|$)`,
]

export interface GateScope { bypassLan: boolean; splitMode: SplitMode; sites: string[] }

export async function closeGate(scope: GateScope) {
  const sites = [...new Set(scope.sites.map(normalizeSite).filter(Boolean))]
  const mode = sites.length === 0 ? 'ALL' : scope.splitMode
  const blocked = chrome.runtime.getURL('blocked.html')
  const allTypes = Object.values(chrome.declarativeNetRequest.ResourceType) as chrome.declarativeNetRequest.ResourceType[]
  const sub = allTypes.filter((t) => t !== 'main_frame')
  const onlySites = mode === 'ONLY_SELECTED' ? { requestDomains: sites } : {}
  const rules: chrome.declarativeNetRequest.Rule[] = [
    {
      id: 1, priority: 1,
      action: { type: 'redirect' as chrome.declarativeNetRequest.RuleActionType, redirect: { regexSubstitution: `${blocked}#\\0` } },
      condition: { regexFilter: '^(https?|wss?)://.*$', resourceTypes: ['main_frame' as chrome.declarativeNetRequest.ResourceType], ...onlySites },
    },
    {
      id: 2, priority: 1,
      action: { type: 'block' as chrome.declarativeNetRequest.RuleActionType },
      condition: { resourceTypes: sub, excludedTabIds: [chrome.tabs.TAB_ID_NONE], ...onlySites },
    },
  ]
  if (scope.bypassLan) {
    LAN_REGEXES.forEach((regexFilter, i) => {
      rules.push({ id: 3 + i, priority: 2, action: { type: 'allow' as chrome.declarativeNetRequest.RuleActionType }, condition: { regexFilter } })
    })
  }
  if (mode === 'EXCEPT_SELECTED') {
    rules.push({ id: 8, priority: 2, action: { type: 'allow' as chrome.declarativeNetRequest.RuleActionType }, condition: { requestDomains: sites } })
  }
  await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: GATE_RULES, addRules: rules })
}

export async function openGate() {
  await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: GATE_RULES })
}

// ---------- WebRTC ----------

/** Keeps WebRTC from sending UDP outside the proxy (it would reveal the real IP). */
export async function protectWebRtc(on: boolean) {
  const setting = chrome.privacy?.network?.webRTCIPHandlingPolicy
  if (!setting) return
  try {
    if (on) await setting.set({ value: 'disable_non_proxied_udp' as chrome.privacy.IPHandlingPolicy })
    else await setting.clear({})
  } catch { /* controlled by policy */ }
}

// ---------- badge ----------

export async function setBadge(kind: 'off' | 'ok' | 'blocked' | 'busy' | 'error') {
  const map = {
    off: { text: '', color: '#777' },
    ok: { text: 'ON', color: '#1b8a4a' },
    blocked: { text: '!', color: '#c62828' },
    busy: { text: '…', color: '#b26a00' },
    error: { text: '×', color: '#c62828' },
  }[kind]
  await chrome.action.setBadgeText({ text: map.text })
  await chrome.action.setBadgeBackgroundColor({ color: map.color })
}

// ---------- notifications ----------

export function notify(title: string, message: string) {
  chrome.notifications?.create(`rp-${Date.now()}`, { type: 'basic', iconUrl: 'icons/icon128.png', title, message, priority: 0 })
}
