// Package main builds libwgbridge.so: userspace WireGuard (wireguard-go + gVisor netstack) for
// ResProxy. Instead of a TUN it exposes local TCP forwarders, so ResProxy can reach its upstream
// proxy through a WireGuard VPN while owning the only Android VpnService itself.
package main

/*
#cgo LDFLAGS: -llog
#include <jni.h>
#include <stdlib.h>

static const char* rp_get(JNIEnv* env, jstring s) { return (*env)->GetStringUTFChars(env, s, NULL); }
static void rp_rel(JNIEnv* env, jstring s, const char* c) { (*env)->ReleaseStringUTFChars(env, s, c); }
static jstring rp_new(JNIEnv* env, const char* c) { return (*env)->NewStringUTF(env, c); }
*/
import "C"

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type forwarder struct {
	ln     net.Listener
	target string
}

var (
	mu         sync.Mutex
	dev        *device.Device
	tnet       *netstack.Net
	forwarders = map[string]*forwarder{}
)

func goStr(env *C.JNIEnv, s C.jstring) string {
	c := C.rp_get(env, s)
	defer C.rp_rel(env, s, c)
	return C.GoString(c)
}

func jStr(env *C.JNIEnv, s string) C.jstring {
	c := C.CString(s)
	defer C.free(unsafe.Pointer(c))
	return C.rp_new(env, c)
}

func parseAddrs(csv string) ([]netip.Addr, error) {
	var out []netip.Addr
	for _, p := range strings.Split(csv, ",") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		if i := strings.IndexByte(p, '/'); i >= 0 {
			p = p[:i]
		}
		a, err := netip.ParseAddr(p)
		if err != nil {
			return nil, fmt.Errorf("bad address %q", p)
		}
		out = append(out, a)
	}
	return out, nil
}

func start(uapi, addresses, dns string, mtu int) error {
	stop()
	addrs, err := parseAddrs(addresses)
	if err != nil {
		return err
	}
	if len(addrs) == 0 {
		return fmt.Errorf("config has no interface address")
	}
	dnsAddrs, err := parseAddrs(dns)
	if err != nil {
		return err
	}
	if len(dnsAddrs) == 0 {
		dnsAddrs = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	t, n, err := netstack.CreateNetTUN(addrs, dnsAddrs, mtu)
	if err != nil {
		return err
	}
	d := device.NewDevice(t, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wg: "))
	if err := d.IpcSet(uapi); err != nil {
		d.Close()
		return fmt.Errorf("invalid WireGuard config: %v", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return err
	}
	dev, tnet = d, n
	return nil
}

func stop() {
	for k, f := range forwarders {
		f.ln.Close()
		delete(forwarders, k)
	}
	if dev != nil {
		dev.Close()
		dev, tnet = nil, nil
	}
}

// forward returns a 127.0.0.1 port whose connections are relayed to target (host:port) through
// the tunnel. Hostnames are resolved by the VPN's DNS servers, inside the tunnel.
func forward(target string) (int, error) {
	if tnet == nil {
		return 0, fmt.Errorf("WireGuard is not running")
	}
	if f, ok := forwarders[target]; ok {
		return f.ln.Addr().(*net.TCPAddr).Port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	f := &forwarder{ln: ln, target: target}
	forwarders[target] = f
	n := tnet
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go relay(n, c, target)
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func relay(n *netstack.Net, c net.Conn, target string) {
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	r, err := n.DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		return
	}
	defer r.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(r, c); closeWrite(r); done <- struct{}{} }()
	go func() { io.Copy(c, r); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
	}
}

// status returns "handshakeUnixSec,rxBytes,txBytes" summed over peers.
func status() string {
	if dev == nil {
		return ""
	}
	s, err := dev.IpcGet()
	if err != nil {
		return ""
	}
	var hs, rx, tx int64
	for _, line := range strings.Split(s, "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "last_handshake_time_sec":
			if n > hs {
				hs = n
			}
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		}
	}
	return fmt.Sprintf("%d,%d,%d", hs, rx, tx)
}

//export Java_dev_resproxy_net_WireGuard_nativeStart
func Java_dev_resproxy_net_WireGuard_nativeStart(env *C.JNIEnv, cls C.jclass, uapi, addresses, dns C.jstring, mtu C.jint) C.jstring {
	mu.Lock()
	defer mu.Unlock()
	if err := start(goStr(env, uapi), goStr(env, addresses), goStr(env, dns), int(mtu)); err != nil {
		return jStr(env, err.Error())
	}
	return jStr(env, "")
}

//export Java_dev_resproxy_net_WireGuard_nativeForward
func Java_dev_resproxy_net_WireGuard_nativeForward(env *C.JNIEnv, cls C.jclass, target C.jstring) C.jint {
	mu.Lock()
	defer mu.Unlock()
	p, err := forward(goStr(env, target))
	if err != nil {
		return -1
	}
	return C.jint(p)
}

//export Java_dev_resproxy_net_WireGuard_nativeStop
func Java_dev_resproxy_net_WireGuard_nativeStop(env *C.JNIEnv, cls C.jclass) {
	mu.Lock()
	defer mu.Unlock()
	stop()
}

//export Java_dev_resproxy_net_WireGuard_nativeStatus
func Java_dev_resproxy_net_WireGuard_nativeStatus(env *C.JNIEnv, cls C.jclass) C.jstring {
	mu.Lock()
	defer mu.Unlock()
	return jStr(env, status())
}

func main() {}
