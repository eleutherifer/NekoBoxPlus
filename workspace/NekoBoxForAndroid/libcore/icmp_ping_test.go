package libcore

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"slices"
	"testing"

	"github.com/miekg/dns"
)

func TestICMPPingValidatesInput(t *testing.T) {
	if _, err := IcmpPing("", 1000); err == nil {
		t.Fatal("expected empty host to fail")
	}
	if _, err := IcmpPing("192.0.2.1", 0); err == nil {
		t.Fatal("expected non-positive timeout to fail")
	}
}

func TestResolveICMPPingAddressesSkipsDNSForIPLiteral(t *testing.T) {
	addresses, err := resolveICMPPingAddresses(t.Context(), "[2001:db8::10]", nil)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("2001:db8::10")}) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestResolveICMPPingHostUsesLocalDNS(t *testing.T) {
	directCalled := false
	addresses, err := resolveICMPPingHost(
		t.Context(),
		"proxy.example",
		icmpPingDNSAnswers("192.0.2.10", "2001:db8::10"),
		func(context.Context, *dns.Msg) (*dns.Msg, error) {
			directCalled = true
			return nil, errors.New("direct DNS should not run")
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if directCalled {
		t.Fatal("direct DNS was called after local DNS succeeded")
	}
	want := []netip.Addr{
		netip.MustParseAddr("192.0.2.10"),
		netip.MustParseAddr("2001:db8::10"),
	}
	if !slices.Equal(addresses, want) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestResolveICMPPingHostFallsBackToDirectDNS(t *testing.T) {
	localCalls := 0
	directCalls := 0
	addresses, err := resolveICMPPingHost(
		t.Context(),
		"proxy.example",
		func(context.Context, *dns.Msg) (*dns.Msg, error) {
			localCalls++
			return nil, errors.New("local DNS failed")
		},
		func(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
			directCalls++
			return icmpPingDNSAnswers("198.51.100.20")(ctx, message)
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if localCalls != 2 || directCalls != 2 {
		t.Fatalf("local calls = %d, direct calls = %d", localCalls, directCalls)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("198.51.100.20")}) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestResolveICMPPingHostFailsWithoutAddresses(t *testing.T) {
	empty := func(_ context.Context, request *dns.Msg) (*dns.Msg, error) {
		return new(dns.Msg).SetReply(request), nil
	}
	_, err := resolveICMPPingHost(t.Context(), "proxy.example", empty, empty)
	if err == nil {
		t.Fatal("expected empty DNS responses to fail")
	}
}

func TestExchangeICMPPingDNSHonorsContext(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err := exchangeICMPPingDNS(ctx, "proxy.example", func(context.Context, *dns.Msg) (*dns.Msg, error) {
		t.Fatal("exchange called after cancellation")
		return nil, nil
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context cancellation, got %v", err)
	}
}

func icmpPingDNSAnswers(rawAddresses ...string) icmpPingDNSExchange {
	return func(_ context.Context, request *dns.Msg) (*dns.Msg, error) {
		response := new(dns.Msg)
		response.SetReply(request)
		for _, rawAddress := range rawAddresses {
			address := netip.MustParseAddr(rawAddress)
			switch {
			case request.Question[0].Qtype == dns.TypeA && address.Is4():
				response.Answer = append(response.Answer, &dns.A{
					Hdr: dns.RR_Header{
						Name:   request.Question[0].Name,
						Rrtype: dns.TypeA,
						Class:  dns.ClassINET,
					},
					A: net.IP(address.AsSlice()),
				})
			case request.Question[0].Qtype == dns.TypeAAAA && address.Is6():
				response.Answer = append(response.Answer, &dns.AAAA{
					Hdr: dns.RR_Header{
						Name:   request.Question[0].Name,
						Rrtype: dns.TypeAAAA,
						Class:  dns.ClassINET,
					},
					AAAA: net.IP(address.AsSlice()),
				})
			}
		}
		return response, nil
	}
}
