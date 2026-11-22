// Package tailnet runs ResProxy's own Tailscale node (tsnet: userspace, gVisor netstack, no TUN), so the phone joins
// the user's tailnet without the Tailscale app (Android allows one VpnService, and ResProxy owns it).
//
//   - Outbound: a SOCKS5 front on 127.0.0.1 (random password) that only dials tailnet peers. The Kotlin relay sends
//     tailnet targets there. Anything else is refused, never dialed directly: tsnet's own dialer would send
//     non-tailnet addresses out of the app process, past the residential proxy.
//   - Inbound: TCP to this node's tailnet IPs is handed to a local listener (see [Config.LocalAddr] and
//     [Config.LoopbackPorts]).
//
// Log uploads to Tailscale are off. The node state (node key) is kept AES-GCM encrypted with a key the app keeps in
// the Android Keystore.
package tailnet

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logtail"
	"tailscale.com/net/socks5"
	"tailscale.com/tsnet"
)

// Config for [Start].
type Config struct {
	// Dir holds tsnet's state and caches (per install).
	Dir string
	// Hostname is the node name shown in the tailnet.
	Hostname string
	// AuthKey logs in without a browser; empty = interactive login (AuthURL in the status).
	AuthKey string
	// ControlURL is the coordination server; empty = Tailscale's. Set for Headscale.
	ControlURL string
	// StateKey encrypts the node state (32 bytes). Nil = tsnet's plain state file (tests only).
	StateKey []byte
	// LocalAddr is where inbound connections go for any port (e.g. the VPN interface's own address, which only
	// listeners bound to all interfaces accept). Empty = only LoopbackPorts are reachable.
	LocalAddr string
	// LoopbackPorts are forwarded to 127.0.0.1 (services bound to loopback only), on explicit opt-in.
	LoopbackPorts []int
	// Logf receives tsnet's backend log (verbose). Nil = discard.
	Logf func(format string, args ...any)
	// ephemeral registers an ephemeral node (tests).
	ephemeral bool
}

// Peer is a tailnet device.
type Peer struct {
	HostName string   `json:"hostname"`
	DNSName  string   `json:"dnsName"`
	IPs      []string `json:"ips"`
	Online   bool     `json:"online"`
	OS       string   `json:"os"`
}

// Status of the node. State is ipn.State's name: NoState, NeedsLogin, NeedsMachineAuth, Stopped, Starting, Running.
type Status struct {
	State          string   `json:"state"`
	AuthURL        string   `json:"authUrl"`
	IPs            []string `json:"ips"`
	DNSName        string   `json:"dnsName"`
	MagicDNSSuffix string   `json:"magicDnsSuffix"`
	KeyExpiry      int64    `json:"keyExpiry"` // unix ms, 0 = never / unknown
	Peers          []Peer   `json:"peers"`
	SocksPort      int      `json:"socksPort"`
	Error          string   `json:"error"`
	InboundConns   int64    `json:"inboundConns"`
	OutboundConns  int64    `json:"outboundConns"`
}

// Node is a running tailnet node.
type Node struct {
	cfg       Config
	srv       *tsnet.Server
	socks     net.Listener
	socksPass string
	ctx       context.Context
	cancel    context.CancelFunc

	mu      sync.Mutex
	status  Status
	names   map[string]netip.Addr // lowercase MagicDNS name / short name → IP
	addrs   map[netip.Addr]bool   // tailnet IPs of peers and self
	self    []netip.Addr
	updated time.Time

	inbound, outbound counter
}

type counter struct {
	mu sync.Mutex
	n  int64
}

func (c *counter) add() { c.mu.Lock(); c.n++; c.mu.Unlock() }
func (c *counter) get() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.n
}

var noLogsOnce sync.Once

// Start creates and starts the node; it doesn't wait for login.
func Start(cfg Config) (*Node, error) {
	noLogsOnce.Do(func() {
		// Never upload logs to log.tailscale.com.
		envknob.SetNoLogsNoSupport()
		logtail.Disable()
	})
	if cfg.Dir == "" {
		return nil, errors.New("no state directory")
	}
	if cfg.Hostname == "" {
		cfg.Hostname = "resproxy"
	}
	// An Android app has no HOME, TMPDIR or /var/lib: without these, tailscale's log setup finds no writable place and
	// panics ("no safe place found to store log state"). Nothing is uploaded; these are local buffers.
	logsDir := filepath.Join(cfg.Dir, "logs")
	if err := os.MkdirAll(logsDir, 0o700); err != nil {
		return nil, err
	}
	os.Setenv("TS_LOGS_DIR", logsDir)
	if os.Getenv("TMPDIR") == "" {
		tmp := filepath.Join(cfg.Dir, "tmp")
		if os.MkdirAll(tmp, 0o700) == nil {
			os.Setenv("TMPDIR", tmp)
		}
	}
	logf := cfg.Logf
	if logf == nil {
		logf = func(string, ...any) {}
	}
	srv := &tsnet.Server{
		Dir:        cfg.Dir,
		Hostname:   cfg.Hostname,
		AuthKey:    cfg.AuthKey,
		ControlURL: cfg.ControlURL,
		Ephemeral:  cfg.ephemeral,
		Logf:       logf,
		UserLogf:   logf,
	}
	if cfg.StateKey != nil {
		st, err := newEncryptedStore(cfg.Dir, cfg.StateKey)
		if err != nil {
			return nil, err
		}
		srv.Store = st
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &Node{cfg: cfg, srv: srv, ctx: ctx, cancel: cancel, names: map[string]netip.Addr{}, addrs: map[netip.Addr]bool{}}
	srv.RegisterFallbackTCPHandler(n.inboundHandler)
	if err := srv.Start(); err != nil {
		cancel()
		srv.Close()
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		n.Close()
		return nil, err
	}
	var pw [16]byte
	if _, err := crand.Read(pw[:]); err != nil {
		ln.Close()
		n.Close()
		return nil, err
	}
	n.socks, n.socksPass = ln, hex.EncodeToString(pw[:])
	s5 := &socks5.Server{Username: SocksUser, Password: n.socksPass, Dialer: n.dialPeer, Logf: func(string, ...any) {}}
	go s5.Serve(ln)
	go n.poll()
	return n, nil
}

// SocksUser is the SOCKS5 username of the outbound front.
const SocksUser = "tailnet"

// Socks returns the outbound SOCKS5 front's port and password.
func (n *Node) Socks() (int, string) {
	return n.socks.Addr().(*net.TCPAddr).Port, n.socksPass
}

// Close stops the node (it stays registered in the tailnet).
func (n *Node) Close() {
	n.cancel()
	if n.socks != nil {
		n.socks.Close()
	}
	n.srv.Close()
}

// Logout removes the node from the tailnet and stops it.
func (n *Node) Logout() error {
	lc, err := n.srv.LocalClient()
	if err == nil {
		ctx, cancel := context.WithTimeout(n.ctx, 10*time.Second)
		err = lc.Logout(ctx)
		cancel()
	}
	n.Close()
	return err
}

// NetworkChanged makes tsnet re-check interfaces and paths now (Android's network callback; the poller is slow).
func (n *Node) NetworkChanged() {
	if nm := n.srv.Sys().NetMon.Get(); nm != nil {
		nm.InjectEvent()
	}
}

// poll keeps Status and the peer tables current: fast while logging in, slower once running.
func (n *Node) poll() {
	for {
		n.refresh()
		d := 15 * time.Second
		if n.Status().State != "Running" {
			d = 2 * time.Second
		}
		select {
		case <-n.ctx.Done():
			return
		case <-time.After(d):
		}
	}
}

func (n *Node) refresh() {
	lc, err := n.srv.LocalClient()
	if err != nil {
		n.setError(err)
		return
	}
	ctx, cancel := context.WithTimeout(n.ctx, 5*time.Second)
	st, err := lc.Status(ctx)
	cancel()
	if err != nil {
		if n.ctx.Err() == nil {
			n.setError(err)
		}
		return
	}
	n.apply(st)
}

func (n *Node) setError(err error) {
	n.mu.Lock()
	n.status.Error = err.Error()
	n.mu.Unlock()
}

func (n *Node) apply(st *ipnstate.Status) {
	out := Status{State: st.BackendState, AuthURL: st.AuthURL}
	names := map[string]netip.Addr{}
	addrs := map[netip.Addr]bool{}
	var self []netip.Addr
	for _, a := range st.TailscaleIPs {
		out.IPs = append(out.IPs, a.String())
		self = append(self, a)
		addrs[a] = true
	}
	if st.CurrentTailnet != nil {
		out.MagicDNSSuffix = st.CurrentTailnet.MagicDNSSuffix
	}
	if st.Self != nil {
		out.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
		if st.Self.KeyExpiry != nil {
			out.KeyExpiry = st.Self.KeyExpiry.UnixMilli()
		}
	}
	for _, p := range st.Peer {
		peer := Peer{HostName: p.HostName, DNSName: strings.TrimSuffix(p.DNSName, "."), Online: p.Online, OS: p.OS}
		for _, a := range p.TailscaleIPs {
			peer.IPs = append(peer.IPs, a.String())
			addrs[a] = true
		}
		out.Peers = append(out.Peers, peer)
		if len(p.TailscaleIPs) == 0 || peer.DNSName == "" {
			continue
		}
		ip := pickIP(p.TailscaleIPs)
		full := strings.ToLower(peer.DNSName)
		names[full] = ip
		if short, _, ok := strings.Cut(full, "."); ok {
			if _, taken := names[short]; !taken {
				names[short] = ip
			}
		}
	}
	slices.SortFunc(out.Peers, func(a, b Peer) int { return strings.Compare(a.DNSName, b.DNSName) })
	n.mu.Lock()
	out.SocksPort = 0
	if n.socks != nil {
		out.SocksPort = n.socks.Addr().(*net.TCPAddr).Port
	}
	n.status, n.names, n.addrs, n.self, n.updated = out, names, addrs, self, time.Now()
	n.mu.Unlock()
}

// pickIP prefers IPv4: the relay and most apps on the phone speak IPv4 to the tunnel.
func pickIP(ips []netip.Addr) netip.Addr {
	for _, a := range ips {
		if a.Is4() {
			return a
		}
	}
	return ips[0]
}

// Status returns the last known status.
func (n *Node) Status() Status {
	n.mu.Lock()
	defer n.mu.Unlock()
	s := n.status
	// Empty lists as [], not null: the app's JSON decoder wants lists.
	s.Peers = append([]Peer{}, s.Peers...)
	s.IPs = append([]string{}, s.IPs...)
	for i := range s.Peers {
		s.Peers[i].IPs = append([]string{}, s.Peers[i].IPs...)
	}
	s.InboundConns = n.inbound.get()
	s.OutboundConns = n.outbound.get()
	return s
}

var (
	cgnat = netip.MustParsePrefix("100.64.0.0/10")
	ula   = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

// InTailnetRange reports whether ip is in Tailscale's address ranges (CGNAT 100.64/10 or its IPv6 ULA).
func InTailnetRange(ip netip.Addr) bool {
	ip = ip.Unmap()
	return cgnat.Contains(ip) || ula.Contains(ip)
}

// IsTailnetHost reports whether host (an IP literal or a name) belongs to the tailnet: Tailscale's address ranges,
// names under the tailnet's MagicDNS suffix, and bare names of peers.
func (n *Node) IsTailnetHost(host string) bool {
	h := normHost(host)
	if ip, err := netip.ParseAddr(h); err == nil {
		return InTailnetRange(ip)
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if _, ok := n.names[h]; ok {
		return true
	}
	suf := strings.ToLower(n.status.MagicDNSSuffix)
	return suf != "" && strings.HasSuffix(h, "."+suf)
}

// resolve maps a tailnet target to a peer's (or our own) IP; ok=false for anything that isn't a known tailnet host.
func (n *Node) resolve(host string) (netip.Addr, bool) {
	h := normHost(host)
	n.mu.Lock()
	defer n.mu.Unlock()
	if ip, err := netip.ParseAddr(h); err == nil {
		ip = ip.Unmap()
		return ip, n.addrs[ip]
	}
	ip, ok := n.names[h]
	return ip, ok
}

func normHost(h string) string {
	return strings.TrimSuffix(strings.ToLower(strings.Trim(h, "[]")), ".")
}

// dialPeer is the SOCKS5 front's dialer: tailnet peers only.
func (n *Node) dialPeer(ctx context.Context, network, addr string) (net.Conn, error) {
	if !strings.HasPrefix(network, "tcp") {
		return nil, errors.New("tailnet: TCP only")
	}
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, err
	}
	ip, ok := n.resolve(host)
	if !ok {
		// A peer added since the last poll: refresh once before refusing.
		n.refresh()
		if ip, ok = n.resolve(host); !ok {
			return nil, fmt.Errorf("tailnet: %s is not a device in the tailnet", host)
		}
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	c, err := n.srv.Dial(ctx, "tcp", netip.AddrPortFrom(ip, uint16(port)).String())
	if err == nil {
		n.outbound.add()
	}
	return c, err
}

// inboundHandler accepts TCP to this node's tailnet IPs and hands it to a local listener.
func (n *Node) inboundHandler(src, dst netip.AddrPort) (func(net.Conn), bool) {
	n.mu.Lock()
	mine := slices.Contains(n.self, dst.Addr())
	n.mu.Unlock()
	if !mine {
		// Before the first poll our IPs aren't known yet; ask tsnet.
		v4, v6 := n.srv.TailscaleIPs()
		mine = dst.Addr() == v4 || dst.Addr() == v6
	}
	if !mine {
		return nil, true // reject
	}
	target := n.localTarget(int(dst.Port()))
	if target == "" {
		return nil, true
	}
	// Dial before accepting, so a closed port answers with a reset instead of accept-then-close. Local connects are
	// immediate (refused or accepted).
	local, err := net.DialTimeout("tcp", target, 2*time.Second)
	if err != nil {
		return nil, true
	}
	n.inbound.add()
	return func(c net.Conn) { pipe(c, local) }, true
}

// localTarget is where an inbound connection to port goes, or "" to refuse it.
func (n *Node) localTarget(port int) string {
	if slices.Contains(n.cfg.LoopbackPorts, port) {
		return net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	}
	if n.cfg.LocalAddr != "" {
		return net.JoinHostPort(n.cfg.LocalAddr, strconv.Itoa(port))
	}
	return ""
}

func pipe(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(a, b); closeWrite(a); done <- struct{}{} }()
	go func() { io.Copy(b, a); closeWrite(b); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
	}
}
