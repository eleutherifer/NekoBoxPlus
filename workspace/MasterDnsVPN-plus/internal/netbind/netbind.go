// Package netbind centralizes outbound UDP socket creation so sockets can be
// pinned to a specific physical interface and source address on Darwin/iOS.
package netbind

import (
	"net"
	"sync"
	"sync/atomic"
)

var (
	iface       atomic.Pointer[string]
	addrV4      atomic.Pointer[string]
	addrV6      atomic.Pointer[string]
	hooksMu     sync.Mutex
	hooks       = map[uint64]func(){}
	hookCounter uint64
)

func SetInterface(name string) {
	prev := iface.Load()
	previous := ""
	if prev != nil {
		previous = *prev
	}
	if previous == name {
		return
	}
	copy := name
	iface.Store(&copy)
	fireHooks()
}

func SetAddress(ipv4, ipv6 string) {
	changed := false

	if current := pointerValue(addrV4.Load()); current != ipv4 {
		value := ipv4
		addrV4.Store(&value)
		changed = true
	}
	if current := pointerValue(addrV6.Load()); current != ipv6 {
		value := ipv6
		addrV6.Store(&value)
		changed = true
	}

	if changed {
		fireHooks()
	}
}

func Current() string {
	return pointerValue(iface.Load())
}

func CurrentIPv4() string {
	return pointerValue(addrV4.Load())
}

func CurrentIPv6() string {
	return pointerValue(addrV6.Load())
}

type HookHandle uint64

func OnChange(fn func()) HookHandle {
	if fn == nil {
		return 0
	}
	hooksMu.Lock()
	hookCounter++
	id := hookCounter
	hooks[id] = fn
	hooksMu.Unlock()
	return HookHandle(id)
}

func RemoveHook(h HookHandle) {
	if h == 0 {
		return
	}
	hooksMu.Lock()
	delete(hooks, uint64(h))
	hooksMu.Unlock()
}

func fireHooks() {
	hooksMu.Lock()
	snapshot := make([]func(), 0, len(hooks))
	for _, fn := range hooks {
		snapshot = append(snapshot, fn)
	}
	hooksMu.Unlock()

	for _, fn := range snapshot {
		fn()
	}
}

func DialUDP(network string, laddr, raddr *net.UDPAddr) (*net.UDPConn, error) {
	name := Current()
	boundLocal := localAddressForRemote(laddr, raddr)
	if name == "" && sameUDPAddr(laddr, boundLocal) {
		return net.DialUDP(network, boundLocal, raddr)
	}
	return dialUDPBound(network, boundLocal, raddr, name)
}

func ListenUDP(network string, laddr *net.UDPAddr) (*net.UDPConn, error) {
	name := Current()
	boundLocal := localAddressForListen(network, laddr)
	if name == "" && sameUDPAddr(laddr, boundLocal) {
		return net.ListenUDP(network, boundLocal)
	}
	return listenUDPBound(network, boundLocal, name)
}

func pointerValue(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func localAddressForRemote(laddr, raddr *net.UDPAddr) *net.UDPAddr {
	if laddr != nil && laddr.IP != nil && !laddr.IP.IsUnspecified() {
		return laddr
	}

	ip := CurrentIPv4()
	if raddr != nil && raddr.IP != nil && raddr.IP.To4() == nil {
		ip = CurrentIPv6()
	}
	if ip == "" {
		return laddr
	}

	parsed := net.ParseIP(ip)
	if parsed == nil {
		return laddr
	}

	if laddr == nil {
		return &net.UDPAddr{IP: parsed}
	}
	copy := *laddr
	copy.IP = parsed
	return &copy
}

func localAddressForListen(network string, laddr *net.UDPAddr) *net.UDPAddr {
	if laddr != nil && laddr.IP != nil && !laddr.IP.IsUnspecified() {
		return laddr
	}

	ip := listenSourceIP(network)
	if ip == "" {
		return laddr
	}

	parsed := net.ParseIP(ip)
	if parsed == nil {
		return laddr
	}

	if laddr == nil {
		return &net.UDPAddr{IP: parsed}
	}
	copy := *laddr
	copy.IP = parsed
	return &copy
}

func listenSourceIP(network string) string {
	switch network {
	case "udp6":
		return CurrentIPv6()
	case "udp4":
		return CurrentIPv4()
	default:
		if ip := CurrentIPv4(); ip != "" {
			return ip
		}
		return CurrentIPv6()
	}
}

func sameUDPAddr(a, b *net.UDPAddr) bool {
	if a == nil || b == nil {
		return a == b
	}
	return a.IP.Equal(b.IP) && a.Port == b.Port && a.Zone == b.Zone
}
