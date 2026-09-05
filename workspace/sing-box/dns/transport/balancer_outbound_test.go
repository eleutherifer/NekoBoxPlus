package transport

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"

	mDNS "github.com/miekg/dns"
)

type balancerOutboundTestTransport struct {
	dns.TransportAdapter
}

func (t *balancerOutboundTestTransport) Start(adapter.StartStage) error { return nil }
func (t *balancerOutboundTestTransport) Close() error                   { return nil }
func (t *balancerOutboundTestTransport) Reset()                         {}
func (t *balancerOutboundTestTransport) Exchange(context.Context, *mDNS.Msg) (*mDNS.Msg, error) {
	return nil, nil
}
func (t *balancerOutboundTestTransport) ExchangeAsync(_ context.Context, _ *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	callback(nil, nil)
}

func TestBalancerDNSOutbound(t *testing.T) {
	child := func(tag string, detour string) *balancerChild {
		return &balancerChild{
			tag: tag,
			transport: &balancerOutboundTestTransport{
				TransportAdapter: dns.NewTransportAdapterWithRemoteOptions(
					"udp",
					tag,
					option.RemoteDNSServerOptions{
						RawLocalDNSServerOptions: option.RawLocalDNSServerOptions{
							DialerOptions: option.DialerOptions{Detour: detour},
						},
					},
				),
			},
		}
	}

	balancer := &BalancerTransport{children: []*balancerChild{
		child("one", "awg"),
		child("two", "awg"),
	}}
	if outbound, available := balancer.DNSOutbound(); !available || outbound != "awg" {
		t.Fatalf("unexpected common outbound: %q, %v", outbound, available)
	}

	balancer.children[1] = child("two", "direct")
	if outbound, available := balancer.DNSOutbound(); available {
		t.Fatalf("unexpected mixed outbound: %q", outbound)
	}
}

var _ adapter.DNSTransport = (*balancerOutboundTestTransport)(nil)
