package masque

import (
	"net"
	"net/netip"
	"time"

	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/tls"
)

type TunnelOptions struct {
	System                  bool
	Name                    string
	CreateDialer            func(interfaceName string) N.Dialer
	Dialer                  N.Dialer
	Address                 []netip.Prefix
	AllowedAddress          []netip.Prefix
	H3Endpoint              *net.UDPAddr
	H2Endpoint              *net.TCPAddr
	TLSConfig               tls.Config
	Transport               string
	H3FallbackTimeout       time.Duration
	MTU                     uint32
	UDPTimeout              time.Duration
	UDPKeepalivePeriod      time.Duration
	UDPInitialPacketSize    uint16
	DisablePathMTUDiscovery bool
	ReconnectDelay          time.Duration
}
