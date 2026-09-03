//go:build with_adblock

package adblock

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
	dnsOutbound "github.com/sagernet/sing-box/protocol/dns"
	"github.com/sagernet/sing/common/bufio/deadline"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

func (s *Service) HandleTCP(c context.Context, conn net.Conn, metadata adapter.InboundContext, outbound adapter.Outbound, onClose N.CloseHandlerFunc) (bool, error) {
	s.debugContext(c, "TCP handling: protocol=", metadata.Protocol, ", destination=", metadata.Destination, ", domain=", metadata.Domain)
	engineRef, engine := s.readyEngine()
	if engine == nil {
		s.debugContext(c, "TCP skipped: engine is not ready")
		return false, nil
	}
	defer engineRef.release()
	if !s.constraintsMatch(&metadata) {
		s.debugContext(c, "TCP skipped: constraints mismatch, destination=", metadata.Destination, ", domain=", metadata.Domain)
		return false, nil
	}
	if deadline.NeedAdditionalReadDeadline(conn) {
		conn = deadline.NewConn(conn)
	}
	switch metadata.Protocol {
	case C.ProtocolHTTP:
		if !s.options.FilterHTTP() {
			s.debugContext(c, "TCP skipped: HTTP filtering disabled")
			return false, nil
		}
		s.debugContext(c, "TCP accepted as HTTP")
		return true, s.handleHTTP(ctx.NewConn(c, engine, conn, nil, metadata, outbound))
	case C.ProtocolTLS:
		if !s.options.FilterHTTPS() {
			s.debugContext(c, "TCP skipped: HTTPS filtering disabled")
			return false, nil
		}
		checkResult := s.domainCheckNoStats(engine, metadata.Domain, "https", "document")
		if checkResultException(checkResult) || s.domainException(engine, metadata.Domain, "https", "document") {
			s.debugContext(c, "TCP skipped TLS domain: document filtering excluded by exception, domain=", metadata.Domain)
			return false, nil
		}
		if !checkResultBlocked(checkResult) && s.options.TLS != nil && s.options.TLS.SkipEV && s.peerCertificateIsEV(c, outbound, metadata) {
			s.debugContext(c, "TCP skipped: EV certificate detected, domain=", metadata.Domain)
			return false, nil
		}
		s.debugContext(c, "TCP accepted as TLS HTTP")
		return true, s.handleTLSHTTP(ctx.NewConn(c, engine, conn, nil, metadata, outbound))
	default:
		if metadata.Destination.Port == 80 {
			if !s.options.FilterHTTP() {
				s.debugContext(c, "TCP skipped: HTTP filtering disabled")
				return false, nil
			}
			s.debugContext(c, "TCP accepted as HTTP by destination port")
			return true, s.handleHTTP(ctx.NewConn(c, engine, conn, nil, metadata, outbound))
		}
		if metadata.Destination.Port == 443 {
			if !s.options.FilterHTTPS() {
				s.debugContext(c, "TCP skipped: HTTPS filtering disabled")
				return false, nil
			}
			var checkResult adblockrust.CheckResult
			if metadata.Domain != "" {
				checkResult = s.domainCheckNoStats(engine, metadata.Domain, "https", "document")
				if checkResultException(checkResult) || s.domainException(engine, metadata.Domain, "https", "document") {
					s.debugContext(c, "TCP skipped HTTPS domain: document filtering excluded by exception, domain=", metadata.Domain)
					return false, nil
				}
			}
			if !checkResultBlocked(checkResult) && s.options.TLS != nil && s.options.TLS.SkipEV && s.peerCertificateIsEV(c, outbound, metadata) {
				s.debugContext(c, "TCP skipped: EV certificate detected, domain=", metadata.Domain)
				return false, nil
			}
			s.debugContext(c, "TCP accepted as TLS HTTP by destination port")
			return true, s.handleTLSHTTP(ctx.NewConn(c, engine, conn, nil, metadata, outbound))
		}
		s.debugContext(c, "TCP skipped: unsupported protocol and port")
		return false, nil
	}
}

func (s *Service) HandleUDP(c context.Context, conn N.PacketConn, metadata adapter.InboundContext, outbound adapter.Outbound, onClose N.CloseHandlerFunc) (bool, error) {
	s.debugContext(c, "UDP handling: protocol=", metadata.Protocol, ", destination=", metadata.Destination, ", domain=", metadata.Domain)
	engineRef, engine := s.readyEngine()
	if engine == nil {
		s.debugContext(c, "UDP skipped: engine is not ready")
		return false, nil
	}
	defer engineRef.release()
	if !s.constraintsMatch(&metadata) || metadata.Domain == "" {
		s.debugContext(c, "UDP skipped: constraints mismatch or missing domain")
		return false, nil
	}
	switch metadata.Protocol {
	case C.ProtocolDNS:
		if !s.options.FilterDNS() {
			s.debugContext(c, "UDP skipped: DNS filtering disabled")
			return false, nil
		}
		if checkResultBlocked(s.dnsCheck(engine, metadata.Domain)) {
			s.debugContext(c, "UDP blocked DNS domain: ", metadata.Domain)
			return true, s.blockUDP(conn, metadata)
		}
		if s.options.Filtering.CNAMEUncloaking {
			dnsRouter := service.FromContext[adapter.DNSRouter](s.ctx)
			if dnsRouter == nil {
				s.debugContext(c, "UDP skipped: CNAME uncloaking requested but DNS router unavailable")
				return false, nil
			}
			s.debugContext(c, "UDP accepted for DNS CNAME uncloaking")
			return true, dnsOutbound.NewDNSPacketConnection(c, dnsRouter, conn, nil, metadata)
		}
	case C.ProtocolQUIC:
		if !s.options.FilterQUIC() {
			s.debugContext(c, "UDP skipped: QUIC filtering disabled")
			return false, nil
		}
		checkResult := s.domainCheckNoStats(engine, metadata.Domain, "https", "document")
		if checkResultException(checkResult) || s.domainException(engine, metadata.Domain, "https", "document") {
			s.debugContext(c, "UDP skipped QUIC domain: document filtering excluded by exception, domain=", metadata.Domain)
			return false, nil
		}
		if !checkResultBlocked(checkResult) && s.options.TLS != nil && s.options.TLS.SkipEV && s.peerCertificateIsEV(c, outbound, metadata) {
			s.debugContext(c, "UDP skipped: EV certificate detected, domain=", metadata.Domain)
			return false, nil
		}
		if !metadata.Source.IsValid() || !metadata.Destination.IsValid() {
			s.debugContext(c, "UDP skipped QUIC: missing packet metadata")
			return false, nil
		}
		s.debugContext(c, "UDP accepted as QUIC HTTP")
		return true, s.handleQUICHTTP(ctx.NewConn(c, engine, nil, conn, metadata, outbound))
	}
	s.debugContext(c, "UDP skipped: unsupported protocol")
	return false, nil
}

func (s *Service) handleHTTP(c *ctx.Conn) error {
	s.debugContext(c.Ctx, "HTTP handler started: destination=", c.Metadata.Destination, ", domain=", c.Metadata.Domain)
	c.UseTLS = false
	c.UseHTTP2 = false
	c.Cronet = s.cronet
	forwarder := httpconn.NewHTTPForwarder(s.ctx, c)
	defer forwarder.Close()
	server := s.httpServer(c, forwarder)
	defer server.Release()
	err := server.Value.Serve(&httpconn.SingleConnListener{Conn: c.Conn})
	if errors.Is(err, net.ErrClosed) || errors.Is(err, http.ErrServerClosed) {
		s.debugContext(c.Ctx, "HTTP handler stopped")
		return nil
	}
	s.debugContext(c.Ctx, "HTTP handler failed: ", err)
	return err
}

func (s *Service) handleTLSHTTP(c *ctx.Conn) error {
	s.debugContext(c.Ctx, "TLS HTTP handler started: destination=", c.Metadata.Destination, ", domain=", c.Metadata.Domain)
	c.UseTLS = true
	c.UseHTTP2 = true
	c.UTLS = s.utls
	c.Cronet = s.cronet
	forwarder := httpconn.NewHTTPForwarder(s.ctx, c)
	defer forwarder.Close()
	server := s.httpServer(c, forwarder)
	defer server.Release()
	tlsListener := tls.NewListener(&httpconn.SingleConnListener{Conn: c.Conn}, s.tlsServerConfig(c.Metadata, c.Outbound))
	err := server.Value.Serve(tlsListener)
	if errors.Is(err, net.ErrClosed) || errors.Is(err, http.ErrServerClosed) {
		s.debugContext(c.Ctx, "TLS HTTP handler stopped")
		return nil
	}
	s.debugContext(c.Ctx, "TLS HTTP handler failed: ", err)
	return err
}
