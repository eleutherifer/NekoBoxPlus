package direct

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"math/big"
	"net"
	"net/netip"
	"reflect"
	"strconv"
	"strings"
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
	"github.com/sagernet/sing/service"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.DirectOutboundOptions](registry, C.TypeDirect, NewOutbound)
}

var (
	_ N.ParallelDialer                = (*Outbound)(nil)
	_ dialer.ParallelNetworkDialer    = (*Outbound)(nil)
	_ dialer.DirectDialer             = (*Outbound)(nil)
	_ adapter.FlowOutbound            = (*Outbound)(nil)
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
)

type Outbound struct {
	outbound.Adapter
	ctx            context.Context
	logger         logger.ContextLogger
	network        adapter.NetworkManager
	dialer         dialer.ParallelInterfaceDialer
	domainStrategy C.DomainStrategy
	fallbackDelay  time.Duration
	isEmpty        bool
	myAddresses    common.TypedValue[[]netip.Prefix]
	fragment       *Fragment
	icmpPort       *ping.Port
}

type Fragment struct {
	MinInterval int32
	MaxInterval int32
	MinLength   int32
	MaxLength   int32
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.DirectOutboundOptions) (adapter.Outbound, error) {
	options.UDPFragmentDefault = true
	if options.Detour != "" {
		return nil, E.New("`detour` is not supported in direct context")
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
	outbound := &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(C.TypeDirect, tag, []string{N.NetworkTCP, N.NetworkUDP, N.NetworkICMP}, options.DialerOptions),
		ctx:     ctx,
		logger:  logger,
		network: service.FromContext[adapter.NetworkManager](ctx),
		//nolint:staticcheck
		domainStrategy: C.DomainStrategy(options.DomainStrategy),
		fallbackDelay:  time.Duration(options.FallbackDelay),
		dialer:         outboundDialer.(dialer.ParallelInterfaceDialer),
		isEmpty: reflect.DeepEqual(options.DialerOptions, option.DialerOptions{
			AbstractDialerOptions: option.AbstractDialerOptions{UDPFragmentDefault: true},
		}),
	}
	//nolint:staticcheck
	if options.ProxyProtocol != 0 {
		return nil, E.New("Proxy Protocol is deprecated and removed in sing-box 1.6.0")
	}
	if options.Fragment != nil {
		if len(options.Fragment.Interval) == 0 || len(options.Fragment.Length) == 0 {
			return nil, E.New("Invalid interval or length")
		}
		intervalMinMax := strings.Split(options.Fragment.Interval, "-")
		var minInterval, maxInterval int64
		var err, err2 error
		if len(intervalMinMax) == 2 {
			minInterval, err = strconv.ParseInt(intervalMinMax[0], 10, 64)
			maxInterval, err2 = strconv.ParseInt(intervalMinMax[1], 10, 64)
		} else {
			minInterval, err = strconv.ParseInt(intervalMinMax[0], 10, 64)
			maxInterval = minInterval
		}
		if err != nil {
			return nil, E.Cause(err, "Invalid minimum interval: ")
		}
		if err2 != nil {
			return nil, E.Cause(err2, "Invalid maximum interval: ")
		}

		lengthMinMax := strings.Split(options.Fragment.Length, "-")
		var minLength, maxLength int64
		if len(lengthMinMax) == 2 {
			minLength, err = strconv.ParseInt(lengthMinMax[0], 10, 64)
			maxLength, err2 = strconv.ParseInt(lengthMinMax[1], 10, 64)

		} else {
			minLength, err = strconv.ParseInt(lengthMinMax[0], 10, 64)
			maxLength = minLength
		}
		if err != nil {
			return nil, E.Cause(err, "Invalid minimum length: ")
		}
		if err2 != nil {
			return nil, E.Cause(err2, "Invalid maximum length: ")
		}

		if minInterval > maxInterval {
			minInterval, maxInterval = maxInterval, minInterval
		}
		if minLength > maxLength {
			minLength, maxLength = maxLength, minLength
		}

		outbound.fragment = &Fragment{
			MinInterval: int32(minInterval),
			MaxInterval: int32(maxInterval),
			MinLength:   int32(minLength),
			MaxLength:   int32(maxLength),
		}
		outbound.isEmpty = false
	}
	if defaultDialer, isDefaultDialer := common.Cast[*dialer.DefaultDialer](outbound.dialer); isDefaultDialer {
		outbound.icmpPort = ping.NewPort(ctx, logger, func(destination netip.Addr) control.Func {
			return defaultDialer.DialerForICMPDestination(destination).Control
		}, 0)
	}
	return outbound, nil
}

func (h *Outbound) Start(stage adapter.StartStage) error {
	switch stage {
	case adapter.StartStatePostStart, adapter.StartStateStarted:
		if len(h.myAddresses.Load()) == 0 {
			h.fetchMyAddresses()
		}
	}
	return nil
}

func (h *Outbound) fetchMyAddresses() {
	myInterfaceNames := h.network.InterfaceMonitor().MyInterfaces()
	if len(myInterfaceNames) == 0 {
		return
	}
	var (
		myAddresses []netip.Prefix
		found       bool
	)
	for _, myInterfaceName := range myInterfaceNames {
		myInterface, err := h.network.InterfaceFinder().ByName(myInterfaceName)
		if err != nil {
			continue
		}
		found = true
		myAddresses = append(myAddresses, myInterface.Addresses...)
	}
	if !found {
		return
	}
	h.myAddresses.Store(myAddresses)
}

func (h *Outbound) InterfaceUpdated(ctx context.Context) {
	h.fetchMyAddresses()
	if h.icmpPort != nil {
		h.icmpPort.Close()
	}
}

func (h *Outbound) isMyLoopbackAddress(addresses ...netip.Addr) bool {
	for _, prefix := range h.myAddresses.Load() {
		for _, address := range addresses {
			if !C.IsDarwin && prefix.Addr() == address {
				continue
			}
			if prefix.Contains(address) {
				return true
			}
		}
	}
	return false
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if h.isMyLoopbackAddress(destination.Addr) {
		return nil, E.New("loopback connection to TUN range")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	}
	/*conn, err := h.dialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	return h.loopBack.NewConn(conn), nil*/
	conn, err := h.dialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	if network == N.NetworkTCP && h.fragment != nil {
		conn = &FragmentedClientHelloConn{
			Conn:        conn,
			ctx:         ctx,
			logger:      h.logger,
			maxLength:   int(h.fragment.MaxLength),
			minInterval: time.Duration(h.fragment.MinInterval) * time.Millisecond,
			maxInterval: time.Duration(h.fragment.MaxInterval) * time.Millisecond,
		}
	}
	return conn, nil
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if h.isMyLoopbackAddress(destination.Addr) {
		return nil, E.New("loopback connection to TUN range")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection")
	conn, err := h.dialer.ListenPacket(ctx, destination)
	if err != nil {
		return nil, err
	}
	return conn, nil
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

func (h *Outbound) DialParallel(ctx context.Context, network string, destination M.Socksaddr, destinationAddresses []netip.Addr) (net.Conn, error) {
	if h.isMyLoopbackAddress(destinationAddresses...) {
		return nil, E.New("loopback connection to TUN range")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	}
	conn, err := dialer.DialParallelNetwork(ctx, h.dialer, network, destination, destinationAddresses, len(destinationAddresses) > 0 && destinationAddresses[0].Is6(), nil, nil, nil, h.fallbackDelay)
	if err != nil {
		return nil, err
	}
	if network == N.NetworkTCP && h.fragment != nil {
		conn = &FragmentedClientHelloConn{
			Conn:        conn,
			ctx:         ctx,
			logger:      h.logger,
			maxLength:   int(h.fragment.MaxLength),
			minInterval: time.Duration(h.fragment.MinInterval) * time.Millisecond,
			maxInterval: time.Duration(h.fragment.MaxInterval) * time.Millisecond,
		}
	}
	return conn, nil
}

func (h *Outbound) DialParallelNetwork(ctx context.Context, network string, destination M.Socksaddr, destinationAddresses []netip.Addr, networkStrategy *C.NetworkStrategy, networkType []C.InterfaceType, fallbackNetworkType []C.InterfaceType, fallbackDelay time.Duration) (net.Conn, error) {
	if h.isMyLoopbackAddress(destinationAddresses...) {
		return nil, E.New("loopback connection to TUN range")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	}
	conn, err := dialer.DialParallelNetwork(ctx, h.dialer, network, destination, destinationAddresses, len(destinationAddresses) > 0 && destinationAddresses[0].Is6(), networkStrategy, networkType, fallbackNetworkType, fallbackDelay)
	if err != nil {
		return nil, err
	}
	if network == N.NetworkTCP && h.fragment != nil {
		conn = &FragmentedClientHelloConn{
			Conn:        conn,
			ctx:         ctx,
			logger:      h.logger,
			maxLength:   int(h.fragment.MaxLength),
			minInterval: time.Duration(h.fragment.MinInterval) * time.Millisecond,
			maxInterval: time.Duration(h.fragment.MaxInterval) * time.Millisecond,
		}
	}
	return conn, nil
}

func (h *Outbound) ListenSerialNetworkPacket(ctx context.Context, destination M.Socksaddr, destinationAddresses []netip.Addr, networkStrategy *C.NetworkStrategy, networkType []C.InterfaceType, fallbackNetworkType []C.InterfaceType, fallbackDelay time.Duration) (net.PacketConn, netip.Addr, error) {
	if h.isMyLoopbackAddress(destinationAddresses...) {
		return nil, netip.Addr{}, E.New("loopback connection to TUN range")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection")
	conn, newDestination, err := dialer.ListenSerialNetworkPacket(ctx, h.dialer, destination, destinationAddresses, networkStrategy, networkType, fallbackNetworkType, fallbackDelay)
	if err != nil {
		return nil, netip.Addr{}, err
	}
	return conn, newDestination, nil
}

func (h *Outbound) IsEmpty() bool {
	return h.isEmpty
}

/*func (h *Outbound) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {
	if h.loopBack.CheckConn(metadata.Source.AddrPort(), M.AddrPortFromNet(conn.LocalAddr())) {
		return E.New("reject loopback connection to ", metadata.Destination)
	}
	return NewConnection(ctx, h, conn, metadata)
}

func (h *Outbound) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext) error {
	if h.loopBack.CheckPacketConn(metadata.Source.AddrPort(), M.AddrPortFromNet(conn.LocalAddr())) {
		return E.New("reject loopback packet connection to ", metadata.Destination)
	}
	return NewPacketConnection(ctx, h, conn, metadata)
}
*/

// stolen from github.com/xtls/xray-core/transport/internet/reality
func randBetween(left int64, right int64) int64 {
	if left == right {
		return left
	}
	bigInt, _ := rand.Int(rand.Reader, big.NewInt(right-left))
	return left + bigInt.Int64()
}

type FragmentedClientHelloConn struct {
	net.Conn
	ctx         context.Context
	logger      log.ContextLogger
	PacketCount int
	minLength   int
	maxLength   int
	minInterval time.Duration
	maxInterval time.Duration
}

func (c *FragmentedClientHelloConn) Write(b []byte) (n int, err error) {
	if c.PacketCount == 0 {
		if len(b) >= 5 && b[0] == 22 {
			n, err = sendFragmentedClientHello(c, b, c.minLength, c.maxLength)
		} else {
			n, err = c.Conn.Write(b)
		}

		if err == nil {
			c.PacketCount++
		}

		return
	}

	return c.Conn.Write(b)
}

func (c *FragmentedClientHelloConn) Upstream() any {
	return c.Conn
}

func sendFragmentedClientHello(conn *FragmentedClientHelloConn, clientHello []byte, minFragmentSize, maxFragmentSize int) (n int, err error) {
	if len(clientHello) < 5 || clientHello[0] != 22 {
		return 0, E.New("not a valid TLS ClientHello message")
	}

	clientHelloLen := (int(clientHello[3]) << 8) | int(clientHello[4])
	if conn.logger != nil {
		conn.logger.InfoContext(conn.ctx, "Sending fragmented TLS client hello: ", clientHelloLen)
	}

	clientHelloData := clientHello[5:]
	i := 0
	for {
		fragmentEnd := i + int(randBetween(int64(minFragmentSize), int64(maxFragmentSize)))
		if fragmentEnd > clientHelloLen {
			fragmentEnd = clientHelloLen
		}

		fragment := clientHelloData[i:fragmentEnd]
		i = fragmentEnd

		err = writeFragmentedRecord(conn, 22, fragment, clientHello)
		if err != nil {
			return 0, err
		}

		if i >= clientHelloLen {
			break
		}

		randomInterval := randBetween(int64(conn.minInterval), int64(conn.maxInterval))
		if randomInterval > 0 {
			time.Sleep(time.Duration(randomInterval))
		}
	}

	return len(clientHello), nil
}

func writeFragmentedRecord(c *FragmentedClientHelloConn, contentType uint8, data []byte, clientHello []byte) error {
	header := make([]byte, 5)
	header[0] = byte(clientHello[0])

	tlsVersion := (int(clientHello[1]) << 8) | int(clientHello[2])
	binary.BigEndian.PutUint16(header[1:], uint16(tlsVersion))

	binary.BigEndian.PutUint16(header[3:], uint16(len(data)))
	_, err := c.Conn.Write(append(header, data...))

	return err
}
