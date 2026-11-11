package main

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// state is everything the helper runs: local proxy, upstream, optional WireGuard.
type state struct {
	mu       sync.Mutex
	ln       net.Listener
	token    string
	upstream endpoint
	wg       *wgTunnel
	socks    *socksServer // iOS: the relay hev talks to
	gate     *gate        // iOS: closed until the exit IP passed the check; the Chrome helper keeps it open

	conns   sync.Map // net.Conn → struct{}
	down    atomic.Int64
	up      atomic.Int64
	ok      atomic.Int64
	failed  atomic.Int64
	rttMs   atomic.Int64 // EWMA × 1000
	failMu  sync.Mutex
	fails   []hostFail
	lastErr atomic.Value
}

type hostFail struct {
	at   time.Time
	host string
}

func newState() *state { return &state{gate: newGate(true)} }

func (s *state) dialer() dialFunc {
	s.mu.Lock()
	wg := s.wg
	s.mu.Unlock()
	if wg != nil {
		// The proxy hostname is resolved by the VPN's DNS, inside the tunnel.
		return wg.net.DialContext
	}
	d := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	return d.DialContext
}

// start (re)starts the upstream: local proxy, WireGuard and the proxy endpoint. A running SOCKS front (iOS) keeps
// its port and credentials, so hev keeps running and its fake-IP DNS table stays valid; its connections through the
// old upstream are dropped, and new ones are refused until this returns.
func (s *state) start(up endpoint, wgConf, token string) (int, error) {
	s.stopUpstream()
	if up.Host == "" || up.Port == 0 {
		return 0, errors.New("proxy host or port is empty")
	}
	if wgConf != "" {
		t, err := startWireGuard(wgConf)
		if err != nil {
			return 0, withCode("vpn", fmt.Errorf("VPN: %w", err))
		}
		s.mu.Lock()
		s.wg = t
		s.mu.Unlock()
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.stopUpstream()
		return 0, err
	}
	s.mu.Lock()
	s.ln, s.token, s.upstream = ln, token, up
	s.mu.Unlock()
	// Counters are per start: the extension takes them as the session's traffic.
	s.down.Store(0)
	s.up.Store(0)
	s.ok.Store(0)
	s.failed.Store(0)
	go s.accept(ln)
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func (s *state) setUpstream(up endpoint) error {
	s.mu.Lock()
	s.upstream = up
	s.mu.Unlock()
	// Connections on the old session would keep its IP: drop them (like the Android relay).
	s.closeConns()
	s.mu.Lock()
	ss := s.socks
	s.mu.Unlock()
	if ss != nil {
		ss.closeConns()
	}
	return nil
}

func (s *state) closeConns() {
	s.conns.Range(func(k, _ any) bool {
		k.(net.Conn).Close()
		s.conns.Delete(k)
		return true
	})
}

// stopUpstream stops the local proxy and WireGuard and drops every proxied connection. The SOCKS front keeps
// listening but refuses connections until the next start (never through an old, possibly unchained upstream).
func (s *state) stopUpstream() {
	s.mu.Lock()
	ln, wg, ss := s.ln, s.wg, s.socks
	s.ln, s.wg = nil, nil
	s.mu.Unlock()
	if ln != nil {
		ln.Close()
	}
	if ss != nil {
		ss.closeConns()
	}
	s.closeConns()
	if wg != nil {
		wg.stop()
	}
}

// stopAll stops everything, the SOCKS front included.
func (s *state) stopAll() {
	s.mu.Lock()
	ss := s.socks
	s.socks = nil
	s.mu.Unlock()
	if ss != nil {
		ss.stop()
	}
	s.stopUpstream()
}

// route is the dialer and endpoint for one connection, taken together so a connection never mixes a new endpoint
// with an old (or missing) VPN. Fails while the upstream is stopped or rebuilding.
func (s *state) route() (dialFunc, endpoint, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ln == nil {
		return nil, endpoint{}, errRebuilding
	}
	if s.wg != nil {
		// The proxy hostname is resolved by the VPN's DNS, inside the tunnel.
		return s.wg.net.DialContext, s.upstream, nil
	}
	d := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	return d.DialContext, s.upstream, nil
}

var errRebuilding = errors.New("upstream is not running")

func (s *state) currentUpstream() endpoint {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.upstream
}

func (s *state) recordRtt(d time.Duration) {
	ms := d.Seconds() * 1000
	for {
		old := s.rttMs.Load()
		var next float64
		if old == 0 {
			next = ms
		} else {
			next = float64(old)/1000*0.7 + ms*0.3
		}
		if s.rttMs.CompareAndSwap(old, int64(next*1000)) {
			return
		}
	}
}

func (s *state) probe() (int, error) {
	dial, ep, err := s.route()
	if err != nil {
		return 0, errors.New("helper proxy is not running")
	}
	return probeUpstream(dial, ep)
}

func (s *state) status() map[string]any {
	out := map[string]any{
		"down": s.down.Load(), "up": s.up.Load(), "conns": s.ok.Load(), "connFails": s.failed.Load(),
	}
	if v := s.rttMs.Load(); v > 0 {
		out["rttMs"] = float64(v) / 1000
	}
	if e, ok := s.lastErr.Load().(string); ok && e != "" {
		out["lastError"] = e
	}
	s.failMu.Lock()
	cut := time.Now().Add(-60 * time.Second)
	hosts := map[string]bool{}
	kept := s.fails[:0]
	for _, f := range s.fails {
		if f.at.After(cut) {
			kept = append(kept, f)
			hosts[f.host] = true
		}
	}
	s.fails = kept
	s.failMu.Unlock()
	out["failedHosts"] = len(hosts)
	out["gateOpen"] = s.gate.isOpen()
	s.mu.Lock()
	wg, ss := s.wg, s.socks
	s.mu.Unlock()
	if ss != nil {
		// The SOCKS front's own counters span upstream restarts (they belong to hev's side).
		out["socksAccepted"] = ss.accepted.Load()
		out["socksOpen"] = ss.open.Load()
		out["staleFakeIps"] = ss.staleFake.Load()
	}
	if wg != nil {
		if hs := wg.handshake(); !hs.IsZero() {
			out["handshakeAgeSec"] = int(time.Since(hs).Seconds())
		}
	}
	return out
}

func (s *state) vpnPing(ip string) (int, error) {
	s.mu.Lock()
	wg := s.wg
	s.mu.Unlock()
	if wg == nil {
		return 0, errors.New("no VPN running")
	}
	return wg.ping(ip)
}

// ---------- local proxy ----------

func (s *state) accept(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go s.serveConn(c)
	}
}

func (s *state) authorized(r *http.Request) bool {
	h := r.Header.Get("Proxy-Authorization")
	const prefix = "Basic "
	if !strings.HasPrefix(h, prefix) {
		return false
	}
	b, err := base64.StdEncoding.DecodeString(h[len(prefix):])
	if err != nil {
		return false
	}
	s.mu.Lock()
	want := "resproxy:" + s.token
	s.mu.Unlock()
	return subtle.ConstantTimeCompare(b, []byte(want)) == 1
}

func (s *state) serveConn(c net.Conn) {
	s.conns.Store(c, struct{}{})
	defer func() {
		s.conns.Delete(c)
		c.Close()
	}()
	br := bufio.NewReader(c)
	for {
		c.SetReadDeadline(time.Now().Add(2 * time.Minute))
		req, err := http.ReadRequest(br)
		if err != nil {
			return
		}
		c.SetReadDeadline(time.Time{})
		if !s.authorized(req) {
			io.Copy(io.Discard, req.Body)
			io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"ResProxy\"\r\nContent-Length: 0\r\n\r\n")
			continue // Chrome answers the challenge on the same connection.
		}
		if req.Method == http.MethodConnect {
			s.tunnel(c, br, req.Host)
			return
		}
		s.forward(c, req)
		return
	}
}

func (s *state) dialTarget(target string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	host, _, _ := net.SplitHostPort(target)
	dial, ep, err := s.route()
	if err != nil {
		return nil, err
	}
	up, err := dialUpstream(ctx, dial, ep, target, s.recordRtt)
	if err != nil {
		var refused *refusedError
		if !errors.As(err, &refused) {
			s.failed.Add(1)
			s.lastErr.Store(err.Error())
			s.failMu.Lock()
			s.fails = append(s.fails, hostFail{time.Now(), host})
			if len(s.fails) > 500 {
				s.fails = s.fails[250:]
			}
			s.failMu.Unlock()
		}
		return nil, err
	}
	s.ok.Add(1)
	return up, nil
}

func (s *state) tunnel(c net.Conn, br *bufio.Reader, target string) {
	if !strings.Contains(target, ":") {
		target += ":443"
	}
	up, err := s.dialTarget(target)
	if err != nil {
		log.Printf("CONNECT %s: %v", target, err)
		fmt.Fprintf(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
		return
	}
	s.conns.Store(up, struct{}{})
	defer func() { s.conns.Delete(up); up.Close() }()
	if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	// Bytes the client sent right after the CONNECT (e.g. TLS ClientHello) may sit in br.
	s.pipe(c, br, up)
}

// forward handles a plain-HTTP request (absolute URI): one request per connection, Connection: close.
func (s *state) forward(c net.Conn, req *http.Request) {
	if req.URL == nil || req.URL.Host == "" {
		io.WriteString(c, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
		return
	}
	target := req.URL.Host
	if !strings.Contains(target, ":") || strings.HasSuffix(target, "]") {
		target = net.JoinHostPort(req.URL.Hostname(), "80")
	}
	up, err := s.dialTarget(target)
	if err != nil {
		fmt.Fprintf(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
		return
	}
	s.conns.Store(up, struct{}{})
	defer func() { s.conns.Delete(up); up.Close() }()
	req.Header.Del("Proxy-Authorization")
	req.Header.Del("Proxy-Connection")
	req.Header.Set("Connection", "close")
	req.Close = true
	cw := &countWriter{w: up, n: &s.up}
	if err := req.Write(cw); err != nil {
		return
	}
	io.Copy(&countWriter{w: c, n: &s.down}, up)
}

func (s *state) pipe(c net.Conn, br *bufio.Reader, up net.Conn) {
	done := make(chan struct{}, 2)
	// Counted as the bytes flow (tunnels stay open for minutes), not when they close.
	go func() {
		io.Copy(&countWriter{w: up, n: &s.up}, br)
		closeWrite(up)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(&countWriter{w: c, n: &s.down}, up)
		closeWrite(c)
		done <- struct{}{}
	}()
	<-done
	// One side finished: give the other a moment to drain, then don't keep a half-open pair around.
	dl := time.Now().Add(30 * time.Second)
	c.SetDeadline(dl)
	up.SetDeadline(dl)
	<-done
}

type countWriter struct {
	w io.Writer
	n *atomic.Int64
}

func (c *countWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.n.Add(int64(n))
	return n, err
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
	}
}
