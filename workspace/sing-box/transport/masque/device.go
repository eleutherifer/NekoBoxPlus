package masque

import (
	"context"
	"net/netip"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
	wgTun "github.com/sagernet/wireguard-go/tun"
)

type Device interface {
	wgTun.Device
	N.Dialer
	Start() error
	NewDirectRouteConnection(metadata adapter.InboundContext, routeContext tun.DirectRouteContext, timeout time.Duration) (tun.DirectRouteDestination, error)
}

type DeviceOptions struct {
	Context        context.Context
	Logger         logger.ContextLogger
	System         bool
	UDPTimeout     time.Duration
	CreateDialer   func(interfaceName string) N.Dialer
	Name           string
	MTU            uint32
	Address        []netip.Prefix
	AllowedAddress []netip.Prefix
}

func NewDevice(options DeviceOptions) (Device, error) {
	if !options.System {
		return newStackDevice(options)
	} else if !tun.WithGVisor {
		return newSystemDevice(options)
	} else {
		return newSystemStackDevice(options)
	}
}
