package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// wgTunnel is a userspace WireGuard interface whose TCP dials stay inside the tunnel.
type wgTunnel struct {
	dev *device.Device
	net *netstack.Net
}

type wgPeer struct {
	publicKey, presharedKey []byte
	host                    string
	port                    int
	allowedIPs              []string
	keepalive               int
}

type wgConfig struct {
	privateKey []byte
	addresses  []netip.Addr
	dns        []netip.Addr
	mtu        int
	peers      []wgPeer
}

// parseWgQuick parses a wg-quick `.conf` (same rules as the Kotlin and TypeScript parsers).
func parseWgQuick(text string) (*wgConfig, error) {
	cfg := &wgConfig{mtu: 1280}
	section := ""
	var peer map[string]string
	key := func(v string) ([]byte, error) {
		b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(v))
		if err != nil || len(b) != 32 {
			return nil, errors.New("invalid WireGuard key")
		}
		return b, nil
	}
	list := func(v string) []string {
		var out []string
		for _, p := range strings.Split(v, ",") {
			if p = strings.TrimSpace(p); p != "" {
				out = append(out, p)
			}
		}
		return out
	}
	flush := func() error {
		if peer == nil {
			return nil
		}
		defer func() { peer = nil }()
		pub, err := key(peer["publickey"])
		if peer["publickey"] == "" {
			return errors.New("[Peer] has no PublicKey")
		}
		if err != nil {
			return err
		}
		ep := peer["endpoint"]
		if ep == "" {
			return errors.New("[Peer] has no Endpoint")
		}
		i := strings.LastIndex(ep, ":")
		if i <= 0 {
			return fmt.Errorf("bad Endpoint: %s", ep)
		}
		port, err := strconv.Atoi(ep[i+1:])
		if err != nil {
			return fmt.Errorf("bad Endpoint port: %s", ep)
		}
		p := wgPeer{publicKey: pub, host: strings.Trim(ep[:i], "[]"), port: port, keepalive: 25}
		if v := peer["presharedkey"]; v != "" {
			if p.presharedKey, err = key(v); err != nil {
				return err
			}
		}
		p.allowedIPs = list(peer["allowedips"])
		if len(p.allowedIPs) == 0 {
			p.allowedIPs = []string{"0.0.0.0/0", "::/0"}
		}
		if v, err := strconv.Atoi(peer["persistentkeepalive"]); err == nil {
			p.keepalive = v
		}
		cfg.peers = append(cfg.peers, p)
		return nil
	}
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(strings.SplitN(raw, "#", 2)[0])
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			if err := flush(); err != nil {
				return nil, err
			}
			section = strings.ToLower(line)
			if section == "[peer]" {
				peer = map[string]string{}
			}
			continue
		}
		k, v, _ := strings.Cut(line, "=")
		k, v = strings.ToLower(strings.TrimSpace(k)), strings.TrimSpace(v)
		switch section {
		case "[interface]":
			switch k {
			case "privatekey":
				b, err := key(v)
				if err != nil {
					return nil, err
				}
				cfg.privateKey = b
			case "address":
				for _, a := range list(v) {
					if i := strings.IndexByte(a, '/'); i >= 0 {
						a = a[:i]
					}
					ip, err := netip.ParseAddr(a)
					if err != nil {
						return nil, fmt.Errorf("bad address %q", a)
					}
					cfg.addresses = append(cfg.addresses, ip)
				}
			case "dns":
				for _, a := range list(v) {
					if ip, err := netip.ParseAddr(a); err == nil {
						cfg.dns = append(cfg.dns, ip)
					}
				}
			case "mtu":
				if n, err := strconv.Atoi(v); err == nil {
					cfg.mtu = n
				}
			}
		case "[peer]":
			if peer != nil {
				peer[k] = v
			}
		}
	}
	if err := flush(); err != nil {
		return nil, err
	}
	switch {
	case cfg.privateKey == nil:
		return nil, errors.New("[Interface] has no PrivateKey")
	case len(cfg.addresses) == 0:
		return nil, errors.New("[Interface] has no Address")
	case len(cfg.peers) == 0:
		return nil, errors.New("config has no [Peer]")
	}
	cfg.mtu = min(max(cfg.mtu, 1280), 1420)
	if len(cfg.dns) == 0 {
		cfg.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	return cfg, nil
}

// uapi builds wireguard-go's IPC config; endpoint names are resolved here (outside the tunnel).
func (c *wgConfig) uapi() (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\nreplace_peers=true\n", hex.EncodeToString(c.privateKey))
	for _, p := range c.peers {
		ip := p.host
		if _, err := netip.ParseAddr(ip); err != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			addrs, err := net.DefaultResolver.LookupHost(ctx, p.host)
			cancel()
			if err != nil || len(addrs) == 0 {
				return "", fmt.Errorf("can't resolve VPN server %s", p.host)
			}
			ip = addrs[0]
		}
		fmt.Fprintf(&b, "public_key=%s\n", hex.EncodeToString(p.publicKey))
		if p.presharedKey != nil {
			fmt.Fprintf(&b, "preshared_key=%s\n", hex.EncodeToString(p.presharedKey))
		}
		fmt.Fprintf(&b, "endpoint=%s\npersistent_keepalive_interval=%d\nreplace_allowed_ips=true\n", net.JoinHostPort(ip, strconv.Itoa(p.port)), p.keepalive)
		for _, a := range p.allowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String(), nil
}

func startWireGuard(conf string) (*wgTunnel, error) {
	cfg, err := parseWgQuick(conf)
	if err != nil {
		return nil, err
	}
	u, err := cfg.uapi()
	if err != nil {
		return nil, err
	}
	t, n, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return nil, err
	}
	d := device.NewDevice(t, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wg: "))
	if err := d.IpcSet(u); err != nil {
		d.Close()
		return nil, fmt.Errorf("invalid WireGuard config: %v", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return nil, err
	}
	return &wgTunnel{dev: d, net: n}, nil
}

func (w *wgTunnel) stop() { w.dev.Close() }

// handshake: latest handshake over all peers (zero if none yet).
func (w *wgTunnel) handshake() time.Time {
	s, err := w.dev.IpcGet()
	if err != nil {
		return time.Time{}
	}
	var latest int64
	for _, line := range strings.Split(s, "\n") {
		if k, v, ok := strings.Cut(line, "="); ok && k == "last_handshake_time_sec" {
			if n, _ := strconv.ParseInt(v, 10, 64); n > latest {
				latest = n
			}
		}
	}
	if latest == 0 {
		return time.Time{}
	}
	return time.Unix(latest, 0)
}

// ping sends one ICMP echo through the tunnel and returns the round trip in ms.
func (w *wgTunnel) ping(target string) (int, error) {
	addr, err := netip.ParseAddr(target)
	if err != nil {
		return 0, err
	}
	c, err := w.net.DialPingAddr(netip.Addr{}, addr)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	var typ icmp.Type = ipv4.ICMPTypeEcho
	proto := 1
	if addr.Is6() {
		typ, proto = ipv6.ICMPTypeEchoRequest, 58
	}
	msg := icmp.Message{Type: typ, Body: &icmp.Echo{ID: 0x5250, Seq: int(time.Now().UnixNano() & 0x7fff), Data: []byte("resproxy")}}
	b, err := msg.Marshal(nil)
	if err != nil {
		return 0, err
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	start := time.Now()
	if _, err := c.Write(b); err != nil {
		return 0, err
	}
	buf := make([]byte, 1500)
	for {
		k, err := c.Read(buf)
		if err != nil {
			return 0, err
		}
		m, err := icmp.ParseMessage(proto, buf[:k])
		if err == nil && (m.Type == ipv4.ICMPTypeEchoReply || m.Type == ipv6.ICMPTypeEchoReply) {
			return int(time.Since(start).Milliseconds()), nil
		}
	}
}
