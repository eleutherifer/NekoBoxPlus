package libcore

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type fakeGroupURLTestResolver struct {
	lookups atomic.Int32
	result  string
	err     error
}

func (r *fakeGroupURLTestResolver) Raw() bool            { return false }
func (r *fakeGroupURLTestResolver) NetworkHandle() int64 { return 0 }

func (r *fakeGroupURLTestResolver) Lookup(ctx *ExchangeContext, network, domain string) error {
	r.lookups.Add(1)
	if r.err == nil && r.result != "" {
		ctx.Success(r.result)
	}
	return r.err
}

func (*fakeGroupURLTestResolver) Exchange(*ExchangeContext, []byte) error {
	return errors.New("unexpected raw DNS exchange")
}

func TestNewGroupURLTesterKeepsDomainForOutboundResolution(t *testing.T) {
	resolver := &fakeGroupURLTestResolver{err: errors.New("local DNS is unavailable")}
	tester, err := NewGroupURLTester("https://connectivity.example/check", 1000, 1, 50, false, resolver)
	if err != nil {
		t.Fatal(err)
	}
	if got := resolver.lookups.Load(); got != 0 {
		t.Fatalf("lookups = %d", got)
	}
	if got := tester.destination.Fqdn; got != "connectivity.example" {
		t.Fatalf("destination = %v", got)
	}
	if tester.destination.Addr.IsValid() {
		t.Fatalf("destination address = %v", tester.destination.Addr)
	}
	if tester.destination.Port != 443 {
		t.Fatalf("port = %d", tester.destination.Port)
	}
	if tester.host != "connectivity.example" {
		t.Fatalf("host = %q", tester.host)
	}
}

func TestNewGroupURLTesterSkipsLookupForIPLiteral(t *testing.T) {
	resolver := &fakeGroupURLTestResolver{err: errors.New("lookup should not run")}
	tester, err := NewGroupURLTester("http://[2001:db8::10]/check", 1000, 1, 50, false, resolver)
	if err != nil {
		t.Fatal(err)
	}
	if got := resolver.lookups.Load(); got != 0 {
		t.Fatalf("lookups = %d", got)
	}
	if got := tester.destination.Addr; got != netip.MustParseAddr("2001:db8::10") {
		t.Fatalf("destination = %v", got)
	}
	if tester.destination.Port != 80 {
		t.Fatalf("port = %d", tester.destination.Port)
	}
}

type groupURLTestDialer struct {
	destination chan M.Socksaddr
	host        chan string
}

func (d *groupURLTestDialer) DialContext(_ context.Context, _ string, destination M.Socksaddr) (net.Conn, error) {
	client, server := net.Pipe()
	d.destination <- destination
	go func() {
		defer server.Close()
		request, err := http.ReadRequest(bufio.NewReader(server))
		if err != nil {
			d.host <- ""
			return
		}
		d.host <- request.Host
		_ = request.Body.Close()
		_, _ = server.Write([]byte("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"))
	}()
	return client, nil
}

func (*groupURLTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected packet listener")
}

func TestGroupURLTesterPassesDomainToOutboundAndPreservesHost(t *testing.T) {
	tester := &GroupURLTester{
		link:        "http://connectivity.example/check",
		host:        "connectivity.example",
		destination: M.ParseSocksaddrHostPort("connectivity.example", 80),
		timeout:     time.Second,
		hardened:    true,
	}
	dialer := &groupURLTestDialer{
		destination: make(chan M.Socksaddr, 1),
		host:        make(chan string, 1),
	}
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	if _, err := tester.testOnce(ctx, new(BoxInstance), dialer); err != nil {
		t.Fatal(err)
	}
	if got := <-dialer.destination; got != tester.destination {
		t.Fatalf("destination = %v", got)
	}
	if got := <-dialer.host; got != tester.host {
		t.Fatalf("Host = %q", got)
	}
}

func TestRunGroupURLTestStartRetrySucceedsWithFreshAttempt(t *testing.T) {
	var events []string
	latency, err := runGroupURLTestStartRetry(t.Context(), func() (_ int32, err error) {
		events = append(events, "start")
		defer func() { events = append(events, "cleanup") }()
		if len(events) == 1 {
			return -1, fmt.Errorf("%w: temporary failure", errGroupURLTestStart)
		}
		return 42, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if latency != 42 {
		t.Fatalf("latency = %d", latency)
	}
	wantEvents := []string{"start", "cleanup", "start", "cleanup"}
	if !slices.Equal(events, wantEvents) {
		t.Fatalf("events = %v, want %v", events, wantEvents)
	}
}

func TestRunGroupURLTestStartRetryReturnsFinalStartError(t *testing.T) {
	errorsByAttempt := []error{
		fmt.Errorf("%w: first failure", errGroupURLTestStart),
		fmt.Errorf("%w: final failure", errGroupURLTestStart),
	}
	var calls int
	latency, err := runGroupURLTestStartRetry(t.Context(), func() (int32, error) {
		result := errorsByAttempt[calls]
		calls++
		return -1, result
	})
	if latency != -1 || !errors.Is(err, errorsByAttempt[1]) || calls != 2 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestRunGroupURLTestStartRetryDoesNotRetryOtherFailures(t *testing.T) {
	wantErr := errors.New("create or probe failed")
	var calls int
	latency, err := runGroupURLTestStartRetry(t.Context(), func() (int32, error) {
		calls++
		return -1, wantErr
	})
	if latency != -1 || !errors.Is(err, wantErr) || calls != 1 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestGroupURLTestProfileBudgetIncludesAttemptsAndPauses(t *testing.T) {
	tests := []struct {
		name     string
		attempts int32
		pause    int32
		want     time.Duration
	}{
		{name: "minimum attempts", attempts: 0, pause: 100, want: time.Second},
		{name: "configured attempts", attempts: 3, pause: 50, want: 3100 * time.Millisecond},
		{name: "clamped attempts", attempts: 10, pause: 50, want: 5200 * time.Millisecond},
		{name: "negative pause", attempts: 2, pause: -1, want: 2 * time.Second},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := groupURLTestProfileBudget(time.Second, test.attempts, test.pause); got != test.want {
				t.Fatalf("budget = %v, want %v", got, test.want)
			}
		})
	}
}

func TestRunGroupURLTestOperationHonorsDeadlineWhenWorkerBlocks(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	workerDone := make(chan struct{})

	started := time.Now()
	latency, err := runGroupURLTestOperation(ctx, func() (int32, error) {
		defer close(workerDone)
		<-release
		return 42, nil
	})
	if latency != -1 || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("latency = %d, error = %v", latency, err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("blocking operation returned too late: %v", elapsed)
	}

	close(release)
	select {
	case <-workerDone:
	case <-time.After(time.Second):
		t.Fatal("late group URLTest worker did not finish")
	}
}

func TestRunGroupURLTestStartRetryHonorsCancellationDuringPause(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	var calls int
	latency, err := runGroupURLTestStartRetry(ctx, func() (int32, error) {
		calls++
		cancel()
		return -1, fmt.Errorf("%w: temporary failure", errGroupURLTestStart)
	})
	if latency != -1 || !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestShouldRetryProbeOnlyFastTransientErrors(t *testing.T) {
	timeout := 2 * time.Second
	if !shouldRetryProbe(syscall.ENETUNREACH, 100*time.Millisecond, timeout) {
		t.Fatal("fast network error was not retryable")
	}
	if !shouldRetryProbe(&net.DNSError{Err: "temporary", IsTemporary: true}, 100*time.Millisecond, timeout) {
		t.Fatal("temporary DNS error was not retryable")
	}
	if shouldRetryProbe(syscall.ECONNREFUSED, 100*time.Millisecond, timeout) {
		t.Fatal("connection refusal was retryable")
	}
	if shouldRetryProbe(syscall.ENETUNREACH, time.Second, timeout) {
		t.Fatal("slow network error was retryable")
	}
	if shouldRetryProbe(context.Canceled, 100*time.Millisecond, timeout) {
		t.Fatal("cancellation was retryable")
	}
}

type retryingLocalResolver struct {
	access sync.Mutex
	calls  int
}

func (*retryingLocalResolver) Raw() bool            { return false }
func (*retryingLocalResolver) NetworkHandle() int64 { return 0 }
func (*retryingLocalResolver) Exchange(*ExchangeContext, []byte) error {
	return errors.New("unexpected raw DNS exchange")
}

func (r *retryingLocalResolver) Lookup(ctx *ExchangeContext, _, _ string) error {
	r.access.Lock()
	r.calls++
	call := r.calls
	r.access.Unlock()
	if call < 3 {
		ctx.ErrnoCode(int32(syscall.EAGAIN))
	} else {
		ctx.Success("192.0.2.30")
	}
	return nil
}

func TestResolveHardenedTCPPingHostRetriesDNS(t *testing.T) {
	resolver := new(retryingLocalResolver)
	addresses, err := resolveHardenedTCPPingHost(t.Context(), "proxy.example", resolver)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("192.0.2.30")}) {
		t.Fatalf("addresses = %v", addresses)
	}
	resolver.access.Lock()
	calls := resolver.calls
	resolver.access.Unlock()
	if calls != 3 {
		t.Fatalf("lookups = %d, want 3", calls)
	}
}

func TestRunHardenedTCPPingRetriesConnections(t *testing.T) {
	var calls atomic.Int32
	var server net.Conn
	t.Cleanup(func() {
		if server != nil {
			_ = server.Close()
		}
	})
	latency, err := runHardenedTCPPing(
		t.Context(),
		[]netip.Addr{netip.MustParseAddr("192.0.2.40")},
		"443",
		func(context.Context, string, string) (net.Conn, error) {
			if calls.Add(1) < 3 {
				return nil, syscall.ENETUNREACH
			}
			client, peer := net.Pipe()
			server = peer
			return client, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if latency.latency < 0 || latency.address != "192.0.2.40" || calls.Load() != 3 {
		t.Fatalf("result = %+v, dials = %d", latency, calls.Load())
	}
}
