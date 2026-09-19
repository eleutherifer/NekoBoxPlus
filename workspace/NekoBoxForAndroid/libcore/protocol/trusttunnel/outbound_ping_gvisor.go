//go:build with_gvisor

package trusttunnel

import (
	"bytes"
	"context"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/checksum"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	E "github.com/sagernet/sing/common/exceptions"

	trusttunnel "libcore/protocol/trusttunnel/sing-trusttunnel"
)

const withGvisor = true

func (h *Outbound) NewDirectRouteConnection(metadata adapter.InboundContext, routeContext tun.DirectRouteContext, timeout time.Duration) (tun.DirectRouteDestination, error) {
	ctx := log.ContextWithNewID(h.ctx)
	icmpConn, err := h.client.ListenICMP(ctx)
	if err != nil {
		return nil, err
	}
	pinger := &pingAdapter{
		ctx:          ctx,
		logger:       h.logger,
		routeContext: routeContext,
		source:       metadata.Source.Addr,
		destination:  metadata.Destination.Addr,
		timeout:      timeout,
		requests:     make(map[pingRequest]pingRequestData),
		IcmpConn:     icmpConn,
	}
	go pinger.loopRead()
	h.logger.InfoContext(ctx, "linked ", metadata.Network, " connection from ", metadata.Source.AddrString(), " to ", metadata.Destination.AddrString())
	return pinger, nil
}

var _ tun.DirectRouteDestination = (*pingAdapter)(nil)

type pingAdapter struct {
	isClosed      atomic.Bool
	ctx           context.Context
	logger        log.ContextLogger
	routeContext  tun.DirectRouteContext
	source        netip.Addr
	destination   netip.Addr
	timeout       time.Duration
	requestAccess sync.Mutex
	requests      map[pingRequest]pingRequestData
	*trusttunnel.IcmpConn
}

func (p *pingAdapter) WritePacket(packet *buf.Buffer) error {
	data := packet.Bytes()
	ipVersion := header.IPVersion(data)
	switch ipVersion {
	case header.IPv4Version:
		ipHdr := header.IPv4(data)
		if !ipHdr.IsValid(packet.Len()) {
			return E.New("invalid IPv4 header")
		}
		if ipHdr.TransportProtocol() != header.ICMPv4ProtocolNumber {
			return E.New("invalid ICMPv4 protocol")
		}
		if ipHdr.PayloadLength() < header.ICMPv4MinimumSize {
			return E.New("invalid ICMPv4 header")
		}
		icmpHdr := header.ICMPv4(ipHdr.Payload())
		if icmpHdr.Type() != header.ICMPv4Echo {
			return E.New("unsupported ICMPv4 type: ", icmpHdr.Type())
		}
		payload := bytes.Clone(icmpHdr.Payload())
		p.registerRequest(false, icmpHdr.Ident(), icmpHdr.Sequence(), payload)
		return p.IcmpConn.WritePing(icmpHdr.Ident(), p.destination, icmpHdr.Sequence(), ipHdr.TTL(), uint16(len(payload)))
	case header.IPv6Version:
		ipHdr := header.IPv6(data)
		if !ipHdr.IsValid(packet.Len()) {
			return E.New("invalid IPv6 header")
		}
		if ipHdr.TransportProtocol() != header.ICMPv6ProtocolNumber {
			return E.New("invalid ICMPv6 protocol")
		}
		if ipHdr.PayloadLength() < header.ICMPv6MinimumSize {
			return E.New("invalid ICMPv6 header")
		}
		icmpHdr := header.ICMPv6(ipHdr.Payload())
		if icmpHdr.Type() != header.ICMPv6EchoRequest {
			return E.New("unsupported ICMPv6 type: ", icmpHdr.Type())
		}
		payload := bytes.Clone(icmpHdr.Payload())
		p.registerRequest(true, icmpHdr.Ident(), icmpHdr.Sequence(), payload)
		return p.IcmpConn.WritePing(icmpHdr.Ident(), p.destination, icmpHdr.Sequence(), ipHdr.HopLimit(), uint16(len(payload)))
	default:
		return E.New("invalid IP version ", ipVersion)
	}
}

func (p *pingAdapter) Close() error {
	p.isClosed.Store(true)
	return p.IcmpConn.Close()
}

func (p *pingAdapter) IsClosed() bool {
	return p.isClosed.Load()
}

type pingRequest struct {
	id     uint16
	seq    uint16
	isIPv6 bool
}

type pingRequestData struct {
	createdAt time.Time
	payload   []byte
}

func (p *pingAdapter) registerRequest(isIPv6 bool, id uint16, seq uint16, payload []byte) {
	p.requestAccess.Lock()
	p.requests[pingRequest{id: id, seq: seq, isIPv6: isIPv6}] = pingRequestData{
		createdAt: time.Now(),
		payload:   payload,
	}
	p.requestAccess.Unlock()
}

func (p *pingAdapter) loopRead() {
	for {
		id, source, icmpType, code, sequence, err := p.IcmpConn.ReadPing()
		if err != nil {
			if !p.IsClosed() {
				p.logger.ErrorContext(p.ctx, "read ICMP response: ", err)
			}
			return
		}
		p.handleResponse(id, source, icmpType, code, sequence)
	}
}

func (p *pingAdapter) handleResponse(id uint16, source netip.Addr, icmpType uint8, code uint8, sequence uint16) {
	key := pingRequest{
		id:     id,
		seq:    sequence,
		isIPv6: source.Is6(),
	}
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
	var packet *buf.Buffer
	if key.isIPv6 {
		packet = buildIPv6EchoReply(p.source, source, id, sequence, icmpType, code, request.payload)
	} else {
		packet = buildIPv4EchoReply(p.source, source, id, sequence, icmpType, code, request.payload)
	}
	defer packet.Release()
	err := p.routeContext.WritePacket(packet.Bytes())
	if err != nil && !p.IsClosed() {
		p.logger.ErrorContext(p.ctx, "write ICMP response: ", err)
	}
}

func buildIPv4EchoReply(destination netip.Addr, source netip.Addr, id uint16, seq uint16, icmpType uint8, code uint8, payload []byte) *buf.Buffer {
	packet := buf.NewSize(header.IPv4MinimumSize + header.ICMPv4MinimumSize + len(payload))
	packet.Resize(0, header.IPv4MinimumSize+header.ICMPv4MinimumSize+len(payload))
	ipHdr := header.IPv4(packet.Bytes())
	ipHdr.Encode(&header.IPv4Fields{
		TotalLength: uint16(packet.Len()),
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
	icmpHdr.SetChecksum(0)
	icmpHdr.SetChecksum(^checksum.Checksum(icmpHdr, 0))
	ipHdr.SetChecksum(0)
	ipHdr.SetChecksum(^ipHdr.CalculateChecksum())
	return packet
}

func buildIPv6EchoReply(destination netip.Addr, source netip.Addr, id uint16, seq uint16, icmpType uint8, code uint8, payload []byte) *buf.Buffer {
	packet := buf.NewSize(header.IPv6MinimumSize + header.ICMPv6MinimumSize + len(payload))
	packet.Resize(0, header.IPv6MinimumSize+header.ICMPv6MinimumSize+len(payload))
	ipHdr := header.IPv6(packet.Bytes())
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
	icmpHdr.SetChecksum(0)
	icmpHdr.SetChecksum(^checksum.Checksum(icmpHdr, header.PseudoHeaderChecksum(
		header.ICMPv6ProtocolNumber,
		tcpip.AddrFromSlice(source.AsSlice()),
		tcpip.AddrFromSlice(destination.AsSlice()),
		uint16(len(icmpHdr)),
	)))
	return packet
}
