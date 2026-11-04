// Console logging plus the last 200 lines in chrome.storage.session, shown under Settings → Diagnostics.
// Never pass secrets (passwords, keys, backup passphrases) to these functions.

const lines: string[] = []
let flush: ReturnType<typeof setTimeout> | null = null

function add(level: string, args: unknown[]) {
  const text = args.map((a) => (a instanceof Error ? a.message : typeof a === 'string' ? a : JSON.stringify(a))).join(' ')
  lines.push(`${new Date().toISOString().slice(11, 19)} ${level} ${text}`)
  if (lines.length > 200) lines.splice(0, lines.length - 200)
  flush ??= setTimeout(() => { flush = null; void chrome.storage.session.set({ log: lines }).catch(() => undefined) }, 300)
}

export const log = {
  info: (...a: unknown[]) => { console.info('[ResProxy]', ...a); add('I', a) },
  warn: (...a: unknown[]) => { console.warn('[ResProxy]', ...a); add('W', a) },
  error: (...a: unknown[]) => { console.error('[ResProxy]', ...a); add('E', a) },
}
