package adapter

import (
	"context"
	"net"

	N "github.com/sagernet/sing/common/network"
)

type V2RayServerTransport interface {
	Network() []string
	Serve(listener net.Listener) error
	ServePacket(listener net.PacketConn) error
	Close() error
}

type V2RayServerTransportHandler interface {
	N.TCPConnectionHandlerEx
}

type V2RayClientTransport interface {
	DialContext(ctx context.Context) (net.Conn, error)
	Close() error
}

type V2RayClientTransportResetter interface {
	Reset() error
}

func ResetV2RayClientTransport(transport V2RayClientTransport) error {
	if resetter, ok := transport.(V2RayClientTransportResetter); ok {
		return resetter.Reset()
	}
	return transport.Close()
}
