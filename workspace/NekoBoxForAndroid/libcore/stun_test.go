package libcore

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"testing"
	"time"
)

func TestStunTestSessionValidatesAndDeduplicatesServers(t *testing.T) {
	session := NewStunTestSession(StunIPv6Disable)
	t.Cleanup(session.Close)

	for _, invalid := range []string{"", "example.com", ":3478", "example.com:0", "example.com:70000"} {
		if err := session.AddServer(invalid); err == nil {
			t.Fatalf("AddServer(%q) unexpectedly succeeded", invalid)
		}
	}
	if err := session.AddServer("stun.example.com:3478"); err != nil {
		t.Fatal(err)
	}
	if err := session.AddServer("STUN.EXAMPLE.COM:3478"); err != nil {
		t.Fatal(err)
	}
	if got := len(session.servers); got != 1 {
		t.Fatalf("expected one deduplicated server, got %d", got)
	}
}

func TestStunTestSessionPreservesInputOrder(t *testing.T) {
	session := NewStunTestSession(StunIPv6Disable)
	t.Cleanup(session.Close)
	servers := []string{"one.example:3478", "two.example:3478", "three.example:3478"}
	for _, server := range servers {
		if err := session.AddServer(server); err != nil {
			t.Fatal(err)
		}
	}
	session.runServer = func(_ context.Context, server string) *StunServerResult {
		if server == servers[0] {
			time.Sleep(20 * time.Millisecond)
		}
		return &StunServerResult{Server: server, BindingSuccess: true}
	}

	result := session.Run()
	got := make([]string, result.Count())
	for index := range result.Count() {
		got[index] = result.Get(index).Server
	}
	if !slices.Equal(got, servers) {
		t.Fatalf("result order %v does not match input %v", got, servers)
	}
	if result.Get(-1) != nil || result.Get(result.Count()) != nil {
		t.Fatal("out-of-range result lookup did not return nil")
	}
}

func TestStunTestSessionCloseCancelsRunningServers(t *testing.T) {
	session := NewStunTestSession(StunIPv6Disable)
	if err := session.AddServer("stun.example.com:3478"); err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	session.runServer = func(ctx context.Context, server string) *StunServerResult {
		close(started)
		<-ctx.Done()
		return &StunServerResult{
			Server:       server,
			ErrorCode:    "cancelled",
			ErrorMessage: context.Cause(ctx).Error(),
		}
	}
	done := make(chan *StunTestResult, 1)
	go func() {
		done <- session.Run()
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("STUN test did not start")
	}

	session.Close()
	select {
	case result := <-done:
		if result.Count() != 1 || result.Get(0).ErrorCode != "cancelled" {
			t.Fatalf("unexpected cancellation result: %#v", result.Get(0))
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("STUN cancellation did not unblock the run")
	}
	if !errors.Is(context.Cause(session.ctx), errStunCancelled) {
		t.Fatalf("unexpected cancellation cause: %v", context.Cause(session.ctx))
	}
}

func TestStunTestSessionCloseInterruptsUDPRead(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = server.Close() })
	session := NewStunTestSession(StunIPv6Disable)
	address := net.JoinHostPort(
		server.LocalAddr().(*net.UDPAddr).IP.String(),
		strconv.Itoa(server.LocalAddr().(*net.UDPAddr).Port),
	)
	if err := session.AddServer(address); err != nil {
		t.Fatal(err)
	}
	packetReceived := make(chan struct{})
	go func() {
		buffer := make([]byte, 1024)
		if _, _, readErr := server.ReadFromUDP(buffer); readErr == nil {
			close(packetReceived)
		}
	}()
	done := make(chan *StunTestResult, 1)
	go func() {
		done <- session.Run()
	}()
	select {
	case <-packetReceived:
	case <-time.After(time.Second):
		t.Fatal("STUN server did not receive a packet")
	}

	session.Close()
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("closing the session did not interrupt the UDP read")
	}
}

func TestStunTestSessionRejectsMoreThanMaximumServers(t *testing.T) {
	session := NewStunTestSession(StunIPv6Disable)
	t.Cleanup(session.Close)
	for index := range StunMaxServers {
		if err := session.AddServer("server" + string(rune('a'+index)) + ".example:3478"); err != nil {
			t.Fatal(err)
		}
	}
	if err := session.AddServer("overflow.example:3478"); err == nil {
		t.Fatal("expected too-many-servers error")
	}
}

func TestSelectStunServerAddressUsesIPv6Mode(t *testing.T) {
	ipv4 := netip.MustParseAddr("192.0.2.1")
	ipv6 := netip.MustParseAddr("2001:db8::1")
	tests := []struct {
		name      string
		mode      int32
		addresses []netip.Addr
		want      netip.Addr
		wantOK    bool
	}{
		{"disabled chooses IPv4", StunIPv6Disable, []netip.Addr{ipv6, ipv4}, ipv4, true},
		{"disabled rejects IPv6 only", StunIPv6Disable, []netip.Addr{ipv6}, netip.Addr{}, false},
		{"enabled prefers IPv4", StunIPv6Enable, []netip.Addr{ipv6, ipv4}, ipv4, true},
		{"enabled falls back to IPv6", StunIPv6Enable, []netip.Addr{ipv6}, ipv6, true},
		{"prefer chooses IPv6", StunIPv6Prefer, []netip.Addr{ipv4, ipv6}, ipv6, true},
		{"prefer falls back to IPv4", StunIPv6Prefer, []netip.Addr{ipv4}, ipv4, true},
		{"only chooses IPv6", StunIPv6Only, []netip.Addr{ipv4, ipv6}, ipv6, true},
		{"only rejects IPv4 only", StunIPv6Only, []netip.Addr{ipv4}, netip.Addr{}, false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, ok := selectStunServerAddress(test.addresses, test.mode)
			if ok != test.wantOK || got != test.want {
				t.Fatalf("selectStunServerAddress() = %v, %v; want %v, %v", got, ok, test.want, test.wantOK)
			}
		})
	}
}

func TestResolveStunServerUsesSelectedFamily(t *testing.T) {
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{
			netip.MustParseAddr("192.0.2.1"),
			netip.MustParseAddr("2001:db8::1"),
		}, nil
	}
	network, address, err := resolveStunServer(
		t.Context(),
		"stun.example:3478",
		StunIPv6Prefer,
		lookup,
	)
	if err != nil {
		t.Fatal(err)
	}
	if network != "udp6" || address.String() != "[2001:db8::1]:3478" {
		t.Fatalf("resolved %s %s", network, address)
	}
}

func TestResolveStunServerDoesNotResolveLiteral(t *testing.T) {
	lookupCalled := false
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		lookupCalled = true
		return nil, errors.New("unexpected lookup")
	}
	network, address, err := resolveStunServer(
		t.Context(),
		"[2001:db8::1]:3478",
		StunIPv6Disable,
		lookup,
	)
	if err != nil {
		t.Fatal(err)
	}
	if lookupCalled || network != "udp6" || address.String() != "[2001:db8::1]:3478" {
		t.Fatalf("resolved %s %s, lookup called: %v", network, address, lookupCalled)
	}
}

func TestResolveStunServerReportsMissingRequiredFamilyAsDNS(t *testing.T) {
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("192.0.2.1")}, nil
	}
	_, _, err := resolveStunServer(
		t.Context(),
		"stun.example:3478",
		StunIPv6Only,
		lookup,
	)
	if _, ok := errors.AsType[*net.DNSError](err); !ok {
		t.Fatalf("expected DNS error, got %v", err)
	}
}

func TestNewStunTestSessionDefaultsInvalidIPv6Mode(t *testing.T) {
	session := NewStunTestSession(99)
	t.Cleanup(session.Close)
	if session.ipv6Mode != StunIPv6Disable {
		t.Fatalf("invalid mode normalized to %d", session.ipv6Mode)
	}
}
