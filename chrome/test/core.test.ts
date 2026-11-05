import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { open, seal, normPayload, WrongPassphraseError, looksLikeBackup } from '../src/core/backup'
import { buildEndpoint, parseProxyLine, type Profile } from '../src/core/presets'
import { defaultSettings, normSettings, restoreSession, withSession, sessionKey, MAX_LIFETIME_MINUTES } from '../src/core/models'
import { aggregate, interpretDnsbl, Parsers, dnsblName, parseEcho } from '../src/core/reputation'
import { parseWireGuard, parseRelays, pickRelay, relayLabel, wgQuick, parseDevices } from '../src/core/vpn'
import { buildPac, evalPac, normalizeSite } from '../src/core/pac'
import { parseGeonode, parseIpRoyal, geonodeAuth, builtInCatalog } from '../src/core/locations'

const VEC = join(__dirname, '../../shared/test-vectors')
const PASS = 'correct horse battery staple'
const payloadJson = () => JSON.parse(readFileSync(join(VEC, 'backup-payload.json'), 'utf8'))

describe('backup (shared vectors)', () => {
  it('opens a backup made by Android', async () => {
    const got = await open(readFileSync(join(VEC, 'backup-android.txt'), 'utf8'), PASS)
    expect(got).toEqual(normPayload(payloadJson()))
  })

  it('normalizing the shared payload changes nothing (field-for-field the Android encoding)', () => {
    expect(normPayload(payloadJson())).toEqual(payloadJson())
  })

  it('round-trips, tolerates line wrapping, and writes the Chrome vector', async () => {
    const text = await seal(normPayload(payloadJson()), PASS)
    expect(looksLikeBackup(text)).toBe(true)
    for (const secret of ['ippass', 'gpass', 'abuse-key', 'PrivateKey']) expect(text).not.toContain(secret)
    const wrapped = text.match(/.{1,60}/g)!.join('\n')
    expect(await open(wrapped, PASS)).toEqual(normPayload(payloadJson()))
    if (process.env.WRITE_VECTORS === '1' || !existsSync(join(VEC, 'backup-chrome.txt'))) writeFileSync(join(VEC, 'backup-chrome.txt'), text + '\n')
  })

  it('opens the committed Chrome vector', async () => {
    expect(await open(readFileSync(join(VEC, 'backup-chrome.txt'), 'utf8'), PASS)).toEqual(normPayload(payloadJson()))
  })

  it('carries a shared Mullvad device only when present', async () => {
    const device = { id: 'd1', name: 'Calm Fox', privateKey: 'yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=', publicKey: 'pk', ipv4: '10.1.2.2/32', ipv6: '' }
    const p = { ...normPayload(payloadJson()), mullvadDevice: device }
    expect((await open(await seal(p, PASS), PASS)).mullvadDevice).toEqual(device)
    expect(normPayload({ mullvadDevice: { id: 'x' } }).mullvadDevice).toBeNull()
  })

  it('rejects a wrong passphrase', async () => {
    const text = await seal(normPayload({}), 'right')
    await expect(open(text, 'wrong')).rejects.toBeInstanceOf(WrongPassphraseError)
  })

  it('keeps unknown fields from newer versions', async () => {
    const p = normPayload({ futureTop: 1, settings: { futureSetting: 'x', connection: { futureConn: true } } })
    expect(p.futureTop).toBe(1)
    expect(p.settings.futureSetting).toBe('x')
    expect((p.settings.connection as unknown as Record<string, unknown>).futureConn).toBe(true)
  })
})

describe('presets (shared vectors)', () => {
  const cases = JSON.parse(readFileSync(join(VEC, 'presets.json'), 'utf8')).cases as { name: string; profile: Profile; endpoint: unknown }[]
  for (const c of cases) it(c.name, () => expect(buildEndpoint(c.profile)).toEqual(c.endpoint))

  it('parses pasted proxy lines', () => {
    expect(parseProxyLine('geo.iproyal.com:12321:user:pass_country-us')).toEqual({ host: 'geo.iproyal.com', port: 12321, username: 'user', password: 'pass_country-us' })
    expect(parseProxyLine('user:pass@proxy.geonode.io:9000')).toEqual({ host: 'proxy.geonode.io', port: 9000, username: 'user', password: 'pass' })
    expect(parseProxyLine('socks5://u:p@h.example:1080')).toMatchObject({ protocol: 'SOCKS5', host: 'h.example', port: 1080 })
    expect(parseProxyLine('just-a-username')).toBeNull()
  })
})

describe('settings', () => {
  it('upgrades old schemas like Android', () => {
    const s = normSettings({ schema: 1, killSwitch: false, connection: { lifetimeMinutes: 1440 } })
    expect(s.killSwitch).toBe(true)
    expect(s.connection.lifetimeMinutes).toBe(MAX_LIFETIME_MINUTES)
    expect(s.schema).toBe(3)
    expect(normSettings({ schema: 3, connection: { lifetimeMinutes: 1440 } }).connection.lifetimeMinutes).toBe(1440)
  })

  it('remembers sticky sessions per provider + location', () => {
    const now = 1_000_000_000
    let s = withSession({ ...defaultSettings(), location: { ...defaultSettings().location, country: 'us' } }, 'aaa', now)
    const usKey = sessionKey(s)
    s = restoreSession({ ...s, location: { ...s.location, country: 'de' } }, now)
    expect(s.sessionId).toBe('')
    s = withSession(s, 'bbb', now)
    s = restoreSession({ ...s, location: { ...s.location, country: 'us' } }, now + 1000)
    expect(s.sessionId).toBe('aaa')
    expect(s.sessions[usKey].createdAt).toBe(now)
    // Expired (Geonode caps at 24 h): not reused.
    const g = withSession({ ...defaultSettings(), provider: 'GEONODE' }, 'ccc', now)
    expect(restoreSession(g, now + 1440 * 60_000).sessionId).toBe('')
  })
})

describe('reputation', () => {
  it('residential proxy flags alone stay clean', () => {
    const r = aggregate('1.2.3.4', [
      { source: 'IPAPI_IS', ok: true, proxy: true, datacenter: false },
      { source: 'PROXYCHECK', ok: true, proxy: true, score: 33 },
    ])
    expect(r.verdict).toBe('CLEAN')
    expect(r.proxyFlags).toBe(2)
  })

  it('applies score thresholds', () => {
    expect(aggregate('x', [{ source: 'ABUSEIPDB', ok: true, score: 60, reports: 3 }]).verdict).toBe('BAD')
    expect(aggregate('x', [{ source: 'ABUSEIPDB', ok: true, score: 5 }]).verdict).toBe('SUSPICIOUS')
    expect(aggregate('x', [{ source: 'IPQS', ok: true, score: 80 }]).verdict).toBe('SUSPICIOUS')
    expect(aggregate('x', [{ source: 'SCAMALYTICS', ok: true, riskLabel: 'very high' }]).verdict).toBe('BAD')
    expect(aggregate('x', [{ source: 'IPAPI_IS', ok: true, vpn: true }, { source: 'IPQS', ok: true, vpn: true }]).verdict).toBe('SUSPICIOUS')
    expect(aggregate('x', [{ source: 'IPAPI_IS', ok: false, error: 'x' }]).verdict).toBe('UNKNOWN')
  })

  it('parses sources', () => {
    const r = Parsers.ipapiIs(JSON.stringify({ is_proxy: true, is_datacenter: false, asn: { asn: 7922, org: 'Comcast' }, location: { country: 'United States', country_code: 'US', city: 'Austin' }, company: { name: 'Comcast', type: 'isp' } }))
    expect(r).toMatchObject({ proxy: true, datacenter: false, asn: 'AS7922', isp: 'Comcast', city: 'Austin' })
    expect(Parsers.proxycheck(JSON.stringify({ status: 'ok', '1.2.3.4': { proxy: 'yes', type: 'Residential', risk: 70 } }), '1.2.3.4')).toMatchObject({ proxy: true, vpn: false, score: 70 })
    expect(Parsers.ipApi(JSON.stringify({ status: 'fail', message: 'quota' }))).toMatchObject({ ok: false, error: 'quota' })
    expect(Parsers.abuseIpDb(JSON.stringify({ data: { abuseConfidenceScore: 0, totalReports: 0, usageType: 'Data Center/Web Hosting/Transit' } }))).toMatchObject({ datacenter: true, score: 0 })
    expect(Parsers.scamalytics(JSON.stringify({ scamalytics: { status: 'ok', scamalytics_score: 12, scamalytics_risk: 'Low' } }))).toMatchObject({ score: 12, riskLabel: 'low' })
    expect(Parsers.ipqs(JSON.stringify({ success: true, fraud_score: '90', ASN: 123 }))).toMatchObject({ score: 90, asn: 'AS123' })
  })

  it('interprets DNSBL answers', () => {
    expect(dnsblName('1.2.3.4', 'zen.spamhaus.org')).toBe('4.3.2.1.zen.spamhaus.org')
    expect(dnsblName('::1', 'z')).toBeNull()
    expect(interpretDnsbl('DNSBL_SPAMHAUS', ['127.0.0.10'])).toMatchObject({ listed: false })
    expect(interpretDnsbl('DNSBL_SPAMHAUS', ['127.0.0.4'])).toMatchObject({ listed: true, note: 'Listed in XBL' })
    expect(interpretDnsbl('DNSBL_SPAMHAUS', ['127.255.255.254'])).toMatchObject({ ok: false })
    expect(interpretDnsbl('DNSBL_SPAMCOP', [])).toMatchObject({ listed: false })
  })

  it('parses IP echo bodies', () => {
    expect(parseEcho('{"ip":"5.6.7.8"}')).toBe('5.6.7.8')
    expect(parseEcho('2001:db8::1\n')).toBe('2001:db8::1')
    expect(parseEcho('<html>')).toBe('')
  })
})

describe('vpn', () => {
  it('parses wg-quick configs', () => {
    const cfg = parseWireGuard(payloadJson().vpnProfiles[0].config)
    expect(cfg.addresses).toEqual(['10.2.0.2/32'])
    expect(cfg.dns).toEqual(['10.2.0.1'])
    expect(cfg.peers[0]).toMatchObject({ endpointHost: 'nl.example.net', endpointPort: 51820, keepalive: 25 })
    expect(() => parseWireGuard('[Interface]\nPrivateKey = short\n')).toThrow('Invalid WireGuard key')
  })

  it('lists Mullvad devices (shared vector)', () => {
    const v = JSON.parse(readFileSync(join(VEC, 'mullvad-devices.json'), 'utf8'))
    expect(parseDevices(JSON.stringify(v.body))).toEqual(v.devices)
  })

  it('parses and picks Mullvad relays', () => {
    const body = JSON.stringify({
      locations: { 'se-got': { country: 'Sweden', city: 'Gothenburg' } },
      wireguard: { relays: [
        { hostname: 'se-got-wg-001', location: 'se-got', active: true, ipv4_addr_in: '1.1.1.1', public_key: 'k1', weight: 100 },
        { hostname: 'se-got-wg-002', location: 'se-got', active: false, ipv4_addr_in: '1.1.1.2', public_key: 'k2' },
      ] },
    })
    const relays = parseRelays(body)
    expect(relays).toHaveLength(1)
    expect(pickRelay('se', relays)?.hostname).toBe('se-got-wg-001')
    expect(pickRelay('de', relays)).toBeNull()
    expect(relayLabel('se-got', relays)).toBe('Sweden, Gothenburg')
    const conf = wgQuick({ id: 'd', name: 'n', privateKey: 'yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=', publicKey: '', ipv4: '10.1.2.3/32', ipv6: '' }, { ...relays[0], publicKey: 'xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=' })
    expect(parseWireGuard(conf).peers[0].endpointHost).toBe('1.1.1.1')
  })
})

describe('pac', () => {
  const pac = (o: Partial<Parameters<typeof buildPac>[0]> = {}) => buildPac({ proxy: 'PROXY 127.0.0.1:9', bypassLan: true, splitMode: 'ALL', sites: [], ...o })

  it('bypasses the LAN only when asked; loopback always direct', () => {
    for (const u of ['http://192.168.1.1/', 'http://printer.local/', 'http://nas/', 'http://10.0.0.8:8080/']) expect(evalPac(pac(), u)).toBe('DIRECT')
    expect(evalPac(pac(), 'https://example.com/')).toBe('PROXY 127.0.0.1:9')
    expect(evalPac(pac({ bypassLan: false }), 'http://192.168.1.1/')).toBe('PROXY 127.0.0.1:9')
    expect(evalPac(pac({ bypassLan: false }), 'http://127.0.0.1:3000/')).toBe('DIRECT')
    expect(evalPac(pac(), 'http://172.32.0.1/')).toBe('PROXY 127.0.0.1:9')
  })

  it('splits by site', () => {
    const only = pac({ splitMode: 'ONLY_SELECTED', sites: ['https://Example.com/x'] })
    expect(evalPac(only, 'https://www.example.com/')).toBe('PROXY 127.0.0.1:9')
    expect(evalPac(only, 'https://other.org/')).toBe('DIRECT')
    const except = pac({ splitMode: 'EXCEPT_SELECTED', sites: ['bank.com'] })
    expect(evalPac(except, 'https://login.bank.com/')).toBe('DIRECT')
    expect(evalPac(except, 'https://notbank.com/')).toBe('PROXY 127.0.0.1:9')
    // Empty "only" list means everything goes through the proxy.
    expect(evalPac(pac({ splitMode: 'ONLY_SELECTED' }), 'https://other.org/')).toBe('PROXY 127.0.0.1:9')
    expect(normalizeSite('*.Foo.com')).toBe('foo.com')
    expect(normalizeSite('not a site')).toBe('')
  })
})

describe('locations', () => {
  it('parses IPRoyal and Geonode catalogs', () => {
    const ipr = parseIpRoyal(JSON.stringify({ countries: [{ code: 'us', name: 'United States', states: { options: [{ code: 'newyork', name: 'New York', cities: { options: [{ code: 'NYC', name: 'New York' }] } }] }, cities: { options: [] } }] }))
    expect(ipr[0].states![0].cities![0].code).toBe('nyc')
    const geo = parseGeonode(JSON.stringify({ countries: [{ code: 'US', name: 'United States', states: [{ code: 'NY', name: 'New York', cities: [{ code: 'nyc', name: 'NYC' }] }, { code: 'CA', name: 'California', cities: [{ code: 'la', name: 'LA' }, { code: 'nyc', name: 'NYC' }] }] }] }))
    expect(geo[0].code).toBe('us')
    expect(geo[0].cities!.map((c) => c.code)).toEqual(['la', 'nyc'])
  })

  it('falls back to built-in countries and uses proxy login for Geonode', () => {
    const b = builtInCatalog()
    expect(b.countries.find((c) => c.code === 'us')?.states?.length).toBe(51)
    expect(geonodeAuth({ provider: 'GEONODE', username: 'geonode_abc-type-residential', password: 'p', apiToken: '', apiLogin: '', apiPassword: '' })).toEqual(['geonode_abc', 'p'])
  })
})
