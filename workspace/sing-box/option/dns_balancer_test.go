package option_test

import (
	"context"
	"testing"
	"time"

	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

func TestBalancerDNSServerOptionsUnmarshal(t *testing.T) {
	registry := dns.NewTransportRegistry()
	transport.RegisterBalancer(registry)
	transport.RegisterUDP(registry)
	ctx := service.ContextWith[option.DNSTransportOptionsRegistry](context.Background(), registry)
	var server option.DNSServerOptions
	err := json.UnmarshalContext(ctx, []byte(`{
		"type": "balancer",
		"tag": "balanced",
		"query_deadline": "25ms",
		"servers": [
			{
				"type": "udp",
				"tag": "one",
				"server": "1.1.1.1"
			},
			{
				"type": "udp",
				"tag": "two",
				"server": "8.8.8.8"
			}
		]
	}`), &server)
	if err != nil {
		t.Fatal(err)
	}
	if server.Type != constant.DNSTypeBalancer {
		t.Fatalf("server type = %q, want %q", server.Type, constant.DNSTypeBalancer)
	}
	options, ok := server.Options.(*option.BalancerDNSServerOptions)
	if !ok {
		t.Fatalf("server options type = %T, want *option.BalancerDNSServerOptions", server.Options)
	}
	if len(options.Servers) != 2 {
		t.Fatalf("child server count = %d, want 2", len(options.Servers))
	}
	if time.Duration(options.QueryDeadline) != 25*time.Millisecond {
		t.Fatalf("query deadline = %v, want 25ms", time.Duration(options.QueryDeadline))
	}
	if options.Servers[0].Type != constant.DNSTypeUDP {
		t.Fatalf("child server type = %q, want %q", options.Servers[0].Type, constant.DNSTypeUDP)
	}
	if _, ok := options.Servers[0].Options.(*option.RemoteDNSServerOptions); !ok {
		t.Fatalf("child server options type = %T, want *option.RemoteDNSServerOptions", options.Servers[0].Options)
	}
}
