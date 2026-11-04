// Package testproxy is a tiny HTTP-CONNECT / SOCKS5 proxy with username/password auth for tests: it
// stands in for a residential proxy and records the credentials each connection used.
package testproxy

import (
	"bufio"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
)

// Proxy accepts any username equal to User and any password starting with PasswordPrefix (session
// suffixes vary), and connects targets directly.
type Proxy struct {
	User           string
	PasswordPrefix string
	ln             net.Listener
	mu             sync.Mutex
	used           []string
}

// Used returns "user:password" of every authenticated connection, in order.
func (p *Proxy) Used() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.used...)
}

func (p *Proxy) Addr() string { return p.ln.Addr().String() }
func (p *Proxy) Close()       { p.ln.Close() }

func (p *Proxy) ok(user, pass string) bool {
	good := user == p.User && strings.HasPrefix(pass, p.PasswordPrefix)
	if good {
		p.mu.Lock()
		p.used = append(p.used, user+":"+pass)
		p.mu.Unlock()
	}
	return good
}

// Start listens on addr ("127.0.0.1:0" for any port). mode is "http" or "socks5".
func Start(addr, mode, user, passwordPrefix string) (*Proxy, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	p := &Proxy{User: user, PasswordPrefix: passwordPrefix, ln: ln}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			if mode == "socks5" {
				go p.socks(c)
			} else {
				go p.http(c)
			}
		}
	}()
	return p, nil
}

func (p *Proxy) http(c net.Conn) {
	defer c.Close()
	br := bufio.NewReader(c)
	for {
		req, err := http.ReadRequest(br)
		if err != nil {
			return
		}
		auth := req.Header.Get("Proxy-Authorization")
		user, pass := "", ""
		if b, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(auth, "Basic ")); err == nil {
			user, pass, _ = strings.Cut(string(b), ":")
		}
		if !p.ok(user, pass) {
			io.Copy(io.Discard, req.Body)
			io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n\r\n")
			continue
		}
		if req.Method == http.MethodConnect {
			t, err := net.Dial("tcp", req.Host)
			if err != nil {
				io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
				return
			}
			io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n")
			relay(c, br, t)
			return
		}
		// Plain HTTP through the proxy.
		host := req.URL.Host
		if !strings.Contains(host, ":") {
			host += ":80"
		}
		t, err := net.Dial("tcp", host)
		if err != nil {
			io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			return
		}
		req.Header.Del("Proxy-Authorization")
		req.Header.Set("Connection", "close")
		req.Write(t)
		io.Copy(c, t)
		t.Close()
		return
	}
}

func (p *Proxy) socks(c net.Conn) {
	defer c.Close()
	var h [2]byte
	if _, err := io.ReadFull(c, h[:]); err != nil || h[0] != 5 {
		return
	}
	methods := make([]byte, h[1])
	io.ReadFull(c, methods)
	c.Write([]byte{5, 2})
	var v [2]byte
	io.ReadFull(c, v[:])
	u := make([]byte, v[1])
	io.ReadFull(c, u)
	var pl [1]byte
	io.ReadFull(c, pl[:])
	pw := make([]byte, pl[0])
	io.ReadFull(c, pw)
	if !p.ok(string(u), string(pw)) {
		c.Write([]byte{1, 1})
		return
	}
	c.Write([]byte{1, 0})
	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil {
		return
	}
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 3:
		var l [1]byte
		io.ReadFull(c, l[:])
		b := make([]byte, l[0])
		io.ReadFull(c, b)
		host = string(b)
	}
	var pb [2]byte
	io.ReadFull(c, pb[:])
	target := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(pb[:]))))
	t, err := net.Dial("tcp", target)
	if err != nil {
		c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	relay(c, bufio.NewReader(c), t)
}

func relay(c net.Conn, r io.Reader, t net.Conn) {
	defer t.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(t, r); done <- struct{}{} }()
	go func() { io.Copy(c, t); done <- struct{}{} }()
	<-done
}

// String describes the proxy for logs.
func (p *Proxy) String() string { return fmt.Sprintf("testproxy %s", p.Addr()) }
