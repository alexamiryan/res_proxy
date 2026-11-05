package main

// SOCKS5 front and traffic gate, used by the iOS packet tunnel (hev-socks5-tunnel → this relay → upstream), the
// counterpart of Android's LocalSocksServer + TrafficGate. The Chrome helper doesn't start it, and its gate stays open.

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

// gate: closed until the exit IP passed the reputation check. While closed, new connections wait up to
// gateHold, then are refused; closing it drops the connections that are open.
type gate struct {
	mu   sync.Mutex
	open bool
	ch   chan struct{} // closed while the gate is open
}

const gateHold = 8 * time.Second

func newGate(open bool) *gate {
	g := &gate{ch: make(chan struct{})}
	if open {
		g.open = true
		close(g.ch)
	}
	return g
}

func (g *gate) set(open bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if open == g.open {
		return
	}
	g.open = open
	if open {
		close(g.ch)
	} else {
		g.ch = make(chan struct{})
	}
}

func (g *gate) isOpen() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.open
}

func (g *gate) wait(d time.Duration) bool {
	g.mu.Lock()
	ch := g.ch
	g.mu.Unlock()
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ch:
		return true
	case <-t.C:
		return false
	}
}

type socksServer struct {
	ln         net.Listener
	user, pass string
	lanDirect  bool
	conns      sync.Map // net.Conn → struct{}, apps' connections (dropped when the gate closes)
}

// startSocks starts the SOCKS5 front on 127.0.0.1 (random port, username/password auth).
func (s *state) startSocks(user, pass string, lanDirect bool) (int, error) {
	s.mu.Lock()
	running := s.ln != nil
	old := s.socks
	s.socks = nil
	s.mu.Unlock()
	if !running {
		return 0, errors.New("relay is not running")
	}
	if old != nil {
		old.stop()
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	ss := &socksServer{ln: ln, user: user, pass: pass, lanDirect: lanDirect}
	s.mu.Lock()
	s.socks = ss
	s.mu.Unlock()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serveSocks(ss, c)
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func (ss *socksServer) stop() {
	ss.ln.Close()
	ss.closeConns()
}

func (ss *socksServer) closeConns() {
	ss.conns.Range(func(k, _ any) bool {
		k.(net.Conn).Close()
		ss.conns.Delete(k)
		return true
	})
}

// setGate opens or closes the traffic gate; closing drops apps' open connections (the IP is new or bad).
func (s *state) setGate(open bool) {
	s.gate.set(open)
	if !open {
		s.mu.Lock()
		ss := s.socks
		s.mu.Unlock()
		if ss != nil {
			ss.closeConns()
		}
	}
}

func socksReply(c net.Conn, code byte) {
	c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
}

func (s *state) serveSocks(ss *socksServer, c net.Conn) {
	ss.conns.Store(c, struct{}{})
	defer func() {
		ss.conns.Delete(c)
		c.Close()
	}()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	br := bufio.NewReader(c)
	var hdr [2]byte
	if _, err := io.ReadFull(br, hdr[:]); err != nil || hdr[0] != 5 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	if ss.user != "" {
		if !strings.Contains(string(methods), "\x02") {
			c.Write([]byte{5, 0xff})
			return
		}
		c.Write([]byte{5, 2})
		var v [2]byte
		if _, err := io.ReadFull(br, v[:]); err != nil {
			return
		}
		u := make([]byte, v[1])
		if _, err := io.ReadFull(br, u); err != nil {
			return
		}
		var pl [1]byte
		if _, err := io.ReadFull(br, pl[:]); err != nil {
			return
		}
		p := make([]byte, pl[0])
		if _, err := io.ReadFull(br, p); err != nil {
			return
		}
		if subtle.ConstantTimeCompare(u, []byte(ss.user)) != 1 || subtle.ConstantTimeCompare(p, []byte(ss.pass)) != 1 {
			c.Write([]byte{1, 1})
			return
		}
		c.Write([]byte{1, 0})
	} else {
		c.Write([]byte{5, 0})
	}
	var req [4]byte
	if _, err := io.ReadFull(br, req[:]); err != nil {
		return
	}
	var host string
	switch req[3] {
	case 1:
		var a [4]byte
		if _, err := io.ReadFull(br, a[:]); err != nil {
			return
		}
		host = netip.AddrFrom4(a).String()
	case 4:
		var a [16]byte
		if _, err := io.ReadFull(br, a[:]); err != nil {
			return
		}
		host = netip.AddrFrom16(a).String()
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(br, l[:]); err != nil {
			return
		}
		h := make([]byte, l[0])
		if _, err := io.ReadFull(br, h); err != nil {
			return
		}
		host = string(h)
	default:
		socksReply(c, 8)
		return
	}
	var pb [2]byte
	if _, err := io.ReadFull(br, pb[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(pb[:])
	if req[1] != 1 {
		// UDP ASSOCIATE / BIND: not relayed (apps fall back to TCP; DNS is answered by hev's fake-IP DNS).
		socksReply(c, 7)
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))

	// Gate: hold new connections while the IP is being checked, then refuse.
	if !s.gate.wait(gateHold) {
		socksReply(c, 2)
		return
	}
	var up net.Conn
	var err error
	if ss.lanDirect && isLanTarget(host) {
		up, err = dialLan(host, int(port))
		if errors.Is(err, errNotLan) {
			up, err = s.dialTarget(target)
		}
	} else {
		up, err = s.dialTarget(target)
	}
	if err != nil {
		var refused *refusedError
		if errors.As(err, &refused) {
			socksReply(c, 5)
		} else {
			socksReply(c, 1)
		}
		return
	}
	ss.conns.Store(up, struct{}{})
	defer func() { ss.conns.Delete(up); up.Close() }()
	// The gate may have closed while we were dialing.
	if !s.gate.isOpen() {
		socksReply(c, 2)
		return
	}
	c.SetDeadline(time.Time{})
	if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	s.pipe(c, br, up)
}

// ---------- LAN targets (bypassLan): private addresses and local names go direct ----------

var privatePrefixes = func() []netip.Prefix {
	var out []netip.Prefix
	for _, p := range []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "fc00::/7", "fe80::/10"} {
		out = append(out, netip.MustParsePrefix(p))
	}
	return out
}()

func isPrivate(a netip.Addr) bool {
	a = a.Unmap()
	if a.IsLoopback() {
		return false // never: a loopback target is another app's local server, not the LAN
	}
	for _, p := range privatePrefixes {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// isLocalName: single-label names and the usual local suffixes. Public names are never resolved locally.
func isLocalName(h string) bool {
	h = strings.TrimSuffix(strings.ToLower(h), ".")
	if h == "" {
		return false
	}
	if !strings.Contains(h, ".") {
		return true
	}
	for _, sfx := range []string{".local", ".lan", ".internal", ".home", ".home.arpa", ".localdomain", ".intranet", ".corp"} {
		if strings.HasSuffix(h, sfx) {
			return true
		}
	}
	return false
}

func isLanTarget(host string) bool {
	if a, err := netip.ParseAddr(host); err == nil {
		// 100.64/10 is hev's fake-IP range; it only arrives as an address when mapping failed: not LAN.
		if netip.MustParsePrefix("100.64.0.0/10").Contains(a.Unmap()) {
			return false
		}
		return isPrivate(a)
	}
	return isLocalName(host)
}

var errNotLan = errors.New("not a LAN target")

func dialLan(host string, port int) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ip := host
	if _, err := netip.ParseAddr(host); err != nil {
		addrs, err := net.DefaultResolver.LookupHost(ctx, host)
		if err != nil || len(addrs) == 0 {
			return nil, &refusedError{"can't resolve " + host}
		}
		a, err := netip.ParseAddr(addrs[0])
		if err != nil || !isPrivate(a) {
			// A "local" name that resolves publicly goes through the proxy like any other.
			return nil, errNotLan
		}
		ip = addrs[0]
	}
	d := net.Dialer{}
	return d.DialContext(ctx, "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
}
