//go:build !linux && !android

package nativeclient

import "net"

func protectedDialUDP(protect ProtectFunc) func(string, *net.UDPAddr, *net.UDPAddr) (*net.UDPConn, error) {
	return net.DialUDP
}

func protectedListenUDP(protect ProtectFunc) func(string, *net.UDPAddr) (*net.UDPConn, error) {
	return net.ListenUDP
}
