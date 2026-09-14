package endpoint

import (
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"
)

type startupEndpoint struct {
	adapter.Endpoint
	tag          string
	dependencies []string
	start        func() error
}

func (e *startupEndpoint) Tag() string            { return e.tag }
func (e *startupEndpoint) Type() string           { return "test" }
func (e *startupEndpoint) Dependencies() []string { return e.dependencies }
func (e *startupEndpoint) Start(stage adapter.StartStage) error {
	if stage == adapter.StartStatePostStart && e.start != nil {
		return e.start()
	}
	return nil
}

type startupOutboundManager struct {
	adapter.OutboundManager
	outbounds map[string]adapter.Outbound
}

func (m *startupOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	outbound, loaded := m.outbounds[tag]
	return outbound, loaded
}

func TestPostStartInitializesFrontEndpointBeforeDependent(t *testing.T) {
	for _, indirect := range []bool{false, true} {
		var order []string
		front := &startupEndpoint{tag: "front", start: func() error {
			order = append(order, "front")
			return nil
		}}
		selected := &startupEndpoint{tag: "selected", dependencies: []string{"front"}}
		outbounds := &startupOutboundManager{outbounds: make(map[string]adapter.Outbound)}
		if indirect {
			outbounds.outbounds["selector"] = &startupEndpoint{tag: "selector", dependencies: []string{"front"}}
			selected.dependencies = []string{"selector"}
		}
		ctx := service.ContextWith[adapter.OutboundManager](t.Context(), outbounds)
		manager := NewManager(ctx, log.NewNOPFactory().Logger(), nil)
		manager.endpoints = []adapter.Endpoint{selected, front}
		manager.endpointByTag = map[string]adapter.Endpoint{"selected": selected, "front": front}
		selected.start = func() error {
			// A bootstrap DNS dialer can re-enter the endpoint manager here.
			if _, loaded := manager.Get("front"); !loaded || !slices.Equal(order, []string{"front"}) {
				return errors.New("front endpoint is not ready for bootstrap DNS")
			}
			order = append(order, "selected")
			return nil
		}
		done := make(chan error, 1)
		go func() { done <- manager.Start(adapter.StartStatePostStart) }()
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("endpoint bootstrap deadlocked on manager lookup")
		}
		if !slices.Equal(order, []string{"front", "selected"}) {
			t.Fatalf("incorrect startup order: %v", order)
		}
	}
}

func TestEndpointDependencyOrderRejectsInvalidGraph(t *testing.T) {
	for _, dependency := range []string{"missing", "selected"} {
		_, err := orderEndpointDependencies([]adapter.Endpoint{
			&startupEndpoint{tag: "selected", dependencies: []string{dependency}},
		}, nil)
		if err == nil {
			t.Fatalf("accepted invalid dependency %q", dependency)
		}
	}
}
