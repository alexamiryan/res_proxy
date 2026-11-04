import type { ComponentChildren } from 'preact'
import { useEffect, useMemo, useRef, useState } from 'preact/hooks'

export function Group({ title, children }: { title?: string; children: ComponentChildren }) {
  return (
    <section class="group">
      {title && <h3>{title}</h3>}
      <div class="group-body">{children}</div>
    </section>
  )
}

export function Row({ title, sub, children, onClick }: { title: ComponentChildren; sub?: ComponentChildren; children?: ComponentChildren; onClick?: () => void }) {
  return (
    <div class={`row${onClick ? ' clickable' : ''}`} onClick={onClick} role={onClick ? 'button' : undefined} tabIndex={onClick ? 0 : undefined}
      onKeyDown={(e) => { if (onClick && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onClick() } }}>
      <div class="row-text">
        <div class="row-title">{title}</div>
        {sub && <div class="row-sub">{sub}</div>}
      </div>
      {children && <div class="row-end">{children}</div>}
    </div>
  )
}

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled}
      class={`switch${checked ? ' on' : ''}`} onClick={() => onChange(!checked)}>
      <span class="knob" />
    </button>
  )
}

export function SwitchRow({ title, sub, checked, onChange, disabled }: { title: string; sub?: ComponentChildren; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <Row title={title} sub={sub} onClick={disabled ? undefined : () => onChange(!checked)}>
      <Switch checked={checked} onChange={onChange} label={title} disabled={disabled} />
    </Row>
  )
}

export function Hint({ children }: { children: ComponentChildren }) {
  return <p class="hint">{children}</p>
}

export function ErrorText({ children }: { children: ComponentChildren }) {
  return children ? <p class="error" role="alert">{children}</p> : null
}

/**
 * Text field with local state: storage writes are async, so binding straight to stored values drops typed
 * characters (same reason as Android's TextSetting). Saves on input (debounced) or on blur.
 */
export function TextField({ label, value, onSave, type = 'text', placeholder, mono, multiline, autoFocus, debounceMs = 600 }: {
  label: string; value: string; onSave: (v: string) => void; type?: string; placeholder?: string; mono?: boolean; multiline?: boolean; autoFocus?: boolean; debounceMs?: number
}) {
  const [v, setV] = useState(value)
  const [reveal, setReveal] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const focused = useRef(false)
  useEffect(() => { if (!focused.current) setV(value) }, [value])
  const commit = (x: string) => { if (timer.current) clearTimeout(timer.current); if (x !== value) onSave(x) }
  const input = (x: string) => {
    setV(x)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => commit(x), debounceMs)
  }
  const common = {
    value: v, placeholder, autoFocus, spellcheck: false, autoComplete: 'off',
    class: mono ? 'mono' : undefined,
    onInput: (e: Event) => input((e.target as HTMLInputElement).value),
    onFocus: () => { focused.current = true },
    onBlur: () => { focused.current = false; commit(v) },
  }
  return (
    <label class="field">
      <span class="field-label">{label}</span>
      <span class="field-wrap">
        {multiline ? <textarea rows={8} {...common} /> : <input type={type === 'password' && !reveal ? 'password' : type === 'password' ? 'text' : type} {...common} />}
        {type === 'password' && (
          <button type="button" class="icon-btn" aria-label={reveal ? 'Hide' : 'Show'} onClick={() => setReveal(!reveal)}>{reveal ? '🙈' : '👁'}</button>
        )}
      </span>
    </label>
  )
}

export function NumberField({ label, value, min, max, onSave, suffix }: { label: string; value: number; min: number; max: number; onSave: (v: number) => void; suffix?: string }) {
  const [v, setV] = useState(String(value))
  const focused = useRef(false)
  useEffect(() => { if (!focused.current) setV(String(value)) }, [value])
  const commit = () => {
    const n = Math.round(Number(v))
    if (!Number.isFinite(n)) { setV(String(value)); return }
    const c = Math.min(max, Math.max(min, n))
    setV(String(c))
    if (c !== value) onSave(c)
  }
  return (
    <label class="field">
      <span class="field-label">{label}</span>
      <span class="field-wrap">
        <input type="number" min={min} max={max} value={v} onInput={(e) => setV((e.target as HTMLInputElement).value)}
          onFocus={() => { focused.current = true }} onBlur={() => { focused.current = false; commit() }}
          onKeyDown={(e) => { if (e.key === 'Enter') commit() }} />
        {suffix && <span class="suffix">{suffix}</span>}
      </span>
    </label>
  )
}

export function Segmented<T extends string>({ options, value, onChange, label }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <div class="segmented" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button type="button" role="radio" aria-checked={o.value === value} class={o.value === value ? 'on' : ''} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  )
}

export interface PickItem { value: string; label: string; detail?: string; badge?: string; group?: string }

/** Searchable single-choice list in a popover. */
export function Picker({ label, value, items, onPick, placeholder, emptyText, allowFree, valueLabel }: {
  label: string; value: string; items: PickItem[]; onPick: (v: PickItem) => void; placeholder?: string; emptyText?: string; allowFree?: boolean
  /** Shown when [value] isn't in [items] (e.g. a free-text city). */
  valueLabel?: string
}) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const box = useRef<HTMLDivElement>(null)
  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase()
    const list = t ? items.filter((i) => `${i.label} ${i.detail ?? ''} ${i.value}`.toLowerCase().includes(t)) : items
    return list.slice(0, 400)
  }, [q, items])
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])
  const current = items.find((i) => i.value === value)
  let lastGroup: string | undefined
  return (
    <div class="picker" ref={box}>
      <span class="field-label">{label}</span>
      <button type="button" class="picker-btn" aria-haspopup="listbox" aria-expanded={open} onClick={() => { setOpen(!open); setQ('') }}>
        <span>{current?.label ?? (valueLabel || value || placeholder || 'Any')}</span><span class="chev">▾</span>
      </button>
      {open && (
        <div class="picker-pop">
          <input class="picker-search" autoFocus placeholder="Search" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setOpen(false)
              if (e.key === 'Enter') {
                const first = filtered[0]
                if (first) { onPick(first); setOpen(false) } else if (allowFree && q.trim()) { onPick({ value: q.trim(), label: q.trim() }); setOpen(false) }
              }
            }} />
          <ul role="listbox">
            {filtered.map((i) => {
              const header = i.group && i.group !== lastGroup ? i.group : null
              lastGroup = i.group
              return (
                <>
                  {header && <li class="picker-group">{header}</li>}
                  <li role="option" aria-selected={i.value === value} class={i.value === value ? 'sel' : ''} onClick={() => { onPick(i); setOpen(false) }}>
                    <span class="picker-label">{i.label}{i.detail && <small>{i.detail}</small>}</span>
                    {i.badge && <span class="picker-badge">{i.badge}</span>}
                  </li>
                </>
              )
            })}
            {filtered.length === 0 && (
              allowFree && q.trim()
                ? <li role="option" onClick={() => { onPick({ value: q.trim(), label: q.trim() }); setOpen(false) }}>Use “{q.trim()}”</li>
                : <li class="picker-empty">{emptyText ?? 'Nothing found'}</li>
            )}
          </ul>
        </div>
      )}
    </div>
  )
}

export function Confirm({ title, text, confirm, onConfirm, onCancel, danger }: { title: string; text?: ComponentChildren; confirm: string; onConfirm: () => void; onCancel: () => void; danger?: boolean }) {
  return (
    <div class="modal-back" onClick={onCancel}>
      <div class="modal" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        {text && <div class="modal-text">{text}</div>}
        <div class="actions">
          <button type="button" class="btn" onClick={onCancel}>Cancel</button>
          <button type="button" class={`btn ${danger ? 'danger' : 'primary'}`} autoFocus onClick={onConfirm}>{confirm}</button>
        </div>
      </div>
    </div>
  )
}

export function VerdictChip({ verdict }: { verdict: string }) {
  return <span class={`chip v-${verdict.toLowerCase()}`}>{verdict === 'UNKNOWN' ? 'Unknown' : verdict[0] + verdict.slice(1).toLowerCase()}</span>
}
