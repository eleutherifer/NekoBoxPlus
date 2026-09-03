//go:build linux || android

package nativeclient

import (
	"context"
	"fmt"
	"net"
	"syscall"
)

func protectedDialUDP(protect ProtectFunc) func(string, *net.UDPAddr, *net.UDPAddr) (*net.UDPConn, error) {
	return func(network string, laddr, raddr *net.UDPAddr) (*net.UDPConn, error) {
		if raddr == nil {
			return nil, fmt.Errorf("remote UDP address is required")
		}
		dialer := net.Dialer{
			LocalAddr: laddr,
			Control:   protectControl(protect),
		}
		conn, err := dialer.DialContext(context.Background(), network, raddr.String())
		if err != nil {
			return nil, err
		}
		udpConn, ok := conn.(*net.UDPConn)
		if !ok {
			_ = conn.Close()
			return nil, fmt.Errorf("unexpected UDP connection type %T", conn)
		}
		return udpConn, nil
	}
}

func protectedListenUDP(protect ProtectFunc) func(string, *net.UDPAddr) (*net.UDPConn, error) {
	return func(network string, laddr *net.UDPAddr) (*net.UDPConn, error) {
		listener := net.ListenConfig{
			Control: protectControl(protect),
		}
		conn, err := listener.ListenPacket(context.Background(), network, udpAddress(laddr))
		if err != nil {
			return nil, err
		}
		udpConn, ok := conn.(*net.UDPConn)
		if !ok {
			_ = conn.Close()
			return nil, fmt.Errorf("unexpected UDP packet connection type %T", conn)
		}
		return udpConn, nil
	}
}

func udpAddress(addr *net.UDPAddr) string {
	if addr == nil {
		return ""
	}
	return addr.String()
}

func protectControl(protect ProtectFunc) func(string, string, syscall.RawConn) error {
	return func(_, _ string, raw syscall.RawConn) error {
		if protect == nil {
			return nil
		}
		var protectErr error
		err := raw.Control(func(fd uintptr) {
			if !protect(int32(fd)) {
				protectErr = fmt.Errorf("protect fd %d returned false", fd)
			}
		})
		if err != nil {
			return err
		}
		return protectErr
	}
}
