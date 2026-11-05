// Client for the native helper (native/wgbridge/cmd/resproxy-helper): a local HTTP proxy that reaches the
// upstream through SOCKS5 / HTTP CONNECT, optionally inside a userspace WireGuard tunnel.
// Wire format: Chrome native messaging (32-bit length + JSON), requests `{id, cmd, …}`, replies `{id, ok, …}`.

import type { Endpoint } from '../core/presets'

export const HOST_NAME = 'dev.resproxy.helper'

export interface HelperStatus {
  /** Seconds since the last WireGuard handshake; absent without a VPN or before the first one. */
  handshakeAgeSec?: number
  /** Bytes Chrome sent/received through the helper since start. */
  down: number
  up: number
  /** Connections to targets that worked / failed since start, and distinct hosts that failed in the last 60 s. */
  conns: number
  connFails: number
  failedHosts: number
  lastError?: string
  /** EWMA of the proxy handshake round trip in ms. */
  rttMs?: number
}

export class HelperError extends Error {
  constructor(message: string, readonly code?: string) { super(message) }
}

type Pending = { resolve: (v: Record<string, unknown>) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }

export class Helper {
  private port: chrome.runtime.Port | null = null
  private seq = 0
  private pending = new Map<number, Pending>()
  /** Called when the helper exits or the connection drops (the local proxy is gone with it). */
  onDisconnect: ((reason: string) => void) | null = null

  get connected() { return this.port !== null }

  private ensure(): chrome.runtime.Port {
    if (this.port) return this.port
    const port = chrome.runtime.connectNative(HOST_NAME)
    port.onMessage.addListener((msg: Record<string, unknown>) => {
      const p = this.pending.get(msg.id as number)
      if (!p) return
      this.pending.delete(msg.id as number)
      clearTimeout(p.timer)
      if (msg.ok) p.resolve(msg)
      else p.reject(new HelperError(String(msg.error ?? 'Helper error'), msg.code as string | undefined))
    })
    port.onDisconnect.addListener(() => {
      const reason = chrome.runtime.lastError?.message ?? 'Helper exited'
      this.port = null
      for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new HelperError(reason, 'gone')) }
      this.pending.clear()
      this.onDisconnect?.(reason)
    })
    this.port = port
    return port
  }

  call(cmd: string, args: Record<string, unknown> = {}, timeoutMs = 30_000): Promise<Record<string, unknown>> {
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new HelperError(`Helper didn't answer (${cmd})`, 'timeout')) }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      try {
        this.ensure().postMessage({ id, cmd, ...args })
      } catch (e) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(new HelperError((e as Error).message, 'gone'))
      }
    })
  }

  disconnect() {
    const p = this.port
    this.port = null
    p?.disconnect()
  }

  /** Generous timeout: the first start of a new helper binary can wait on an antivirus scan. */
  async hello(timeoutMs = 20_000): Promise<{ version: string; platform: string }> {
    const r = await this.call('hello', {}, timeoutMs)
    return { version: String(r.version), platform: String(r.platform) }
  }

  /** Starts (or restarts) the local proxy; returns its port. [wg] is a wg-quick config to chain through. */
  async start(upstream: Endpoint, wg: string | null, token: string): Promise<number> {
    const r = await this.call('start', { upstream, wg, token }, 45_000)
    return Number(r.port)
  }

  /** Switches to new upstream credentials (new session) and closes open connections. */
  async setUpstream(upstream: Endpoint) { await this.call('upstream', { upstream }) }

  async closeConnections() { await this.call('closeConns') }

  /** End-to-end probe of the upstream (and its credentials). Returns latency ms; throws HelperError code 'auth' on bad credentials. */
  async probe(): Promise<number> { return Number((await this.call('probe', {}, 25_000)).ms) }

  async status(): Promise<HelperStatus> { return (await this.call('status', {}, 5000)) as unknown as HelperStatus }

  /** In-tunnel ICMP round trip to [ip] (the VPN's DNS server sits on the VPN server). */
  async vpnPing(ip: string): Promise<number | null> {
    try { return Number((await this.call('vpnPing', { ip }, 6000)).ms) } catch { return null }
  }

  /** System ICMP ping to many hosts in parallel (outside any tunnel). */
  async ping(targets: string[]): Promise<Record<string, number | null>> {
    return (await this.call('ping', { targets }, 60_000)).results as Record<string, number | null>
  }

  async stop() { if (this.port) await this.call('stop', {}, 10_000).catch(() => undefined) }
}

/** One-shot check whether the helper is installed (without keeping it running). */
export async function probeHelper(): Promise<{ installed: boolean; version?: string; platform?: string; error?: string }> {
  const h = new Helper()
  try {
    const info = await h.hello()
    return { installed: true, ...info }
  } catch (e) {
    // Keep the browser's exact words: "…host not found" (not registered for this browser), "…forbidden"
    // (extension ID not allowed), "Failed to start…" (blocked, e.g. by antivirus), a timeout…
    return { installed: false, error: (e as Error).message }
  } finally {
    h.disconnect()
  }
}
