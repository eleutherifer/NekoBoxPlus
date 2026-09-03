package transport

import (
	"context"
	"errors"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

const testDNSType = "test-balancer-child"

type balancerTestOptions struct {
	Name         string
	Delay        time.Duration
	Fail         *atomic.Bool
	Dependencies []string
	LookupTag    string
}

type balancerTestTransport struct {
	dns.TransportAdapter
	name   string
	delay  time.Duration
	fail   *atomic.Bool
	calls  atomic.Int32
	starts atomic.Int32
	closes atomic.Int32
	resets atomic.Int32

	lastDeadline atomic.Int64
}

func registerBalancerTestTransport(registry *dns.TransportRegistry, created map[string]*balancerTestTransport) {
	dns.RegisterTransport[balancerTestOptions](registry, testDNSType, func(ctx context.Context, logger log.ContextLogger, tag string, options balancerTestOptions) (adapter.DNSTransport, error) {
		if options.LookupTag != "" {
			manager := service.FromContext[adapter.DNSTransportManager](ctx)
			if manager == nil {
				return nil, errors.New("missing DNS transport manager")
			}
			if _, loaded := manager.Transport(options.LookupTag); !loaded {
				return nil, errors.New("DNS transport not found: " + options.LookupTag)
			}
		}
		transport := &balancerTestTransport{
			TransportAdapter: dns.NewTransportAdapter(testDNSType, tag, options.Dependencies),
			name:             options.Name,
			delay:            options.Delay,
			fail:             options.Fail,
		}
		created[options.Name] = transport
		return transport, nil
	})
}

func (t *balancerTestTransport) Start(stage adapter.StartStage) error {
	t.starts.Add(1)
	return nil
}

func (t *balancerTestTransport) Close() error {
	t.closes.Add(1)
	return nil
}

func (t *balancerTestTransport) Reset() {
	t.resets.Add(1)
}

func (t *balancerTestTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	t.calls.Add(1)
	if deadline, loaded := ctx.Deadline(); loaded {
		t.lastDeadline.Store(time.Until(deadline).Nanoseconds())
	} else {
		t.lastDeadline.Store(-1)
	}
	if t.delay > 0 {
		select {
		case <-time.After(t.delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if t.fail != nil && t.fail.Load() {
		return nil, errors.New("child failed: " + t.name)
	}
	response := new(mDNS.Msg)
	response.SetReply(message)
	response.Answer = []mDNS.RR{&mDNS.A{
		Hdr: mDNS.RR_Header{
			Name:   message.Question[0].Name,
			Rrtype: mDNS.TypeA,
			Class:  mDNS.ClassINET,
			Ttl:    C.DefaultDNSTTL,
		},
		A: netip.MustParseAddr("192.0.2.1").AsSlice(),
	}}
	return response, nil
}

func (t *balancerTestTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() {
		callback(t.Exchange(ctx, message))
	}()
}

func (t *balancerTestTransport) observedDeadline() time.Duration {
	return time.Duration(t.lastDeadline.Load())
}

func newBalancerTestTransport(t *testing.T, options option.BalancerDNSServerOptions) (*BalancerTransport, map[string]*balancerTestTransport) {
	t.Helper()
	registry := dns.NewTransportRegistry()
	created := make(map[string]*balancerTestTransport)
	RegisterBalancer(registry)
	registerBalancerTestTransport(registry, created)
	ctx := service.ContextWith[adapter.DNSTransportRegistry](context.Background(), registry)
	ctx = service.ContextWith[option.DNSTransportOptionsRegistry](ctx, registry)
	transport, err := NewBalancer(ctx, log.NewNOPFactory().Logger(), "balanced", options)
	if err != nil {
		t.Fatal(err)
	}
	balancer, ok := transport.(*BalancerTransport)
	if !ok {
		t.Fatalf("transport type = %T, want *BalancerTransport", transport)
	}
	return balancer, created
}

type balancerTestManager struct {
	transports map[string]adapter.DNSTransport
}

func (m balancerTestManager) Start(stage adapter.StartStage) error {
	return nil
}

func (m balancerTestManager) Close() error {
	return nil
}

func (m balancerTestManager) Transports() []adapter.DNSTransport {
	transports := make([]adapter.DNSTransport, 0, len(m.transports))
	for _, transport := range m.transports {
		transports = append(transports, transport)
	}
	return transports
}

func (m balancerTestManager) Transport(tag string) (adapter.DNSTransport, bool) {
	transport, loaded := m.transports[tag]
	return transport, loaded
}

func (m balancerTestManager) Default() adapter.DNSTransport {
	return nil
}

func (m balancerTestManager) FakeIP() adapter.FakeIPTransport {
	return nil
}

func (m balancerTestManager) Remove(tag string) error {
	return nil
}

func (m balancerTestManager) Create(ctx context.Context, logger log.ContextLogger, tag string, outboundType string, options any) error {
	return nil
}

func testQuery() *mDNS.Msg {
	message := new(mDNS.Msg)
	message.SetQuestion("example.com.", mDNS.TypeA)
	return message
}

func TestBalancerRanksFastestServerOnFirstQuery(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "slow", Options: &balancerTestOptions{Name: "slow", Delay: 30 * time.Millisecond}},
			{Type: testDNSType, Tag: "fast", Options: &balancerTestOptions{Name: "fast", Delay: 5 * time.Millisecond}},
		},
	})
	_, err := balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["slow"].calls.Load() != 1 || created["fast"].calls.Load() != 1 {
		t.Fatalf("first query calls = slow:%d fast:%d, want 1 each", created["slow"].calls.Load(), created["fast"].calls.Load())
	}
	_, err = balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["slow"].calls.Load() != 1 {
		t.Fatalf("slow calls after second query = %d, want 1", created["slow"].calls.Load())
	}
	if created["fast"].calls.Load() != 2 {
		t.Fatalf("fast calls after second query = %d, want 2", created["fast"].calls.Load())
	}
}

func TestBalancerMeasureUsesQueryDeadlineForHungServer(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "hung", Options: &balancerTestOptions{Name: "hung", Delay: 50 * time.Millisecond}},
			{Type: testDNSType, Tag: "fast", Options: &balancerTestOptions{Name: "fast", Delay: time.Millisecond}},
		},
		QueryDeadline: badoption.Duration(10 * time.Millisecond),
	})
	_, err := balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["hung"].calls.Load() != 1 || created["fast"].calls.Load() != 1 {
		t.Fatalf("measurement calls = hung:%d fast:%d, want 1 each", created["hung"].calls.Load(), created["fast"].calls.Load())
	}
	if deadline := created["hung"].observedDeadline(); deadline <= 0 || deadline > 10*time.Millisecond {
		t.Fatalf("hung deadline = %v, want within 10ms", deadline)
	}
	_, err = balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["fast"].calls.Load() != 2 {
		t.Fatalf("fast calls after ranked query = %d, want 2", created["fast"].calls.Load())
	}
	if created["hung"].calls.Load() != 1 {
		t.Fatalf("hung calls after ranked query = %d, want 1", created["hung"].calls.Load())
	}
}

func TestBalancerMeasureUsesDefaultDeadline(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "first", Options: &balancerTestOptions{Name: "first"}},
			{Type: testDNSType, Tag: "second", Options: &balancerTestOptions{Name: "second"}},
		},
	})
	_, err := balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	deadline := created["first"].observedDeadline()
	if deadline <= C.DNSTimeout-time.Second || deadline > C.DNSTimeout {
		t.Fatalf("measurement deadline = %v, want near %v", deadline, C.DNSTimeout)
	}
}

func TestBalancerCanBeCreatedThroughRegistry(t *testing.T) {
	registry := dns.NewTransportRegistry()
	RegisterBalancer(registry)
	registerBalancerTestTransport(registry, make(map[string]*balancerTestTransport))
	ctx := service.ContextWith[adapter.DNSTransportRegistry](t.Context(), registry)
	ctx = service.ContextWith[option.DNSTransportOptionsRegistry](ctx, registry)
	done := make(chan error, 1)
	go func() {
		_, err := registry.CreateDNSTransport(ctx, log.NewNOPFactory().Logger(), "balanced", C.DNSTypeBalancer, &option.BalancerDNSServerOptions{
			Servers: []option.DNSServerOptions{
				{Type: testDNSType, Tag: "one", Options: &balancerTestOptions{Name: "one"}},
				{Type: testDNSType, Tag: "two", Options: &balancerTestOptions{Name: "two"}},
			},
		})
		done <- err
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out creating balancer through registry")
	}
}

func TestBalancerFailoverPromotesNextServer(t *testing.T) {
	fastFail := new(atomic.Bool)
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "slow", Options: &balancerTestOptions{Name: "slow", Delay: 20 * time.Millisecond}},
			{Type: testDNSType, Tag: "fast", Options: &balancerTestOptions{Name: "fast", Delay: 1 * time.Millisecond, Fail: fastFail}},
		},
	})
	_, err := balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	fastFail.Store(true)
	_, err = balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	_, err = balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["slow"].calls.Load() != 3 {
		t.Fatalf("slow calls = %d, want 3", created["slow"].calls.Load())
	}
	if created["fast"].calls.Load() != 2 {
		t.Fatalf("fast calls = %d, want 2", created["fast"].calls.Load())
	}
}

func TestBalancerExchangeRankedQueryDeadlineDemotesTimedOutServer(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "hung", Options: &balancerTestOptions{Name: "hung", Delay: 50 * time.Millisecond}},
			{Type: testDNSType, Tag: "fast", Options: &balancerTestOptions{Name: "fast", Delay: time.Millisecond}},
		},
		QueryDeadline: badoption.Duration(10 * time.Millisecond),
	})
	balancer.measured = true
	_, err := balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["hung"].calls.Load() != 1 || created["fast"].calls.Load() != 1 {
		t.Fatalf("ranked calls = hung:%d fast:%d, want 1 each", created["hung"].calls.Load(), created["fast"].calls.Load())
	}
	if deadline := created["hung"].observedDeadline(); deadline <= 0 || deadline > 10*time.Millisecond {
		t.Fatalf("hung deadline = %v, want within 10ms", deadline)
	}
	_, err = balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if created["fast"].calls.Load() != 2 {
		t.Fatalf("fast calls after promotion = %d, want 2", created["fast"].calls.Load())
	}
	if created["hung"].calls.Load() != 1 {
		t.Fatalf("hung calls after promotion = %d, want 1", created["hung"].calls.Load())
	}
}

func TestBalancerExchangeRankedWithoutQueryDeadlineKeepsExistingTimeoutBehavior(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "slow", Options: &balancerTestOptions{Name: "slow", Delay: 15 * time.Millisecond}},
			{Type: testDNSType, Tag: "fast", Options: &balancerTestOptions{Name: "fast"}},
		},
	})
	balancer.measured = true
	_, err := balancer.Exchange(context.Background(), testQuery())
	if err != nil {
		t.Fatal(err)
	}
	if deadline := created["slow"].observedDeadline(); deadline != -1 {
		t.Fatalf("slow deadline = %v, want none", deadline)
	}
	if created["slow"].calls.Load() != 1 {
		t.Fatalf("slow calls = %d, want 1", created["slow"].calls.Load())
	}
	if created["fast"].calls.Load() != 0 {
		t.Fatalf("fast calls = %d, want 0", created["fast"].calls.Load())
	}
}

func TestBalancerAllServersDeadReturnsError(t *testing.T) {
	firstFail := new(atomic.Bool)
	secondFail := new(atomic.Bool)
	firstFail.Store(true)
	secondFail.Store(true)
	balancer, _ := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "first", Options: &balancerTestOptions{Name: "first", Fail: firstFail}},
			{Type: testDNSType, Tag: "second", Options: &balancerTestOptions{Name: "second", Fail: secondFail}},
		},
	})
	if _, err := balancer.Exchange(context.Background(), testQuery()); err == nil {
		t.Fatal("expected error")
	}
}

func TestBalancerLifecycleForwardsToChildren(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "first", Options: &balancerTestOptions{Name: "first"}},
			{Type: testDNSType, Tag: "second", Options: &balancerTestOptions{Name: "second"}},
		},
	})
	if err := balancer.Start(adapter.StartStateStart); err != nil {
		t.Fatal(err)
	}
	balancer.Reset()
	if err := balancer.Close(); err != nil {
		t.Fatal(err)
	}
	for name, child := range created {
		if child.starts.Load() != 1 {
			t.Fatalf("%s starts = %d, want 1", name, child.starts.Load())
		}
		if child.resets.Load() != 1 {
			t.Fatalf("%s resets = %d, want 1", name, child.resets.Load())
		}
		if child.closes.Load() != 1 {
			t.Fatalf("%s closes = %d, want 1", name, child.closes.Load())
		}
	}
}

func TestBalancerFirstQueryMeasurementIsShared(t *testing.T) {
	balancer, created := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "first", Options: &balancerTestOptions{Name: "first", Delay: time.Millisecond}},
			{Type: testDNSType, Tag: "second", Options: &balancerTestOptions{Name: "second", Delay: 20 * time.Millisecond}},
		},
	})
	var wg sync.WaitGroup
	for range 5 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := balancer.Exchange(context.Background(), testQuery())
			if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if created["first"].calls.Load() != 5 || created["second"].calls.Load() != 1 {
		t.Fatalf("shared measurement calls = first:%d second:%d, want first as measured main plus waiters and second once", created["first"].calls.Load(), created["second"].calls.Load())
	}
}

func TestBalancerKeepsChildDependenciesLocal(t *testing.T) {
	balancer, _ := newBalancerTestTransport(t, option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "resolver", Options: &balancerTestOptions{Name: "resolver"}},
			{Type: testDNSType, Tag: "remote", Options: &balancerTestOptions{Name: "remote", Dependencies: []string{"resolver"}}},
		},
	})
	if len(balancer.Dependencies()) != 0 {
		t.Fatalf("balancer dependencies = %v, want none", balancer.Dependencies())
	}
	if err := balancer.Start(adapter.StartStateStart); err != nil {
		t.Fatal(err)
	}
}

func TestBalancerExposesOuterChildDependency(t *testing.T) {
	registry := dns.NewTransportRegistry()
	created := make(map[string]*balancerTestTransport)
	RegisterBalancer(registry)
	registerBalancerTestTransport(registry, created)
	parent := balancerTestManager{
		transports: map[string]adapter.DNSTransport{
			"outer": &balancerTestTransport{
				TransportAdapter: dns.NewTransportAdapter(testDNSType, "outer", nil),
				name:             "outer",
			},
		},
	}
	ctx := service.ContextWith[adapter.DNSTransportRegistry](t.Context(), registry)
	ctx = service.ContextWith[adapter.DNSTransportManager](ctx, parent)
	ctx = service.ContextWith[option.DNSTransportOptionsRegistry](ctx, registry)
	transport, err := NewBalancer(ctx, log.NewNOPFactory().Logger(), "balanced", option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "remote", Options: &balancerTestOptions{Name: "remote", Dependencies: []string{"outer"}}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	balancer := transport.(*BalancerTransport)
	if dependencies := balancer.Dependencies(); len(dependencies) != 1 || dependencies[0] != "outer" {
		t.Fatalf("balancer dependencies = %v, want [outer]", dependencies)
	}
	if err = balancer.Start(adapter.StartStateStart); err != nil {
		t.Fatal(err)
	}
}

func TestBalancerChildrenCanLookUpParentTransports(t *testing.T) {
	registry := dns.NewTransportRegistry()
	RegisterBalancer(registry)
	registerBalancerTestTransport(registry, make(map[string]*balancerTestTransport))
	parent := balancerTestManager{
		transports: map[string]adapter.DNSTransport{
			"outer": &balancerTestTransport{
				TransportAdapter: dns.NewTransportAdapter(testDNSType, "outer", nil),
				name:             "outer",
			},
		},
	}
	ctx := service.ContextWith[adapter.DNSTransportRegistry](t.Context(), registry)
	ctx = service.ContextWith[adapter.DNSTransportManager](ctx, parent)
	ctx = service.ContextWith[option.DNSTransportOptionsRegistry](ctx, registry)
	_, err := NewBalancer(ctx, log.NewNOPFactory().Logger(), "balanced", option.BalancerDNSServerOptions{
		Servers: []option.DNSServerOptions{
			{Type: testDNSType, Tag: "remote", Options: &balancerTestOptions{Name: "remote", LookupTag: "outer"}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestBalancerRejectsEmptyServers(t *testing.T) {
	registry := dns.NewTransportRegistry()
	RegisterBalancer(registry)
	ctx := service.ContextWith[adapter.DNSTransportRegistry](context.Background(), registry)
	if _, err := NewBalancer(ctx, log.NewNOPFactory().Logger(), "balanced", option.BalancerDNSServerOptions{}); err == nil {
		t.Fatal("expected error")
	}
}
