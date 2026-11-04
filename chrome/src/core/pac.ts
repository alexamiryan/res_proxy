// Builds the PAC script that routes Chrome's traffic: the proxy for everything except the LAN (when bypassed) and
// the sites excluded by split tunnelling. The PAC only does string matching: it never resolves a name, so
// evaluating it can't leak DNS.

import type { SplitMode } from './models'

export interface PacOptions {
  /** PAC directive for proxied traffic, e.g. `PROXY 127.0.0.1:5555` or `PROXY geo.iproyal.com:12321`. */
  proxy: string
  bypassLan: boolean
  splitMode: SplitMode
  sites: string[]
}

/** `https://www.Example.com/path` → `www.example.com`; `*.example.com` → `example.com`. Returns '' when invalid. */
export function normalizeSite(input: string): string {
  let t = input.trim().toLowerCase()
  if (!t) return ''
  t = t.replace(/^[a-z][a-z0-9+.-]*:\/\//, '').replace(/[/?#].*$/, '').replace(/:\d+$/, '').replace(/^\*\./, '').replace(/^\.+|\.+$/g, '')
  return /^[a-z0-9.-]+$/.test(t) && !t.includes('..') ? t : ''
}

export function buildPac(o: PacOptions): string {
  const sites = [...new Set(o.sites.map(normalizeSite).filter(Boolean))]
  // An empty "only these sites" list would send nothing through the proxy; treat it as "all", like Android does.
  const mode = sites.length === 0 ? 'ALL' : o.splitMode
  return `// ResProxy
var PROXY = ${JSON.stringify(o.proxy)};
var BYPASS_LAN = ${o.bypassLan};
var MODE = ${JSON.stringify(mode)};
var SITES = ${JSON.stringify(sites)};
${PAC_BODY}`
}

// Kept as plain ES5 text: Chrome evaluates PAC scripts in a restricted sandbox.
const PAC_BODY = `
var LOCAL_SUFFIXES = [".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".fritz.box"];
function endsWith(s, suf) { return s.length >= suf.length && s.substring(s.length - suf.length) === suf; }
function isLan(host) {
  if (host === "localhost" || endsWith(host, ".localhost")) return true;
  var m = /^(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}$/.exec(host);
  if (m) {
    var a = +m[1], b = +m[2];
    return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  if (host.indexOf(":") >= 0) {
    var h = host.replace(/^\\[|\\]$/g, "");
    return h === "::1" || /^f[cd]/.test(h) || /^fe[89ab]/.test(h);
  }
  if (host.indexOf(".") < 0) return true;
  for (var i = 0; i < LOCAL_SUFFIXES.length; i++) if (endsWith(host, LOCAL_SUFFIXES[i])) return true;
  return false;
}
function listed(host) {
  for (var i = 0; i < SITES.length; i++) if (host === SITES[i] || endsWith(host, "." + SITES[i])) return true;
  return false;
}
function FindProxyForURL(url, host) {
  host = String(host).toLowerCase().replace(/\\.$/, "");
  if (isLan(host)) {
    // Loopback always goes direct (it never leaves the machine); other LAN targets only with the bypass on.
    if (BYPASS_LAN || host === "localhost" || /^127\\./.test(host) || host === "::1" || host === "[::1]") return "DIRECT";
  }
  if (MODE === "ONLY_SELECTED" && !listed(host)) return "DIRECT";
  if (MODE === "EXCEPT_SELECTED" && listed(host)) return "DIRECT";
  return PROXY;
}
`

/** Evaluates a PAC script outside Chrome (tests and the settings preview). */
export function evalPac(script: string, url: string): string {
  const host = new URL(url).hostname
  // eslint-disable-next-line no-new-func
  return new Function('url', 'host', `${script}\nreturn FindProxyForURL(url, host);`)(url, host) as string
}
