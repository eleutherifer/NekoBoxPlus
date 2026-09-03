//go:build with_gvisor

package trusttunnel

import (
	"bytes"
	"context"
	"net"
	"net/netip"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/checksum"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/sing-box/log"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"

	trusttunnel "libcore/protocol/trusttunnel/sing-trusttunnel"
)

const (
	withGvisor         = true
	defaultICMPTimeout = time.Minute
)

type pingAdapter struct {
	ctx     context.Context
	logger  log.ContextLogger
	client  *trusttunnel.Client
	timeout time.Duration
	closed  atomic.Bool

	connAccess sync.Mutex
	conn       *trusttunnel.IcmpConn

	returnAccess sync.Mutex
	returnPaths  []tun.Return

	requestAccess sync.Mutex
	requests      map[pingRequest]pingRequestData
}

type pingRequest struct {
	destination netip.Addr
	id          uint16
	seq         uint16
}

type pingRequestData struct {
	createdAt time.Time
	source    netip.Addr
	payload   []byte
}

func newPingAdapter(ctx context.Context, logger log.ContextLogger, client *trusttunnel.Client) *pingAdapter {
	return &pingAdapter{
		ctx:      ctx,
		logger:   logger,
		client:   client,
		timeout:  defaultICMPTimeout,
		requests: make(map[pingRequest]pingRequestData),
	}
}

func (p *pingAdapter) PortAddresses() (netip.Addr, netip.Addr) {
	return netip.IPv4Unspecified(), netip.IPv6Unspecified()
}

func (p *pingAdapter) PortMTU() uint32 {
	return 0
}

func (p *pingAdapter) AttachReturn(returnPath tun.Return) error {
	p.returnAccess.Lock()
	defer p.returnAccess.Unlock()
	if !slices.Contains(p.returnPaths, returnPath) {
		p.returnPaths = append(p.returnPaths[:len(p.returnPaths):len(p.returnPaths)], returnPath)
	}
	return nil
}

func (p *pingAdapter) DetachReturn(returnPath tun.Return) error {
	p.returnAccess.Lock()
	defer p.returnAccess.Unlock()
	p.returnPaths = slices.DeleteFunc(p.returnPaths, func(existing tun.Return) bool {
		return existing == returnPath
	})
	return nil
}

func (p *pingAdapter) WritePackets(packets [][]byte) error {
	var errs []error
	for _, packet := range packets {
		if err := p.writePacket(packet); err != nil {
			errs = append(errs, err)
		}
	}
	return E.Errors(errs...)
}

func (p *pingAdapter) writePacket(packet []byte) error {
	if p.closed.Load() {
		return net.ErrClosed
	}
	conn, err := p.ensureConn()
	if err != nil {
		return err
	}
	var (
		source      netip.Addr
		destination netip.Addr
		id          uint16
		sequence    uint16
		ttl         uint8
		payload     []byte
	)
	switch header.IPVersion(packet) {
	case header.IPv4Version:
		ipHdr := header.IPv4(packet)
		if !ipHdr.IsValid(len(packet)) || ipHdr.TransportProtocol() != header.ICMPv4ProtocolNumber || ipHdr.PayloadLength() < header.ICMPv4MinimumSize {
			return nil
		}
		icmpHdr := header.ICMPv4(ipHdr.Payload())
		if icmpHdr.Type() != header.ICMPv4Echo || icmpHdr.Code() != 0 {
			return nil
		}
		source = netip.AddrFrom4(ipHdr.SourceAddress().As4())
		destination = netip.AddrFrom4(ipHdr.DestinationAddress().As4())
		id, sequence, ttl = icmpHdr.Ident(), icmpHdr.Sequence(), ipHdr.TTL()
		payload = bytes.Clone(icmpHdr.Payload())
	case header.IPv6Version:
		ipHdr := header.IPv6(packet)
		if !ipHdr.IsValid(len(packet)) || ipHdr.TransportProtocol() != header.ICMPv6ProtocolNumber || ipHdr.PayloadLength() < header.ICMPv6MinimumSize {
			return nil
		}
		icmpHdr := header.ICMPv6(ipHdr.Payload())
		if icmpHdr.Type() != header.ICMPv6EchoRequest || icmpHdr.Code() != 0 {
			return nil
		}
		source = netip.AddrFrom16(ipHdr.SourceAddress().As16())
		destination = netip.AddrFrom16(ipHdr.DestinationAddress().As16())
		id, sequence, ttl = icmpHdr.Ident(), icmpHdr.Sequence(), ipHdr.HopLimit()
		payload = bytes.Clone(icmpHdr.Payload())
	default:
		return nil
	}
	p.registerRequest(destination, source, id, sequence, payload)
	return conn.WritePing(id, destination, sequence, ttl, uint16(len(payload)))
}

func (p *pingAdapter) ensureConn() (*trusttunnel.IcmpConn, error) {
	p.connAccess.Lock()
	defer p.connAccess.Unlock()
	if p.conn != nil {
		return p.conn, nil
	}
	ctx := log.ContextWithNewID(p.ctx)
	conn, err := p.client.ListenICMP(ctx)
	if err != nil {
		return nil, err
	}
	p.conn = conn
	go p.loopRead(ctx, conn)
	return conn, nil
}

func (p *pingAdapter) registerRequest(destination netip.Addr, source netip.Addr, id uint16, sequence uint16, payload []byte) {
	p.requestAccess.Lock()
	defer p.requestAccess.Unlock()
	p.requests[pingRequest{destination: destination, id: id, seq: sequence}] = pingRequestData{
		createdAt: time.Now(),
		source:    source,
		payload:   payload,
	}
}

func (p *pingAdapter) loopRead(ctx context.Context, conn *trusttunnel.IcmpConn) {
	for {
		id, source, icmpType, code, sequence, err := conn.ReadPing()
		if err != nil {
			p.connAccess.Lock()
			if p.conn == conn {
				p.conn = nil
			}
			p.connAccess.Unlock()
			if !p.closed.Load() {
				p.logger.ErrorContext(ctx, "read ICMP response: ", err)
			}
			return
		}
		p.handleResponse(source, id, sequence, icmpType, code)
	}
}

func (p *pingAdapter) handleResponse(source netip.Addr, id uint16, sequence uint16, icmpType uint8, code uint8) {
	key := pingRequest{destination: source, id: id, seq: sequence}
	p.requestAccess.Lock()
	request, ok := p.requests[key]
	if ok {
		delete(p.requests, key)
	}
	now := time.Now()
	for staleKey, staleRequest := range p.requests {
		if now.Sub(staleRequest.createdAt) > p.timeout {
			delete(p.requests, staleKey)
		}
	}
	p.requestAccess.Unlock()
	if !ok {
		return
	}
	var packet []byte
	if source.Is6() {
		packet = buildIPv6EchoReply(request.source, source, id, sequence, icmpType, code, request.payload)
	} else {
		packet = buildIPv4EchoReply(request.source, source, id, sequence, icmpType, code, request.payload)
	}
	p.returnPacket(packet)
}

func (p *pingAdapter) returnPacket(packet []byte) {
	p.returnAccess.Lock()
	returnPaths := slices.Clone(p.returnPaths)
	p.returnAccess.Unlock()
	for _, returnPath := range returnPaths {
		headroom := returnPath.ReturnHeadroom()
		buffer := make([]byte, headroom+len(packet))
		copy(buffer[headroom:], packet)
		if len(returnPath.ReturnPackets([][]byte{buffer})) == 0 {
			return
		}
	}
}

func (p *pingAdapter) Reset() error {
	p.connAccess.Lock()
	conn := p.conn
	p.conn = nil
	p.connAccess.Unlock()
	if conn != nil {
		return conn.Close()
	}
	return nil
}

func (p *pingAdapter) Close() error {
	p.closed.Store(true)
	return p.Reset()
}

func buildIPv4EchoReply(destination netip.Addr, source netip.Addr, id uint16, seq uint16, icmpType uint8, code uint8, payload []byte) []byte {
	packet := make([]byte, header.IPv4MinimumSize+header.ICMPv4MinimumSize+len(payload))
	ipHdr := header.IPv4(packet)
	ipHdr.Encode(&header.IPv4Fields{
		TotalLength: uint16(len(packet)),
		TTL:         64,
		Protocol:    uint8(header.ICMPv4ProtocolNumber),
		SrcAddr:     tcpip.AddrFromSlice(source.AsSlice()),
		DstAddr:     tcpip.AddrFromSlice(destination.AsSlice()),
	})
	icmpHdr := header.ICMPv4(ipHdr.Payload())
	icmpHdr.SetType(header.ICMPv4Type(icmpType))
	icmpHdr.SetCode(header.ICMPv4Code(code))
	icmpHdr.SetIdent(id)
	icmpHdr.SetSequence(seq)
	copy(icmpHdr.Payload(), payload)
	icmpHdr.SetChecksum(^checksum.Checksum(icmpHdr, 0))
	ipHdr.SetChecksum(^ipHdr.CalculateChecksum())
	return packet
}

func buildIPv6EchoReply(destination netip.Addr, source netip.Addr, id uint16, seq uint16, icmpType uint8, code uint8, payload []byte) []byte {
	packet := make([]byte, header.IPv6MinimumSize+header.ICMPv6MinimumSize+len(payload))
	ipHdr := header.IPv6(packet)
	ipHdr.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(header.ICMPv6MinimumSize + len(payload)),
		TransportProtocol: header.ICMPv6ProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFromSlice(source.AsSlice()),
		DstAddr:           tcpip.AddrFromSlice(destination.AsSlice()),
	})
	icmpHdr := header.ICMPv6(ipHdr.Payload())
	icmpHdr.SetType(header.ICMPv6Type(icmpType))
	icmpHdr.SetCode(header.ICMPv6Code(code))
	icmpHdr.SetIdent(id)
	icmpHdr.SetSequence(seq)
	copy(icmpHdr.Payload(), payload)
	icmpHdr.SetChecksum(^checksum.Checksum(icmpHdr, header.PseudoHeaderChecksum(
		header.ICMPv6ProtocolNumber,
		tcpip.AddrFromSlice(source.AsSlice()),
		tcpip.AddrFromSlice(destination.AsSlice()),
		uint16(len(icmpHdr)),
	)))
	return packet
}
