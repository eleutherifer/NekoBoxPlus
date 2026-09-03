package awg

import (
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func TestNetworkTunResetConnections(t *testing.T) {
	tunAdapter, err := newNetworkTun(
		[]netip.Prefix{netip.MustParsePrefix("192.0.2.1/24")},
		1408,
	)
	if err != nil {
		t.Fatal(err)
	}
	tunDevice := tunAdapter.(*networkTun)
	defer tunDevice.Close()

	firstConn := newNetworkTunPacketConn(t, tunDevice)
	tunDevice.ResetConnections()
	assertPacketConnClosed(t, firstConn)

	secondConn := newNetworkTunPacketConn(t, tunDevice)
	tunDevice.ResetConnections()
	assertPacketConnClosed(t, secondConn)
}

func TestNetworkTunResetConnectionsConcurrent(t *testing.T) {
	tunAdapter, err := newNetworkTun(
		[]netip.Prefix{netip.MustParsePrefix("192.0.2.1/24")},
		1408,
	)
	if err != nil {
		t.Fatal(err)
	}
	tunDevice := tunAdapter.(*networkTun)
	defer tunDevice.Close()

	var wg sync.WaitGroup
	for range 32 {
		wg.Go(func() {
			conn, err := tunDevice.ListenPacket(
				t.Context(),
				M.SocksaddrFrom(netip.MustParseAddr("192.0.2.2"), 51820),
			)
			if err != nil {
				t.Error(err)
				return
			}
			defer conn.Close()
			tunDevice.ResetConnections()
		})
	}
	wg.Wait()
}

func newNetworkTunPacketConn(t *testing.T, tunDevice *networkTun) net.PacketConn {
	t.Helper()
	conn, err := tunDevice.ListenPacket(
		t.Context(),
		M.SocksaddrFrom(netip.MustParseAddr("192.0.2.2"), 51820),
	)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func assertPacketConnClosed(t *testing.T, conn net.PacketConn) {
	t.Helper()
	if err := conn.SetDeadline(time.Now().Add(time.Second)); err != nil && !errors.Is(err, net.ErrClosed) {
		t.Fatal(err)
	}
	_, _, err := conn.ReadFrom(make([]byte, 1))
	if !errors.Is(err, net.ErrClosed) && !errors.Is(err, io.EOF) {
		t.Fatalf("expected a closed connection error, got %v", err)
	}
}
