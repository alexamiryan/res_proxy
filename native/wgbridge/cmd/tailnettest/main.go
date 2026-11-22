// tailnettest runs a throwaway tailnet for testing ResProxy's tailnet support by hand, without a Tailscale account:
// a coordination server (tailscale's testcontrol, which approves every node), DERP + STUN, and one device "hostpeer"
// that serves HTTP on port 8080.
//
//	go run ./cmd/tailnettest -ip 192.168.1.10
//
// -ip must be an address of this machine the phone (or emulator) can reach. In ResProxy: Settings → Tailnet →
// Coordination server http://<ip>:9911, then connect. A line "dial <tailnet-ip>:<port>" on stdin makes hostpeer open a
// connection to that address, send "ping" and print what comes back (tests reaching the phone from the tailnet).
package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
)

// tb lets the test helpers run in a plain program.
type tb struct{ testing.TB }

func (tb) Helper()                   {}
func (tb) Logf(f string, a ...any)   { log.Printf(f, a...) }
func (tb) Fatal(a ...any)            { log.Fatal(a...) }
func (tb) Fatalf(f string, a ...any) { log.Fatalf(f, a...) }
func (tb) Errorf(f string, a ...any) { log.Printf("error: "+f, a...) }
func (tb) Cleanup(func())            {}
func (tb) Name() string              { return "tailnettest" }

func main() {
	ip := flag.String("ip", "", "address of this machine that the phone can reach (required)")
	port := flag.Int("port", 9911, "coordination server port")
	verbose := flag.Bool("v", false, "log tsnet and control details")
	flag.Parse()
	if *ip == "" {
		log.Fatal("-ip is required")
	}
	netns.SetEnabled(false)
	quiet := func(string, ...any) {}
	logf := quiet
	if *verbose {
		logf = log.Printf
	}

	derpMap := integration.RunDERPAndSTUN(tb{}, logf, *ip)
	// Printed for `adb reverse` (a phone on mobile data reaches this machine only over USB: -ip 127.0.0.1).
	for _, r := range derpMap.Regions {
		for _, n := range r.Nodes {
			log.Printf("DERP: %s:%d (STUN udp %d)", n.HostName, n.DERPPort, n.STUNPort)
		}
	}
	control := &testcontrol.Server{
		DERPMap:        derpMap,
		DNSConfig:      &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain: "tail-test.ts.net",
		Logf:           logf,
	}
	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", *ip, *port))
	if err != nil {
		log.Fatal(err)
	}
	go http.Serve(ln, control)
	controlURL := "http://" + ln.Addr().String()
	log.Printf("coordination server: %s", controlURL)

	dir, _ := os.MkdirTemp("", "tailnettest")
	defer os.RemoveAll(dir)
	peer := &tsnet.Server{
		Dir: filepath.Join(dir, "hostpeer"), Hostname: "hostpeer", ControlURL: controlURL,
		Ephemeral: true, Store: new(mem.Store), Logf: logf, UserLogf: logf,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	st, err := peer.Up(ctx)
	cancel()
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("hostpeer is up: %v", st.TailscaleIPs)
	web, err := peer.Listen("tcp", ":8080")
	if err != nil {
		log.Fatal(err)
	}
	go http.Serve(web, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("hostpeer: HTTP %s %s from %s", r.Method, r.URL, r.RemoteAddr)
		fmt.Fprintf(w, "hello from hostpeer\n")
	}))

	go func() {
		seen := map[string]bool{}
		for {
			time.Sleep(2 * time.Second)
			lc, err := peer.LocalClient()
			if err != nil {
				continue
			}
			s, err := lc.Status(context.Background())
			if err != nil {
				continue
			}
			for _, p := range s.Peer {
				k := fmt.Sprintf("%s %v online=%v", p.DNSName, p.TailscaleIPs, p.Online)
				if !seen[k] {
					seen[k] = true
					log.Printf("device: %s", k)
				}
			}
		}
	}()

	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		target, ok := strings.CutPrefix(strings.TrimSpace(sc.Text()), "dial ")
		if !ok {
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		c, err := peer.Dial(ctx, "tcp", target)
		cancel()
		if err != nil {
			log.Printf("dial %s: %v", target, err)
			continue
		}
		c.SetDeadline(time.Now().Add(10 * time.Second))
		c.Write([]byte("ping\n"))
		line, err := bufio.NewReader(c).ReadString('\n')
		c.Close()
		log.Printf("dial %s: got %q (err %v)", target, line, err)
	}
	select {}
}
