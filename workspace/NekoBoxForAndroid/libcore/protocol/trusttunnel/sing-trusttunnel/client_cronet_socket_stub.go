//go:build with_trusttunnel_cronet && !unix

package trusttunnel

import (
	"net"

	E "github.com/sagernet/sing/common/exceptions"
)

func createSocketPair() (int, net.Conn, error) {
	return -1, nil, E.New("TrustTunnel Cronet socketpair is not supported on this platform")
}

func createPacketSocketPair(forceUDPLoopback bool) (int, net.PacketConn, error) {
	return -1, nil, E.New("TrustTunnel Cronet packet socketpair is not supported on this platform")
}
