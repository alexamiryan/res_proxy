package tailnet

import (
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
)

// Android doesn't let apps enumerate interfaces through netlink (Go's net.Interfaces fails since API 30), which tsnet
// needs to find its local endpoints. The app reports them from Java's NetworkInterface instead (what the Tailscale app
// does), and as a fallback the source address of an outbound route is used.

var (
	ifMu   sync.Mutex
	ifList []netmon.Interface
	ifSet  bool
)

func init() {
	netmon.RegisterInterfaceGetter(interfaces)
}

// SetInterfaces takes the app's interface list, one per line: `name index mtu flags addr/prefix…` where flags is a
// combination of `u` (up), `l` (loopback), `p` (point-to-point), `m` (multicast). The VPN's own TUN must be left out.
func SetInterfaces(spec string) {
	var list []netmon.Interface
	for _, line := range strings.Split(spec, "\n") {
		f := strings.Fields(line)
		if len(f) < 4 {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		var flags net.Flags
		for _, c := range f[3] {
			switch c {
			case 'u':
				flags |= net.FlagUp | net.FlagRunning
			case 'l':
				flags |= net.FlagLoopback
			case 'p':
				flags |= net.FlagPointToPoint
			case 'm':
				flags |= net.FlagMulticast
			}
		}
		var addrs []net.Addr
		for _, a := range f[4:] {
			p, err := netip.ParsePrefix(a)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
		}
		list = append(list, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	ifMu.Lock()
	ifList, ifSet = list, true
	ifMu.Unlock()
}

func interfaces() ([]netmon.Interface, error) {
	ifMu.Lock()
	list, set := ifList, ifSet
	ifMu.Unlock()
	if set && len(list) > 0 {
		return list, nil
	}
	if ifs, err := net.Interfaces(); err == nil {
		out := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			out[i].Interface = &ifs[i]
		}
		return out, nil
	}
	return synthetic(), nil
}

// synthetic is one made-up interface carrying the outbound source addresses (dialing UDP sends nothing).
func synthetic() []netmon.Interface {
	var addrs []net.Addr
	for _, t := range [][2]string{{"udp4", "8.8.8.8:53"}, {"udp6", "[2001:4860:4860::8888]:53"}} {
		c, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial(t[0], t[1])
		if err != nil {
			continue
		}
		if ua, ok := c.LocalAddr().(*net.UDPAddr); ok && !ua.IP.IsLoopback() && !ua.IP.IsUnspecified() {
			bits := 32
			if ua.IP.To4() == nil {
				bits = 128
			}
			addrs = append(addrs, &net.IPNet{IP: ua.IP, Mask: net.CIDRMask(bits, bits)})
		}
		c.Close()
	}
	if len(addrs) == 0 {
		return nil
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}
}
