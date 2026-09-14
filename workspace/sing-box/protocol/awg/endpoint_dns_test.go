package awg

import (
	"context"
	"encoding/base64"
	"errors"
	"net"
	"net/netip"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/bbolt"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/experimental/cachefile"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"

	mDNS "github.com/miekg/dns"
)

type endpointDNSRouter struct {
	adapter.DNSRouter
	lookup func(context.Context, string, adapter.DNSQueryOptions) ([]netip.Addr, error)
}

func (r *endpointDNSRouter) Lookup(ctx context.Context, domain string, options adapter.DNSQueryOptions) ([]netip.Addr, error) {
	return r.lookup(ctx, domain, options)
}

type endpointDNSTransport struct {
	adapter.DNSTransport
	tag      string
	outbound string
	calls    atomic.Int32
}

func (t *endpointDNSTransport) Tag() string                 { return t.tag }
func (t *endpointDNSTransport) DNSOutbound() (string, bool) { return t.outbound, true }
func (t *endpointDNSTransport) Exchange(ctx context.Context, request *mDNS.Msg) (*mDNS.Msg, error) {
	t.calls.Add(1)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	response := new(mDNS.Msg)
	response.SetReply(request)
	question := request.Question[0]
	header := mDNS.RR_Header{Name: question.Name, Rrtype: question.Qtype, Class: mDNS.ClassINET, Ttl: 300}
	switch question.Qtype {
	case mDNS.TypeA:
		response.Answer = []mDNS.RR{&mDNS.A{Hdr: header, A: net.IPv4(127, 0, 0, 1)}}
	case mDNS.TypeAAAA:
		response.Answer = []mDNS.RR{&mDNS.AAAA{Hdr: header, AAAA: net.ParseIP("::1")}}
	}
	return response, nil
}

type endpointDNSManager struct {
	adapter.DNSTransportManager
	transports []adapter.DNSTransport
}

func (m *endpointDNSManager) Transports() []adapter.DNSTransport { return m.transports }
func (m *endpointDNSManager) Default() adapter.DNSTransport      { return m.transports[0] }
func (m *endpointDNSManager) Transport(tag string) (adapter.DNSTransport, bool) {
	for _, transport := range m.transports {
		if transport.Tag() == tag {
			return transport, true
		}
	}
	return nil, false
}

func endpointDNSContext(ctx context.Context, router adapter.DNSRouter, transports ...adapter.DNSTransport) context.Context {
	ctx = service.ContextWith[adapter.DNSRouter](ctx, router)
	ctx = service.ContextWith[adapter.DNSTransportManager](ctx, &endpointDNSManager{transports: transports})
	// Detoured endpoints only need the manager when opening their lazy socket.
	return service.ContextWith[adapter.OutboundManager](ctx, &struct{ adapter.OutboundManager }{})
}

func endpointDNSOptions(address string) option.AwgEndpointOptions {
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	return option.AwgEndpointOptions{
		PrivateKey: key,
		Address:    []netip.Prefix{netip.MustParsePrefix("10.77.0.2/32")},
		Peers: []option.AwgPeerOptions{{
			Address: address, Port: 51820, PublicKey: key,
			AllowedIPs: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")},
			Reserved:   []uint8{1, 2, 3},
		}},
		DialerOptions: option.DialerOptions{
			AbstractDialerOptions: option.AbstractDialerOptions{
				DomainResolver: &option.DomainResolveOptions{Server: "dns-direct"},
			},
		},
	}
}

func newDNSEndpoint(t *testing.T, ctx context.Context, options option.AwgEndpointOptions) *Endpoint {
	t.Helper()
	ctx = pause.WithDefaultManager(ctx)
	service.FromContext[pause.Manager](ctx).DevicePause()
	endpoint, err := NewEndpoint(ctx, nil, log.NewNOPFactory().Logger(), "awg", options)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = endpoint.Close() })
	return endpoint.(*Endpoint)
}

func startDNSEndpoint(t *testing.T, endpoint *Endpoint) {
	t.Helper()
	for _, stage := range []adapter.StartStage{adapter.StartStateInitialize, adapter.StartStateStart, adapter.StartStatePostStart, adapter.StartStateStarted} {
		if err := endpoint.Start(stage); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDomainEndpointUsesConfiguredDNSAfterStartup(t *testing.T) {
	// A retained VPN may make the process-wide resolver unusable. No AWG
	// bootstrap lookup should reach it, including on a fresh core instance.
	originalResolver := net.DefaultResolver
	var systemCalls atomic.Int32
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(context.Context, string, string) (net.Conn, error) {
		systemCalls.Add(1)
		return nil, errors.New("retained VPN has no DNS consumer")
	}}
	t.Cleanup(func() { net.DefaultResolver = originalResolver })
	for _, strategy := range []C.DomainStrategy{C.DomainStrategyIPv4Only, C.DomainStrategyIPv6Only} {
		for _, detour := range []string{"", "front"} {
			for _, disableCache := range []bool{false, true} {
				direct := &endpointDNSTransport{tag: "dns-direct"}
				bootstrap := &endpointDNSTransport{tag: "dns-bootstrap", outbound: "front"}
				calls := 0
				ready := false
				router := &endpointDNSRouter{lookup: func(_ context.Context, domain string, query adapter.DNSQueryOptions) ([]netip.Addr, error) {
					calls++
					if !ready || domain != "peer.invalid" {
						t.Fatalf("unexpected lookup before post-start or for domain %q", domain)
					}
					wantTransport := direct
					if detour != "" {
						wantTransport = bootstrap
					}
					if query.Transport != wantTransport || query.Strategy != strategy || query.DisableCache != disableCache || query.Timeout != 2*time.Second {
						t.Fatalf("resolver options not honored: %+v", query)
					}
					if strategy == C.DomainStrategyIPv6Only {
						return []netip.Addr{netip.IPv6Loopback()}, nil
					}
					return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
				}}
				ctx := endpointDNSContext(t.Context(), router, direct, bootstrap)
				options := endpointDNSOptions("peer.invalid")
				options.Detour = detour
				options.DomainResolver.Strategy = option.DomainStrategy(strategy)
				options.DomainResolver.DisableCache = disableCache
				options.DomainResolver.Timeout = badoption.Duration(2 * time.Second)
				endpoint := newDNSEndpoint(t, ctx, options)
				for _, stage := range []adapter.StartStage{adapter.StartStateInitialize, adapter.StartStateStart} {
					if err := endpoint.Start(stage); err != nil {
						t.Fatal(err)
					}
				}
				if endpoint.device != nil || calls != 0 {
					t.Fatal("domain peer initialized before DNS was ready")
				}
				ready = true
				if err := endpoint.Start(adapter.StartStatePostStart); err != nil {
					t.Fatal(err)
				}
				if endpoint.device == nil || calls != 1 {
					t.Fatalf("device must resolve its peer exactly once, got %d lookups", calls)
				}
				if err := endpoint.Close(); err != nil {
					t.Fatal(err)
				}
			}
		}
	}
	if systemCalls.Load() != 0 {
		t.Fatal("AWG used the process-wide resolver")
	}
}

func TestDomainEndpointResolutionFailures(t *testing.T) {
	lookupFailure := errors.New("DNS unavailable")
	for _, test := range []struct {
		name    string
		err     error
		cancel  bool
		missing bool
	}{
		{name: "empty response"},
		{name: "lookup failure", err: lookupFailure},
		{name: "cancelled", err: context.Canceled, cancel: true},
		{name: "missing router", missing: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			var router adapter.DNSRouter = &endpointDNSRouter{lookup: func(ctx context.Context, _ string, _ adapter.DNSQueryOptions) ([]netip.Addr, error) {
				if err := ctx.Err(); err != nil {
					return nil, err
				}
				return nil, test.err
			}}
			if test.missing {
				router = nil
			}
			ctx = endpointDNSContext(ctx, router, &endpointDNSTransport{tag: "dns-direct"})
			endpoint := newDNSEndpoint(t, ctx, endpointDNSOptions("peer.invalid"))
			if test.cancel {
				cancel()
			}
			err := endpoint.Start(adapter.StartStatePostStart)
			if err == nil || test.err != nil && !errors.Is(err, test.err) {
				t.Fatalf("unexpected startup error: %v", err)
			}
			if endpoint.device != nil {
				t.Fatal("failed DNS lookup published a device")
			}
		})
	}
}

func TestDomainEndpointCloseBeforeStart(t *testing.T) {
	router := &endpointDNSRouter{lookup: func(context.Context, string, adapter.DNSQueryOptions) ([]netip.Addr, error) {
		t.Fatal("closed endpoint attempted DNS resolution")
		return nil, nil
	}}
	ctx := endpointDNSContext(t.Context(), router, &endpointDNSTransport{tag: "dns-direct"})
	endpoint := newDNSEndpoint(t, ctx, endpointDNSOptions("peer.invalid"))
	if err := endpoint.Close(); err != nil {
		t.Fatal(err)
	}
	if err := endpoint.Start(adapter.StartStatePostStart); err == nil {
		t.Fatal("closed endpoint restarted")
	}
}

func TestDomainEndpointDoesNotPublishDeviceAfterStartupFailure(t *testing.T) {
	router := &endpointDNSRouter{lookup: func(context.Context, string, adapter.DNSQueryOptions) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
	}}
	ctx := endpointDNSContext(t.Context(), router, &endpointDNSTransport{tag: "dns-direct"})
	options := endpointDNSOptions("peer.invalid")
	// Decodes successfully but is rejected when the device applies its IPC config.
	options.PrivateKey = base64.StdEncoding.EncodeToString([]byte{1})
	endpoint := newDNSEndpoint(t, ctx, options)
	if err := endpoint.Start(adapter.StartStatePostStart); err == nil || !strings.Contains(err.Error(), "set ipc config") {
		t.Fatalf("expected device startup failure, got %v", err)
	}
	if endpoint.device != nil {
		t.Fatal("failed startup published a device")
	}
}

func TestLiteralEndpointDoesNotResolveDNS(t *testing.T) {
	for _, address := range []string{"127.0.0.1", "::1"} {
		endpoint := newDNSEndpoint(t, t.Context(), endpointDNSOptions(address))
		if endpoint.deferredDevice != nil || endpoint.device == nil {
			t.Fatal("literal peer unexpectedly deferred")
		}
		startDNSEndpoint(t, endpoint)
	}
}

func TestDomainEndpointHonorsDNSCacheAcrossInstances(t *testing.T) {
	for _, test := range []struct {
		name       string
		disabled   bool
		persistent bool
		wantCalls  int32
	}{
		{name: "memory", wantCalls: 2},
		{name: "disabled", disabled: true, wantCalls: 4},
		{name: "persistent", persistent: true, wantCalls: 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			transport := &endpointDNSTransport{tag: "dns-direct"}
			path := filepath.Join(t.TempDir(), "dns.db")
			for range 2 {
				var store *cachefile.CacheFile
				clientOptions := dns.ClientOptions{Context: t.Context(), Logger: log.NewNOPFactory().Logger(), DisableCache: test.disabled}
				if test.persistent {
					store = cachefile.New(t.Context(), log.NewNOPFactory().Logger(), option.CacheFileOptions{Enabled: true, Path: path, StoreDNS: true})
					if err := store.Start(adapter.StartStateInitialize); err != nil {
						t.Fatal(err)
					}
					clientOptions.DNSCache = func() adapter.DNSCacheStore { return store }
				}
				client := dns.NewClient(clientOptions)
				client.Start()
				router := &endpointDNSRouter{lookup: func(ctx context.Context, domain string, query adapter.DNSQueryOptions) ([]netip.Addr, error) {
					return client.Lookup(ctx, query.Transport, domain, query, nil)
				}}
				ctx := endpointDNSContext(t.Context(), router, transport)
				for range 2 {
					options := endpointDNSOptions("peer.invalid")
					options.DomainResolver.Strategy = option.DomainStrategy(C.DomainStrategyIPv4Only)
					endpoint := newDNSEndpoint(t, ctx, options)
					startDNSEndpoint(t, endpoint)
					if err := endpoint.Close(); err != nil {
						t.Fatal(err)
					}
				}
				if store != nil {
					// DNS cache writes are asynchronous; wait for the disk entry before
					// closing the store to simulate a later profile switch.
					deadline := time.Now().Add(5 * time.Second)
					for {
						var persisted bool
						if err := store.DB.View(func(tx *bbolt.Tx) error {
							bucket := tx.Bucket([]byte("dns_cache"))
							if bucket != nil {
								bucket = bucket.Bucket([]byte("dns-direct"))
								persisted = bucket != nil && bucket.Get(append([]byte{0, byte(mDNS.TypeA)}, []byte("peer.invalid.")...)) != nil
							}
							return nil
						}); err != nil {
							t.Fatal(err)
						}
						if persisted {
							break
						}
						if time.Now().After(deadline) {
							t.Fatal("DNS cache entry was not persisted")
						}
						time.Sleep(time.Millisecond)
					}
					if err := store.Close(); err != nil {
						t.Fatal(err)
					}
				}
			}
			if got := transport.calls.Load(); got != test.wantCalls {
				t.Fatalf("got %d DNS exchanges, want %d", got, test.wantCalls)
			}
		})
	}
}

func TestPeerResolverKeepsReservedAndIPCAddressesConsistent(t *testing.T) {
	calls := 0
	resolve := cachedPeerResolver(func(string) (netip.Addr, error) {
		calls++
		return netip.MustParseAddr("192.0.2.1"), nil
	})
	options := endpointDNSOptions("peer.invalid")
	options.Peers = append(options.Peers, options.Peers[0])
	options.Peers[1].Port++
	_, reserved, err := resolveReservedForPeers(options, resolve)
	if err != nil {
		t.Fatal(err)
	}
	ipc, err := genIpcConfig(options, resolve)
	if err != nil {
		t.Fatal(err)
	}
	for _, address := range []string{"192.0.2.1:51820", "192.0.2.1:51821"} {
		if reserved[netip.MustParseAddrPort(address)] != [3]uint8{1, 2, 3} || !strings.Contains(ipc, "endpoint="+address) {
			t.Fatalf("inconsistent resolved peer address %s", address)
		}
	}
	if calls != 1 {
		t.Fatalf("got %d lookups for the same peer domain", calls)
	}
}
