import { useEffect, useState, useCallback } from 'preact/hooks'
import type { Request, Response, TunnelStatus, UiState } from '../shared/protocol'
import { idleStatus } from '../shared/protocol'

export async function send<T = unknown>(req: Request): Promise<T> {
  const r = (await chrome.runtime.sendMessage(req)) as Response<T> | undefined
  if (!r) throw new Error('The extension background is not responding. Reload the extension.')
  if (!r.ok) throw new Error(r.error)
  return r.value
}

/** Live tunnel status (mirrored by the worker into chrome.storage.session). */
export function useStatus(): TunnelStatus {
  const [s, set] = useState<TunnelStatus>(idleStatus())
  useEffect(() => {
    void chrome.storage.session.get('runtime').then(({ runtime }) => { if (runtime) set((runtime as { status: TunnelStatus }).status) })
    const l = (c: Record<string, chrome.storage.StorageChange>, area: string) => {
      if (area === 'session' && c.runtime?.newValue) set((c.runtime.newValue as { status: TunnelStatus }).status)
    }
    chrome.storage.onChanged.addListener(l)
    return () => chrome.storage.onChanged.removeListener(l)
  }, [])
  return s
}

/** Settings, accounts, keys…; refreshed whenever stored data changes. */
export function useUiState(): [UiState | null, () => void] {
  const [s, set] = useState<UiState | null>(null)
  const reload = useCallback(() => { void send<UiState>({ type: 'state' }).then(set).catch(console.error) }, [])
  useEffect(() => {
    reload()
    const l = (_: unknown, area: string) => { if (area === 'local') reload() }
    chrome.storage.onChanged.addListener(l)
    return () => chrome.storage.onChanged.removeListener(l)
  }, [reload])
  return [s, reload]
}

export function formatBytes(n: number | undefined): string {
  if (!n || n < 0) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${u[i]}`
}

export function openOptions(hash = '') {
  void chrome.tabs.create({ url: chrome.runtime.getURL(`options.html${hash ? `#${hash}` : ''}`) })
}
