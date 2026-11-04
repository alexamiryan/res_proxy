import { render } from 'preact'
import { useEffect, useState } from 'preact/hooks'
import {
  type Account, type AppSettings, type ApiKeys, type Provider, type VpnProfile, MAX_LIFETIME_MINUTES, PROVIDERS, PROVIDER_LABEL,
  emptyAccount, randomId,
} from '../core/models'
import { baseUsername, buildEndpoint, parseProxyLine, presetOf, profileFrom } from '../core/presets'
import { parseWireGuard, wgEndpoint } from '../core/vpn'
import { normalizeSite } from '../core/pac'
import type { BackupPreview, HelperInfo, UiState } from '../shared/protocol'
import { formatBytes, send, useUiState } from './api'
import { Confirm, ErrorText, Group, Hint, NumberField, Row, Segmented, SwitchRow, TextField } from './components'
import { LocationPicker, VpnPicker } from './pickers'

type Page = 'accounts' | 'location' | 'vpn' | 'split' | 'protection' | 'reputation' | 'backup' | 'usage' | 'advanced' | 'helper'
const PAGES: [Page, string][] = [
  ['accounts', 'Proxy accounts'], ['location', 'Location'], ['vpn', 'VPN'], ['split', 'Split tunnelling'], ['protection', 'Protection'],
  ['reputation', 'IP reputation'], ['backup', 'Backup & transfer'], ['usage', 'Data usage'], ['advanced', 'Advanced'], ['helper', 'Helper app'],
]

const patch = (p: Partial<AppSettings>) => send({ type: 'updateSettings', patch: p })

function useHash(): [string, (h: string) => void] {
  const [h, set] = useState(location.hash.slice(1))
  useEffect(() => {
    const l = () => set(location.hash.slice(1))
    addEventListener('hashchange', l)
    return () => removeEventListener('hashchange', l)
  }, [])
  return [h, (x) => { location.hash = x }]
}

function Options() {
  const [state] = useUiState()
  const [hash, go] = useHash()
  const [helper, setHelper] = useState<HelperInfo | null>(null)
  useEffect(() => { void send<HelperInfo>({ type: 'helperInfo' }).then(setHelper).catch(() => setHelper({ installed: false })) }, [])
  if (!state) return <div class="loading">Loading…</div>
  const st: UiState = { ...state, helper: helper ?? state.helper }
  const [page, arg] = hash.split('/') as [Page | 'account', string | undefined]
  const current: Page = page === 'account' ? 'accounts' : PAGES.some(([p]) => p === page) ? (page as Page) : 'accounts'

  return (
    <div class="options">
      <nav class="sidenav" aria-label="Settings">
        <div class="brand"><img src="icons/icon32.png" alt="" width={24} height={24} /> ResProxy</div>
        {PAGES.map(([p, label]) => <a href={`#${p}`} class={p === current ? 'on' : ''} aria-current={p === current ? 'page' : undefined}>{label}</a>)}
      </nav>
      <main class="content">
        {current === 'accounts' && <AccountsPage state={st} provider={page === 'account' ? (arg as Provider) : undefined} go={go} />}
        {current === 'location' && <Group title="Location"><LocationPicker state={st} /></Group>}
        {current === 'vpn' && <VpnPage state={st} />}
        {current === 'split' && <SplitPage s={st.settings} />}
        {current === 'protection' && <ProtectionPage state={st} />}
        {current === 'reputation' && <ReputationPage keys={st.apiKeys} />}
        {current === 'backup' && <BackupPage />}
        {current === 'usage' && <UsagePage state={st} />}
        {current === 'advanced' && <AdvancedPage state={st} />}
        {current === 'helper' && <HelperPage state={st} helper={helper} recheck={() => { setHelper(null); void send<HelperInfo>({ type: 'helperInfo' }).then(setHelper) }} />}
      </main>
    </div>
  )
}

// ---------- accounts ----------

function AccountsPage({ state, provider, go }: { state: UiState; provider?: Provider; go: (h: string) => void }) {
  if (provider && PROVIDERS.includes(provider)) return <AccountEditor state={state} provider={provider} onDone={() => go('accounts')} />
  return (
    <>
      <Group title="Proxy accounts">
        {PROVIDERS.map((p) => {
          const a = state.accounts[p]
          return (
            <Row title={PROVIDER_LABEL[p]} sub={a?.username ? baseUsername(p, a.username) : 'Not set up'} onClick={() => go(`account/${p}`)}>
              {state.settings.provider === p && a?.username ? <span class="chip">In use</span> : <span class="chev">›</span>}
            </Row>
          )
        })}
      </Group>
      <Hint>Stored encrypted in this browser only. Use Backup & transfer to copy them to Android or another browser.</Hint>
    </>
  )
}

function AccountEditor({ state, provider, onDone }: { state: UiState; provider: Provider; onDone: () => void }) {
  const [a, setA] = useState<Account>(state.accounts[provider] ?? emptyAccount(provider))
  const [err, setErr] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const gw = state.settings.connection.gateways[provider] ?? { host: '', port: 0 }
  const [host, setHost] = useState(gw.host)
  const [port, setPort] = useState(gw.port ? String(gw.port) : '')
  const looksLikeEmail = /@.+\./.test(a.username) && !a.username.includes(':')
  const set = (k: keyof Account, v: string) => {
    if (k === 'username' || k === 'password') {
      const line = parseProxyLine(v)
      if (line) {
        setA({ ...a, username: line.username, password: line.password })
        if (provider === 'GENERIC' && line.host) { setHost(line.host); setPort(String(line.port ?? '')) }
        return
      }
    }
    setA({ ...a, [k]: v })
  }
  const save = async () => {
    setErr('')
    if (!a.username.trim()) { setErr('Enter the proxy username'); return }
    if (provider === 'GENERIC' && !host.trim()) { setErr('Enter the proxy host'); return }
    try {
      await send({ type: 'saveAccount', account: { ...a, username: a.username.trim() } })
      const p = Number(port) || 0
      if (host.trim() !== gw.host || p !== gw.port) {
        await patch({ connection: { ...state.settings.connection, gateways: { ...state.settings.connection.gateways, [provider]: { host: host.trim(), port: p } } } })
      }
      if (!PROVIDERS.some((x) => x !== provider && state.accounts[x]?.username)) await send({ type: 'selectProvider', provider })
      onDone()
    } catch (e) { setErr((e as Error).message) }
  }
  return (
    <>
      <button class="link" onClick={onDone}>‹ Proxy accounts</button>
      <Group title={`${PROVIDER_LABEL[provider]} account`}>
        <div class="form">
          {provider === 'GENERIC' && (
            <div class="two">
              <TextField label="Proxy host" value={host} onSave={setHost} debounceMs={0} />
              <TextField label="Port" value={port} onSave={setPort} debounceMs={0} />
            </div>
          )}
          <TextField label="Proxy username" value={a.username} onSave={(v) => set('username', v)} debounceMs={0} />
          {looksLikeEmail && provider !== 'GENERIC' && <p class="hint warn">This looks like your website login email. The proxy username is a different, generated value.</p>}
          <TextField label="Proxy password" type="password" value={a.password} onSave={(v) => set('password', v)} debounceMs={0} />
          {provider === 'IPROYAL' && (
            <>
              <TextField label="API token (optional, for the city list)" type="password" value={a.apiToken} onSave={(v) => set('apiToken', v)} debounceMs={0} />
              <Hint>IPRoyal dashboard → Settings → API. Used only to list the locations you can target.</Hint>
            </>
          )}
          {provider === 'GEONODE' && (
            <>
              <TextField label="Dashboard login (email, optional)" value={a.apiLogin} onSave={(v) => set('apiLogin', v)} debounceMs={0} />
              <TextField label="Dashboard password" type="password" value={a.apiPassword} onSave={(v) => set('apiPassword', v)} debounceMs={0} />
              <Hint>Used for the location list. Without it the proxy login is tried.</Hint>
            </>
          )}
          <Hint>You can paste a whole proxy line (host:port:user:pass or user:pass@host:port).</Hint>
          <ErrorText>{err}</ErrorText>
          <div class="actions">
            {state.accounts[provider] && <button class="btn danger" onClick={() => setConfirmDelete(true)}>Remove</button>}
            <button class="btn primary" onClick={() => void save()}>Save</button>
          </div>
        </div>
      </Group>
      <Hint>Stored encrypted in this browser only.</Hint>
      {confirmDelete && (
        <Confirm title={`Remove ${PROVIDER_LABEL[provider]} account?`} confirm="Remove" danger onCancel={() => setConfirmDelete(false)}
          onConfirm={() => { void send({ type: 'deleteAccount', provider }).then(onDone) }} />
      )}
    </>
  )
}

// ---------- VPN ----------

function VpnPage({ state }: { state: UiState }) {
  const [number, setNumber] = useState(state.mullvad?.number ?? '')
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [adding, setAdding] = useState<VpnProfile | null>(null)
  const [del, setDel] = useState<VpnProfile | null>(null)
  const [removeMv, setRemoveMv] = useState(false)
  const [latency, setLatency] = useState<Record<string, number | null> | undefined>()
  const run = async (f: () => Promise<unknown>) => {
    setErr(''); setMsg(''); setBusy(true)
    try { await f() } catch (e) { setErr((e as Error).message) } finally { setBusy(false) }
  }
  const importFile = async (f: File) => {
    const text = await f.text()
    setAdding({ id: randomId(8), name: f.name.replace(/\.conf$/i, ''), config: text })
  }
  return (
    <>
      <Group title="Chain">
        <div class="form">
          <VpnPicker state={state} latency={latency} />
          <Hint>Traffic goes Chrome → VPN → residential proxy, so the proxy provider sees the VPN's IP instead of yours. Needs the helper app.</Hint>
        </div>
      </Group>

      <Group title="Mullvad">
        <div class="form">
          <TextField label="Mullvad account number" value={number} onSave={setNumber} debounceMs={0} mono />
          <div class="actions">
            {state.mullvad && <button class="btn danger" disabled={busy} onClick={() => setRemoveMv(true)}>Remove</button>}
            <button class="btn primary" disabled={busy || !number.replace(/\D/g, '')} onClick={() => void run(async () => {
              await send({ type: 'setMullvad', number })
              const r = await send<{ expiry: string; deviceName: string }>({ type: 'mullvadLogin' })
              setMsg(`Logged in as device “${r.deviceName}”${r.expiry ? `, paid until ${r.expiry.slice(0, 10)}` : ''}`)
            })}>{state.mullvad?.deviceName ? 'Log in again' : 'Log in'}</button>
          </div>
          {state.mullvad?.deviceName && !msg && <Hint>This browser is the Mullvad device “{state.mullvad.deviceName}”{state.mullvad.expiry ? `, paid until ${state.mullvad.expiry.slice(0, 10)}` : ''}.</Hint>}
          <Hint>Registers this browser as one of your account's 5 devices. Backups carry only the account number; each install registers its own key.</Hint>
          {msg && <p class="hint ok">{msg}</p>}
        </div>
      </Group>

      <Group title="WireGuard profiles">
        {state.vpnProfiles.map((p) => {
          let ep = ''
          try { ep = wgEndpoint(parseWireGuard(p.config)) } catch (e) { ep = (e as Error).message }
          const ms = latency?.[`wg:${p.id}`]
          return (
            <Row title={p.name || 'WireGuard'} sub={ep}>
              {ms !== undefined && <span class="muted small">{ms === null ? '—' : `${ms} ms`}</span>}
              <button class="btn small" onClick={() => setAdding(p)}>Edit</button>
              <button class="btn small danger" onClick={() => setDel(p)}>Delete</button>
            </Row>
          )
        })}
        <div class="form">
          <div class="actions">
            <label class="btn">Import .conf file<input type="file" accept=".conf,text/plain" hidden onChange={(e) => {
              const f = (e.target as HTMLInputElement).files?.[0]
              if (f) void importFile(f)
            }} /></label>
            <button class="btn" onClick={() => setAdding({ id: randomId(8), name: '', config: '' })}>Paste WireGuard config</button>
          </div>
        </div>
      </Group>

      <Group title="Latency">
        <div class="form">
          <div class="actions">
            <button class="btn" disabled={busy || !state.helper.installed} onClick={() => void run(async () => setLatency(await send({ type: 'latency' })))}>Measure ping to all servers</button>
          </div>
          {!state.helper.installed && <Hint>Needs the helper app (browsers can't send ICMP).</Hint>}
          {latency && <Hint>The VPN list is now sorted by ping.</Hint>}
        </div>
      </Group>
      <ErrorText>{err}</ErrorText>

      {adding && <ProfileEditor profile={adding} onClose={() => setAdding(null)} />}
      {del && <Confirm title={`Delete ${del.name || 'profile'}?`} confirm="Delete" danger onCancel={() => setDel(null)}
        onConfirm={() => { void send({ type: 'deleteVpnProfile', id: del.id }); setDel(null) }} />}
      {removeMv && <Confirm title="Remove Mullvad account?" text="This browser is removed from your Mullvad devices. Other installs keep theirs." confirm="Remove" danger
        onCancel={() => setRemoveMv(false)} onConfirm={() => { setRemoveMv(false); void run(async () => { await send({ type: 'removeMullvad' }); setNumber('') }) }} />}
    </>
  )
}

function ProfileEditor({ profile, onClose }: { profile: VpnProfile; onClose: () => void }) {
  const [name, setName] = useState(profile.name)
  const [config, setConfig] = useState(profile.config)
  const [err, setErr] = useState('')
  const save = () => {
    try { parseWireGuard(config) } catch (e) { setErr((e as Error).message); return }
    void send({ type: 'saveVpnProfile', profile: { id: profile.id, name: name.trim() || 'WireGuard', config } }).then(onClose, (e: Error) => setErr(e.message))
  }
  return (
    <div class="modal-back" onClick={onClose}>
      <div class="modal wide" role="dialog" aria-modal="true" aria-label="WireGuard profile" onClick={(e) => e.stopPropagation()}>
        <h3>WireGuard profile</h3>
        <TextField label="Name (e.g. Proton NL)" value={name} onSave={setName} debounceMs={0} autoFocus />
        <TextField label="Config" value={config} onSave={setConfig} debounceMs={0} multiline mono placeholder="[Interface] … [Peer] …" />
        <ErrorText>{err}</ErrorText>
        <div class="actions">
          <button class="btn" onClick={onClose}>Cancel</button>
          <button class="btn primary" onClick={save}>Save</button>
        </div>
      </div>
    </div>
  )
}

// ---------- split ----------

function SplitPage({ s }: { s: AppSettings }) {
  const [site, setSite] = useState('')
  const [err, setErr] = useState('')
  const c = s.connection
  const setSites = (sites: string[]) => patch({ connection: { ...c, sites } })
  const add = () => {
    const n = normalizeSite(site)
    if (!n) { setErr('Enter a domain like example.com'); return }
    setErr('')
    setSite('')
    if (!c.sites.includes(n)) void setSites([...c.sites, n].sort())
  }
  return (
    <>
      <Group title="Which sites use the proxy">
        <div class="form">
          <Segmented label="Split mode" value={c.siteSplitMode} onChange={(m) => void patch({ connection: { ...c, siteSplitMode: m } })} options={[
            { value: 'ALL', label: 'All sites' }, { value: 'ONLY_SELECTED', label: 'Only these' }, { value: 'EXCEPT_SELECTED', label: 'All except these' },
          ]} />
          <Hint>
            {c.siteSplitMode === 'ALL' ? 'Every site goes through the proxy.'
              : c.siteSplitMode === 'ONLY_SELECTED' ? 'Only the sites below (and their subdomains) use the proxy; everything else connects directly with your real IP.'
                : 'The sites below (and their subdomains) connect directly with your real IP; everything else uses the proxy.'}
          </Hint>
        </div>
      </Group>
      {c.siteSplitMode !== 'ALL' && (
        <Group title="Sites">
          {c.sites.map((d) => <Row title={d}><button class="btn small" onClick={() => void setSites(c.sites.filter((x) => x !== d))}>Remove</button></Row>)}
          {c.sites.length === 0 && <Hint>No sites yet. With none, every site uses the proxy.</Hint>}
          <form class="form inline-form" onSubmit={(e) => { e.preventDefault(); add() }}>
            <input aria-label="Domain" placeholder="example.com" value={site} onInput={(e) => setSite((e.target as HTMLInputElement).value)} />
            <button class="btn" type="submit">Add</button>
          </form>
          <ErrorText>{err}</ErrorText>
        </Group>
      )}
      <Hint>Android's per-app split settings are kept in backups but don't apply in Chrome.</Hint>
    </>
  )
}

// ---------- protection ----------

function ProtectionPage({ state }: { state: UiState }) {
  const s = state.settings
  return (
    <>
      <Group title="Connection">
        <SwitchRow title="Kill switch" sub="While the proxy is down, block traffic instead of disconnecting." checked={s.killSwitch} onChange={(v) => void patch({ killSwitch: v })} />
        <SwitchRow title="Reconnect automatically" sub="Connect when Chrome starts if you didn't disconnect." checked={s.autoConnect} onChange={(v) => void patch({ autoConnect: v })} />
        <SwitchRow title="Bypass for local network" sub="Routers, printers, NAS and *.local names connect directly." checked={s.bypassLan} onChange={(v) => void patch({ bypassLan: v })} />
        <SwitchRow title="Block WebRTC leaks" sub="Stops WebRTC (video calls) from using UDP outside the proxy, which would reveal your real IP."
          checked={state.prefs.webRtcProtection} onChange={(v) => void send({ type: 'savePrefs', prefs: { webRtcProtection: v } })} />
        <div class="form">
          <NumberField label="Health check every" suffix="s" min={10} max={600} value={s.healthIntervalSec} onSave={(v) => void patch({ healthIntervalSec: v })} />
        </div>
      </Group>
      <Group title="IP check">
        <SwitchRow title="Check on connect" sub="Tabs get internet only after the exit IP passes the reputation check." checked={s.recheckOnConnect} onChange={(v) => void patch({ recheckOnConnect: v })} />
        <SwitchRow title="Auto-rotate bad IPs" sub="Get a new sticky session until the IP is acceptable." checked={s.autoRotate} onChange={(v) => void patch({ autoRotate: v })} />
        <div class="form">
          <span class="field-label">Accept an IP only if it is</span>
          <Segmented label="Accept threshold" value={s.rotateThreshold === 'BAD' ? 'BAD' : 'SUSPICIOUS'} onChange={(v) => void patch({ rotateThreshold: v })}
            options={[{ value: 'SUSPICIOUS', label: 'Clean' }, { value: 'BAD', label: 'Not bad' }]} />
          <NumberField label="Max rotations" min={1} max={50} value={s.maxRotations} onSave={(v) => void patch({ maxRotations: v })} />
        </div>
      </Group>
      <Hint>Chrome removes an extension's proxy when the extension is turned off or removed; traffic then goes direct.</Hint>
    </>
  )
}

// ---------- reputation ----------

function ReputationPage({ keys }: { keys: ApiKeys }) {
  const save = (k: keyof ApiKeys) => (v: string) => void send({ type: 'saveApiKeys', keys: { ...keys, [k]: v } })
  return (
    <Group title="API keys">
      <div class="form">
        <Hint>ipapi.is, proxycheck.io, ip-api.com and DNS blacklists work without keys. Keys raise limits and add AbuseIPDB, IPQualityScore and Scamalytics.</Hint>
        <TextField label="AbuseIPDB" type="password" value={keys.abuseIpDb} onSave={save('abuseIpDb')} />
        <TextField label="IPQualityScore" type="password" value={keys.ipqs} onSave={save('ipqs')} />
        <TextField label="proxycheck.io" type="password" value={keys.proxycheck} onSave={save('proxycheck')} />
        <TextField label="ipapi.is" type="password" value={keys.ipapiIs} onSave={save('ipapiIs')} />
        <TextField label="Scamalytics username" value={keys.scamalyticsUser} onSave={save('scamalyticsUser')} />
        <TextField label="Scamalytics key" type="password" value={keys.scamalyticsKey} onSave={save('scamalyticsKey')} />
        <Hint>Saved automatically. While connected, the IP is re-checked with the new keys.</Hint>
      </div>
    </Group>
  )
}

// ---------- backup ----------

function BackupPage() {
  const [mode, setMode] = useState<'idle' | 'export' | 'import'>('idle')
  return (
    <>
      <Group title="Backup & transfer">
        <div class="form">
          <Hint>Move accounts, settings, API keys and VPN profiles between this browser, other browsers and the ResProxy Android app. The file is encrypted with a passphrase you choose.</Hint>
          <div class="actions">
            <button class="btn" onClick={() => setMode('import')}>Import</button>
            <button class="btn primary" onClick={() => setMode('export')}>Export</button>
          </div>
        </div>
      </Group>
      {mode === 'export' && <ExportBox onClose={() => setMode('idle')} />}
      {mode === 'import' && <ImportBox onClose={() => setMode('idle')} />}
    </>
  )
}

function ExportBox({ onClose }: { onClose: () => void }) {
  const [p1, setP1] = useState('')
  const [p2, setP2] = useState('')
  const [err, setErr] = useState('')
  const [text, setText] = useState('')
  const [copied, setCopied] = useState(false)
  const go = async () => {
    setErr('')
    if (p1.length < 8) { setErr('Use a passphrase of at least 8 characters'); return }
    if (p1 !== p2) { setErr("The passphrases don't match"); return }
    try { setText(await send<string>({ type: 'exportBackup', passphrase: p1 })) } catch (e) { setErr((e as Error).message) }
  }
  const download = () => {
    const url = URL.createObjectURL(new Blob([text + '\n'], { type: 'text/plain' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `resproxy-backup-${new Date().toISOString().slice(0, 10)}.txt`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return (
    <Group title="Export backup">
      <div class="form">
        {!text ? (
          <>
            <Hint>Contains your proxy passwords and VPN keys. You'll need this passphrase to import it.</Hint>
            <label class="field"><span class="field-label">Passphrase</span><input type="password" value={p1} onInput={(e) => setP1((e.target as HTMLInputElement).value)} autoFocus /></label>
            <label class="field"><span class="field-label">Repeat passphrase</span><input type="password" value={p2} onInput={(e) => setP2((e.target as HTMLInputElement).value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void go() }} /></label>
            <ErrorText>{err}</ErrorText>
            <div class="actions"><button class="btn" onClick={onClose}>Cancel</button><button class="btn primary" onClick={() => void go()}>Create backup</button></div>
          </>
        ) : (
          <>
            <p class="hint ok">Backup ready. Save the file, or copy the text and paste it into the Android app's Import.</p>
            <div class="actions">
              <button class="btn" onClick={onClose}>Done</button>
              <button class="btn" onClick={() => { void navigator.clipboard.writeText(text).then(() => setCopied(true)) }}>{copied ? 'Copied' : 'Copy'}</button>
              <button class="btn primary" onClick={download}>Save file</button>
            </div>
          </>
        )}
      </div>
    </Group>
  )
}

function ImportBox({ onClose }: { onClose: () => void }) {
  const [text, setText] = useState('')
  const [pass, setPass] = useState('')
  const [preview, setPreview] = useState<BackupPreview | null>(null)
  const [err, setErr] = useState('')
  const [done, setDone] = useState(false)
  const unlock = async () => {
    setErr('')
    if (!text.trim().startsWith('resproxy-backup-v1:')) { setErr("This isn't a ResProxy backup. Choose the file the app exported, or paste its text."); return }
    try { setPreview(await send<BackupPreview>({ type: 'openBackup', text, passphrase: pass })) } catch (e) { setErr((e as Error).message) }
  }
  if (done) return <Group title="Import backup"><div class="form"><p class="hint ok">Imported. Your accounts and settings are ready.</p><div class="actions"><button class="btn primary" onClick={onClose}>Done</button></div></div></Group>
  return (
    <Group title="Import backup">
      <div class="form">
        <Hint>Choose the backup file, or paste backup text you copied.</Hint>
        <div class="actions">
          <label class="btn">Choose file<input type="file" accept=".txt,text/plain" hidden onChange={(e) => {
            const f = (e.target as HTMLInputElement).files?.[0]
            if (f) void f.text().then(setText)
          }} /></label>
        </div>
        <TextField label="Backup text" value={text} onSave={setText} debounceMs={0} multiline mono placeholder="resproxy-backup-v1:…" />
        <label class="field"><span class="field-label">Backup passphrase</span><input type="password" value={pass} onInput={(e) => setPass((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void unlock() }} /></label>
        <ErrorText>{err}</ErrorText>
        <div class="actions"><button class="btn" onClick={onClose}>Cancel</button><button class="btn primary" disabled={!text || !pass} onClick={() => void unlock()}>Unlock</button></div>
      </div>
      {preview && (
        <Confirm title="Replace current setup?" confirm="Import" onCancel={() => setPreview(null)}
          text={<p>Backup from {preview.createdAt ? new Date(preview.createdAt).toLocaleString() : 'an unknown date'}: {preview.accounts} account(s), {preview.vpnProfiles} VPN profile(s){preview.mullvad ? ', a Mullvad account' : ''}. Your accounts, settings, API keys and VPN profiles here are replaced.</p>}
          onConfirm={() => { setPreview(null); void send({ type: 'importBackup', text, passphrase: pass }).then(() => setDone(true), (e: Error) => setErr(e.message)) }} />
      )}
    </Group>
  )
}

// ---------- usage ----------

function UsagePage({ state }: { state: UiState }) {
  const rows = PROVIDERS.filter((p) => state.usage[p])
  return (
    <Group title="Data usage">
      {rows.length === 0 && <div class="form"><Hint>No traffic yet.</Hint></div>}
      {rows.map((p) => {
        const u = state.usage[p]!
        return <Row title={PROVIDER_LABEL[p]} sub={`↓ ${formatBytes(u.rxBytes)} · ↑ ${formatBytes(u.txBytes)}`}><strong>{formatBytes(u.rxBytes + u.txBytes)}</strong></Row>
      })}
      <div class="form">
        <Hint>Measured in this browser. Exact with the helper app; without it, only response bodies are counted. Providers' billing may count protocol overhead differently.</Hint>
        {rows.length > 0 && <div class="actions"><button class="btn" onClick={() => void send({ type: 'resetUsage' })}>Reset</button></div>}
      </div>
    </Group>
  )
}

// ---------- advanced ----------

function AdvancedPage({ state }: { state: UiState }) {
  const s = state.settings
  const c = s.connection
  const preset = presetOf(s.provider)
  const gw = c.gateways[s.provider] ?? { host: '', port: 0 }
  const setGw = (g: { host: string; port: number }) => patch({ connection: { ...c, gateways: { ...c.gateways, [s.provider]: g } } })
  const acc = state.accounts[s.provider]
  let preview: ReturnType<typeof buildEndpoint> | null = null
  if (acc) preview = buildEndpoint(profileFrom(acc, s, s.sessionId || 'session1'))
  return (
    <>
      <Group title={`Connection · ${PROVIDER_LABEL[s.provider]}`}>
        <div class="form">
          <span class="field-label">Protocol</span>
          <Segmented label="Protocol" value={c.protocol} onChange={(v) => void patch({ connection: { ...c, protocol: v } })}
            options={[{ value: 'SOCKS5', label: 'SOCKS5' }, { value: 'HTTP', label: 'HTTP' }]} />
          {c.protocol === 'SOCKS5' && !state.helper.installed && s.provider !== 'GENERIC' && (
            <Hint>Chrome can't log in to a SOCKS5 proxy by itself. Without the helper app, the extension uses {PROVIDER_LABEL[s.provider]}'s HTTP gateway with the same credentials.</Hint>
          )}
        </div>
        <SwitchRow title="Sticky IP" sub="Keep the same exit IP for the session lifetime. Off: a new IP per connection." checked={c.sticky}
          onChange={(v) => void patch({ connection: { ...c, sticky: v } })} />
        <div class="form">
          {c.sticky && <NumberField label="Session lifetime" suffix="min" min={1} max={MAX_LIFETIME_MINUTES} value={c.lifetimeMinutes}
            onSave={(v) => void patch({ connection: { ...c, lifetimeMinutes: v } })} />}
          {c.sticky && <Hint>Max: IPRoyal 7 days (10080), Geonode 24 h (1440, applied automatically).</Hint>}
          <div class="two">
            <TextField label="Gateway host" placeholder={preset.defaultHost || 'proxy.example.com'} value={gw.host} onSave={(v) => void setGw({ ...gw, host: v.trim() })} />
            <NumberField label="Port" min={0} max={65535} value={gw.port} onSave={(v) => void setGw({ ...gw, port: v })} />
          </div>
          <Hint>Empty host / port 0 = {PROVIDER_LABEL[s.provider]} default ({preset.defaultHost || '—'}:{preset.defaultPort(c.protocol, c.sticky)}). Changes apply to a running connection automatically.</Hint>
        </div>
      </Group>
      {preview && (
        <Group title="Generated credentials">
          <div class="form mono small">
            <div>{preview.protocol.toLowerCase()}://{preview.host}:{preview.port}</div>
            <div>user: {preview.username}</div>
            <div>pass: {preview.password ? '•'.repeat(Math.min(12, preview.password.length)) + (preview.password.includes('_') ? preview.password.slice(preview.password.indexOf('_')) : '') : ''}</div>
          </div>
        </Group>
      )}
    </>
  )
}

// ---------- helper ----------

function HelperPage({ state, helper, recheck }: { state: UiState; helper: HelperInfo | null; recheck: () => void }) {
  const release = 'https://github.com/alexamiryan/res_proxy/releases/latest'
  return (
    <>
      <Group title="Helper app">
        <Row title="Status" sub={helper === null ? 'Checking…' : helper.installed ? `Installed (${helper.version}, ${helper.platform})` : helper.error ?? 'Not installed'}>
          <button class="btn small" onClick={recheck}>Check again</button>
        </Row>
        <div class="form">
          <Hint>Chrome extensions can't run WireGuard, log in to SOCKS5 proxies or send pings. The small helper app adds that:</Hint>
          <ul class="hint">
            <li>VPN chaining (WireGuard profiles and Mullvad)</li>
            <li>SOCKS5 proxies with a password</li>
            <li>Exact data usage and speed, VPN ping and server latency</li>
            <li>New IP closes open connections right away</li>
          </ul>
          <Hint>Without it, IPRoyal, Geonode and HTTP proxies work directly.</Hint>
        </div>
      </Group>
      <Group title="Install">
        <div class="form">
          <ol class="hint">
            <li>Download <code>resproxy-helper</code> for your system from the <a href={release} target="_blank" rel="noreferrer">latest release</a>.</li>
            <li>Put it somewhere permanent and run it once with <code>install</code>:
              <pre>resproxy-helper.exe install   (Windows)
./resproxy-helper install     (macOS / Linux)</pre>
            </li>
            <li>Click <b>Check again</b>. Uninstall with <code>resproxy-helper uninstall</code>.</li>
          </ol>
          <Hint>It registers itself for Chrome, Edge, Brave and Chromium, allowed only for this extension (ID <code>{state.extensionId}</code>).</Hint>
        </div>
      </Group>
      <Group>
        <SwitchRow title="Use the helper when installed" sub="Off: Chrome connects to the proxy directly (no VPN chaining)." checked={state.prefs.useHelper}
          onChange={(v) => void send({ type: 'savePrefs', prefs: { useHelper: v } })} />
      </Group>
    </>
  )
}

render(<Options />, document.getElementById('app')!)
