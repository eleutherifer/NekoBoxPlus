//go:build with_adblock

package httpconn

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
	"golang.org/x/net/http2"
)

const http11 = "http/1.1"

type httpForwarder struct {
	transport http.RoundTripper
}

type utlsForwarder struct {
	http2Transport *http2.Transport
	httpTransport  *http.Transport
	originCache    sync.Map
}

func NewHTTPForwarder(ctx context.Context, c *ctx.Conn) ClosableRoundTripper {
	if c.Cronet {
		return NewCronetForwarder(ctx, c)
	}
	return newStandardHTTPForwarder(ctx, c)
}

func newStandardHTTPForwarder(ctx context.Context, c *ctx.Conn) ClosableRoundTripper {
	tlsConfig := &tls.Config{
		Time:    ntp.TimeFuncFromContext(ctx),
		RootCAs: adapter.RootPoolFromContext(ctx),
	}
	if c.UseTLS && SupportsUTLS && c.UTLS != consts.Invalid {
		return newUTLSForwarder(c, tlsConfig)
	}

	transport := &http.Transport{
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   C.TCPTimeout,
		ResponseHeaderTimeout: C.TCPTimeout,
		IdleConnTimeout:       C.TCPTimeout,
		MaxIdleConns:          32,
		MaxIdleConnsPerHost:   8,
		DialContext: func(ctx context.Context, network string, address string) (net.Conn, error) {
			return dialForwarder(ctx, network, address, c)
		},
	}
	if c.UseTLS {
		transport.TLSClientConfig = tlsConfig
	}
	return &httpForwarder{transport: transport}
}

func newUTLSForwarder(c *ctx.Conn, tlsConfig *tls.Config) ClosableRoundTripper {
	return &utlsForwarder{
		http2Transport: &http2.Transport{
			DialTLSContext:  makeHTTP2DialTLS(c, tlsConfig, []string{http2.NextProtoTLS, http11}),
			TLSClientConfig: tlsConfig,
		},
		httpTransport: &http.Transport{
			TLSHandshakeTimeout:   C.TCPTimeout,
			ResponseHeaderTimeout: C.TCPTimeout,
			IdleConnTimeout:       C.TCPTimeout,
			MaxIdleConns:          32,
			MaxIdleConnsPerHost:   8,
			DialContext: func(ctx context.Context, network string, address string) (net.Conn, error) {
				return dialForwarder(ctx, network, address, c)
			},
			DialTLSContext:  makeDialTLS(c, tlsConfig, []string{http11}),
			TLSClientConfig: tlsConfig,
			TLSNextProto:    map[string]func(string, *tls.Conn) http.RoundTripper{},
		},
	}
}

func dialForwarder(ctx context.Context, network, addr string, c *ctx.Conn) (net.Conn, error) {
	if c.Outbound == nil {
		var d net.Dialer
		return d.DialContext(ctx, network, addr)
	}
	return c.Outbound.DialContext(outboundContext(ctx, c), network, M.ParseSocksaddr(addr))
}

func (f *httpForwarder) RoundTrip(req *http.Request) (*http.Response, error) {
	return f.transport.RoundTrip(req)
}

func (f *httpForwarder) Close() {
	if f == nil || f.transport == nil {
		return
	}
	if val, ok := f.transport.(interface {
		CloseIdleConnections()
	}); ok {
		val.CloseIdleConnections()
	}
}

func (f *utlsForwarder) RoundTrip(req *http.Request) (*http.Response, error) {
	// HTTP/2 cannot carry connection-level control headers such as a WebSocket
	// Upgrade handshake, and our dedicated http2.Transport only rejects them
	// after a full TLS+HTTP/2 handshake. Detect such requests up front and route
	// them straight to HTTP/1.1.
	if !http2Capable(req) {
		return f.httpTransport.RoundTrip(req)
	}
	origin := http2OriginKey(req)
	if _, loaded := f.originCache.Load(origin); loaded {
		return f.httpTransport.RoundTrip(req)
	}
	response, err := f.http2Transport.RoundTrip(req)
	if err == nil || !isHTTP2Unavailable(err) {
		return response, err
	}
	if isHTTP2OriginUnavailable(err) {
		f.originCache.Store(origin, struct{}{})
	}
	return f.httpTransport.RoundTrip(req)
}

func http2OriginKey(req *http.Request) string {
	if req == nil || req.URL == nil {
		return ""
	}
	return strings.ToLower(req.URL.Scheme) + "://" + strings.ToLower(req.URL.Host)
}

func (f *utlsForwarder) Close() {
	if f == nil {
		return
	}
	if f.http2Transport != nil {
		f.http2Transport.CloseIdleConnections()
	}
	if f.httpTransport != nil {
		f.httpTransport.CloseIdleConnections()
	}
}
