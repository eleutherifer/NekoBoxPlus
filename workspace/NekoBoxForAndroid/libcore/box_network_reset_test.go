package libcore

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

type resetProbe struct {
	endpoint.Adapter
	updates atomic.Int32
}

type explicitResetKey struct{}

func (*resetProbe) Start(adapter.StartStage) error { return nil }
func (*resetProbe) Close() error                   { return nil }

func (p *resetProbe) InterfaceUpdated(ctx context.Context) {
	if ctx.Value(explicitResetKey{}) != nil {
		p.updates.Add(1)
	}
}

func (*resetProbe) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("not used")
}
func (*resetProbe) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not used")
}

func TestBoxResetReachesTransportListeners(t *testing.T) {
	endpoints := nekoboxAndroidEndpointRegistry()
	outbounds := nekoboxAndroidOutboundRegistry()
	ep := &resetProbe{Adapter: endpoint.NewAdapter("reset-probe", "endpoint", []string{"tcp", "udp"}, nil)}
	op := &resetProbe{Adapter: endpoint.NewAdapter("reset-probe", "outbound", []string{"tcp", "udp"}, nil)}
	endpoint.Register(endpoints, "reset-probe", func(context.Context, adapter.Router, log.ContextLogger, string, struct{}) (adapter.Endpoint, error) {
		return ep, nil
	})
	outbound.Register(outbounds, "reset-probe", func(context.Context, adapter.Router, log.ContextLogger, string, struct{}) (adapter.Outbound, error) {
		return op, nil
	})
	ctx := box.Context(t.Context(), nekoboxAndroidInboundRegistry(), outbounds, endpoints,
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	core, err := box.New(box.Options{Context: ctx, Options: option.Options{
		Log:       &option.LogOptions{Disabled: true},
		Endpoints: []option.Endpoint{{Type: "reset-probe", Tag: "endpoint", Options: &struct{}{}}},
		Outbounds: []option.Outbound{{Type: "reset-probe", Tag: "outbound", Options: &struct{}{}}},
	}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { core.Close() })
	if err = core.Start(); err != nil {
		t.Fatal(err)
	}
	// Count only the explicit reset, independently of host interface callbacks.
	resetCtx := context.WithValue(ctx, explicitResetKey{}, true)
	instance := &BoxInstance{Box: core, ctx: resetCtx, state: boxStateStarted}
	if err = instance.ResetNetwork(); err != nil {
		t.Fatal(err)
	}
	if ep.updates.Load() != 1 || op.updates.Load() != 1 {
		t.Fatalf("reset missed listeners: endpoint=%d outbound=%d", ep.updates.Load(), op.updates.Load())
	}
	instance.state = boxStateClosed
	if err = instance.ResetNetwork(); err != nil {
		t.Fatal(err)
	}
	if ep.updates.Load() != 1 || op.updates.Load() != 1 {
		t.Fatal("reset reached a closed instance")
	}
}
