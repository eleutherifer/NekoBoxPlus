//go:build !darwin && !ios

package netbind

import "net"

func dialUDPBound(network string, laddr, raddr *net.UDPAddr, _ string) (*net.UDPConn, error) {
	return net.DialUDP(network, laddr, raddr)
}

func listenUDPBound(network string, laddr *net.UDPAddr, _ string) (*net.UDPConn, error) {
	return net.ListenUDP(network, laddr)
}
