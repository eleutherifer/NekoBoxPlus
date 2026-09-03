package wireguard

import (
	"testing"

	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type contextSentinel struct {
	value int
}

func TestWireGuardDeviceContext(t *testing.T) {
	ctx := service.ContextWithDefaultRegistry(t.Context())
	ctx = pause.WithDefaultManager(ctx)
	originalPauseManager := service.FromContext[pause.Manager](ctx)
	ctx = service.ContextWith(ctx, contextSentinel{value: 42})

	deviceContext := wireGuardDeviceContext(ctx)

	if service.FromContext[pause.Manager](deviceContext) != nil {
		t.Fatal("pause manager is available to wireguard-go")
	}
	if sentinel := service.FromContext[contextSentinel](deviceContext); sentinel.value != 42 {
		t.Fatalf("unrelated service was not preserved: %+v", sentinel)
	}
	if service.FromContext[pause.Manager](ctx) != originalPauseManager {
		t.Fatal("original context pause manager was modified")
	}
}
