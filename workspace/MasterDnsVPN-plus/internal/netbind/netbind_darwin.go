//go:build darwin || ios

package netbind

import (
	"context"
	"errors"
	"net"
	"syscall"

	"golang.org/x/sys/unix"
)

func dialUDPBound(network string, laddr, raddr *net.UDPAddr, ifname string) (*net.UDPConn, error) {
	if raddr == nil {
		return nil, errors.New("netbind: remote UDP address is required")
	}
	network = udpNetwork(network, raddr, laddr)
	dialer := net.Dialer{
		LocalAddr: laddr,
		Control:   bindControl(network, ifname),
	}
	conn, err := dialer.DialContext(context.Background(), network, raddr.String())
	if err != nil {
		return nil, err
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, errors.New("netbind: dialer returned non-UDP connection")
	}
	return udpConn, nil
}

func listenUDPBound(network string, laddr *net.UDPAddr, ifname string) (*net.UDPConn, error) {
	network = udpNetwork(network, laddr, nil)
	listener := net.ListenConfig{
		Control: bindControl(network, ifname),
	}
	conn, err := listener.ListenPacket(context.Background(), network, udpAddress(laddr))
	if err != nil {
		return nil, err
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, errors.New("netbind: listener returned non-UDP packet connection")
	}
	return udpConn, nil
}

func udpAddress(addr *net.UDPAddr) string {
	if addr == nil {
		return ""
	}
	return addr.String()
}

func udpNetwork(network string, primary, fallback *net.UDPAddr) string {
	switch network {
	case "udp4", "udp6":
		return network
	}
	addr := primary
	if addr == nil || addr.IP == nil || addr.IP.IsUnspecified() {
		addr = fallback
	}
	if addr == nil || addr.IP == nil {
		return "udp4"
	}
	if addr.IP.To4() != nil {
		return "udp4"
	}
	return "udp6"
}

func bindControl(network string, ifname string) func(string, string, syscall.RawConn) error {
	return func(_, _ string, raw syscall.RawConn) error {
		if ifname == "" {
			return nil
		}
		iface, err := net.InterfaceByName(ifname)
		if err != nil {
			return err
		}
		idx := iface.Index

		var bindErr error
		err = raw.Control(func(fd uintptr) {
			switch network {
			case "udp6":
				bindErr = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_BOUND_IF, idx)
			default:
				bindErr = unix.SetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_BOUND_IF, idx)
			}
		})
		if err != nil {
			return err
		}
		return bindErr
	}
}
