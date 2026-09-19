package masque

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing-tun/ping"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	wgTun "github.com/sagernet/wireguard-go/tun"
)

var _ Device = (*systemDevice)(nil)

type systemDevice struct {
	options        DeviceOptions
	dialer         N.Dialer
	device         tun.Tun
	batchDevice    tun.LinuxTUN
	events         chan wgTun.Event
	closeOnce      sync.Once
	inet4Address   netip.Addr
	inet6Address   netip.Addr
	packetOutbound chan *buf.Buffer
	rewriter       *ping.SourceRewriter
	writeBufs      [][]byte
}

func newSystemDevice(options DeviceOptions) (*systemDevice, error) {
	if options.Name == "" {
		options.Name = tun.CalculateInterfaceName("masque")
	}
	var inet4Address netip.Addr
	var inet6Address netip.Addr
	if len(options.Address) > 0 {
		if prefix := common.Find(options.Address, func(it netip.Prefix) bool {
			return it.Addr().Is4()
		}); prefix.IsValid() {
			inet4Address = prefix.Addr()
		}
	}
	if len(options.Address) > 0 {
		if prefix := common.Find(options.Address, func(it netip.Prefix) bool {
			return it.Addr().Is6()
		}); prefix.IsValid() {
			inet6Address = prefix.Addr()
		}
	}
	return &systemDevice{
		options:        options,
		dialer:         options.CreateDialer(options.Name),
		events:         make(chan wgTun.Event, 1),
		inet4Address:   inet4Address,
		inet6Address:   inet6Address,
		packetOutbound: make(chan *buf.Buffer, 256),
		rewriter:       ping.NewSourceRewriter(options.Context, options.Logger, inet4Address, inet6Address),
	}, nil
}

func (w *systemDevice) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return w.dialer.DialContext(ctx, network, destination)
}

func (w *systemDevice) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return w.dialer.ListenPacket(ctx, destination)
}

func (w *systemDevice) Inet4Address() netip.Addr {
	return w.inet4Address
}

func (w *systemDevice) Inet6Address() netip.Addr {
	return w.inet6Address
}

func (w *systemDevice) Start() error {
	networkManager := service.FromContext[adapter.NetworkManager](w.options.Context)
	tunOptions := tun.Options{
		Name: w.options.Name,
		Inet4Address: common.Filter(w.options.Address, func(it netip.Prefix) bool {
			return it.Addr().Is4()
		}),
		Inet6Address: common.Filter(w.options.Address, func(it netip.Prefix) bool {
			return it.Addr().Is6()
		}),
		MTU:            w.options.MTU,
		GSO:            true,
		InterfaceScope: true,
		Inet4RouteAddress: common.Filter(w.options.AllowedAddress, func(it netip.Prefix) bool {
			return it.Addr().Is4()
		}),
		Inet6RouteAddress: common.Filter(w.options.AllowedAddress, func(it netip.Prefix) bool {
			return it.Addr().Is6()
		}),
		InterfaceMonitor: networkManager.InterfaceMonitor(),
		InterfaceFinder:  networkManager.InterfaceFinder(),
		Logger:           w.options.Logger,
	}
	if runtime.GOOS == "darwin" {
		tunOptions.AutoRoute = true
	}
	tunInterface, err := tun.New(tunOptions)
	if err != nil {
		return err
	}
	err = tunInterface.Start()
	if err != nil {
		tunInterface.Close()
		return err
	}
	w.options.Logger.Debug("started at ", w.options.Name)
	w.device = tunInterface
	batchTUN, isBatchTUN := tunInterface.(tun.LinuxTUN)
	if isBatchTUN && batchTUN.BatchSize() > 1 {
		w.batchDevice = batchTUN
	}
	w.events <- wgTun.EventUp
	return nil
}

func (w *systemDevice) File() *os.File {
	return nil
}

func (w *systemDevice) Read(bufs [][]byte, sizes []int, offset int) (count int, err error) {
	select {
	case packet := <-w.packetOutbound:
		defer packet.Release()
		sizes[0] = copy(bufs[0][offset:], packet.Bytes())
		return 1, nil
	default:
	}
	if w.batchDevice != nil {
		count, err = w.batchDevice.BatchRead(bufs, offset-tun.PacketOffset, sizes)
	} else {
		sizes[0], err = w.device.Read(bufs[0][offset-tun.PacketOffset:])
		if err == nil {
			count = 1
		} else if errors.Is(err, tun.ErrTooManySegments) {
			err = wgTun.ErrTooManySegments
		}
	}
	return
}

func (w *systemDevice) Write(bufs [][]byte, offset int) (count int, err error) {
	w.writeBufs = w.writeBufs[:0]
	for _, packet := range bufs {
		handled, writeErr := w.rewriter.WriteBack(packet[offset:])
		if handled {
			if writeErr != nil {
				err = writeErr
				return
			}
			count++
		} else {
			w.writeBufs = append(w.writeBufs, packet)
		}
	}
	if len(w.writeBufs) == 0 {
		return
	}
	if w.batchDevice != nil {
		writeCount, writeErr := w.batchDevice.BatchWrite(w.writeBufs, offset)
		return count + writeCount, writeErr
	}
	for _, packet := range w.writeBufs {
		if tun.PacketOffset > 0 {
			clear(packet[offset-tun.PacketOffset : offset])
			tun.PacketFillHeader(packet[offset-tun.PacketOffset:], tun.PacketIPVersion(packet[offset:]))
		}
		_, err = w.device.Write(packet[offset-tun.PacketOffset:])
		if err != nil {
			return
		}
	}
	return
}

func (w *systemDevice) Flush() error {
	return nil
}

func (w *systemDevice) MTU() (int, error) {
	return int(w.options.MTU), nil
}

func (w *systemDevice) Name() (string, error) {
	return w.options.Name, nil
}

func (w *systemDevice) Events() <-chan wgTun.Event {
	return w.events
}

func (w *systemDevice) Close() error {
	var err error
	w.closeOnce.Do(func() {
		close(w.events)
		if w.device != nil {
			err = w.device.Close()
		}
	})
	return err
}

func (w *systemDevice) BatchSize() int {
	if w.batchDevice != nil {
		return w.batchDevice.BatchSize()
	}
	return 1
}

func (w *systemDevice) NewDirectRouteConnection(metadata adapter.InboundContext, routeContext tun.DirectRouteContext, timeout time.Duration) (tun.DirectRouteDestination, error) {
	ctx := log.ContextWithNewID(w.options.Context)
	session := tun.DirectRouteSession{
		Source:      metadata.Source.Addr,
		Destination: metadata.Destination.Addr,
	}
	w.rewriter.CreateSession(session, routeContext)
	w.options.Logger.InfoContext(ctx, "linked ", metadata.Network, " connection from ", metadata.Source.AddrString(), " to ", metadata.Destination.AddrString())
	return &systemDirectRouteDestination{device: w, session: session}, nil
}

var _ tun.DirectRouteDestination = (*systemDirectRouteDestination)(nil)

type systemDirectRouteDestination struct {
	device  *systemDevice
	session tun.DirectRouteSession
	closed  atomic.Bool
}

func (d *systemDirectRouteDestination) WritePacket(packet *buf.Buffer) error {
	d.device.rewriter.RewritePacket(packet.Bytes())
	d.device.packetOutbound <- packet
	return nil
}

func (d *systemDirectRouteDestination) Close() error {
	d.closed.Store(true)
	d.device.rewriter.DeleteSession(d.session)
	return nil
}

func (d *systemDirectRouteDestination) IsClosed() bool {
	return d.closed.Load()
}
