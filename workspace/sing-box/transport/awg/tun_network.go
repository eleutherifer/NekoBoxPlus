package awg

import (
	"context"
	"net"
	"net/netip"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing/common/metadata"
)

type networkTun struct {
	tun.Device
	conn        *netstack.Net
	connections *interrupt.Group
	events      chan tun.Event
}

func newNetworkTun(address []netip.Prefix, mtu uint32) (tunAdapter, error) {
	var localAddresses []netip.Addr
	for _, prefix := range address {
		localAddresses = append(localAddresses, prefix.Addr())
	}

	tunDevice, conn, err := netstack.CreateNetTUN(localAddresses, []netip.Addr{}, int(mtu))
	if err != nil {
		return nil, err
	}

	return &networkTun{
		Device:      tunDevice,
		conn:        conn,
		connections: interrupt.NewGroup(),
		events:      make(chan tun.Event),
	}, nil
}

// Hide the netstack constructor's synthetic EventUp. Only the lifecycle
// controller may bring the device up, particularly when starting in Doze.
func (t *networkTun) Events() <-chan tun.Event { return t.events }

func (t *networkTun) Start() error {
	return nil
}

func (t *networkTun) DialContext(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
	conn, err := t.conn.DialContext(ctx, network, destination.String())
	if err != nil {
		return nil, err
	}
	return t.connections.NewConn(conn, true), nil
}

func (t *networkTun) ListenPacket(ctx context.Context, destination metadata.Socksaddr) (net.PacketConn, error) {
	conn, err := t.conn.DialUDPAddrPort(netip.AddrPort{}, destination.AddrPort())
	if err != nil {
		return nil, err
	}
	return t.connections.NewPacketConn(conn, true), nil
}

func (t *networkTun) ResetConnections() {
	t.connections.Interrupt(true)
}

func (t *networkTun) Close() error {
	close(t.events)
	t.ResetConnections()
	return t.Device.Close()
}
