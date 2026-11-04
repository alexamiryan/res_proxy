// Builds the extension into dist/: bundles TypeScript with esbuild, copies static files, renders the icons,
// stamps the version (0.1.<commit count>, same as the Android app) and the fixed extension key into the manifest.
//   node build.mjs            → dist/
//   node build.mjs --watch    → rebuild on change
//   node build.mjs --zip      → also dist/ResProxy-chrome-v<version>.zip
import * as esbuild from 'esbuild'
import { execSync } from 'node:child_process'
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { deflateRawSync, deflateSync } from 'node:zlib'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(fileURLToPath(import.meta.url))
const out = join(root, 'dist', 'extension')
const watch = process.argv.includes('--watch')
const zip = process.argv.includes('--zip')

let commits = '0'
try { commits = execSync('git rev-list --count HEAD', { cwd: root }).toString().trim() } catch { /* not a git checkout */ }
const version = `0.1.${commits}`

rmSync(out, { recursive: true, force: true })
mkdirSync(join(out, 'icons'), { recursive: true })
cpSync(join(root, 'static'), out, { recursive: true })

const manifest = JSON.parse(readFileSync(join(root, 'static', 'manifest.json'), 'utf8'))
manifest.version = version
// Fixed key → fixed extension ID, which the native helper's allowed_origins names.
manifest.key = readFileSync(join(root, 'extension-key.pub'), 'utf8').trim()
writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2))

for (const size of [16, 32, 48, 128]) writeFileSync(join(out, 'icons', `icon${size}.png`), icon(size))

const options = {
  entryPoints: {
    background: join(root, 'src/background/main.ts'),
    popup: join(root, 'src/ui/popup.tsx'),
    options: join(root, 'src/ui/options.tsx'),
    blocked: join(root, 'src/ui/blocked.ts'),
  },
  outdir: out,
  bundle: true,
  format: 'esm',
  target: 'chrome120',
  jsx: 'automatic',
  jsxImportSource: 'preact',
  minify: !watch,
  sourcemap: watch ? 'inline' : false,
  legalComments: 'none',
  logLevel: 'info',
}

if (watch) {
  const ctx = await esbuild.context(options)
  await ctx.watch()
} else {
  await esbuild.build(options)
  console.log(`ResProxy ${version} → ${out}`)
  if (zip) {
    const file = join(root, 'dist', `ResProxy-chrome-v${version}.zip`)
    writeFileSync(file, zipDir(out))
    console.log(file)
  }
}

// ---------- icon: white shield with a check on the app's green, like the Android launcher icon ----------

function icon(size) {
  const ss = 4 // supersampling
  const shield = bezierPolygon()
  const check = [[10, 17], [6, 13], [7.41, 11.59], [10, 14.17], [16.59, 7.58], [18, 9]]
  const px = Buffer.alloc(size * size * 4)
  const bg = [0x1e, 0x6b, 0x45]
  const r = size * 0.22
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let cover = 0, white = 0
      for (let sy = 0; sy < ss; sy++) {
        for (let sx = 0; sx < ss; sx++) {
          const fx = x + (sx + 0.5) / ss, fy = y + (sy + 0.5) / ss
          if (!inRoundRect(fx, fy, size, r)) continue
          cover++
          // Shield drawn in a 24-unit box, inset to 70% of the icon.
          const u = (fx / size - 0.15) / 0.7 * 24, v = (fy / size - 0.15) / 0.7 * 24
          if (inPoly(u, v, shield) && !inPoly(u, v, check)) white++
        }
      }
      const a = cover / (ss * ss), w = cover ? white / cover : 0
      const i = (y * size + x) * 4
      px[i] = Math.round(bg[0] + (255 - bg[0]) * w)
      px[i + 1] = Math.round(bg[1] + (255 - bg[1]) * w)
      px[i + 2] = Math.round(bg[2] + (255 - bg[2]) * w)
      px[i + 3] = Math.round(a * 255)
    }
  }
  return png(size, size, px)
}

function bezierPolygon() {
  // M12,1 L3,5 v6 c0,5.55 3.84,10.74 9,12 c5.16,-1.26 9,-6.45 9,-12 V5 L12,1
  const pts = [[12, 1], [3, 5], [3, 11]]
  const cubic = (p0, p1, p2, p3) => {
    for (let t = 1; t <= 16; t++) {
      const s = t / 16, m = 1 - s
      pts.push([0, 1].map((k) => m * m * m * p0[k] + 3 * m * m * s * p1[k] + 3 * m * s * s * p2[k] + s * s * s * p3[k]))
    }
  }
  cubic([3, 11], [3, 16.55], [6.84, 21.74], [12, 23])
  cubic([12, 23], [17.16, 21.74], [21, 16.55], [21, 11])
  pts.push([21, 5])
  return pts
}

function inPoly(x, y, poly) {
  let inside = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i], [xj, yj] = poly[j]
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

function inRoundRect(x, y, s, r) {
  const cx = Math.min(Math.max(x, r), s - r), cy = Math.min(Math.max(y, r), s - r)
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
}

function crc(buf) {
  const table = (crc.table ??= Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 }))
  let c = 0xffffffff
  for (const b of buf) c = table[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** Minimal zip (deflate) of a directory, entries relative to it — no platform zip tool needed. */
function zipDir(dir) {
  const files = []
  const walk = (d, prefix) => {
    for (const name of readdirSync(d).sort()) {
      const p = join(d, name)
      if (statSync(p).isDirectory()) walk(p, `${prefix}${name}/`)
      else files.push([`${prefix}${name}`, readFileSync(p)])
    }
  }
  walk(dir, '')
  const local = [], central = []
  let offset = 0
  for (const [name, data] of files) {
    const n = Buffer.from(name)
    const comp = deflateRawSync(data)
    const c = crc(data)
    const h = Buffer.alloc(30)
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8)
    h.writeUInt32LE(0x00210000, 10) // 1980-01-01 00:00 (reproducible)
    h.writeUInt32LE(c, 14); h.writeUInt32LE(comp.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(n.length, 26)
    local.push(h, n, comp)
    const ch = Buffer.alloc(46)
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0x0800, 8); ch.writeUInt16LE(8, 10)
    ch.writeUInt32LE(0x00210000, 12); ch.writeUInt32LE(c, 16); ch.writeUInt32LE(comp.length, 20); ch.writeUInt32LE(data.length, 24)
    ch.writeUInt16LE(n.length, 28); ch.writeUInt32LE(offset, 42)
    central.push(ch, n)
    offset += 30 + n.length + comp.length
  }
  const cd = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, cd, end])
}

function png(w, h, rgba) {
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
    const td = Buffer.concat([Buffer.from(type), data])
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td))
    return Buffer.concat([len, td, c])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6
  const raw = Buffer.alloc((w * 4 + 1) * h)
  for (let y = 0; y < h; y++) rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4)
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
