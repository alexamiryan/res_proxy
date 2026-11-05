import { useEffect, useMemo, useState } from 'preact/hooks'
import { type AppSettings, type Location, type Provider, PROVIDERS, PROVIDER_LABEL, emptyLocation, vpnExitImported, vpnExitMullvad } from '../core/models'
import { type LocationCatalog, type Place, flagEmoji } from '../core/locations'
import { type MullvadRelay, relayCountry } from '../core/vpn'
import type { UiState } from '../shared/protocol'
import { Picker, type PickItem, Segmented, Switch, Hint } from './components'
import { send } from './api'

/** Provider + country / state / city. Changes apply to a running connection right away. */
export function LocationPicker({ state, compact }: { state: UiState; compact?: boolean }) {
  const s = state.settings
  const providers = PROVIDERS.filter((p) => state.accounts[p]?.username.trim())
  const [catalog, setCatalog] = useState<LocationCatalog | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    setCatalog(null)
    if (s.provider === 'GENERIC') return
    void send<LocationCatalog>({ type: 'catalog', provider: s.provider }).then(setCatalog).catch(() => undefined)
  }, [s.provider, state.accounts[s.provider]?.apiToken, state.accounts[s.provider]?.apiLogin])

  const apply = async (loc: Location) => {
    setBusy(true)
    try {
      await send({ type: 'updateSettings', patch: { location: loc } })
      await send({ type: 'applyLocation' })
    } finally { setBusy(false) }
  }
  const country = catalog?.countries.find((c) => c.code === s.location.country)
  const state_ = country?.states?.find((x) => x.code === s.location.state)
  const cities: Place[] = state_?.cities?.length ? state_.cities : country?.cities ?? []
  const toItems = (list: Place[], any: string): PickItem[] => [{ value: '', label: any }, ...list.map((p) => ({ value: p.code, label: p.name }))]

  return (
    <div class={`location${compact ? ' compact' : ''}`}>
      {providers.length > 1 && (
        <Segmented label="Provider" value={s.provider} options={providers.map((p) => ({ value: p, label: PROVIDER_LABEL[p] }))}
          onChange={(p: Provider) => void send({ type: 'selectProvider', provider: p })} />
      )}
      {s.provider === 'GENERIC' ? (
        <Hint>Custom proxies are used as configured; location targeting is up to the proxy.</Hint>
      ) : (
        <>
          <Picker label="Country" value={s.location.country}
            items={[{ value: '', label: 'Any country' }, ...(catalog?.countries ?? []).map((c) => ({ value: c.code, label: `${flagEmoji(c.code)} ${c.name}` }))]}
            onPick={(i) => void apply({ ...emptyLocation(), country: i.value, countryName: i.value ? (catalog?.countries.find((c) => c.code === i.value)?.name ?? i.value) : '' })} />
          {s.location.country && (country?.states?.length ?? 0) > 0 && (
            <Picker label="State / region" value={s.location.state} valueLabel={s.location.stateName} items={toItems(country!.states!, 'Any state')}
              onPick={(i) => void apply({ ...s.location, state: i.value, stateName: i.value ? i.label : '', city: '', cityName: '' })} />
          )}
          {s.location.country && (
            <Picker label="City" value={s.location.city} valueLabel={s.location.cityName} items={toItems(cities, 'Any city')} allowFree={catalog?.source === 'BUILT_IN' || cities.length === 0}
              emptyText="Type a city name and press Enter"
              onPick={(i) => void apply({ ...s.location, city: i.value ? i.value.toLowerCase().replace(/[^a-z0-9]/g, '') : '', cityName: i.value ? i.label : '' })} />
          )}
          {catalog?.error && <p class="hint warn">{catalog.error}</p>}
          {catalog?.source === 'BUILT_IN' && !compact && (
            <Hint>
              {s.provider === 'IPROYAL'
                ? 'Add your IPRoyal API token (Settings → Accounts) to pick from the cities IPRoyal offers.'
                : 'Showing a built-in country list; cities are free text.'}
            </Hint>
          )}
        </>
      )}
      {busy && <span class="sr-only">Applying location…</span>}
    </div>
  )
}

/** VPN to reach the proxy through: imported WireGuard profiles, or a Mullvad country, city or server. */
export function VpnPicker({ state, latency }: { state: UiState; latency?: Record<string, number | null> }) {
  const s: AppSettings = state.settings
  const [relays, setRelays] = useState<MullvadRelay[]>([])
  const [err, setErr] = useState('')
  useEffect(() => {
    if (!state.mullvad) return
    void send<MullvadRelay[]>({ type: 'mullvadRelays' }).then(setRelays).catch((e: Error) => setErr(e.message))
  }, [state.mullvad?.number])

  const items = useMemo(() => {
    const out: PickItem[] = []
    const ms = (k: string) => latency?.[k] ?? null
    const fmt = (v: number | null) => (v === null ? undefined : `${v} ms`)
    const sortByMs = (a: PickItem & { ms: number | null }, b: PickItem & { ms: number | null }) =>
      (a.ms ?? 1e9) - (b.ms ?? 1e9) || a.label.localeCompare(b.label)
    const profiles = state.vpnProfiles.map((p) => ({ value: vpnExitImported(p.id), label: p.name || 'WireGuard', group: 'Imported', ms: ms(`wg:${p.id}`) }))
    out.push(...profiles.sort(sortByMs).map((p) => ({ ...p, badge: fmt(p.ms) })))
    if (relays.length) {
      const best = (list: MullvadRelay[]) => {
        const v = list.map((r) => ms(`mullvad:${r.hostname}`)).filter((x): x is number => x !== null)
        return v.length ? Math.min(...v) : null
      }
      const byCountry = new Map<string, MullvadRelay[]>()
      for (const r of relays) byCountry.set(relayCountry(r), [...(byCountry.get(relayCountry(r)) ?? []), r])
      const countries = [...byCountry.entries()].map(([cc, list]) => ({
        value: vpnExitMullvad(cc), label: `${flagEmoji(cc)} ${list[0].country}`, group: 'Mullvad countries', ms: best(list),
      }))
      out.push(...countries.sort(sortByMs).map((c) => ({ ...c, badge: fmt(c.ms) })))
      const byCity = new Map<string, MullvadRelay[]>()
      for (const r of relays) byCity.set(r.location, [...(byCity.get(r.location) ?? []), r])
      const cities = [...byCity.entries()].map(([loc, list]) => ({
        value: vpnExitMullvad(loc), label: `${flagEmoji(relayCountry(list[0]))} ${list[0].city}`, detail: list[0].country, group: 'Mullvad cities', ms: best(list),
      }))
      out.push(...cities.sort(sortByMs).map((c) => ({ ...c, badge: fmt(c.ms) })))
      const servers = relays.map((r) => ({
        value: vpnExitMullvad(r.hostname), label: r.hostname, detail: `${r.city}${r.owned ? ' · owned' : ''}`, group: 'Mullvad servers', ms: ms(`mullvad:${r.hostname}`),
      }))
      out.push(...servers.sort(sortByMs).map((c) => ({ ...c, badge: fmt(c.ms) })))
    }
    return out
  }, [relays, state.vpnProfiles, latency])

  const hasAny = state.vpnProfiles.length > 0 || !!state.mullvad
  return (
    <div class="vpn-picker">
      <div class="inline">
        <span class="field-label">VPN before the proxy</span>
        <Switch label="Chain through VPN" checked={s.chainEnabled} disabled={!hasAny}
          onChange={(v) => void send({ type: 'updateSettings', patch: { chainEnabled: v } })} />
      </div>
      {!hasAny && <Hint>Add a Mullvad account or import WireGuard configs in Settings → VPN.</Hint>}
      {hasAny && s.chainEnabled && (
        <Picker label="VPN server" value={s.vpnExit} items={items} placeholder="Choose a VPN server" emptyText="No VPN servers"
          onPick={(i) => void send({ type: 'updateSettings', patch: { vpnExit: i.value } })} />
      )}
      {s.chainEnabled && s.vpnExit.startsWith('mullvad:') && state.mullvad?.deviceName && (
        <p class="hint">Mullvad device: {state.mullvad.deviceName}</p>
      )}
      {err && <p class="hint warn">{err}</p>}
      {s.chainEnabled && !state.helper.installed && <p class="hint warn">VPN chaining needs the ResProxy helper app (Settings → Helper).</p>}
    </div>
  )
}
