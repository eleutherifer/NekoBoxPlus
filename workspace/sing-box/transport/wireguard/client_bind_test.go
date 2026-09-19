package wireguard

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
)

type failingDialer struct {
	called chan struct{}
}

type blockingDialer struct {
	called chan struct{}
	once   sync.Once
}

func (d *blockingDialer) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	d.once.Do(func() { close(d.called) })
	<-ctx.Done()
	return nil, context.Cause(ctx)
}

func (d *blockingDialer) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	d.once.Do(func() { close(d.called) })
	<-ctx.Done()
	return nil, context.Cause(ctx)
}

type deadlinePacketConn struct {
	writeDeadlineSet chan struct{}
	once             sync.Once
}

func (c *deadlinePacketConn) ReadFrom([]byte) (int, net.Addr, error) {
	return 0, nil, io.EOF
}

func (c *deadlinePacketConn) WriteTo([]byte, net.Addr) (int, error) {
	select {
	case <-c.writeDeadlineSet:
		return 0, osErrDeadlineExceeded
	default:
		return 0, errors.New("write deadline was not set")
	}
}

func (c *deadlinePacketConn) Close() error                    { return nil }
func (c *deadlinePacketConn) LocalAddr() net.Addr             { return &net.UDPAddr{} }
func (c *deadlinePacketConn) SetDeadline(time.Time) error     { return nil }
func (c *deadlinePacketConn) SetReadDeadline(time.Time) error { return nil }
func (c *deadlinePacketConn) SetWriteDeadline(time.Time) error {
	c.once.Do(func() { close(c.writeDeadlineSet) })
	return nil
}

var osErrDeadlineExceeded = &timeoutError{}

type timeoutError struct{}

func (*timeoutError) Error() string   { return "deadline exceeded" }
func (*timeoutError) Timeout() bool   { return true }
func (*timeoutError) Temporary() bool { return true }

type packetDialer struct {
	conn net.PacketConn
}

type streamDialer struct {
	conn net.Conn
}

func (d *streamDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}

func (d *streamDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected listen")
}

func (d *packetDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("unexpected dial")
}

func (d *packetDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return d.conn, nil
}

func (d *failingDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	close(d.called)
	return nil, errors.New("dial failed")
}

func (d *failingDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	close(d.called)
	return nil, errors.New("listen failed")
}

func TestClientBindSendRetryCanceledByClose(t *testing.T) {
	dialer := &failingDialer{called: make(chan struct{})}
	bind := NewClientBind(
		t.Context(),
		logger.NOP(),
		dialer,
		true,
		netip.MustParseAddrPort("192.0.2.1:51820"),
		[3]uint8{},
	)
	_, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}

	sendDone := make(chan error, 1)
	go func() {
		sendDone <- bind.Send(nil, remoteEndpoint(netip.MustParseAddrPort("192.0.2.1:51820")), 0)
	}()
	<-dialer.called

	if err = bind.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-sendDone:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("expected net.ErrClosed, got %v", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Send did not stop after the bind was closed")
	}
}

func TestClientBindCloseCancelsInFlightDial(t *testing.T) {
	dialer := &blockingDialer{called: make(chan struct{})}
	bind := NewClientBind(
		t.Context(),
		logger.NOP(),
		dialer,
		true,
		netip.MustParseAddrPort("192.0.2.1:51820"),
		[3]uint8{},
	)
	_, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}

	sendDone := make(chan error, 1)
	go func() {
		sendDone <- bind.Send(nil, remoteEndpoint(netip.MustParseAddrPort("192.0.2.1:51820")), 0)
	}()
	<-dialer.called

	closeDone := make(chan error, 1)
	go func() {
		closeDone <- bind.Close()
	}()
	select {
	case err = <-closeDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Close blocked behind an in-flight dial")
	}
	select {
	case err = <-sendDone:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("expected net.ErrClosed, got %v", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("in-flight dial did not stop after Close")
	}
}

func TestClientBindSendSetsWriteDeadline(t *testing.T) {
	packetConn := &deadlinePacketConn{writeDeadlineSet: make(chan struct{})}
	bind := NewClientBind(
		t.Context(),
		logger.NOP(),
		&packetDialer{conn: packetConn},
		false,
		netip.AddrPort{},
		[3]uint8{},
	)
	_, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	defer bind.Close()

	err = bind.Send(
		[][]byte{{0, 0, 0, 0}},
		remoteEndpoint(netip.MustParseAddrPort("192.0.2.1:51820")),
		0,
	)
	if !errors.Is(err, osErrDeadlineExceeded) {
		t.Fatalf("expected deadline error, got %v", err)
	}
}

func TestClientBindUsesPeerEndpointForReceivedPacket(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("192.0.2.1:51820")
	clientConn, serverConn := net.Pipe()
	t.Cleanup(func() {
		_ = clientConn.Close()
		_ = serverConn.Close()
	})
	bind := NewClientBind(
		t.Context(),
		logger.NOP(),
		&streamDialer{conn: clientConn},
		true,
		peerEndpoint,
		[3]uint8{},
	)
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := bind.Close(); err != nil {
			t.Fatal(err)
		}
	})

	packetConn, err := bind.connect()
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		_, _ = serverConn.Write([]byte{1, 2, 3, 4})
	}()
	buffer := make([]byte, 4)
	_, source, err := packetConn.ReadFrom(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if sourceEndpoint := M.SocksaddrFromNet(source).AddrPort(); sourceEndpoint != peerEndpoint {
		t.Fatalf("received source mismatch: got %v, want %v", sourceEndpoint, peerEndpoint)
	}
}
