package quic

import (
	"context"
	gotls "crypto/tls"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	q "github.com/sagernet/quic-go"
	boxTLS "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/dns/transport"
	M "github.com/sagernet/sing/common/metadata"

	mDNS "github.com/miekg/dns"
	"golang.org/x/net/http2"
)

type terminalErrorConn struct {
	err    error
	reads  atomic.Int32
	closed atomic.Bool
}

func (c *terminalErrorConn) Read([]byte) (int, error) {
	c.reads.Add(1)
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	return 0, c.err
}

func (c *terminalErrorConn) Write(p []byte) (int, error) {
	return len(p), nil
}

func (c *terminalErrorConn) Close() error {
	c.closed.Store(true)
	return nil
}

func (c *terminalErrorConn) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4zero, Port: 10000}
}

func (c *terminalErrorConn) RemoteAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 853}
}

func (c *terminalErrorConn) SetDeadline(time.Time) error {
	return nil
}

func (c *terminalErrorConn) SetReadDeadline(time.Time) error {
	return nil
}

func (c *terminalErrorConn) SetWriteDeadline(time.Time) error {
	return nil
}

type terminalErrorDialer struct {
	conn net.Conn
}

func (d *terminalErrorDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}

func (d *terminalErrorDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.ErrClosed
}

type testTLSConfig struct {
	config           *gotls.Config
	handshakeTimeout time.Duration
}

func (c *testTLSConfig) ServerName() string {
	return c.config.ServerName
}

func (c *testTLSConfig) SetServerName(serverName string) {
	c.config.ServerName = serverName
}

func (c *testTLSConfig) NextProtos() []string {
	return c.config.NextProtos
}

func (c *testTLSConfig) SetNextProtos(nextProtos []string) {
	c.config.NextProtos = slices.Clone(nextProtos)
}

func (c *testTLSConfig) HandshakeTimeout() time.Duration {
	return c.handshakeTimeout
}

func (c *testTLSConfig) SetHandshakeTimeout(timeout time.Duration) {
	c.handshakeTimeout = timeout
}

func (c *testTLSConfig) STDConfig() (*gotls.Config, error) {
	return c.config, nil
}

func (c *testTLSConfig) Client(conn net.Conn) (boxTLS.Conn, error) {
	return gotls.Client(conn, c.config), nil
}

func (c *testTLSConfig) Clone() boxTLS.Config {
	return &testTLSConfig{config: c.config.Clone(), handshakeTimeout: c.handshakeTimeout}
}

func newTestTLSConfig(nextProto string) *testTLSConfig {
	return &testTLSConfig{config: &gotls.Config{
		ServerName:         "dns.example",
		NextProtos:         []string{nextProto},
		InsecureSkipVerify: true, //nolint:gosec // The peer never reaches a TLS handshake in this failure-path test.
	}}
}

func newDNSQuery() *mDNS.Msg {
	message := &mDNS.Msg{}
	message.SetQuestion("example.com.", mDNS.TypeA)
	return message
}

func persistentStreamError() http2.StreamError {
	return http2.StreamError{
		StreamID: 1,
		Code:     http2.ErrCodeCancel,
	}
}

func TestQUICExchangeStopsAfterUnderlyingStreamError(t *testing.T) {
	conn := &terminalErrorConn{err: persistentStreamError()}
	tlsConfig := newTestTLSConfig("doq")
	serverAddr := M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: 853}
	dnsTransport := &Transport{
		dialer:     &terminalErrorDialer{conn: conn},
		serverAddr: serverAddr,
		tlsConfig:  tlsConfig,
		connection: transport.NewConnPool(transport.ConnPoolOptions[*q.Conn]{
			Mode: transport.ConnPoolSingle,
			IsAlive: func(conn *q.Conn) bool {
				return conn != nil && conn.Context().Err() == nil
			},
			Close: func(conn *q.Conn, _ error) {
				_ = conn.CloseWithError(0, "")
			},
		}),
	}
	defer func() {
		if err := dnsTransport.Close(); err != nil {
			t.Error(err)
		}
	}()

	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	_, err := dnsTransport.Exchange(ctx, newDNSQuery())
	if err == nil {
		t.Fatal("expected DNS-over-QUIC exchange error")
	}
	if ctx.Err() != nil {
		t.Fatal("DNS-over-QUIC exchange waited for context timeout")
	}
	if reads := conn.reads.Load(); reads != 1 {
		t.Fatalf("DNS-over-QUIC reads = %d, want 1", reads)
	}
	if !conn.closed.Load() {
		t.Fatal("DNS-over-QUIC packet connection was not closed")
	}
}

func TestHTTP3ExchangeStopsAfterUnderlyingStreamError(t *testing.T) {
	conn := &terminalErrorConn{err: persistentStreamError()}
	tlsConfig := newTestTLSConfig("h3")
	serverAddr := M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: 443}
	dnsTransport := &HTTP3Transport{
		dialer:      &terminalErrorDialer{conn: conn},
		destination: &url.URL{Scheme: "https", Host: "dns.example", Path: "/dns-query"},
		headers:     make(http.Header),
		serverAddr:  serverAddr,
		tlsConfig:   tlsConfig.config,
	}
	dnsTransport.transport = dnsTransport.newTransport()
	defer func() {
		if err := dnsTransport.Close(); err != nil {
			t.Error(err)
		}
	}()

	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	_, err := dnsTransport.Exchange(ctx, newDNSQuery())
	if err == nil {
		t.Fatal("expected DNS-over-HTTP/3 exchange error")
	}
	if ctx.Err() != nil {
		t.Fatal("DNS-over-HTTP/3 exchange waited for context timeout")
	}
	if reads := conn.reads.Load(); reads != 1 {
		t.Fatalf("DNS-over-HTTP/3 reads = %d, want 1", reads)
	}
	if !conn.closed.Load() {
		t.Fatal("DNS-over-HTTP/3 packet connection was not closed")
	}
}
