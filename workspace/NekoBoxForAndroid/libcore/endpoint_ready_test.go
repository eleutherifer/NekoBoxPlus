package libcore

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

type fakeReadyOutbound struct {
	tag          string
	dependencies []string
	wait         func(context.Context) error
}

func (o *fakeReadyOutbound) Type() string           { return "fake" }
func (o *fakeReadyOutbound) Tag() string            { return o.tag }
func (o *fakeReadyOutbound) Network() []string      { return []string{"tcp"} }
func (o *fakeReadyOutbound) Dependencies() []string { return o.dependencies }
func (o *fakeReadyOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("not used")
}
func (o *fakeReadyOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not used")
}
func (o *fakeReadyOutbound) WaitReady(ctx context.Context) error { return o.wait(ctx) }

type fakeReadyGroup struct {
	*fakeReadyOutbound
	selected string
}

func (g *fakeReadyGroup) Now() string   { return g.selected }
func (g *fakeReadyGroup) All() []string { return []string{g.selected} }

type fakeReadyOutboundManager struct {
	adapter.OutboundManager
	outbounds map[string]adapter.Outbound
}

func (m *fakeReadyOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	outbound, loaded := m.outbounds[tag]
	return outbound, loaded
}

func TestWaitURLTestOutboundReadyUsesGenericReadiness(t *testing.T) {
	called := false
	outbound := &fakeReadyOutbound{tag: "ready", wait: func(context.Context) error {
		called = true
		return nil
	}}
	manager := &fakeReadyOutboundManager{outbounds: map[string]adapter.Outbound{"ready": outbound}}
	if err := waitURLTestOutboundReady(t.Context(), manager, outbound); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("generic readiness was not checked")
	}
}

func TestWaitURLTestOutboundReadyTraversesGroupAndDependencies(t *testing.T) {
	var order []string
	dependency := &fakeReadyOutbound{tag: "dependency", wait: func(context.Context) error {
		order = append(order, "dependency")
		return nil
	}}
	selected := &fakeReadyOutbound{
		tag:          "selected",
		dependencies: []string{"dependency"},
		wait: func(context.Context) error {
			order = append(order, "selected")
			return nil
		},
	}
	group := &fakeReadyGroup{
		fakeReadyOutbound: &fakeReadyOutbound{tag: "group", wait: func(context.Context) error {
			t.Fatal("group readiness should not replace selected outbound readiness")
			return nil
		}},
		selected: "selected",
	}
	manager := &fakeReadyOutboundManager{outbounds: map[string]adapter.Outbound{
		"dependency": dependency,
		"selected":   selected,
		"group":      group,
	}}
	if err := waitURLTestOutboundReady(t.Context(), manager, group); err != nil {
		t.Fatal(err)
	}
	if len(order) != 2 || order[0] != "dependency" || order[1] != "selected" {
		t.Fatalf("readiness order = %v", order)
	}
}

func TestWaitURLTestOutboundReadyTimeoutStopsBeforeProbe(t *testing.T) {
	outbound := &fakeReadyOutbound{tag: "blocked", wait: func(ctx context.Context) error {
		<-ctx.Done()
		return context.Cause(ctx)
	}}
	manager := &fakeReadyOutboundManager{outbounds: map[string]adapter.Outbound{"blocked": outbound}}
	started := time.Now()
	err := waitURLTestOutboundReadyTimeout(t.Context(), manager, outbound, 20*time.Millisecond)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v", err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("readiness timeout returned too late: %v", elapsed)
	}
}

func TestRunURLTestAfterOutboundReadyStartsFreshProbeBudget(t *testing.T) {
	const timeout = 100 * time.Millisecond
	outbound := &fakeReadyOutbound{tag: "delayed", wait: func(ctx context.Context) error {
		select {
		case <-ctx.Done():
			return context.Cause(ctx)
		case <-time.After(60 * time.Millisecond):
			return nil
		}
	}}
	manager := &fakeReadyOutboundManager{outbounds: map[string]adapter.Outbound{"delayed": outbound}}
	latency, err := runURLTestAfterOutboundReady(
		t.Context(),
		manager,
		outbound,
		int32(timeout/time.Millisecond),
		1,
		0,
		func(ctx context.Context) (int32, error) {
			deadline, loaded := ctx.Deadline()
			if !loaded {
				t.Fatal("probe context has no deadline")
			}
			remaining := time.Until(deadline)
			if remaining < 80*time.Millisecond {
				t.Fatalf("probe inherited readiness budget: %v remaining", remaining)
			}
			if remaining > timeout {
				t.Fatalf("probe timeout was inflated: %v remaining", remaining)
			}
			return 42, nil
		},
	)
	if err != nil || latency != 42 {
		t.Fatalf("latency = %d, error = %v", latency, err)
	}
}

func TestRunURLTestAfterOutboundReadyDoesNotProbeOnTimeout(t *testing.T) {
	outbound := &fakeReadyOutbound{tag: "blocked", wait: func(ctx context.Context) error {
		<-ctx.Done()
		return context.Cause(ctx)
	}}
	manager := &fakeReadyOutboundManager{outbounds: map[string]adapter.Outbound{"blocked": outbound}}
	probeCalled := false
	_, err := runURLTestAfterOutboundReady(t.Context(), manager, outbound, 20, 1, 0, func(context.Context) (int32, error) {
		probeCalled = true
		return 42, nil
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v", err)
	}
	if probeCalled {
		t.Fatal("probe started after readiness timeout")
	}
}
