package awg

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"sync"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var _ conn.Bind = (*bind_adapter)(nil)

type bind_adapter struct {
	conn4           net.PacketConn
	conn6           net.PacketConn
	dialer          N.Dialer
	ctx             context.Context
	logger          logger.Logger
	mutex           sync.Mutex
	connect4Mutex   sync.Mutex
	connect6Mutex   sync.Mutex
	bindCtx         context.Context
	bindDone        context.CancelFunc
	lazy            bool
	port            uint16
	peerEndpoint    netip.AddrPort
	reserved        [3]uint8
	reservedForPeer map[netip.AddrPort][3]uint8
	hasReserved     bool
}

const (
	bindOperationTimeout = 5 * time.Second
	bindRetryInterval    = time.Second
)

func newBind(ctx context.Context, logger logger.Logger, dial N.Dialer, lazy bool, peerEndpoint netip.AddrPort, reserved [3]uint8, reservedForEndpoint map[netip.AddrPort][3]uint8) conn.Bind {
	hasReserved := reserved != [3]uint8{}
	if !hasReserved {
		for _, peerReserved := range reservedForEndpoint {
			if peerReserved != [3]uint8{} {
				hasReserved = true
				break
			}
		}
	}
	return &bind_adapter{
		ctx:             ctx,
		logger:          logger,
		dialer:          dial,
		lazy:            lazy,
		peerEndpoint:    peerEndpoint,
		reserved:        reserved,
		reservedForPeer: reservedForEndpoint,
		hasReserved:     hasReserved,
	}
}

func (b *bind_adapter) connect(ctx context.Context, addr netip.Addr, port uint16) (net.PacketConn, error) {
	if b.peerEndpoint.IsValid() && b.peerEndpoint.Addr() == addr {
		udpConn, err := b.dialer.DialContext(ctx, N.NetworkUDP, M.SocksaddrFromNetIP(b.peerEndpoint))
		if err != nil {
			return nil, err
		}
		return bufio.NewUnbindPacketConnWithAddr(udpConn, M.SocksaddrFromNetIP(b.peerEndpoint)), nil
	}
	return b.dialer.ListenPacket(ctx, M.Socksaddr{Addr: addr, Port: port})
}

func (b *bind_adapter) receive(ipv6 bool) conn.ReceiveFunc {
	return func(packets [][]byte, sizes []int, eps []conn.Endpoint) (n int, err error) {
		c, err := b.connection(ipv6)
		if err != nil {
			if b.waitForRetry() {
				b.logger.Error(E.Cause(err, "connect AmneziaWG UDP bind"))
				return 0, nil
			}
			return 0, net.ErrClosed
		}
		n, addr, err := c.ReadFrom(packets[0])
		if err != nil {
			b.invalidate(ipv6, c)
			if b.isClosed() {
				return 0, net.ErrClosed
			}
			b.logger.Error(E.Cause(err, "read AmneziaWG UDP bind"))
			return 0, nil
		}

		bindEp, err := b.ParseEndpoint(addr.String())
		if err != nil {
			return 0, E.Cause(err, "parse endpoint")
		}
		if b.hasReserved && n > 3 {
			buf := packets[0]
			buf[1], buf[2], buf[3] = 0, 0, 0
		}

		sizes[0] = n
		eps[0] = bindEp
		return 1, nil
	}
}

func (b *bind_adapter) Open(port uint16) (fns []conn.ReceiveFunc, actualPort uint16, err error) {
	b.mutex.Lock()
	if b.bindDone != nil {
		b.mutex.Unlock()
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	b.bindCtx, b.bindDone = context.WithCancel(b.ctx)
	b.port = port
	b.mutex.Unlock()

	if b.peerEndpoint.IsValid() {
		ipv6 := b.peerEndpoint.Addr().Is6()
		if !b.lazy {
			if _, err = b.connection(ipv6); err != nil {
				_ = b.Close()
				return nil, 0, E.Cause(err, "create single peer connection")
			}
		}
		return []conn.ReceiveFunc{b.receive(ipv6)}, port, nil
	}

	if b.lazy {
		return []conn.ReceiveFunc{b.receive(false), b.receive(true)}, port, nil
	}

	if _, err = b.connection(false); err != nil && !errors.Is(err, syscall.EAFNOSUPPORT) {
		_ = b.Close()
		return nil, 0, E.Cause(err, "create ipv4 connection")
	} else if err == nil {
		fns = append(fns, b.receive(false))
	}
	if _, err = b.connection(true); err != nil && !errors.Is(err, syscall.EAFNOSUPPORT) {
		_ = b.Close()
		return nil, 0, E.Cause(err, "create ipv6 connection")
	} else if err == nil {
		fns = append(fns, b.receive(true))
	}
	return fns, port, nil
}

func (b *bind_adapter) Close() error {
	b.mutex.Lock()
	if b.bindDone == nil {
		b.mutex.Unlock()
		return nil
	}
	b.bindDone()
	b.bindDone = nil
	b.bindCtx = nil
	conn4 := b.conn4
	conn6 := b.conn6
	b.conn4 = nil
	b.conn6 = nil
	b.mutex.Unlock()
	err4 := commonClosePacketConn(conn4)
	err6 := commonClosePacketConn(conn6)
	return errors.Join(err4, err6)
}

func commonClosePacketConn(conn net.PacketConn) error {
	if conn == nil {
		return nil
	}
	return ignoreClosedConnError(conn.Close())
}

func ignoreClosedConnError(err error) error {
	if errors.Is(err, net.ErrClosed) || errors.Is(err, os.ErrClosed) {
		return nil
	}
	return err
}

func (b *bind_adapter) SetMark(mark uint32) error {
	return nil
}

func (b *bind_adapter) Send(bufs [][]byte, ep conn.Endpoint) error {
	bindEp, ok := ep.(*bind_endpoint)
	if !ok {
		return errors.ErrUnsupported
	}
	ipv6 := bindEp.DstIP().Is6()
	packetConn, err := b.connection(ipv6)
	if err != nil {
		if !b.waitForRetry() {
			return net.ErrClosed
		}
		return err
	}

	udpAddr := &net.UDPAddr{
		IP:   bindEp.AddrPort.Addr().AsSlice(),
		Port: int(bindEp.AddrPort.Port()),
	}

	for _, buf := range bufs {
		if b.hasReserved && len(buf) > 3 {
			reserved, loaded := b.reservedForPeer[bindEp.AddrPort]
			if !loaded {
				reserved = b.reserved
			}
			copy(buf[1:4], reserved[:])
		}
		if err = packetConn.SetWriteDeadline(time.Now().Add(bindOperationTimeout)); err == nil {
			_, err = packetConn.WriteTo(buf, udpAddr)
		}
		if err != nil {
			b.invalidate(ipv6, packetConn)
			return err
		}
	}

	return nil
}

func (b *bind_adapter) connection(ipv6 bool) (net.PacketConn, error) {
	connectMutex := &b.connect4Mutex
	if ipv6 {
		connectMutex = &b.connect6Mutex
	}
	connectMutex.Lock()
	defer connectMutex.Unlock()

	b.mutex.Lock()
	var packetConn net.PacketConn
	if ipv6 {
		packetConn = b.conn6
	} else {
		packetConn = b.conn4
	}
	bindCtx := b.bindCtx
	port := b.port
	b.mutex.Unlock()
	if packetConn != nil {
		return packetConn, nil
	}
	if bindCtx == nil {
		return nil, net.ErrClosed
	}

	dialCtx, cancel := context.WithTimeout(bindCtx, bindOperationTimeout)
	defer cancel()
	address := netip.IPv4Unspecified()
	if ipv6 {
		address = netip.IPv6Unspecified()
	}
	if b.peerEndpoint.IsValid() {
		address = b.peerEndpoint.Addr()
	}
	packetConn, err := b.connect(dialCtx, address, port)
	if err != nil {
		return nil, err
	}

	b.mutex.Lock()
	if b.bindCtx != bindCtx {
		b.mutex.Unlock()
		_ = packetConn.Close()
		return nil, net.ErrClosed
	}
	if ipv6 {
		b.conn6 = packetConn
	} else {
		b.conn4 = packetConn
	}
	b.mutex.Unlock()
	return packetConn, nil
}

func (b *bind_adapter) invalidate(ipv6 bool, packetConn net.PacketConn) {
	b.mutex.Lock()
	if ipv6 {
		if b.conn6 == packetConn {
			b.conn6 = nil
		}
	} else if b.conn4 == packetConn {
		b.conn4 = nil
	}
	b.mutex.Unlock()
	_ = packetConn.Close()
}

func (b *bind_adapter) isClosed() bool {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	return b.bindDone == nil
}

func (b *bind_adapter) waitForRetry() bool {
	timer := time.NewTimer(bindRetryInterval)
	defer timer.Stop()
	b.mutex.Lock()
	bindCtx := b.bindCtx
	b.mutex.Unlock()
	if bindCtx == nil {
		return false
	}
	select {
	case <-bindCtx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func (b *bind_adapter) ParseEndpoint(s string) (conn.Endpoint, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, E.Cause(err, "parse addrport")
	}

	return &bind_endpoint{AddrPort: ap}, nil
}

func (b *bind_adapter) BatchSize() int {
	return 1
}

var _ conn.Endpoint = (*bind_endpoint)(nil)

type bind_endpoint struct {
	AddrPort netip.AddrPort
}

func (e bind_endpoint) ClearSrc() {
}

func (e bind_endpoint) SrcToString() string {
	return ""
}

func (e bind_endpoint) DstToString() string {
	return e.AddrPort.String()
}

func (e bind_endpoint) DstToBytes() []byte {
	b, err := e.AddrPort.MarshalBinary()
	if err != nil {
		return nil
	}
	return b
}

func (e bind_endpoint) DstIP() netip.Addr {
	return e.AddrPort.Addr()
}

func (e bind_endpoint) SrcIP() netip.Addr {
	return netip.Addr{}
}
