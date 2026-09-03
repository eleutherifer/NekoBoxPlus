//go:build !with_gvisor

package trusttunnel

import (
	"context"
	"net/netip"

	"github.com/sagernet/sing-box/log"
	tun "github.com/sagernet/sing-tun"

	trusttunnel "libcore/protocol/trusttunnel/sing-trusttunnel"
)

const withGvisor = false

type pingAdapter struct{}

func newPingAdapter(context.Context, log.ContextLogger, *trusttunnel.Client) *pingAdapter {
	return nil
}

func (*pingAdapter) PortAddresses() (netip.Addr, netip.Addr) { return netip.Addr{}, netip.Addr{} }
func (*pingAdapter) PortMTU() uint32                         { return 0 }
func (*pingAdapter) AttachReturn(tun.Return) error           { return nil }
func (*pingAdapter) DetachReturn(tun.Return) error           { return nil }
func (*pingAdapter) WritePackets([][]byte) error             { return nil }
func (*pingAdapter) Reset() error                            { return nil }
func (*pingAdapter) Close() error                            { return nil }
