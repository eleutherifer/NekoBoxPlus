package masque

import (
	"context"
	"net/netip"
	"sync"
	"time"

	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
	wgTun "github.com/sagernet/wireguard-go/tun"
)

type Device interface {
	wgTun.Device
	N.Dialer
	tun.Port
	Start() error
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

type flowPort struct {
	ctx            context.Context
	inet4Address   netip.Addr
	inet6Address   netip.Addr
	mtu            uint32
	packetOutbound chan *buf.Buffer
	returnAccess   sync.Mutex
	returnPaths    []tun.Return
}

func newFlowPort(options DeviceOptions) *flowPort {
	port := &flowPort{
		ctx:            options.Context,
		mtu:            options.MTU,
		packetOutbound: make(chan *buf.Buffer, 256),
	}
	for _, prefix := range options.Address {
		if prefix.Addr().Is4() && !port.inet4Address.IsValid() {
			port.inet4Address = prefix.Addr()
		} else if prefix.Addr().Is6() && !port.inet6Address.IsValid() {
			port.inet6Address = prefix.Addr()
		}
	}
	return port
}

func (p *flowPort) PortAddresses() (netip.Addr, netip.Addr) {
	return p.inet4Address, p.inet6Address
}

func (p *flowPort) PortMTU() uint32 {
	return p.mtu
}

func (p *flowPort) AttachReturn(returnPath tun.Return) error {
	p.returnAccess.Lock()
	defer p.returnAccess.Unlock()
	for _, existing := range p.returnPaths {
		if existing == returnPath {
			return nil
		}
	}
	p.returnPaths = append(p.returnPaths[:len(p.returnPaths):len(p.returnPaths)], returnPath)
	return nil
}

func (p *flowPort) DetachReturn(returnPath tun.Return) error {
	p.returnAccess.Lock()
	defer p.returnAccess.Unlock()
	returnPaths := make([]tun.Return, 0, len(p.returnPaths))
	for _, existing := range p.returnPaths {
		if existing != returnPath {
			returnPaths = append(returnPaths, existing)
		}
	}
	p.returnPaths = returnPaths
	return nil
}

func (p *flowPort) WritePackets(packets [][]byte) error {
	for _, packet := range packets {
		packetBuffer := buf.NewSize(len(packet))
		_, _ = packetBuffer.Write(packet)
		select {
		case p.packetOutbound <- packetBuffer:
		case <-p.ctx.Done():
			packetBuffer.Release()
			return p.ctx.Err()
		}
	}
	return nil
}

func (p *flowPort) returnPackets(packets [][]byte, offset int) [][]byte {
	p.returnAccess.Lock()
	returnPaths := append([]tun.Return(nil), p.returnPaths...)
	p.returnAccess.Unlock()
	if len(returnPaths) == 0 {
		return packets
	}
	unconsumed := make([][]byte, 0, len(packets))
	for _, packet := range packets {
		rawPacket := packet[offset:]
		consumed := false
		for _, returnPath := range returnPaths {
			headroom := returnPath.ReturnHeadroom()
			returnPacket := make([]byte, headroom+len(rawPacket))
			copy(returnPacket[headroom:], rawPacket)
			if len(returnPath.ReturnPackets([][]byte{returnPacket})) == 0 {
				consumed = true
				break
			}
		}
		if !consumed {
			unconsumed = append(unconsumed, packet)
		}
	}
	return unconsumed
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
