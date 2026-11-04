package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"resproxy/wgbridge/internal/testproxy"
)

// TestWireGuardChain runs the helper's local proxy → WireGuard → HTTP proxy → target path against a running
// cmd/wgtestserver. Manual (needs UDP on localhost):
//
//	go run ./cmd/wgtestserver -endpoint 127.0.0.1 -port 51999 -forward 21080=127.0.0.1:18090 > wg.conf
//	RESPROXY_WG_CONF=wg.conf go test ./cmd/resproxy-helper -run WireGuardChain
func TestWireGuardChain(t *testing.T) {
	path := os.Getenv("RESPROXY_WG_CONF")
	if path == "" {
		t.Skip("set RESPROXY_WG_CONF to a client config printed by cmd/wgtestserver")
	}
	conf, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	up, err := testproxy.Start("127.0.0.1:18090", "http", "user", "pass")
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "through the tunnel") }))
	defer target.Close()

	s := newState()
	defer s.stopAll()
	// wgtestserver answers every DNS query with its tunnel IP, so the proxy hostname resolves inside the tunnel.
	port, err := s.start(endpoint{Host: "proxy.example", Port: 21080, Protocol: "HTTP", Username: "user", Password: "pass_1"}, string(conf), "tok")
	if err != nil {
		t.Fatal(err)
	}
	code, body := get(t, port, "tok", target.URL+"/")
	if code != 200 || body != "through the tunnel" {
		t.Fatalf("got %d %q", code, body)
	}
	if ms, err := s.vpnPing(strings.TrimSpace(dnsOf(string(conf)))); err != nil {
		t.Logf("in-tunnel ping failed (the test server may not answer ICMP): %v", err)
	} else {
		t.Logf("in-tunnel ping %d ms", ms)
	}
	time.Sleep(200 * time.Millisecond)
	st := s.status()
	if _, ok := st["handshakeAgeSec"]; !ok {
		t.Fatalf("no handshake reported: %v", st)
	}
	t.Logf("status %v", st)
}

func dnsOf(conf string) string {
	for _, l := range strings.Split(conf, "\n") {
		if k, v, ok := strings.Cut(l, "="); ok && strings.EqualFold(strings.TrimSpace(k), "dns") {
			return strings.Split(v, ",")[0]
		}
	}
	return ""
}
