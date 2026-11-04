package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"time"
)

// endpoint is the concrete upstream proxy (provider targeting/session already applied by the extension).
type endpoint struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Protocol string `json:"protocol"` // SOCKS5 | HTTP
	Username string `json:"username"`
	Password string `json:"password"`
}

func (e endpoint) addr() string { return net.JoinHostPort(e.Host, strconv.Itoa(e.Port)) }

var (
	// errAuth: the proxy rejected the credentials.
	errAuth = errors.New("the proxy rejected the username/password")
)

// refusedError: the proxy works, but the target failed (SOCKS5 reply 1–8, HTTP 5xx on CONNECT). Doesn't count as a relay failure.
type refusedError struct{ msg string }

func (e *refusedError) Error() string { return e.msg }

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// dialUpstream opens a TCP tunnel to target (host:port) through the proxy. onRtt gets handshake round trips.
func dialUpstream(ctx context.Context, dial dialFunc, ep endpoint, target string, onRtt func(time.Duration)) (net.Conn, error) {
	c, err := dial(ctx, "tcp", ep.addr())
	if err != nil {
		return nil, fmt.Errorf("can't reach the proxy %s: %w", ep.addr(), err)
	}
	if dl, ok := ctx.Deadline(); ok {
		c.SetDeadline(dl)
	}
	var out net.Conn
	if ep.Protocol == "HTTP" {
		out, err = httpConnect(c, ep, target, onRtt)
	} else {
		err = socks5Connect(c, ep, target, onRtt)
		out = c
	}
	if err != nil {
		c.Close()
		return nil, err
	}
	c.SetDeadline(time.Time{})
	return out, nil
}

func socks5Connect(c net.Conn, ep endpoint, target string, onRtt func(time.Duration)) error {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return err
	}
	port, _ := strconv.Atoi(portStr)
	auth := ep.Username != ""
	start := time.Now()
	if auth {
		_, err = c.Write([]byte{5, 2, 0, 2})
	} else {
		_, err = c.Write([]byte{5, 1, 0})
	}
	if err != nil {
		return err
	}
	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		return fmt.Errorf("proxy closed the connection: %w", err)
	}
	if onRtt != nil {
		onRtt(time.Since(start))
	}
	if hdr[0] != 5 {
		return errors.New("not a SOCKS5 proxy")
	}
	switch hdr[1] {
	case 0:
	case 2:
		u, p := []byte(ep.Username), []byte(ep.Password)
		if len(u) > 255 || len(p) > 255 {
			return withCode("auth", errors.New("username/password too long for SOCKS5"))
		}
		msg := append([]byte{1, byte(len(u))}, u...)
		msg = append(msg, byte(len(p)))
		msg = append(msg, p...)
		if _, err := c.Write(msg); err != nil {
			return err
		}
		var r [2]byte
		if _, err := io.ReadFull(c, r[:]); err != nil {
			return withCode("auth", errAuth)
		}
		if r[1] != 0 {
			return withCode("auth", errAuth)
		}
	case 0xff:
		return withCode("auth", errors.New("the proxy needs a username/password"))
	default:
		return fmt.Errorf("unsupported SOCKS5 auth method %d", hdr[1])
	}
	req := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			req = append(req, 1)
			req = append(req, v4...)
		} else {
			req = append(req, 4)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return errors.New("host name too long")
		}
		req = append(req, 3, byte(len(host)))
		req = append(req, host...)
	}
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	if _, err := c.Write(req); err != nil {
		return err
	}
	var rep [4]byte
	if _, err := io.ReadFull(c, rep[:]); err != nil {
		return fmt.Errorf("proxy closed the connection: %w", err)
	}
	if rep[1] != 0 {
		return &refusedError{fmt.Sprintf("proxy couldn't reach %s (SOCKS5 reply %d)", target, rep[1])}
	}
	var skip int
	switch rep[3] {
	case 1:
		skip = 4
	case 4:
		skip = 16
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return err
		}
		skip = int(l[0])
	}
	_, err = io.CopyN(io.Discard, c, int64(skip+2))
	return err
}

// bufConn keeps bytes the response reader buffered past the CONNECT reply.
type bufConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufConn) Read(p []byte) (int, error) { return b.r.Read(p) }

func (b *bufConn) CloseWrite() error {
	if cw, ok := b.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

func httpConnect(c net.Conn, ep endpoint, target string, onRtt func(time.Duration)) (net.Conn, error) {
	req := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
	if ep.Username != "" {
		req += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(ep.Username+":"+ep.Password)) + "\r\n"
	}
	req += "\r\n"
	start := time.Now()
	if _, err := io.WriteString(c, req); err != nil {
		return nil, err
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		return nil, fmt.Errorf("proxy closed the connection: %w", err)
	}
	if onRtt != nil {
		onRtt(time.Since(start))
	}
	resp.Body.Close()
	switch {
	case resp.StatusCode == 200:
		return &bufConn{c, br}, nil
	case resp.StatusCode == 407:
		return nil, withCode("auth", errAuth)
	case resp.StatusCode >= 500:
		return nil, &refusedError{fmt.Sprintf("proxy couldn't reach %s (%s)", target, resp.Status)}
	default:
		return nil, fmt.Errorf("proxy answered %s", resp.Status)
	}
}

// probe: GET /generate_204 through the proxy. Returns the round trip in ms.
func probeUpstream(dial dialFunc, ep endpoint) (int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	start := time.Now()
	const host = "connectivitycheck.gstatic.com"
	c, err := dialUpstream(ctx, dial, ep, host+":80", nil)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := io.WriteString(c, "GET /generate_204 HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n"); err != nil {
		return 0, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		return 0, fmt.Errorf("proxy returned no response: %w", err)
	}
	resp.Body.Close()
	if resp.StatusCode != 204 && resp.StatusCode != 200 {
		return 0, fmt.Errorf("unexpected response through proxy: %s", resp.Status)
	}
	return int(time.Since(start).Milliseconds()), nil
}
