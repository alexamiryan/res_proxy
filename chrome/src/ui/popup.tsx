import { render } from 'preact'
import { useState } from 'preact/hooks'
import { PROVIDERS, PROVIDER_LABEL } from '../core/models'
import { SOURCE_LABEL, type ReputationReport } from '../core/reputation'
import { flagEmoji } from '../core/locations'
import { isActive, type TunnelStatus } from '../shared/protocol'
import { formatBytes, openOptions, send, useStatus, useUiState } from './api'
import { VerdictChip } from './components'
import { LocationPicker, VpnPicker } from './pickers'

function Popup() {
  const [state] = useUiState()
  const st = useStatus()
  const [err, setErr] = useState('')
  if (!state) return <div class="popup loading">Loading…</div>
  const hasAccount = PROVIDERS.some((p) => state.accounts[p]?.username.trim())

  const act = (type: 'connect' | 'disconnect' | 'rotate' | 'recheck') => {
    setErr('')
    send({ type }).catch((e: Error) => setErr(e.message))
  }

  return (
    <div class="popup">
      <header class="top">
        <img src="icons/icon32.png" alt="" width={20} height={20} />
        <h1>ResProxy</h1>
        <button class="icon-btn" title="Settings" aria-label="Settings" onClick={() => openOptions()}>⚙</button>
      </header>

      {!hasAccount ? (
        <section class="card">
          <h2>Get started</h2>
          <p>Add your residential proxy account:</p>
          <div class="stack">
            {PROVIDERS.map((p) => <button class="btn" onClick={() => openOptions(`account/${p}`)}>Set up {PROVIDER_LABEL[p]}</button>)}
          </div>
          <p>Or import a backup from the Android app or another browser:</p>
          <button class="btn primary" onClick={() => openOptions('backup')}>Import backup</button>
        </section>
      ) : (
        <>
          <Power st={st} onToggle={() => act(isActive(st.phase) ? 'disconnect' : 'connect')} />
          {(st.message || err) && <p class={`status-msg${st.phase === 'ERROR' || err || st.phase === 'PROXY_DOWN' ? ' bad' : ''}`}>{err || st.message}</p>}

          <section class="card">
            <h2>Location</h2>
            <LocationPicker state={state} compact />
          </section>

          <section class="card">
            <VpnPicker state={state} />
          </section>

          {isActive(st.phase) && <ExitCard st={st} />}
          {isActive(st.phase) && st.report && <ReputationCard report={st.report} attempt={st.rotationAttempt} checking={st.checking} />}
          {isActive(st.phase) && (
            <div class="actions">
              <button class="btn" disabled={st.phase === 'ROTATING' || !state.settings.connection.sticky} title={state.settings.connection.sticky ? 'Get a new sticky session' : 'Turn on Sticky IP to rotate'}
                onClick={() => act('rotate')}>New IP</button>
              <button class="btn" disabled={st.checking} onClick={() => act('recheck')}>Re-check</button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function Power({ st, onToggle }: { st: TunnelStatus; onToggle: () => void }) {
  const on = isActive(st.phase)
  const title = st.phase === 'CONNECTED' ? (st.trafficBlocked ? (st.checking ? 'Checking the IP…' : 'Traffic blocked') : 'Connected')
    : st.phase === 'CONNECTING' ? 'Connecting…'
      : st.phase === 'ROTATING' ? 'Getting a new IP…'
        : st.phase === 'PROXY_DOWN' ? 'Proxy unreachable — traffic blocked'
          : st.phase === 'ERROR' ? 'Not connected' : 'Disconnected'
  const tone = st.phase === 'CONNECTED' && !st.trafficBlocked ? 'ok' : on ? (st.phase === 'PROXY_DOWN' || (st.trafficBlocked && !st.checking && st.phase === 'CONNECTED') ? 'bad' : 'busy') : 'off'
  return (
    <section class={`power tone-${tone}`}>
      <button class="power-btn" aria-pressed={on} aria-label={on ? 'Disconnect' : 'Connect'} onClick={onToggle}>⏻</button>
      <div>
        <div class="power-title">{title}</div>
        {on && st.profileName && <div class="power-sub">{st.profileName}{st.via === 'helper' ? ' · via helper' : ''}</div>}
        {on && st.via === 'helper' && st.phase === 'CONNECTED' && (
          <div class="power-sub">↓ {formatBytes(st.downBps)}/s ↑ {formatBytes(st.upBps)}/s · {formatBytes(st.sessionDown + st.sessionUp)} this session</div>
        )}
      </div>
    </section>
  )
}

function ExitCard({ st }: { st: TunnelStatus }) {
  const g = st.report?.geo
  const place = [g?.city, g?.region, g?.country].filter(Boolean).join(', ')
  return (
    <section class="card">
      <h2>Exit IP</h2>
      <div class="ip">{st.exitIp ?? (st.checking ? 'Checking…' : '—')}</div>
      {g && <div>{flagEmoji(g.countryCode)} {place}</div>}
      {g?.isp && <div class="muted">{g.isp}{g.asn ? ` · ${g.asn}` : ''}{g.connectionType ? ` · ${g.connectionType}` : ''}</div>}
      {st.chainName && <div class="muted">Through VPN: {st.chainName}</div>}
      <div class="muted small">
        {st.latencyMs !== undefined && <>Round trip {st.latencyMs} ms</>}
        {st.vpnPingMs !== undefined && <> · VPN ping {st.vpnPingMs} ms</>}
        {st.proxyPingMs !== undefined && <> · proxy ping {st.proxyPingMs} ms</>}
      </div>
    </section>
  )
}

function ReputationCard({ report, attempt, checking }: { report: ReputationReport; attempt: number; checking: boolean }) {
  const [open, setOpen] = useState(false)
  const ok = report.results.filter((r) => r.ok).length
  return (
    <section class="card">
      <h2>IP reputation <VerdictChip verdict={report.verdict} />{checking && attempt > 0 && <span class="muted small">  rotation {attempt}</span>}</h2>
      {report.reasons.length === 0 && report.verdict === 'CLEAN' && <p class="muted">No abuse reports, blacklist hits or datacenter flags.</p>}
      <ul class="reasons">{report.reasons.map((r) => <li class={`v-${r.verdict.toLowerCase()}`}>{r.text}</li>)}</ul>
      {report.proxyFlags > 0 && <p class="muted small">Detected as a proxy by {report.proxyFlags} source(s) — normal for residential proxies.</p>}
      <button class="link" aria-expanded={open} onClick={() => setOpen(!open)}>{ok}/{report.results.length} sources answered {open ? '▴' : '▾'}</button>
      {open && (
        <table class="sources">
          <tbody>
            {report.results.map((r) => (
              <tr>
                <td>{SOURCE_LABEL[r.source]}</td>
                <td>{!r.ok ? <span class="bad">{r.error}</span> : [
                  r.listed !== undefined && (r.listed ? 'listed' : 'not listed'),
                  r.score !== undefined && `score ${r.score}`,
                  r.riskLabel && `risk ${r.riskLabel}`,
                  r.proxy && 'proxy', r.vpn && 'VPN', r.tor && 'Tor', r.datacenter && 'datacenter',
                  r.note,
                ].filter(Boolean).join(' · ') || 'ok'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

render(<Popup />, document.getElementById('app')!)
