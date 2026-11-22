package tailnet

import (
	"bufio"
	"context"
	crand "crypto/rand"
	"encoding/json"
	"net"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/proxy"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

func startControl(t *testing.T) string {
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{
		DERPMap:        derpMap,
		DNSConfig:      &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain: "tail-scale.ts.net",
		Logf:           logger.Discard,
	}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	return control.HTTPTestServer.URL
}

func startNode(t *testing.T, controlURL, name string, cfg Config) *Node {
	t.Helper()
	key := make([]byte, 32)
	crand.Read(key)
	cfg.Dir = filepath.Join(t.TempDir(), name)
	cfg.Hostname = name
	cfg.ControlURL = controlURL
	cfg.StateKey = key
	n, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(n.Close)
	return n
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func echoServer(t *testing.T) int {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				line, _ := bufio.NewReader(c).ReadString('\n')
				c.Write([]byte("echo " + line))
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port
}

func TestTwoNodes(t *testing.T) {
	if testing.Short() {
		t.Skip("starts two tailnet nodes")
	}
	// Start sets these process-wide (an Android app has neither); keep the test process's own.
	t.Setenv("TMPDIR", os.TempDir())
	t.Setenv("TS_LOGS_DIR", t.TempDir())
	controlURL := startControl(t)
	a := startNode(t, controlURL, "alpha", Config{})
	b := startNode(t, controlURL, "beta", Config{LocalAddr: "127.0.0.1"})

	waitFor(t, "both nodes running and seeing each other", func() bool {
		a.refresh()
		b.refresh()
		sa, sb := a.Status(), b.Status()
		return sa.State == "Running" && sb.State == "Running" && len(sa.Peers) == 1 && len(sb.Peers) == 1
	})
	st := a.Status()
	if st.MagicDNSSuffix != "tail-scale.ts.net" || !strings.HasPrefix(st.DNSName, "alpha.") || len(st.IPs) == 0 {
		t.Fatalf("status: %+v", st)
	}

	// Dispatch: what the Kotlin relay asks before choosing tailnet vs residential proxy.
	for host, want := range map[string]bool{
		"beta":                    true,
		"BETA.tail-scale.ts.net.": true,
		"other.tail-scale.ts.net": true, // under the suffix, even if unknown (refused later, never proxied)
		"100.101.102.103":         true,
		"fd7a:115c:a1e0::1":       true,
		"[fd7a:115c:a1e0::1]":     true,
		"example.com":             false,
		"nas":                     false,
		"8.8.8.8":                 false,
		"198.19.0.5":              false,
	} {
		if got := a.IsTailnetHost(host); got != want {
			t.Errorf("IsTailnetHost(%q) = %v, want %v", host, got, want)
		}
	}

	port := echoServer(t)
	socksPort, pass := a.Socks()
	d, err := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(socksPort), &proxy.Auth{User: SocksUser, Password: pass}, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	for _, target := range []string{"beta", "beta.tail-scale.ts.net", b.Status().IPs[0]} {
		c, err := d.Dial("tcp", net.JoinHostPort(target, strconv.Itoa(port)))
		if err != nil {
			t.Fatalf("dial %s: %v", target, err)
		}
		c.SetDeadline(time.Now().Add(10 * time.Second))
		c.Write([]byte("hi\n"))
		got, err := bufio.NewReader(c).ReadString('\n')
		c.Close()
		if err != nil || got != "echo hi\n" {
			t.Fatalf("via %s: got %q, %v", target, got, err)
		}
	}
	if b.Status().InboundConns != 3 || a.Status().OutboundConns != 3 {
		t.Errorf("counters: in %d out %d", b.Status().InboundConns, a.Status().OutboundConns)
	}

	// Never a direct dial past the proxy: non-tailnet targets are refused.
	for _, target := range []string{"example.com:80", "127.0.0.1:" + strconv.Itoa(port), "100.64.0.99:80"} {
		if c, err := d.Dial("tcp", target); err == nil {
			c.Close()
			t.Errorf("dial %s through the tailnet front succeeded", target)
		}
	}
	// Wrong password.
	bad, _ := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(socksPort), &proxy.Auth{User: SocksUser, Password: "x"}, proxy.Direct)
	if c, err := bad.Dial("tcp", net.JoinHostPort("beta", strconv.Itoa(port))); err == nil {
		c.Close()
		t.Error("wrong SOCKS password accepted")
	}
	// A port with nothing listening is refused by beta.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if c, err := a.srv.Dial(ctx, "tcp", netip.AddrPortFrom(netip.MustParseAddr(b.Status().IPs[0]), 1).String()); err == nil {
		c.SetReadDeadline(time.Now().Add(3 * time.Second))
		if _, err := c.Read(make([]byte, 1)); err == nil {
			t.Error("closed port answered")
		}
		c.Close()
	}
}

func TestStatusJSONHasNoNullLists(t *testing.T) {
	n := &Node{}
	b, _ := json.Marshal(n.Status())
	if strings.Contains(string(b), "null") {
		t.Fatalf("null in %s", b)
	}
}

func TestLookup(t *testing.T) {
	a := netip.MustParseAddr
	n := &Node{}
	n.apply(&ipnstate.Status{
		BackendState:   "Running",
		TailscaleIPs:   []netip.Addr{a("100.64.0.2")},
		CurrentTailnet: &ipnstate.TailnetStatus{MagicDNSSuffix: "tail1.ts.net"},
		Self:           &ipnstate.PeerStatus{DNSName: "phone.tail1.ts.net.", TailscaleIPs: []netip.Addr{a("100.64.0.2")}},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {DNSName: "Laptop.tail1.ts.net.", TailscaleIPs: []netip.Addr{a("100.64.0.3"), a("fd7a:115c:a1e0::3")}},
		},
	})
	for _, tc := range []struct {
		name string
		ips  string
		ok   bool
	}{
		{"laptop", "100.64.0.3 fd7a:115c:a1e0::3", true},
		{"LAPTOP.tail1.ts.net.", "100.64.0.3 fd7a:115c:a1e0::3", true},
		{"phone", "100.64.0.2", true},
		{"gone.tail1.ts.net", "", true},
		{"example.com", "", false},
		{"laptop.example.com", "", false},
	} {
		ips, ok := n.Lookup(tc.name)
		var got []string
		for _, ip := range ips {
			got = append(got, ip.String())
		}
		if ok != tc.ok || strings.Join(got, " ") != tc.ips {
			t.Errorf("Lookup(%q) = %v, %v; want %q, %v", tc.name, got, ok, tc.ips, tc.ok)
		}
	}
}

func TestEncryptedStore(t *testing.T) {
	dir := t.TempDir()
	key := make([]byte, 32)
	crand.Read(key)
	s, err := newEncryptedStore(dir, key)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.ReadState("k"); err != ipn.ErrStateNotExist {
		t.Fatalf("empty store: %v", err)
	}
	secret := []byte("privkey:SECRET-NODE-KEY")
	if err := s.WriteState("k", secret); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(filepath.Join(dir, storeFile))
	if strings.Contains(string(raw), "SECRET") {
		t.Fatal("state written in the clear")
	}
	s2, err := newEncryptedStore(dir, key)
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := s2.ReadState("k"); string(got) != string(secret) {
		t.Fatalf("reopened: %q", got)
	}
	if err := s2.WriteState("k", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := s2.ReadState("k"); err != ipn.ErrStateNotExist {
		t.Fatal("delete didn't delete")
	}
	other := make([]byte, 32)
	crand.Read(other)
	if _, err := newEncryptedStore(dir, other); err == nil {
		t.Fatal("opened with the wrong key")
	}
}

func TestSetInterfaces(t *testing.T) {
	t.Cleanup(func() { SetInterfaces(""); ifSet = false })
	SetInterfaces("wlan0 30 1500 um 192.168.1.20/24 fe80::1/64 2a02::5/64\nrmnet0 12 1400 up 10.1.2.3/32 bogus\n\n")
	list, err := interfaces()
	if err != nil || len(list) != 2 {
		t.Fatalf("got %d, %v", len(list), err)
	}
	w := list[0]
	if w.Name != "wlan0" || w.Index != 30 || w.MTU != 1500 || w.Flags&net.FlagUp == 0 || w.Flags&net.FlagMulticast == 0 || len(w.AltAddrs) != 3 {
		t.Fatalf("wlan0: %+v %v", w.Interface, w.AltAddrs)
	}
	if w.AltAddrs[0].String() != "192.168.1.20/24" {
		t.Fatalf("addr: %s", w.AltAddrs[0])
	}
	if r := list[1]; r.Flags&net.FlagPointToPoint == 0 || len(r.AltAddrs) != 1 {
		t.Fatalf("rmnet0: %+v %v", r.Interface, r.AltAddrs)
	}
}

func TestInTailnetRange(t *testing.T) {
	for s, want := range map[string]bool{
		"100.64.0.1": true, "100.127.255.254": true, "100.63.255.255": false, "100.128.0.0": false,
		"fd7a:115c:a1e0:ab12::1": true, "fd00::1": false, "::ffff:100.100.1.1": true,
	} {
		if got := InTailnetRange(netip.MustParseAddr(s)); got != want {
			t.Errorf("%s: %v", s, got)
		}
	}
}
