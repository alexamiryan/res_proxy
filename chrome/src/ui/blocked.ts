// Shown instead of a page while the traffic gate is closed; goes to the page once the exit IP is clean.
import type { TunnelStatus } from '../shared/protocol'

const target = decodeURIComponent(location.hash.slice(1))
const safe = /^(https?|wss?):\/\//i.test(target) ? target : ''
document.getElementById('target')!.textContent = safe
if (safe) document.title = `${new URL(safe).hostname} · waiting for a checked IP`

function show(s: TunnelStatus | undefined) {
  if (!s) return
  const gone = s.phase === 'DISCONNECTED' || s.phase === 'ERROR'
  if (safe && (gone || (s.phase === 'CONNECTED' && !s.trafficBlocked))) {
    location.replace(safe)
    return
  }
  const t = document.getElementById('title')!
  const m = document.getElementById('msg')!
  if (s.phase === 'PROXY_DOWN') t.textContent = 'Proxy unreachable — traffic blocked'
  else if (s.checking) t.textContent = s.rotationAttempt > 0 ? `Finding a clean IP… (rotation ${s.rotationAttempt})` : 'Checking the exit IP…'
  else t.textContent = 'Traffic blocked'
  if (s.message) m.textContent = s.message
}

// Extension pages can't read storage.session from a redirected tab without access level; ask the worker instead.
async function poll() {
  try {
    const { runtime } = await chrome.storage.session.get('runtime')
    show((runtime as { status?: TunnelStatus } | undefined)?.status)
  } catch { /* not allowed */ }
}
chrome.storage.onChanged.addListener((c, area) => {
  if (area === 'session' && c.runtime?.newValue) show((c.runtime.newValue as { status: TunnelStatus }).status)
})
void poll()
setInterval(poll, 2000)
