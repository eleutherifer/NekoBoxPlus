package dialer

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"

	mDNS "github.com/miekg/dns"
)

type peerResolverTestTransport struct {
	tag      string
	outbound string
}

func (t *peerResolverTestTransport) Type() string                   { return "test" }
func (t *peerResolverTestTransport) Tag() string                    { return t.tag }
func (t *peerResolverTestTransport) Dependencies() []string         { return nil }
func (t *peerResolverTestTransport) Start(adapter.StartStage) error { return nil }
func (t *peerResolverTestTransport) Close() error                   { return nil }
func (t *peerResolverTestTransport) Reset()                         {}
func (t *peerResolverTestTransport) Exchange(context.Context, *mDNS.Msg) (*mDNS.Msg, error) {
	return nil, nil
}
func (t *peerResolverTestTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	response, err := t.Exchange(ctx, message)
	callback(response, err)
}
func (t *peerResolverTestTransport) DNSOutbound() (string, bool) {
	return t.outbound, true
}

type peerResolverTestManager struct {
	transports []adapter.DNSTransport
	defaultDNS adapter.DNSTransport
}

func (m *peerResolverTestManager) Start(adapter.StartStage) error { return nil }
func (m *peerResolverTestManager) Close() error                   { return nil }
func (m *peerResolverTestManager) Transports() []adapter.DNSTransport {
	return m.transports
}
func (m *peerResolverTestManager) Transport(tag string) (adapter.DNSTransport, bool) {
	for _, transport := range m.transports {
		if transport.Tag() == tag {
			return transport, true
		}
	}
	return nil, false
}
func (m *peerResolverTestManager) Default() adapter.DNSTransport { return m.defaultDNS }
func (m *peerResolverTestManager) FakeIP() adapter.FakeIPTransport {
	return nil
}
func (m *peerResolverTestManager) Remove(string) error { return nil }
func (m *peerResolverTestManager) Create(context.Context, log.ContextLogger, string, string, any) error {
	return nil
}

func TestPeerDomainQueryOptionsPrefersImmediateDetour(t *testing.T) {
	direct := &peerResolverTestTransport{tag: "direct"}
	throughDetour := &peerResolverTestTransport{tag: "bootstrap", outbound: "awg"}
	manager := &peerResolverTestManager{
		transports: []adapter.DNSTransport{direct, throughDetour},
		defaultDNS: direct,
	}

	options, err := PeerDomainQueryOptions(manager, "wg", "awg", adapter.DNSQueryOptions{
		Transport: direct,
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.Transport != throughDetour {
		t.Fatalf("selected transport %q, want %q", options.Transport.Tag(), throughDetour.Tag())
	}
}

func TestPeerDomainQueryOptionsKeepsSafeConfiguredFallback(t *testing.T) {
	direct := &peerResolverTestTransport{tag: "direct"}
	manager := &peerResolverTestManager{
		transports: []adapter.DNSTransport{direct},
		defaultDNS: direct,
	}

	options, err := PeerDomainQueryOptions(manager, "wg", "awg", adapter.DNSQueryOptions{
		Transport: direct,
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.Transport != direct {
		t.Fatalf("selected transport %q, want %q", options.Transport.Tag(), direct.Tag())
	}
}

func TestPeerDomainQueryOptionsRejectsCircularFallback(t *testing.T) {
	circular := &peerResolverTestTransport{tag: "remote", outbound: "wg"}
	manager := &peerResolverTestManager{
		transports: []adapter.DNSTransport{circular},
		defaultDNS: circular,
	}

	_, err := PeerDomainQueryOptions(manager, "wg", "awg", adapter.DNSQueryOptions{
		Transport: circular,
	})
	if err == nil {
		t.Fatal("expected circular resolver error")
	}
}

var (
	_ adapter.DNSTransport        = (*peerResolverTestTransport)(nil)
	_ adapter.DNSTransportManager = (*peerResolverTestManager)(nil)
)
