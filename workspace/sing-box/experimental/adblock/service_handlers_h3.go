//go:build with_adblock && with_quic

package adblock

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
)

func (s *Service) handleQUICHTTP(ctx *ctx.Conn) error {
	s.debugContext(ctx.Ctx, "QUIC HTTP handler started: destination=", ctx.Metadata.Destination, ", domain=", ctx.Metadata.Domain)
	if !ctx.Metadata.Source.IsValid() || !ctx.Metadata.Destination.IsValid() {
		return E.New("missing QUIC packet metadata")
	}
	ctx.UseTLS = true
	ctx.UseHTTP2 = false
	ctx.UTLS = consts.Invalid
	ctx.Cronet = s.cronet
	forwarder := httpconn.NewHTTPForwarder(s.ctx, ctx)
	defer forwarder.Close()
	srv := s.httpServer(ctx, forwarder)
	defer srv.Release()

	server := &http3.Server{
		TLSConfig: s.tlsHTTP3ServerConfig(ctx.Metadata, ctx.Outbound),
		QUICConfig: &quic.Config{
			DisablePathMTUDiscovery: true,
		},
		Handler: srv.Value.Handler,
	}
	packetConn := &adblockHTTP3PacketConn{
		PacketConn:  ctx.PacketConn,
		source:      ctx.Metadata.Source,
		destination: adblockHTTP3ClientDestination(ctx.Metadata),
		localAddr:   adblockHTTP3LocalAddr(ctx.Metadata),
	}
	stopClose := context.AfterFunc(ctx.Ctx, func() {
		_ = server.Close()
		_ = packetConn.Close()
	})
	defer stopClose()
	err := server.Serve(packetConn)
	if errors.Is(err, net.ErrClosed) || errors.Is(err, http.ErrServerClosed) || E.IsClosedOrCanceled(err) {
		s.debugContext(ctx.Ctx, "QUIC HTTP handler stopped")
		return nil
	}
	s.debugContext(ctx.Ctx, "QUIC HTTP handler failed: ", err)
	return err
}

func adblockHTTP3ClientDestination(metadata adapter.InboundContext) M.Socksaddr {
	if metadata.FakeIP && metadata.OriginDestination.IsValid() {
		return metadata.OriginDestination
	}
	return metadata.Destination
}

func adblockHTTP3LocalAddr(metadata adapter.InboundContext) net.Addr {
	destination := adblockHTTP3ClientDestination(metadata)
	if destination.Addr.IsValid() {
		return destination.UDPAddr()
	}
	return nil
}

func (s *Service) tlsHTTP3ServerConfig(metadata adapter.InboundContext, outbound adapter.Outbound) *tls.Config {
	return http3.ConfigureTLSConfig(&tls.Config{
		Time: ntp.TimeFuncFromContext(s.ctx),
		GetCertificate: func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
			s.debug("HTTP/3 TLS client hello: server_name=", hello.ServerName, ", supported_protos=", hello.SupportedProtos)
			return s.tlsCA.certificateForServerName(s.ctx, outbound, metadata, hello)
		},
	})
}

func (s *Service) forwardHTTP3RequestURL(requestContext *adblockRequestContext) error {
	if err := adblockHTTPForwardURL(requestContext.requestURL); err != nil {
		return err
	}
	outRequest := newForwardedHTTPRequest(requestContext)
	outRequest.Close = true
	transport := &http3.Transport{
		TLSClientConfig: &tls.Config{
			ServerName: requestContext.requestURL.Hostname(),
			Time:       ntp.TimeFuncFromContext(s.ctx),
			RootCAs:    adapter.RootPoolFromContext(s.ctx),
		},
		QUICConfig: &quic.Config{
			DisablePathMTUDiscovery: true,
		},
		Dial: func(ctx context.Context, address string, tlsConfig *tls.Config, quicConfig *quic.Config) (*quic.Conn, error) {
			destination := M.ParseSocksaddr(address)
			var (
				conn net.Conn
				err  error
			)
			if requestContext.outbound == nil {
				var dialer net.Dialer
				conn, err = dialer.DialContext(ctx, N.NetworkUDP, address)
			} else {
				conn, err = requestContext.outbound.DialContext(ctx, N.NetworkUDP, destination)
			}
			if err != nil {
				return nil, err
			}
			quicConn, err := quic.DialEarly(ctx, bufio.NewUnbindPacketConn(conn), conn.RemoteAddr(), tlsConfig, quicConfig)
			if err != nil {
				_ = conn.Close()
				return nil, err
			}
			return quicConn, nil
		},
	}
	defer transport.Close()
	response, err := transport.RoundTrip(outRequest)
	if err != nil {
		s.debugContext(requestContext.ctx, "HTTP/3 forward failed: ", requestContext.requestURL, ": ", err)
		return err
	}
	s.debugContext(requestContext.ctx, "HTTP/3 forward response: ", requestContext.requestURL, ", status: ", response.StatusCode)
	if err = s.filterForwardedHTTPResponse(requestContext, response); err != nil {
		return err
	}
	defer response.Body.Close()
	return s.writeForwardedResponse(requestContext.writer, response, requestContext.request.Method)
}

type adblockHTTP3PacketConn struct {
	N.PacketConn
	source      M.Socksaddr
	destination M.Socksaddr
	localAddr   net.Addr
}

func (c *adblockHTTP3PacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	buffer := buf.NewPacket()
	defer buffer.Release()
	source, err := c.ReadPacket(buffer)
	if err != nil {
		return 0, nil, err
	}
	if c.source.IsValid() {
		source = c.source
	} else if !source.IsValid() {
		source = M.SocksaddrFromNet(c.PacketConn.LocalAddr())
	}
	return copy(p, buffer.Bytes()), source.UDPAddr(), nil
}

func (c *adblockHTTP3PacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	destination := c.destination
	if !destination.IsValid() {
		destination = M.SocksaddrFromNet(addr)
	}
	buffer := buf.As(p)
	if err := c.WritePacket(buffer, destination); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *adblockHTTP3PacketConn) LocalAddr() net.Addr {
	if c.localAddr != nil {
		return c.localAddr
	}
	return c.PacketConn.LocalAddr()
}

func (c *adblockHTTP3PacketConn) SetDeadline(t time.Time) error {
	return c.PacketConn.SetDeadline(t)
}

func (c *adblockHTTP3PacketConn) SetReadDeadline(t time.Time) error {
	return c.PacketConn.SetReadDeadline(t)
}

func (c *adblockHTTP3PacketConn) SetWriteDeadline(t time.Time) error {
	return c.PacketConn.SetWriteDeadline(t)
}

var _ net.PacketConn = (*adblockHTTP3PacketConn)(nil)
