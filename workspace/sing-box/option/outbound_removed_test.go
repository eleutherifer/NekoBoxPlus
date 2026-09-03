package option_test

import (
	"strings"
	"testing"

	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

func TestAmneziaWGOutboundRemoved(t *testing.T) {
	ctx := include.Context(t.Context())
	var outbound option.Outbound
	err := json.UnmarshalContext(ctx, []byte(`{"type":"awg","tag":"legacy"}`), &outbound)
	if err == nil {
		t.Fatal("expected removed AmneziaWG outbound error")
	}
	if !strings.Contains(err.Error(), "use AmneziaWG endpoint instead") {
		t.Fatalf("unexpected error: %v", err)
	}
}
