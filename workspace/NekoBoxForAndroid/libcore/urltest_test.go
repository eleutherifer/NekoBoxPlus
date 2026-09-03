package libcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func TestLookupURLTestHostUsesConfiguredResolver(t *testing.T) {
	var resolvedHost string
	addresses, err := lookupURLTestHost(
		t.Context(),
		"https://connectivity.example/check",
		false,
		func(_ context.Context, host string) ([]netip.Addr, error) {
			resolvedHost = host
			return []netip.Addr{netip.MustParseAddr("192.0.2.10")}, nil
		},
		func(context.Context, string) ([]netip.Addr, error) {
			t.Fatal("system DNS called after proxy DNS success")
			return nil, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if resolvedHost != "connectivity.example" {
		t.Fatalf("resolved host = %q", resolvedHost)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("192.0.2.10")}) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestLookupURLTestHostSkipsIPLiteral(t *testing.T) {
	addresses, err := lookupURLTestHost(
		t.Context(),
		"https://[2001:db8::10]/check",
		false,
		func(context.Context, string) ([]netip.Addr, error) {
			t.Fatal("proxy lookup called for IP literal")
			return nil, nil
		},
		func(context.Context, string) ([]netip.Addr, error) {
			t.Fatal("system lookup called for IP literal")
			return nil, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if addresses != nil {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestLookupURLTestHostFailsWithoutAddresses(t *testing.T) {
	_, err := lookupURLTestHost(
		t.Context(),
		"https://connectivity.example/check",
		false,
		func(context.Context, string) ([]netip.Addr, error) {
			return nil, nil
		},
		func(context.Context, string) ([]netip.Addr, error) {
			return nil, nil
		},
	)
	if err == nil {
		t.Fatal("expected empty DNS response to fail")
	}
}

func TestLookupURLTestHostHonorsContextWhenResolverBlocks(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	defer close(release)

	started := time.Now()
	_, err := lookupURLTestHost(
		ctx,
		"https://connectivity.example/check",
		false,
		func(context.Context, string) ([]netip.Addr, error) {
			<-release
			return nil, nil
		},
		func(context.Context, string) ([]netip.Addr, error) {
			t.Fatal("system DNS called after context deadline")
			return nil, nil
		},
	)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected deadline exceeded, got %v", err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("blocking lookup returned too late: %v", elapsed)
	}
}

func TestLookupURLTestHostRecoversResolverPanic(t *testing.T) {
	_, err := lookupURLTestHost(
		t.Context(),
		"https://connectivity.example/check",
		false,
		func(context.Context, string) ([]netip.Addr, error) {
			panic("resolver panic")
		},
		func(context.Context, string) ([]netip.Addr, error) {
			return nil, errors.New("system DNS failed")
		},
	)
	if err == nil || !strings.Contains(err.Error(), "resolver panic") {
		t.Fatalf("expected resolver panic error, got %v", err)
	}
}

func TestLookupURLTestHostHardenedRetriesUntilSuccess(t *testing.T) {
	var calls atomic.Int32
	addresses, err := lookupURLTestHost(
		t.Context(),
		"https://connectivity.example/check",
		true,
		func(context.Context, string) ([]netip.Addr, error) {
			if calls.Add(1) < 3 {
				return nil, errors.New("temporary DNS failure")
			}
			return []netip.Addr{netip.MustParseAddr("192.0.2.20")}, nil
		},
		func(context.Context, string) ([]netip.Addr, error) {
			return nil, errors.New("temporary system DNS failure")
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if got := calls.Load(); got != 3 {
		t.Fatalf("lookups = %d, want 3", got)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("192.0.2.20")}) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestLookupURLTestHostFallsBackToSystemDNS(t *testing.T) {
	var localHost string
	addresses, err := lookupURLTestHost(
		t.Context(),
		"https://connectivity.example/check",
		false,
		func(context.Context, string) ([]netip.Addr, error) {
			return nil, errors.New("proxy DNS failed")
		},
		func(_ context.Context, host string) ([]netip.Addr, error) {
			localHost = host
			return []netip.Addr{netip.MustParseAddr("192.0.2.25")}, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if localHost != "connectivity.example" {
		t.Fatalf("system DNS host = %q", localHost)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("192.0.2.25")}) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestDialURLTestDestinationFallsBackToSystemAddresses(t *testing.T) {
	resolver := &fakeGroupURLTestResolver{result: "192.0.2.31\n192.0.2.32"}
	destination := M.ParseSocksaddrHostPort("connectivity.example", 443)
	var destinations []M.Socksaddr
	client, server := net.Pipe()
	t.Cleanup(func() {
		_ = client.Close()
		_ = server.Close()
	})

	conn, err := dialURLTestDestination(
		t.Context(),
		"tcp",
		destination,
		func(_ context.Context, _ string, target M.Socksaddr) (net.Conn, error) {
			destinations = append(destinations, target)
			if target.Addr == netip.MustParseAddr("192.0.2.32") {
				return client, nil
			}
			return nil, errors.New("dial failed")
		},
		resolver,
	)
	if err != nil {
		t.Fatal(err)
	}
	if conn != client {
		t.Fatal("fallback did not return the successful connection")
	}
	want := []M.Socksaddr{
		destination,
		{Addr: netip.MustParseAddr("192.0.2.31"), Port: 443},
		{Addr: netip.MustParseAddr("192.0.2.32"), Port: 443},
	}
	if !slices.Equal(destinations, want) {
		t.Fatalf("destinations = %v, want %v", destinations, want)
	}
}

func TestPrepareURLTestConnectionCachesSuccessAndInvalidates(t *testing.T) {
	instance := new(BoxInstance)
	var calls atomic.Int32
	var servers []net.Conn
	t.Cleanup(func() {
		for _, server := range servers {
			_ = server.Close()
		}
	})
	dial := func(context.Context) (net.Conn, error) {
		calls.Add(1)
		client, server := net.Pipe()
		servers = append(servers, server)
		return client, nil
	}

	prepared, err := instance.prepareURLTestConnection(t.Context(), dial)
	if err != nil {
		t.Fatal(err)
	}
	if conn := prepared.take(); conn == nil {
		t.Fatal("prepared connection was not retained")
	} else {
		_ = conn.Close()
	}
	cached, err := instance.prepareURLTestConnection(t.Context(), dial)
	if err != nil {
		t.Fatal(err)
	}
	if cached != nil {
		t.Fatal("cached readiness returned another prepared connection")
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("dials = %d, want 1", got)
	}

	instance.urlTestReady.invalidate()
	prepared, err = instance.prepareURLTestConnection(t.Context(), dial)
	if err != nil {
		t.Fatal(err)
	}
	prepared.close()
	if got := calls.Load(); got != 2 {
		t.Fatalf("dials after invalidation = %d, want 2", got)
	}
}

func TestPreparedURLTestTransportConsumesConnectionOnce(t *testing.T) {
	preparedClient, preparedServer := net.Pipe()
	t.Cleanup(func() { _ = preparedServer.Close() })
	fallbackClient, fallbackServer := net.Pipe()
	t.Cleanup(func() { _ = fallbackServer.Close() })
	var fallbackCalls atomic.Int32
	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			fallbackCalls.Add(1)
			return fallbackClient, nil
		},
	}
	prepared := &preparedURLTestConnection{conn: preparedClient}
	wrapPreparedURLTestTransport(transport, prepared)

	first, err := transport.DialContext(t.Context(), "tcp", "connectivity.example:443")
	if err != nil {
		t.Fatal(err)
	}
	if first != preparedClient {
		t.Fatal("first dial did not consume prepared connection")
	}
	second, err := transport.DialContext(t.Context(), "tcp", "connectivity.example:443")
	if err != nil {
		t.Fatal(err)
	}
	if second != fallbackClient || fallbackCalls.Load() != 1 {
		t.Fatal("second dial did not use fallback")
	}
	_ = first.Close()
	_ = second.Close()
}

func TestRunURLTestAsyncHonorsContextWhenTesterBlocks(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	workerDone := make(chan struct{})

	_, err := runURLTestAsync(ctx, "blocking URLTest", func() (int32, error) {
		defer close(workerDone)
		<-release
		return 1, nil
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected deadline exceeded, got %v", err)
	}
	close(release)
	select {
	case <-workerDone:
	case <-time.After(time.Second):
		t.Fatal("late URLTest worker did not finish")
	}
}

func TestRunURLTestAsyncRecoversTesterPanic(t *testing.T) {
	_, err := runURLTestAsync(t.Context(), "panicking URLTest", func() (int32, error) {
		panic("tester panic")
	})
	if err == nil || !strings.Contains(err.Error(), "tester panic") {
		t.Fatalf("expected tester panic error, got %v", err)
	}
}

func TestRunURLTestAttemptsSucceedsAfterFailures(t *testing.T) {
	wantErr := errors.New("probe failed")
	var calls int
	latency, err := runURLTestAttempts(t.Context(), 1000, 5, 0, func(context.Context) (int32, error) {
		calls++
		if calls < 3 {
			return -1, wantErr
		}
		return 42, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if latency != 42 || calls != 3 {
		t.Fatalf("latency = %d, calls = %d", latency, calls)
	}
}

func TestRunURLTestAttemptsReturnsLastError(t *testing.T) {
	errorsByAttempt := []error{errors.New("first"), errors.New("second"), errors.New("last")}
	var calls int
	latency, err := runURLTestAttempts(t.Context(), 1000, 3, 0, func(context.Context) (int32, error) {
		result := errorsByAttempt[calls]
		calls++
		return -1, result
	})
	if latency != -1 || !errors.Is(err, errorsByAttempt[2]) || calls != 3 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestRunURLTestAttemptsSharesTimeoutAcrossAttempts(t *testing.T) {
	var deadline time.Time
	var calls int
	_, _ = runURLTestAttempts(t.Context(), 1000, 3, 0, func(ctx context.Context) (int32, error) {
		currentDeadline, ok := ctx.Deadline()
		if !ok {
			t.Fatal("attempt context has no deadline")
		}
		if calls == 0 {
			deadline = currentDeadline
		} else if currentDeadline != deadline {
			t.Fatalf("deadline changed between attempts: got %v, want %v", currentDeadline, deadline)
		}
		calls++
		return -1, errors.New("failed")
	})
	if calls != 3 {
		t.Fatalf("calls = %d, want 3", calls)
	}
}

func TestRunURLTestAttemptsClampsAttemptCount(t *testing.T) {
	var calls int
	_, _ = runURLTestAttempts(t.Context(), 1000, 10, 0, func(context.Context) (int32, error) {
		calls++
		return -1, errors.New("failed")
	})
	if calls != 5 {
		t.Fatalf("calls = %d, want 5", calls)
	}
}

func TestRunURLTestAttemptsHonorsCancellationDuringPause(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	wantErr := errors.New("failed")
	var calls int
	_, err := runURLTestAttempts(ctx, 1000, 5, 1000, func(context.Context) (int32, error) {
		calls++
		cancel()
		return -1, wantErr
	})
	if !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatalf("error = %v, calls = %d", err, calls)
	}
}

func TestRemainingURLTestTimeoutUsesSharedDeadline(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()
	time.Sleep(20 * time.Millisecond)

	remaining, err := remainingURLTestTimeout(ctx, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if remaining >= 200*time.Millisecond || remaining <= 0 {
		t.Fatalf("unexpected remaining timeout: %v", remaining)
	}
}

func TestURLTestConnectionSetClosesTrackedConnections(t *testing.T) {
	connections := newURLTestConnectionSet()
	client, server := net.Pipe()
	t.Cleanup(func() { _ = server.Close() })
	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return client, nil
		},
	}
	trackURLTestConnections(transport, connections, time.Second)

	conn, err := transport.DialContext(t.Context(), "tcp", "connectivity.example:443")
	if err != nil {
		t.Fatal(err)
	}
	connections.closeAll()
	if _, err = conn.Write([]byte("test")); err == nil {
		t.Fatal("tracked connection remained open")
	}

	lateClient, lateServer := net.Pipe()
	t.Cleanup(func() { _ = lateServer.Close() })
	lateConn := connections.track(lateClient)
	if _, err = lateConn.Write([]byte("test")); err == nil {
		t.Fatal("connection tracked after cleanup remained open")
	}
}

func TestDialResolvedURLTestAddressesUsesResolvedIPs(t *testing.T) {
	wantErr := errors.New("first address failed")
	var dialed []string
	client, server := net.Pipe()
	t.Cleanup(func() { _ = client.Close() })
	t.Cleanup(func() { _ = server.Close() })

	conn, err := dialResolvedURLTestAddresses(
		t.Context(),
		"tcp",
		"connectivity.example:443",
		[]netip.Addr{netip.MustParseAddr("192.0.2.10"), netip.MustParseAddr("2001:db8::10")},
		func(_ context.Context, _ string, address string) (net.Conn, error) {
			dialed = append(dialed, address)
			if len(dialed) == 1 {
				return nil, wantErr
			}
			return client, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if conn != client {
		t.Fatal("unexpected connection")
	}
	wantDialed := []string{"192.0.2.10:443", "[2001:db8::10]:443"}
	if !slices.Equal(dialed, wantDialed) {
		t.Fatalf("dialed = %v, want %v", dialed, wantDialed)
	}
}
