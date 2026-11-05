// End-to-end test in a real Chromium: loads dist/extension, runs the Go test proxy as the "residential
// proxy", and drives the extension through its own message API. Needs Go and an open-source Chromium build
// (branded Chrome ignores --load-extension):
//   CHROMIUM=…/chrome.exe node test/e2e.mjs [--helper path/to/resproxy-helper]
// With --helper, the helper must already be registered (`resproxy-helper install`) and SOCKS5 + helper mode is tested too.
import { chromium } from 'playwright-core'
import { execFileSync, spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const ext = join(root, 'dist', 'extension')
const helper = process.argv.includes('--helper')
/** Any public HTTPS site whose title contains "Example". */
const SITE = process.env.E2E_SITE ?? 'https://example.com/'
const exe = process.env.CHROMIUM
if (!exe) throw new Error('Set CHROMIUM to a Chromium executable')

const logins = []
const profile = mkdtempSync(join(process.env.E2E_TMP ?? tmpdir(), 'resproxy-e2e-'))
// Build first and run the binary itself: killing `go run` would leave its child process listening.
const proxyExe = join(profile, process.platform === 'win32' ? 'testproxy.exe' : 'testproxy')
execFileSync('go', ['build', '-o', proxyExe, './cmd/testproxy'], { cwd: join(root, '..', 'native', 'wgbridge'), env: { ...process.env, CGO_ENABLED: '0' } })
const proxy = spawn(proxyExe, ['-http', '127.0.0.1:18080', '-socks', '127.0.0.1:11080', '-user', 'u', '-pass', 'p'])
proxy.stderr.on('data', (d) => {
  for (const line of String(d).split('\n')) {
    const m = / login (\S+)/.exec(line)
    if (m) logins.push(m[1])
    else if (line.trim()) console.log('[testproxy]', line.trim())
  }
})
await new Promise((resolve) => proxy.stderr.once('data', resolve))

const ctx = await chromium.launchPersistentContext(profile, {
  executablePath: exe, headless: true,
  // gw.proxy.test → the local test proxy: a hostname gateway on a subdomain, like geo.iproyal.com.
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--host-resolver-rules=MAP gw.proxy.test 127.0.0.1'],
})
let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failures++ }

try {
  let [sw] = ctx.serviceWorkers()
  sw ??= await ctx.waitForEvent('serviceworker')
  const id = new URL(sw.url()).host
  check(id === readFileSync(join(root, 'extension-id.txt'), 'utf8').trim(), `fixed extension ID (${id})`)
  const ui = await ctx.newPage()
  await ui.goto(`chrome-extension://${id}/options.html`)
  const send = async (req) => {
    const r = await ui.evaluate((q) => chrome.runtime.sendMessage(q), req)
    if (!r?.ok) throw new Error(`${req.type}: ${r?.error}`)
    return r.value
  }
  const status = () => ui.evaluate(async () => (await chrome.storage.session.get('runtime')).runtime?.status)
  const waitFor = async (pred, what, ms = 90_000) => {
    const end = Date.now() + ms
    let s
    while (Date.now() < end) {
      s = await status()
      if (s && pred(s)) return s
      await new Promise((r) => setTimeout(r, 500))
    }
    throw new Error(`timeout waiting for ${what}: ${JSON.stringify(s)}`)
  }
  const st0 = await send({ type: 'state' })

  // ---- backup made by Android imports ----
  const vec = readFileSync(join(root, '..', 'shared', 'test-vectors', 'backup-android.txt'), 'utf8')
  await send({ type: 'importBackup', text: vec, passphrase: 'correct horse battery staple' })
  const imported = await send({ type: 'state' })
  check(Object.keys(imported.accounts).length === 3 && imported.vpnProfiles.length === 1 && imported.settings.provider === 'GEONODE', 'Android backup imports')
  const exported = await send({ type: 'exportBackup', passphrase: 'another passphrase' })
  check(exported.startsWith('resproxy-backup-v1:'), 'export produces a backup')

  // ---- direct mode: IPRoyal preset pointed at the local HTTP test proxy ----
  await send({ type: 'saveAccount', account: { provider: 'IPROYAL', username: 'u', password: 'p', apiToken: '', apiLogin: '', apiPassword: '' } })
  await send({ type: 'selectProvider', provider: 'IPROYAL' })
  await send({ type: 'updateSettings', patch: {
    connection: { ...st0.settings.connection, protocol: 'HTTP', sticky: true, gateways: { IPROYAL: { host: 'gw.proxy.test', port: 18080 } } },
    location: { country: '', countryName: '', state: '', stateName: '', city: '', cityName: '' },
    chainEnabled: false, recheckOnConnect: true, rotateThreshold: 'BAD', autoRotate: true, bypassLan: true,
  } })
  await send({ type: 'savePrefs', prefs: { useHelper: false } })
  await send({ type: 'connect' })
  let s = await waitFor((x) => x.phase === 'CONNECTED' && !x.checking && x.report, 'connected + checked')
  console.log(`  exit IP ${s.exitIp}, verdict ${s.report.verdict}, via ${s.via}, blocked ${s.trafficBlocked}`)
  check(s.via === 'direct', 'direct mode without helper')
  check(logins.some((l) => l.startsWith('u:p_session-')), `proxy login carries the session (${logins.at(-1)})`)
  const tab = await ctx.newPage()
  if (!s.trafficBlocked) {
    await tab.goto(SITE)
    check((await tab.title()).includes('Example'), 'tab loads through the proxy')
  }

  // ---- New IP: the new session's credentials must reach the proxy (Chrome caches proxy auth) ----
  const oldSession = s.sessionId
  await send({ type: 'rotate' })
  s = await waitFor((x) => x.phase === 'CONNECTED' && !x.trafficBlocked && x.sessionId !== oldSession, 'rotation')
  const before = logins.length
  // Same site as before New IP (cache-busted, so it really goes to the network).
  await tab.goto(`${SITE}?r=${Date.now()}`)
  await new Promise((r) => setTimeout(r, 1500))
  const after = logins.slice(before)
  check(after.some((l) => l.includes(`_session-${s.sessionId}`)), `after New IP the proxy sees session ${s.sessionId} (${after.join(', ') || 'no new logins'})`)
  check(after.length > 0 && !after.some((l) => l.includes(`_session-${oldSession}`)), 'old session credentials are not sent after New IP')

  // ---- gate: an IP that can never pass keeps tabs blocked ----
  await send({ type: 'updateSettings', patch: { rotateThreshold: 'CLEAN' } })
  await send({ type: 'recheck' })
  s = await waitFor((x) => !x.checking && x.trafficBlocked && /traffic blocked/i.test(x.message ?? ''), 'blocked after failed check')
  await tab.goto('https://example.org/').catch(() => undefined)
  check(tab.url().startsWith(`chrome-extension://${id}/blocked.html`), `blocked tab shows the waiting page (${tab.url()})`)
  check(/blocked/i.test(s.message ?? ''), `status says why (${s.message})`)
  await send({ type: 'updateSettings', patch: { rotateThreshold: 'BAD' } })
  await send({ type: 'rotate' })
  s = await waitFor((x) => x.phase === 'CONNECTED' && !x.checking && !x.trafficBlocked, 'unblocked after New IP')
  await tab.waitForURL(/example\.org/, { timeout: 15_000 }).catch(() => undefined)
  check(tab.url().startsWith('https://example.org'), `waiting page continues to the site (${tab.url()})`)
  if (!tab.url().startsWith('https://example.org')) console.log('  blocked page sees', await tab.evaluate(async () => ({ session: typeof chrome?.storage?.session, st: await chrome.storage.session.get('runtime').then((r) => r.runtime?.status?.trafficBlocked, (e) => String(e)) })).catch(String))

  // ---- wrong password: kill switch keeps traffic blocked ----
  await send({ type: 'saveAccount', account: { provider: 'IPROYAL', username: 'u', password: 'wrong', apiToken: '', apiLogin: '', apiPassword: '' } })
  s = await waitFor((x) => x.phase === 'PROXY_DOWN', 'proxy down on bad password', 60_000)
  check(/rejected the proxy username\/password/.test(s.message ?? ''), `bad credentials reported (${s.message})`)
  await send({ type: 'saveAccount', account: { provider: 'IPROYAL', username: 'u', password: 'p', apiToken: '', apiLogin: '', apiPassword: '' } })
  s = await waitFor((x) => x.phase === 'CONNECTED' && !x.checking && !x.trafficBlocked, 'recovered after fixing the password', 90_000)
  check(true, 'recovers when the password is fixed')

  // ---- the sticky session's exit node dies: the proxy logs us in but reaches nothing → new session ----
  await send({ type: 'updateSettings', patch: { healthIntervalSec: 10 } })
  const deadSession = s.sessionId
  proxy.stdin.write(`kill _session-${deadSession}\n`)
  s = await waitFor((x) => x.sessionId !== deadSession && x.phase === 'CONNECTED' && !x.checking && !x.trafficBlocked, 'new session after the exit node died', 90_000)
  check(true, `dead exit node replaced: session ${deadSession} → ${s.sessionId}`)
  await tab.goto(`${SITE}?d=${Date.now()}`)
  check((await tab.title()).includes('Example'), 'tab loads through the new session')

  await send({ type: 'disconnect' })
  s = await waitFor((x) => x.phase === 'DISCONNECTED', 'disconnect')
  const proxySetting = await ui.evaluate(() => chrome.proxy.settings.get({}))
  check(proxySetting.levelOfControl !== 'controlled_by_this_extension', 'disconnect clears the proxy')

  // ---- helper mode: SOCKS5 with password through the native helper ----
  if (helper) {
    const info = await send({ type: 'helperInfo' })
    check(info.installed, `helper installed (${JSON.stringify(info)})`)
    await send({ type: 'savePrefs', prefs: { useHelper: true } })
    await send({ type: 'saveAccount', account: { provider: 'GENERIC', username: 'u', password: 'psocks', apiToken: '', apiLogin: '', apiPassword: '' } })
    const cur = (await send({ type: 'state' })).settings
    await send({ type: 'selectProvider', provider: 'GENERIC' })
    await send({ type: 'updateSettings', patch: { connection: { ...cur.connection, protocol: 'SOCKS5', gateways: { GENERIC: { host: '127.0.0.1', port: 11080 } } } } })
    await send({ type: 'connect' })
    s = await waitFor((x) => x.phase === 'CONNECTED' && !x.trafficBlocked, 'helper connect')
    check(s.via === 'helper', `connected via helper (${s.via})`)
    if (!s.trafficBlocked) {
      await tab.goto(`${SITE}?h=${Date.now()}`)
      check((await tab.title()).includes('Example'), 'tab loads through helper → SOCKS5')
      await new Promise((r) => setTimeout(r, 11000))
      const s2 = await status()
      check(s2.sessionDown > 0, `helper counts bytes (${s2.sessionDown})`)
    }
    check(logins.some((l) => l === 'u:psocks'), 'SOCKS5 login used')
    await send({ type: 'disconnect' })
  }
} catch (e) {
  console.error(e)
  failures++
} finally {
  if (failures) {
    const p = ctx.pages().find((x) => x.url().startsWith('chrome-extension://'))
    const { log } = (await p?.evaluate(() => chrome.storage.session.get('log')).catch(() => ({}))) ?? {}
    console.log(['--- extension log ---', ...(log ?? [])].join('\n'))
  }
  await ctx.close()
  proxy.kill()
  rmSync(profile, { recursive: true, force: true })
}
console.log(failures ? `${failures} failure(s)` : 'all passed')
process.exit(failures ? 1 : 0)
