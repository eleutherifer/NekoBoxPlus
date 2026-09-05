package netbind

import (
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func resetBindingsForTest(t *testing.T) {
	t.Helper()
	SetInterface("")
	SetAddress("", "")
}

func TestSetAddressFiresOnChange(t *testing.T) {
	resetBindingsForTest(t)

	var count atomic.Int32
	handle := OnChange(func() { count.Add(1) })
	t.Cleanup(func() {
		RemoveHook(handle)
		resetBindingsForTest(t)
	})

	SetAddress("192.0.2.10", "")
	SetAddress("192.0.2.10", "")
	SetAddress("192.0.2.11", "")
	SetAddress("192.0.2.11", "2001:db8::11")

	if got := count.Load(); got != 3 {
		t.Fatalf("OnChange fired %d times, want 3", got)
	}
	if got := CurrentIPv4(); got != "192.0.2.11" {
		t.Fatalf("CurrentIPv4() = %q, want 192.0.2.11", got)
	}
	if got := CurrentIPv6(); got != "2001:db8::11" {
		t.Fatalf("CurrentIPv6() = %q, want 2001:db8::11", got)
	}
}

func TestDialUDPUsesConfiguredIPv4Source(t *testing.T) {
	resetBindingsForTest(t)
	SetAddress("127.0.0.1", "")
	t.Cleanup(func() { resetBindingsForTest(t) })

	echo, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatalf("listen echo UDP: %v", err)
	}
	defer echo.Close()

	conn, err := DialUDP("udp", nil, echo.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatalf("DialUDP with configured source: %v", err)
	}
	defer conn.Close()

	if got := conn.LocalAddr().(*net.UDPAddr).IP.String(); got != "127.0.0.1" {
		t.Fatalf("local IP = %s, want 127.0.0.1", got)
	}
}

func TestListenUDPWithoutBindingReceivesPackets(t *testing.T) {
	resetBindingsForTest(t)

	listener, err := ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatalf("ListenUDP: %v", err)
	}
	defer listener.Close()

	addr, ok := listener.LocalAddr().(*net.UDPAddr)
	if !ok {
		t.Fatalf("unexpected local addr type %T", listener.LocalAddr())
	}
	target := *addr
	if target.IP == nil || target.IP.IsUnspecified() {
		target.IP = net.ParseIP("127.0.0.1")
	}

	sender, err := net.DialUDP("udp", nil, &target)
	if err != nil {
		t.Fatalf("DialUDP sender: %v", err)
	}
	defer sender.Close()

	if _, err := sender.Write([]byte("ping")); err != nil {
		t.Fatalf("sender write: %v", err)
	}

	buf := make([]byte, 16)
	_ = listener.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := listener.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("listener read: %v", err)
	}
	if string(buf[:n]) != "ping" {
		t.Errorf("payload = %q, want ping", buf[:n])
	}
}

func TestListenUDPUsesConfiguredIPv4Source(t *testing.T) {
	resetBindingsForTest(t)
	SetAddress("127.0.0.1", "")
	t.Cleanup(func() { resetBindingsForTest(t) })

	conn, err := ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatalf("ListenUDP with configured source: %v", err)
	}
	defer conn.Close()

	if got := conn.LocalAddr().(*net.UDPAddr).IP.String(); got != "127.0.0.1" {
		t.Fatalf("local IP = %s, want 127.0.0.1", got)
	}
}
