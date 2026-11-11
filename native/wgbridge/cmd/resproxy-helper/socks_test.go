package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"resproxy/wgbridge/internal/testproxy"
)

// socksGet: SOCKS5 CONNECT (user/pass) to target through the relay, then a GET. Returns the reply code (0 = ok) and body.
func socksGet(t *testing.T, port int, user, pass, target string) (byte, string) {
	t.Helper()
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	br := bufio.NewReader(c)
	c.Write([]byte{5, 1, 2})
	var m [2]byte
	if _, err := io.ReadFull(br, m[:]); err != nil || m[1] != 2 {
		t.Fatalf("method %v %v", m, err)
	}
	c.Write(append(append(append([]byte{1, byte(len(user))}, user...), byte(len(pass))), pass...))
	var a [2]byte
	io.ReadFull(br, a[:])
	if a[1] != 0 {
		return 0xff, ""
	}
	host, ps, _ := net.SplitHostPort(target)
	p, _ := strconv.Atoi(ps)
	req := append([]byte{5, 1, 0, 3, byte(len(host))}, host...)
	req = binary.BigEndian.AppendUint16(req, uint16(p))
	c.Write(req)
	var rep [10]byte
	if _, err := io.ReadFull(br, rep[:]); err != nil {
		t.Fatalf("reply: %v", err)
	}
	if rep[1] != 0 {
		return rep[1], ""
	}
	fmt.Fprintf(c, "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", target)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	return 0, string(b)
}

func TestSocksFrontAndGate(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "hello") }))
	defer target.Close()
	targetHost := strings.TrimPrefix(target.URL, "http://")
	// The test target is on 127.0.0.1, which is never treated as LAN: it must go through the upstream.
	up, err := testproxy.Start("127.0.0.1:0", "socks5", "user", "pass")
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	h, ps, _ := net.SplitHostPort(up.Addr())
	p, _ := strconv.Atoi(ps)

	s := newState()
	s.gate = newGate(false)
	defer s.stopAll()
	if _, err := s.start(endpoint{Host: h, Port: p, Protocol: "SOCKS5", Username: "user", Password: "pass"}, "", "tok"); err != nil {
		t.Fatal(err)
	}
	sp, err := s.startSocks("hev", "secret", true)
	if err != nil {
		t.Fatal(err)
	}
	if code, _ := socksGet(t, sp, "hev", "wrong", targetHost); code != 0xff {
		t.Fatalf("bad relay password accepted (code %d)", code)
	}

	// Gate closed: the connection is held, then refused (reply 2, "not allowed").
	start := time.Now()
	if code, _ := socksGet(t, sp, "hev", "secret", targetHost); code != 2 {
		t.Fatalf("closed gate: code %d, want 2", code)
	}
	if time.Since(start) < gateHold-time.Second {
		t.Fatalf("refused after %v, want a hold of ~%v", time.Since(start), gateHold)
	}

	// Opened while a connection waits: it goes through.
	go func() { time.Sleep(500 * time.Millisecond); s.setGate(true) }()
	if code, body := socksGet(t, sp, "hev", "secret", targetHost); code != 0 || body != "hello" {
		t.Fatalf("after opening: code %d body %q", code, body)
	}
	if st := s.status(); st["gateOpen"] != true || st["conns"].(int64) < 1 {
		t.Fatalf("status %v", st)
	}

	// Proxied fetch (the tunnel's own checks) works with the gate closed.
	s.setGate(false)
	code, body, err := fetchWith(s.proxyClient(), fetchArgs{URL: target.URL + "/"})
	if err != nil || code != 200 || body != "hello" {
		t.Fatalf("proxied fetch: %d %q %v", code, body, err)
	}
}

func TestLanTargets(t *testing.T) {
	for host, want := range map[string]bool{
		"192.168.1.10": true, "10.0.0.1": true, "fe80::1": true, "printer.local": true, "nas": true,
		"8.8.8.8": false, "example.com": false, "127.0.0.1": false, "100.64.0.5": false,
	} {
		if got := isLanTarget(host); got != want {
			t.Errorf("isLanTarget(%q) = %v, want %v", host, got, want)
		}
	}
}

// socksConnectIP: SOCKS5 CONNECT to an IPv4 address; returns the reply code.
func socksConnectIP(t *testing.T, port int, user, pass string, ip [4]byte, p uint16) byte {
	t.Helper()
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	br := bufio.NewReader(c)
	c.Write([]byte{5, 1, 2})
	var m [2]byte
	io.ReadFull(br, m[:])
	c.Write(append(append(append([]byte{1, byte(len(user))}, user...), byte(len(pass))), pass...))
	var a [2]byte
	io.ReadFull(br, a[:])
	req := append([]byte{5, 1, 0, 1}, ip[:]...)
	req = binary.BigEndian.AppendUint16(req, p)
	c.Write(req)
	var rep [10]byte
	if _, err := io.ReadFull(br, rep[:]); err != nil {
		t.Fatalf("reply: %v", err)
	}
	return rep[1]
}

// The SOCKS front hev talks to keeps its port and credentials across upstream restarts (hev keeps running, so its
// fake-IP DNS table stays valid), refuses connections while the upstream is down, and refuses fake IPs at once.
func TestSocksFrontSurvivesUpstreamRestart(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "hello") }))
	defer target.Close()
	targetHost := strings.TrimPrefix(target.URL, "http://")
	up, err := testproxy.Start("127.0.0.1:0", "socks5", "user", "pass")
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	h, ps, _ := net.SplitHostPort(up.Addr())
	p, _ := strconv.Atoi(ps)
	ep := endpoint{Host: h, Port: p, Protocol: "SOCKS5", Username: "user", Password: "pass"}

	s := newState()
	defer s.stopAll()
	if _, err := s.start(ep, "", "tok"); err != nil {
		t.Fatal(err)
	}
	sp, err := s.startSocks("hev", "secret", false)
	if err != nil {
		t.Fatal(err)
	}
	if code, body := socksGet(t, sp, "hev", "secret", targetHost); code != 0 || body != "hello" {
		t.Fatalf("first upstream: code %d body %q", code, body)
	}

	// Upstream down (as during a rebuild): the front still answers but refuses, never dials anything.
	s.stopUpstream()
	if code, _ := socksGet(t, sp, "hev", "secret", targetHost); code != 1 {
		t.Fatalf("while rebuilding: code %d, want 1", code)
	}

	// Restart: same credentials keep the same port.
	if _, err := s.start(ep, "", "tok2"); err != nil {
		t.Fatal(err)
	}
	sp2, err := s.startSocks("hev", "secret", true)
	if err != nil {
		t.Fatal(err)
	}
	if sp2 != sp {
		t.Fatalf("SOCKS port changed across restart: %d → %d", sp, sp2)
	}
	if code, body := socksGet(t, sp, "hev", "secret", targetHost); code != 0 || body != "hello" {
		t.Fatalf("after restart: code %d body %q", code, body)
	}

	// Fake IPs (hev's 100.64/10) are refused with "host unreachable" and counted.
	if code := socksConnectIP(t, sp, "hev", "secret", [4]byte{100, 64, 0, 9}, 443); code != 4 {
		t.Fatalf("fake IP: code %d, want 4", code)
	}
	st := s.status()
	if st["staleFakeIps"].(int64) != 1 || st["socksAccepted"].(int64) < 4 {
		t.Fatalf("status %v", st)
	}

	// New credentials (hev restarted with them) get a new front.
	sp3, err := s.startSocks("hev2", "other", false)
	if err != nil {
		t.Fatal(err)
	}
	if code, _ := socksGet(t, sp3, "hev2", "other", targetHost); code != 0 {
		t.Fatalf("new front: code %d", code)
	}
}
