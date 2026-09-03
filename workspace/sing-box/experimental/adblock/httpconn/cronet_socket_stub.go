//go:build with_adblock && with_adblock_cronet && !unix

package httpconn

import (
	"net"

	E "github.com/sagernet/sing/common/exceptions"
)

func createCronetSocketPair() (int, net.Conn, error) {
	return -1, nil, E.New("adblock Cronet socketpair is not supported on this platform")
}

func createCronetTCPBridge() (int, net.Conn, error) {
	return -1, nil, E.New("adblock Cronet TCP bridge is not supported on this platform")
}

func createCronetPacketSocketPair() (int, net.PacketConn, error) {
	return -1, nil, E.New("adblock Cronet packet socketpair is not supported on this platform")
}
