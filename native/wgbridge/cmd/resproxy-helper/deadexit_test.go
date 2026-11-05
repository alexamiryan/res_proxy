package main

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"testing"
)

// fakeDeadExit is a proxy that accepts the login but can't reach any target, like Geonode when the exit node behind a
// sticky session went offline: SOCKS5 reply 4 (host unreachable), or HTTP 502 on CONNECT.
func fakeDeadExit(t *testing.T, protocol string) endpoint {
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
			go func(c net.Conn) {
				defer c.Close()
				if protocol == "HTTP" {
					if _, err := http.ReadRequest(bufio.NewReader(c)); err == nil {
						io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
					}
					return
				}
				b := make([]byte, 512)
				c.Read(b) // greeting
				c.Write([]byte{5, 2})
				c.Read(b) // username/password
				c.Write([]byte{1, 0})
				c.Read(b) // CONNECT
				c.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
			}(c)
		}
	}()
	return endpoint{Host: "127.0.0.1", Port: ln.Addr().(*net.TCPAddr).Port, Protocol: protocol, Username: "u", Password: "p"}
}

func TestProbeDeadExitNodeIsRefused(t *testing.T) {
	for _, proto := range []string{"SOCKS5", "HTTP"} {
		t.Run(proto, func(t *testing.T) {
			_, err := probeUpstream((&net.Dialer{}).DialContext, fakeDeadExit(t, proto))
			if err == nil {
				t.Fatal("probe succeeded")
			}
			if c := errorCode(err); c != "refused" {
				t.Fatalf("code %q for %v", c, err)
			}
		})
	}
	if c := errorCode(withCode("auth", errAuth)); c != "auth" {
		t.Fatalf("auth code %q", c)
	}
}
