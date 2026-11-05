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
