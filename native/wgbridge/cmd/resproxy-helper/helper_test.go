package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"resproxy/wgbridge/internal/testproxy"
)

// get fetches url through the helper's local proxy with the given token (plain HTTP).
func get(t *testing.T, port int, token, url string) (int, string) {
	t.Helper()
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	auth := ""
	if token != "" {
		auth = "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte("resproxy:"+token)) + "\r\n"
	}
	fmt.Fprintf(c, "GET %s HTTP/1.1\r\nHost: x\r\n%s\r\n", url, auth)
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

// connectGet does CONNECT + a request inside the tunnel.
func connectGet(t *testing.T, port int, token, target string) string {
	t.Helper()
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fmt.Fprintf(c, "CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: Basic %s\r\n\r\n", target, target,
		base64.StdEncoding.EncodeToString([]byte("resproxy:"+token)))
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT: %v %v", resp, err)
	}
	fmt.Fprintf(c, "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", target)
	r2, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(r2.Body)
	return string(b)
}

func TestLocalProxyThroughBothUpstreams(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "hello") }))
	defer target.Close()
	targetHost := strings.TrimPrefix(target.URL, "http://")

	for _, mode := range []string{"http", "socks5"} {
		t.Run(mode, func(t *testing.T) {
			up, err := testproxy.Start("127.0.0.1:0", mode, "user", "pass")
			if err != nil {
				t.Fatal(err)
			}
			defer up.Close()
			host, portStr, _ := net.SplitHostPort(up.Addr())
			var port int
			fmt.Sscan(portStr, &port)
			proto := map[string]string{"http": "HTTP", "socks5": "SOCKS5"}[mode]
			s := newState()
			defer s.stopAll()
			lp, err := s.start(endpoint{Host: host, Port: port, Protocol: proto, Username: "user", Password: "pass_session-a"}, "", "tok")
			if err != nil {
				t.Fatal(err)
			}
			if code, _ := get(t, lp, "", target.URL+"/"); code != 407 {
				t.Fatalf("no token: got %d, want 407", code)
			}
			if code, _ := get(t, lp, "wrong", target.URL+"/"); code != 407 {
				t.Fatalf("bad token: got %d, want 407", code)
			}
			if code, body := get(t, lp, "tok", target.URL+"/"); code != 200 || body != "hello" {
				t.Fatalf("plain HTTP: %d %q", code, body)
			}
			if body := connectGet(t, lp, "tok", targetHost); body != "hello" {
				t.Fatalf("CONNECT body %q", body)
			}
			// New session: new credentials are used from the next connection on.
			s.setUpstream(endpoint{Host: host, Port: port, Protocol: proto, Username: "user", Password: "pass_session-b"})
			connectGet(t, lp, "tok", targetHost)
			used := up.Used()
			if last := used[len(used)-1]; last != "user:pass_session-b" {
				t.Fatalf("after rotation used %q", last)
			}
			if ms, err := s.probeTo(targetHost); err != nil || ms < 0 {
				t.Fatalf("probe: %v", err)
			}
			st := s.status()
			if st["conns"].(int64) < 3 || st["down"].(int64) == 0 {
				t.Fatalf("status %v", st)
			}
			// Wrong password → auth error code.
			s.setUpstream(endpoint{Host: host, Port: port, Protocol: proto, Username: "user", Password: "nope"})
			_, err = s.probeTo(targetHost)
			var ce *codedError
			if err == nil || !asCoded(err, &ce) || ce.code != "auth" {
				t.Fatalf("want auth error, got %v", err)
			}
		})
	}
}

func asCoded(err error, ce **codedError) bool {
	for err != nil {
		if c, ok := err.(*codedError); ok {
			*ce = c
			return true
		}
		u, ok := err.(interface{ Unwrap() error })
		if !ok {
			return false
		}
		err = u.Unwrap()
	}
	return false
}

// probeTo is probe() against a local target instead of connectivitycheck.gstatic.com.
func (s *state) probeTo(target string) (int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, err := dialUpstream(ctx, s.dialer(), s.currentUpstream(), target, nil)
	if err != nil {
		return 0, err
	}
	c.Close()
	return 1, nil
}

func TestNativeMessaging(t *testing.T) {
	var in, out bytes.Buffer
	msg := func(v any) {
		b, _ := json.Marshal(v)
		binary.Write(&in, binary.LittleEndian, uint32(len(b)))
		in.Write(b)
	}
	msg(map[string]any{"id": 1, "cmd": "hello"})
	msg(map[string]any{"id": 2, "cmd": "nope"})
	serve(&in, &out)
	time.Sleep(100 * time.Millisecond)
	got := map[int]map[string]any{}
	for out.Len() > 0 {
		var n uint32
		binary.Read(&out, binary.LittleEndian, &n)
		b := make([]byte, n)
		out.Read(b)
		var m map[string]any
		json.Unmarshal(b, &m)
		got[int(m["id"].(float64))] = m
	}
	if got[1]["ok"] != true || got[1]["version"] == nil {
		t.Fatalf("hello: %v", got[1])
	}
	if got[2]["ok"] != false {
		t.Fatalf("unknown: %v", got[2])
	}
}

func TestParseWgQuick(t *testing.T) {
	conf := "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.2.0.2/32, fd00::2/128\nDNS = 10.2.0.1\n\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\nEndpoint = 1.2.3.4:51820\n"
	c, err := parseWgQuick(conf)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.addresses) != 2 || c.dns[0].String() != "10.2.0.1" || c.peers[0].port != 51820 || c.peers[0].keepalive != 25 {
		t.Fatalf("%+v", c)
	}
	u, err := c.uapi()
	if err != nil || !strings.Contains(u, "endpoint=1.2.3.4:51820") || !strings.Contains(u, "allowed_ip=0.0.0.0/0") {
		t.Fatalf("uapi %q %v", u, err)
	}
	if _, err := parseWgQuick("[Interface]\nPrivateKey = short\n"); err == nil {
		t.Fatal("want error")
	}
}

func TestPingParse(t *testing.T) {
	for _, s := range []string{"Reply from 1.1.1.1: bytes=32 time=12ms TTL=57", "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=11.6 ms", "Reply from 127.0.0.1: bytes=32 time<1ms TTL=128"} {
		if m := pingTime.FindStringSubmatch(s); m == nil {
			t.Fatalf("no match: %s", s)
		}
	}
}
