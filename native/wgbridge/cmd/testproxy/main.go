// testproxy runs local HTTP-CONNECT and SOCKS5 proxies with auth, standing in for a residential proxy when
// testing the Chrome extension by hand:
//
//	go run ./cmd/testproxy -http 127.0.0.1:18080 -socks 127.0.0.1:11080 -user u -pass p
//
// Any password starting with -pass is accepted (session suffixes vary); each login is printed.
package main

import (
	"flag"
	"log"
	"time"

	"resproxy/wgbridge/internal/testproxy"
)

func main() {
	httpAddr := flag.String("http", "127.0.0.1:18080", "HTTP proxy address")
	socksAddr := flag.String("socks", "127.0.0.1:11080", "SOCKS5 proxy address")
	user := flag.String("user", "u", "username")
	pass := flag.String("pass", "p", "password prefix")
	flag.Parse()
	h, err := testproxy.Start(*httpAddr, "http", *user, *pass)
	if err != nil {
		log.Fatal(err)
	}
	s, err := testproxy.Start(*socksAddr, "socks5", *user, *pass)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("HTTP proxy on %s, SOCKS5 on %s", h.Addr(), s.Addr())
	seen := map[*testproxy.Proxy]int{}
	for range time.Tick(500 * time.Millisecond) {
		for _, p := range []*testproxy.Proxy{h, s} {
			used := p.Used()
			for _, u := range used[seen[p]:] {
				log.Printf("%s login %s", p.Addr(), u)
			}
			seen[p] = len(used)
		}
	}
}
