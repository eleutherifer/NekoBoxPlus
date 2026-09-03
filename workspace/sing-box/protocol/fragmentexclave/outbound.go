package fragmentexclave

import (
	"context"
	"net"
	"net/netip"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing-tun/ping"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.FragmentExclaveOutboundOptions](registry, C.TypeFragmentExclave, NewOutbound)
}

var _ adapter.FlowOutbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	ctx           context.Context
	logger        logger.ContextLogger
	dialer        dialer.ParallelInterfaceDialer
	fallbackDelay time.Duration
	splitRecord   bool
	splitPacket   bool
	icmpPort      *ping.Port
}

func NewOutbound(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options option.FragmentExclaveOutboundOptions) (adapter.Outbound, error) {
	options.UDPFragmentDefault = true
	if options.Detour != "" {
		return nil, E.New("`detour` is not supported in fragment-exclave context")
	}
	outboundDialer, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: true,
		DirectOutbound: true,
	})
	if err != nil {
		return nil, err
	}
	fragmentOutbound := &Outbound{
		Adapter:       outbound.NewAdapterWithDialerOptions(C.TypeFragmentExclave, tag, []string{N.NetworkTCP, N.NetworkUDP, N.NetworkICMP}, options.DialerOptions),
		ctx:           ctx,
		logger:        logger,
		dialer:        outboundDialer.(dialer.ParallelInterfaceDialer),
		fallbackDelay: time.Duration(options.FallbackDelay),
		splitRecord:   options.TLSRecordFragmentation,
		splitPacket:   options.TCPSegmentation,
	}
	if defaultDialer, isDefaultDialer := common.Cast[*dialer.DefaultDialer](fragmentOutbound.dialer); isDefaultDialer {
		fragmentOutbound.icmpPort = ping.NewPort(ctx, logger, func(destination netip.Addr) control.Func {
			return defaultDialer.DialerForICMPDestination(destination).Control
		}, 0)
	}
	return fragmentOutbound, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	conn, err := h.dialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	return h.wrapConn(network, conn), nil
}

func (h *Outbound) DialParallel(ctx context.Context, network string, destination M.Socksaddr, destinationAddresses []netip.Addr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	conn, err := dialer.DialParallelNetwork(ctx, h.dialer, network, destination, destinationAddresses, len(destinationAddresses) > 0 && destinationAddresses[0].Is6(), nil, nil, nil, h.fallbackDelay)
	if err != nil {
		return nil, err
	}
	return h.wrapConn(network, conn), nil
}

func (h *Outbound) DialParallelNetwork(ctx context.Context, network string, destination M.Socksaddr, destinationAddresses []netip.Addr, networkStrategy *C.NetworkStrategy, networkType []C.InterfaceType, fallbackNetworkType []C.InterfaceType, fallbackDelay time.Duration) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	conn, err := dialer.DialParallelNetwork(ctx, h.dialer, network, destination, destinationAddresses, len(destinationAddresses) > 0 && destinationAddresses[0].Is6(), networkStrategy, networkType, fallbackNetworkType, fallbackDelay)
	if err != nil {
		return nil, err
	}
	return h.wrapConn(network, conn), nil
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection")
	return h.dialer.ListenPacket(ctx, destination)
}

func (h *Outbound) PreMatchFlow(network string, destination netip.Addr) adapter.PreMatchAction {
	if network == N.NetworkICMP && h.icmpPort != nil {
		return adapter.PreMatchFlow
	}
	return adapter.PreMatchContinue
}

func (h *Outbound) PortAddresses() (netip.Addr, netip.Addr) {
	return h.icmpPort.PortAddresses()
}

func (h *Outbound) PortMTU() uint32 {
	return h.icmpPort.PortMTU()
}

func (h *Outbound) AttachReturn(returnPath tun.Return) error {
	return h.icmpPort.AttachReturn(returnPath)
}

func (h *Outbound) DetachReturn(returnPath tun.Return) error {
	return h.icmpPort.DetachReturn(returnPath)
}

func (h *Outbound) WritePackets(packets [][]byte) error {
	return h.icmpPort.WritePackets(packets)
}

func (h *Outbound) Close() error {
	if h.icmpPort != nil {
		return h.icmpPort.Close()
	}
	return nil
}

func (h *Outbound) ListenSerialNetworkPacket(ctx context.Context, destination M.Socksaddr, destinationAddresses []netip.Addr, networkStrategy *C.NetworkStrategy, networkType []C.InterfaceType, fallbackNetworkType []C.InterfaceType, fallbackDelay time.Duration) (net.PacketConn, netip.Addr, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection")
	return dialer.ListenSerialNetworkPacket(ctx, h.dialer, destination, destinationAddresses, networkStrategy, networkType, fallbackNetworkType, fallbackDelay)
}

func (h *Outbound) wrapConn(network string, conn net.Conn) net.Conn {
	if network != N.NetworkTCP || (!h.splitRecord && !h.splitPacket) {
		return conn
	}
	return NewTLSFragmentConn(conn, h.splitRecord, h.splitPacket)
}
