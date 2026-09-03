package awg

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var _ N.Dialer = (*fakeDialer)(nil)

type fakeDialer struct {
	dialCount   int
	listenCount int
	dialDest    M.Socksaddr
	listenDests []M.Socksaddr
	remoteAddr  net.Addr
	lastConn    *fakeConn
}

type blockingDialer struct {
	called chan struct{}
	once   sync.Once
}

type reconnectDialer struct {
	dialCount int
}

func (d *reconnectDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	d.dialCount++
	var writeErr error
	if d.dialCount == 1 {
		writeErr = errors.New("write failed")
	}
	return &fakeConn{
		remoteAddr: &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 51820},
		writeErr:   writeErr,
	}, nil
}

func (d *reconnectDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected packet listener")
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

func (d *fakeDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.dialCount++
	d.dialDest = destination
	d.lastConn = &fakeConn{
		localAddr:  &net.UDPAddr{IP: net.IPv6zero, Port: 12345},
		remoteAddr: d.remoteAddr,
	}
	return d.lastConn, nil
}

func (d *fakeDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	d.listenCount++
	d.listenDests = append(d.listenDests, destination)
	return &fakePacketConn{
		localAddr: &net.UDPAddr{
			IP:   destination.Addr.AsSlice(),
			Port: int(destination.Port),
		},
	}, nil
}

type fakeConn struct {
	localAddr  net.Addr
	remoteAddr net.Addr
	readData   []byte
	writeErr   error
	deadline   bool
}

func (c *fakeConn) Read(b []byte) (int, error) {
	if len(c.readData) > 0 {
		n := copy(b, c.readData)
		c.readData = c.readData[n:]
		return n, nil
	}
	return 0, io.EOF
}

func (c *fakeConn) Write(b []byte) (int, error) {
	if c.writeErr != nil {
		return 0, c.writeErr
	}
	return len(b), nil
}

func (c *fakeConn) Close() error {
	return nil
}

func (c *fakeConn) LocalAddr() net.Addr {
	return c.localAddr
}

func (c *fakeConn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

func (c *fakeConn) SetDeadline(t time.Time) error {
	return nil
}

func (c *fakeConn) SetReadDeadline(t time.Time) error {
	return nil
}

func (c *fakeConn) SetWriteDeadline(t time.Time) error {
	c.deadline = true
	return nil
}

type fakePacketConn struct {
	localAddr net.Addr
}

func (c *fakePacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	return 0, nil, io.EOF
}

func (c *fakePacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	return len(b), nil
}

func (c *fakePacketConn) Close() error {
	return nil
}

func (c *fakePacketConn) LocalAddr() net.Addr {
	return c.localAddr
}

func (c *fakePacketConn) SetDeadline(t time.Time) error {
	return nil
}

func (c *fakePacketConn) SetReadDeadline(t time.Time) error {
	return nil
}

func (c *fakePacketConn) SetWriteDeadline(t time.Time) error {
	return nil
}

func TestBindOpenWithoutPeerEndpointUsesPacketListeners(t *testing.T) {
	dialer := &fakeDialer{}
	bind := newBind(context.Background(), logger.NOP(), dialer, false, netip.AddrPort{}, [3]uint8{}, nil)

	receiveFuncs, actualPort, err := bind.Open(1234)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := bind.Close(); err != nil {
			t.Fatal(err)
		}
	})

	if actualPort != 1234 {
		t.Fatalf("actual port mismatch: got %d, want 1234", actualPort)
	}
	if len(receiveFuncs) != 2 {
		t.Fatalf("receive func count mismatch: got %d, want 2", len(receiveFuncs))
	}
	if dialer.dialCount != 0 {
		t.Fatalf("dial count mismatch: got %d, want 0", dialer.dialCount)
	}
	if dialer.listenCount != 2 {
		t.Fatalf("listen count mismatch: got %d, want 2", dialer.listenCount)
	}
}

func TestBindOpenWithPeerEndpointUsesConnectedDial(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("[2001:db8::1]:2408")
	dialer := &fakeDialer{
		remoteAddr: &net.UDPAddr{
			IP:   peerEndpoint.Addr().AsSlice(),
			Port: int(peerEndpoint.Port()),
		},
	}
	bind := newBind(context.Background(), logger.NOP(), dialer, false, peerEndpoint, [3]uint8{}, nil)

	receiveFuncs, actualPort, err := bind.Open(1234)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := bind.Close(); err != nil {
			t.Fatal(err)
		}
	})

	if actualPort != 1234 {
		t.Fatalf("actual port mismatch: got %d, want 1234", actualPort)
	}
	if len(receiveFuncs) != 1 {
		t.Fatalf("receive func count mismatch: got %d, want 1", len(receiveFuncs))
	}
	if dialer.dialCount != 1 {
		t.Fatalf("dial count mismatch: got %d, want 1", dialer.dialCount)
	}
	if dialer.listenCount != 0 {
		t.Fatalf("listen count mismatch: got %d, want 0", dialer.listenCount)
	}
	if dialer.dialDest.AddrPort() != peerEndpoint {
		t.Fatalf("dial destination mismatch: got %v, want %v", dialer.dialDest, peerEndpoint)
	}
}

func TestConnectedBindUsesPeerEndpointForReceivedPacket(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("192.0.2.1:51820")
	dialer := &fakeDialer{remoteAddr: M.Socksaddr{Fqdn: "naive.example", Port: 443}.TCPAddr()}
	bind := newBind(t.Context(), logger.NOP(), dialer, false, peerEndpoint, [3]uint8{}, nil).(*bind_adapter)

	packetConn, err := bind.connect(t.Context(), peerEndpoint.Addr(), peerEndpoint.Port())
	if err != nil {
		t.Fatal(err)
	}
	dialer.lastConn.readData = []byte{1, 2, 3, 4}

	buffer := make([]byte, 4)
	_, source, err := packetConn.ReadFrom(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if sourceEndpoint := M.SocksaddrFromNet(source).AddrPort(); sourceEndpoint != peerEndpoint {
		t.Fatalf("received source mismatch: got %v, want %v", sourceEndpoint, peerEndpoint)
	}
}

func TestLazyBindOpenDoesNotDial(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("192.0.2.1:51820")
	dialer := &fakeDialer{
		remoteAddr: &net.UDPAddr{
			IP:   peerEndpoint.Addr().AsSlice(),
			Port: int(peerEndpoint.Port()),
		},
	}
	bind := newBind(t.Context(), logger.NOP(), dialer, true, peerEndpoint, [3]uint8{}, nil)

	receiveFuncs, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bind.Close() })
	if len(receiveFuncs) != 1 {
		t.Fatalf("receive func count mismatch: got %d, want 1", len(receiveFuncs))
	}
	if dialer.dialCount != 0 {
		t.Fatalf("lazy Open dialed %d times", dialer.dialCount)
	}

	err = bind.Send([][]byte{{1, 2, 3, 4}}, &bind_endpoint{AddrPort: peerEndpoint})
	if err != nil {
		t.Fatal(err)
	}
	if dialer.dialCount != 1 {
		t.Fatalf("first Send dialed %d times, want 1", dialer.dialCount)
	}
	if !dialer.lastConn.deadline {
		t.Fatal("Send did not set a write deadline")
	}
}

func TestLazyBindReceiveReconnectsAfterReadFailure(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("192.0.2.1:51820")
	dialer := &fakeDialer{
		remoteAddr: &net.UDPAddr{
			IP:   peerEndpoint.Addr().AsSlice(),
			Port: int(peerEndpoint.Port()),
		},
	}
	bind := newBind(t.Context(), logger.NOP(), dialer, true, peerEndpoint, [3]uint8{}, nil)
	receiveFuncs, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bind.Close() })

	packets := [][]byte{make([]byte, 64)}
	sizes := make([]int, 1)
	endpoints := make([]conn.Endpoint, 1)
	if _, err = receiveFuncs[0](packets, sizes, endpoints); err != nil {
		t.Fatal(err)
	}
	if _, err = receiveFuncs[0](packets, sizes, endpoints); err != nil {
		t.Fatal(err)
	}
	if dialer.dialCount != 2 {
		t.Fatalf("read failure did not recreate connection: got %d dials, want 2", dialer.dialCount)
	}
}

func TestLazyBindCloseCancelsInFlightDial(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("192.0.2.1:51820")
	dialer := &blockingDialer{called: make(chan struct{})}
	bind := newBind(t.Context(), logger.NOP(), dialer, true, peerEndpoint, [3]uint8{}, nil)
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal(err)
	}

	sendDone := make(chan error, 1)
	go func() {
		sendDone <- bind.Send([][]byte{{1, 2, 3, 4}}, &bind_endpoint{AddrPort: peerEndpoint})
	}()
	<-dialer.called

	closeDone := make(chan error, 1)
	go func() {
		closeDone <- bind.Close()
	}()
	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Close blocked behind in-flight dial")
	}
	select {
	case err := <-sendDone:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("expected net.ErrClosed, got %v", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("in-flight dial was not canceled")
	}
}

func TestLazyBindSendReconnectsAfterWriteFailure(t *testing.T) {
	peerEndpoint := netip.MustParseAddrPort("192.0.2.1:51820")
	dialer := &reconnectDialer{}
	bind := newBind(t.Context(), logger.NOP(), dialer, true, peerEndpoint, [3]uint8{}, nil)
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bind.Close() })

	endpoint := &bind_endpoint{AddrPort: peerEndpoint}
	if err := bind.Send([][]byte{{1, 2, 3, 4}}, endpoint); err == nil {
		t.Fatal("expected first write to fail")
	}
	if err := bind.Send([][]byte{{1, 2, 3, 4}}, endpoint); err != nil {
		t.Fatal(err)
	}
	if dialer.dialCount != 2 {
		t.Fatalf("write failure did not recreate connection: got %d dials, want 2", dialer.dialCount)
	}
}
