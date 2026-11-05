package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

// ---------- direct HTTP for the extension's API calls (outside the proxy, like the Android app's own sockets) ----------

type fetchArgs struct {
	URL     string            `json:"url"`
	Method  string            `json:"method"`
	Headers map[string]string `json:"headers"`
	Body    *string           `json:"body"`
	// Proxy: through the upstream proxy (the exit IP), not gated. The iOS tunnel's own checks use it.
	Proxy bool `json:"proxy"`
}

var directClient = &http.Client{
	Timeout:   25 * time.Second,
	Transport: &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 10 * time.Second}).DialContext, TLSHandshakeTimeout: 10 * time.Second},
}

// proxyClient sends requests through the current upstream (and VPN chain), outside the traffic gate.
func (s *state) proxyClient() *http.Client {
	return &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				s.mu.Lock()
				running := s.ln != nil
				s.mu.Unlock()
				if !running {
					return nil, errors.New("relay is not running")
				}
				return dialUpstream(ctx, s.dialer(), s.currentUpstream(), addr, s.recordRtt)
			},
			TLSHandshakeTimeout: 15 * time.Second,
			DisableKeepAlives:   true, // a new session must never reuse a connection of the old one
		},
	}
}

func directFetch(a fetchArgs) (int, string, error) { return fetchWith(directClient, a) }

func fetchWith(client *http.Client, a fetchArgs) (int, string, error) {
	if !strings.HasPrefix(a.URL, "https://") && !strings.HasPrefix(a.URL, "http://") {
		return 0, "", errors.New("only http(s) URLs")
	}
	var body io.Reader
	if a.Body != nil {
		body = strings.NewReader(*a.Body)
	}
	method := a.Method
	if method == "" {
		method = http.MethodGet
	}
	req, err := http.NewRequest(method, a.URL, body)
	if err != nil {
		return 0, "", err
	}
	for k, v := range a.Headers {
		req.Header.Set(k, v)
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", "ResProxy-helper/"+Version)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return 0, "", err
	}
	return resp.StatusCode, string(b), nil
}

// lookupA resolves IPv4 addresses with the system resolver (DNSBL queries; public resolvers are often refused).
func lookupA(name string) ([]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", name)
	if err != nil {
		var dnsErr *net.DNSError
		if errors.As(err, &dnsErr) && dnsErr.IsNotFound {
			return nil, withCode("nxdomain", err)
		}
		return nil, err
	}
	out := make([]string, 0, len(ips))
	for _, ip := range ips {
		out = append(out, ip.String())
	}
	return out, nil
}

// ---------- ICMP latency via the system ping (no admin rights needed) ----------

var pingTime = regexp.MustCompile(`(?i)time[=<]\s*([0-9.]+)\s*ms`)

func pingOne(target string) *int {
	if runtime.GOOS == "ios" {
		return icmpPing(target) // no ping binary in the app sandbox; unprivileged ICMP sockets work
	}
	var args []string
	switch runtime.GOOS {
	case "windows":
		args = []string{"-n", "1", "-w", "2000", target}
	case "darwin":
		args = []string{"-c", "1", "-t", "2", target}
	default:
		args = []string{"-c", "1", "-W", "2", target}
	}
	name := "ping"
	if strings.Contains(target, ":") && runtime.GOOS != "windows" {
		name = "ping6"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	hideWindow(cmd)
	out, _ := cmd.Output()
	m := pingTime.FindSubmatch(out)
	if m == nil {
		return nil
	}
	f, err := strconv.ParseFloat(string(m[1]), 64)
	if err != nil {
		return nil
	}
	ms := int(f + 0.5)
	return &ms
}

func pingAll(targets []string) map[string]*int {
	res := make(map[string]*int, len(targets))
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 32)
	for _, t := range targets {
		if t == "" || strings.ContainsAny(t, " \t;&|`$") || strings.HasPrefix(t, "-") {
			continue
		}
		wg.Add(1)
		go func(t string) {
			defer wg.Done()
			sem <- struct{}{}
			ms := pingOne(t)
			<-sem
			mu.Lock()
			res[t] = ms
			mu.Unlock()
		}(t)
	}
	wg.Wait()
	return res
}

// icmpPing: one echo over an unprivileged (SOCK_DGRAM) ICMP socket, as on iOS/macOS.
func icmpPing(target string) *int {
	addr, err := netip.ParseAddr(target)
	if err != nil {
		return nil
	}
	network, listen, proto := "udp4", "0.0.0.0", 1
	var typ icmp.Type = ipv4.ICMPTypeEcho
	if addr.Is6() {
		network, listen, proto, typ = "udp6", "::", 58, ipv6.ICMPTypeEchoRequest
	}
	c, err := icmp.ListenPacket(network, listen)
	if err != nil {
		return nil
	}
	defer c.Close()
	msg := icmp.Message{Type: typ, Body: &icmp.Echo{ID: 0x5250, Seq: int(time.Now().UnixNano() & 0x7fff), Data: []byte("resproxy")}}
	b, err := msg.Marshal(nil)
	if err != nil {
		return nil
	}
	c.SetDeadline(time.Now().Add(2 * time.Second))
	start := time.Now()
	if _, err := c.WriteTo(b, &net.UDPAddr{IP: addr.AsSlice()}); err != nil {
		return nil
	}
	buf := make([]byte, 1500)
	for {
		n, _, err := c.ReadFrom(buf)
		if err != nil {
			return nil
		}
		m, err := icmp.ParseMessage(proto, buf[:n])
		if err == nil && (m.Type == ipv4.ICMPTypeEchoReply || m.Type == ipv6.ICMPTypeEchoReply) {
			ms := int(time.Since(start).Milliseconds())
			return &ms
		}
	}
}
