package transport

import (
	"context"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	boxTLS "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
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
	return &net.UDPAddr{}
}

func (c *terminalErrorConn) RemoteAddr() net.Addr {
	return &net.UDPAddr{}
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

type terminalErrorTLSConn struct {
	*terminalErrorConn
}

func (c *terminalErrorTLSConn) NetConn() net.Conn {
	return c.terminalErrorConn
}

func (c *terminalErrorTLSConn) HandshakeContext(context.Context) error {
	return nil
}

func (c *terminalErrorTLSConn) ConnectionState() boxTLS.ConnectionState {
	return boxTLS.ConnectionState{}
}

type terminalErrorTLSDialer struct {
	conn boxTLS.Conn
}

func (d *terminalErrorTLSDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}

func (d *terminalErrorTLSDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.ErrClosed
}

func (d *terminalErrorTLSDialer) DialTLSContext(context.Context, M.Socksaddr) (boxTLS.Conn, error) {
	return d.conn, nil
}

func testStreamError() http2.StreamError {
	return http2.StreamError{
		StreamID: 1,
		Code:     http2.ErrCodeCancel,
	}
}

func TestReadMessageStopsOnHTTP2StreamError(t *testing.T) {
	conn := &terminalErrorConn{err: testStreamError()}
	_, err := ReadMessage(conn)
	if err == nil {
		t.Fatal("expected read error")
	}
	if reads := conn.reads.Load(); reads != 1 {
		t.Fatalf("DNS stream reads = %d, want 1", reads)
	}
}

func TestTCPExchangeClosesAfterHTTP2StreamError(t *testing.T) {
	conn := &terminalErrorConn{err: testStreamError()}
	transport := &TCPTransport{
		TransportAdapter: dns.NewTransportAdapter("tcp", "test", nil),
		dialer:           &terminalErrorDialer{conn: conn},
		serverAddr:       M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: 53},
	}
	message := &mDNS.Msg{}
	message.SetQuestion("example.com.", mDNS.TypeA)

	_, err := transport.Exchange(t.Context(), message)
	if err == nil {
		t.Fatal("expected exchange error")
	}
	if reads := conn.reads.Load(); reads != 1 {
		t.Fatalf("TCP DNS reads = %d, want 1", reads)
	}
	if !conn.closed.Load() {
		t.Fatal("TCP DNS connection was not closed")
	}
}

func TestTLSExchangeDiscardsAfterHTTP2StreamError(t *testing.T) {
	rawConn := &terminalErrorConn{err: testStreamError()}
	tlsConn := &terminalErrorTLSConn{terminalErrorConn: rawConn}
	transport := &TLSTransport{
		TransportAdapter: dns.NewTransportAdapter("tls", "test", nil),
		logger:           log.NewNOPFactory().Logger(),
		dialer:           &terminalErrorTLSDialer{conn: tlsConn},
		serverAddr:       M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: 853},
		connections: NewConnPool(ConnPoolOptions[*tlsDNSConn]{
			Mode:        ConnPoolOrdered,
			MaxInflight: tlsDNSMaxInflight,
			IsAlive: func(conn *tlsDNSConn) bool {
				return conn != nil
			},
			Close: func(conn *tlsDNSConn, _ error) {
				_ = conn.Close()
			},
		}),
	}
	message := &mDNS.Msg{}
	message.SetQuestion("example.com.", mDNS.TypeA)

	_, err := transport.Exchange(t.Context(), message)
	if err == nil {
		t.Fatal("expected exchange error")
	}
	if reads := rawConn.reads.Load(); reads != 1 {
		t.Fatalf("TLS DNS reads = %d, want 1", reads)
	}
	if !rawConn.closed.Load() {
		t.Fatal("TLS DNS connection was not discarded")
	}
}

func TestUDPReceiveLoopInvalidatesAfterHTTP2StreamError(t *testing.T) {
	conn := &terminalErrorConn{err: testStreamError()}
	pool := NewConnPool(ConnPoolOptions[net.Conn]{
		Mode:    ConnPoolSingle,
		IsAlive: func(net.Conn) bool { return true },
		Close: func(conn net.Conn, _ error) {
			_ = conn.Close()
		},
	})
	defer func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
	}()
	acquired, _, _, err := pool.AcquireShared(t.Context(), func(context.Context) (net.Conn, error) {
		return conn, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	transport := &UDPTransport{
		connection: pool,
		callbacks:  make(map[uint16]*udpCallback),
	}
	transport.udpSize.Store(2048)

	transport.recvLoop(acquired)

	if reads := conn.reads.Load(); reads != 1 {
		t.Fatalf("UDP DNS reads = %d, want 1", reads)
	}
	if !conn.closed.Load() {
		t.Fatal("UDP DNS connection was not invalidated")
	}
	replacement := &terminalErrorConn{err: net.ErrClosed}
	acquired, _, created, err := pool.AcquireShared(t.Context(), func(context.Context) (net.Conn, error) {
		return replacement, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if acquired != replacement || !created {
		t.Fatal("UDP DNS connection pool reused the failed connection")
	}
}
