// wgtestserver is a throwaway WireGuard peer for testing ResProxy's VPN chaining on an emulator.
// It answers every DNS query inside the tunnel with its own tunnel IP and relays TCP on the
// given ports to local targets, then prints a client .conf to import into the app.
//
//	go run ./cmd/wgtestserver -forward 21080=127.0.0.1:21080
package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair() (priv, pub []byte) {
	priv = make([]byte, 32)
	rand.Read(priv)
	priv[0] &= 248
	priv[31] = priv[31]&127 | 64
	pub, _ = curve25519.X25519(priv, curve25519.Basepoint)
	return
}

func main() {
	port := flag.Int("port", 51820, "UDP listen port")
	endpoint := flag.String("endpoint", "10.0.2.2", "address the client uses to reach this server")
	forwards := flag.String("forward", "21080=127.0.0.1:21080", "comma-separated tunnelPort=target")
	flag.Parse()

	sPriv, sPub := keypair()
	cPriv, cPub := keypair()
	server := netip.MustParseAddr("10.9.0.1")
	tun, tnet, err := netstack.CreateNetTUN([]netip.Addr{server}, nil, 1420)
	if err != nil {
		log.Fatal(err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wg: "))
	uapi := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.9.0.2/32\n",
		hex.EncodeToString(sPriv), *port, hex.EncodeToString(cPub))
	if err := dev.IpcSet(uapi); err != nil {
		log.Fatal(err)
	}
	dev.Up()

	go serveDNS(tnet, server)
	for _, f := range strings.Split(*forwards, ",") {
		p, target, _ := strings.Cut(f, "=")
		n, _ := strconv.Atoi(p)
		ln, err := tnet.ListenTCP(&net.TCPAddr{IP: net.IP(server.AsSlice()), Port: n})
		if err != nil {
			log.Fatal(err)
		}
		go func() {
			for {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				go relay(c, target)
			}
		}()
	}

	b64 := base64.StdEncoding.EncodeToString
	fmt.Printf("[Interface]\nPrivateKey = %s\nAddress = 10.9.0.2/32\nDNS = 10.9.0.1\n\n[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0\nEndpoint = %s:%d\n",
		b64(cPriv), b64(sPub), *endpoint, *port)
	log.Printf("listening on udp :%d", *port)
	select {}
}

func serveDNS(tnet *netstack.Net, ip netip.Addr) {
	pc, err := tnet.ListenUDP(&net.UDPAddr{IP: net.IP(ip.AsSlice()), Port: 53})
	if err != nil {
		log.Fatal(err)
	}
	buf := make([]byte, 1500)
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		var m dnsmessage.Message
		if m.Unpack(buf[:n]) != nil || len(m.Questions) == 0 {
			continue
		}
		q := m.Questions[0]
		log.Printf("DNS in tunnel: %s", q.Name)
		m.Header.Response, m.Header.RecursionAvailable = true, true
		if q.Type == dnsmessage.TypeA {
			m.Answers = []dnsmessage.Resource{{
				Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
				Body:   &dnsmessage.AResource{A: ip.As4()},
			}}
		}
		out, _ := m.Pack()
		pc.WriteTo(out, addr)
	}
}

func relay(c net.Conn, target string) {
	defer c.Close()
	log.Printf("TCP in tunnel -> %s", target)
	r, err := net.Dial("tcp", target)
	if err != nil {
		log.Printf("dial %s: %v", target, err)
		return
	}
	defer r.Close()
	go io.Copy(r, c)
	io.Copy(c, r)
}
