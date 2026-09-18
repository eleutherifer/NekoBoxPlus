package masque

import (
	"net/netip"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

func TestParseMASQUEAddresses(t *testing.T) {
	tests := []struct {
		name      string
		config    option.MASQUEConfig
		want      []netip.Prefix
		wantError bool
	}{
		{
			name:   "valid",
			config: option.MASQUEConfig{IPv4: "192.0.2.1", IPv6: "2001:db8::1"},
			want:   []netip.Prefix{netip.MustParsePrefix("192.0.2.1/32"), netip.MustParsePrefix("2001:db8::1/128")},
		},
		{name: "missing IPv4", config: option.MASQUEConfig{IPv6: "2001:db8::1"}, wantError: true},
		{name: "missing IPv6", config: option.MASQUEConfig{IPv4: "192.0.2.1"}, wantError: true},
		{name: "malformed IPv4", config: option.MASQUEConfig{IPv4: "invalid", IPv6: "2001:db8::1"}, wantError: true},
		{name: "malformed IPv6", config: option.MASQUEConfig{IPv4: "192.0.2.1", IPv6: "invalid"}, wantError: true},
		{name: "IPv6 in IPv4 field", config: option.MASQUEConfig{IPv4: "2001:db8::1", IPv6: "2001:db8::1"}, wantError: true},
		{name: "IPv4 in IPv6 field", config: option.MASQUEConfig{IPv4: "192.0.2.1", IPv6: "192.0.2.1"}, wantError: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			addresses, err := parseMASQUEAddresses(&test.config)
			if test.wantError {
				if err == nil {
					t.Fatal("expected error")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(addresses) != len(test.want) {
				t.Fatalf("got %d addresses, want %d", len(addresses), len(test.want))
			}
			for index := range addresses {
				if addresses[index] != test.want[index] {
					t.Fatalf("address %d is %s, want %s", index, addresses[index], test.want[index])
				}
			}
		})
	}
}

func TestResolveTransportOptions(t *testing.T) {
	tests := []struct {
		name          string
		options       option.MASQUEOutboundOptions
		wantTransport string
		wantMTU       uint32
		wantTimeout   time.Duration
		wantError     bool
	}{
		{name: "defaults", wantTransport: "auto", wantMTU: 1280, wantTimeout: 5 * time.Second},
		{name: "legacy h2", options: option.MASQUEOutboundOptions{UseHTTP2: true}, wantTransport: "h2", wantMTU: 1280, wantTimeout: 5 * time.Second},
		{name: "explicit h3", options: option.MASQUEOutboundOptions{Transport: "h3", MTU: 64000}, wantTransport: "h3", wantMTU: 64000, wantTimeout: 5 * time.Second},
		{name: "custom", options: option.MASQUEOutboundOptions{Transport: "auto", MTU: 1400, H3FallbackTimeout: badoption.Duration(2 * time.Second)}, wantTransport: "auto", wantMTU: 1400, wantTimeout: 2 * time.Second},
		{name: "conflict", options: option.MASQUEOutboundOptions{Transport: "h3", UseHTTP2: true}, wantError: true},
		{name: "invalid transport", options: option.MASQUEOutboundOptions{Transport: "tcp"}, wantError: true},
		{name: "h2 mtu", options: option.MASQUEOutboundOptions{Transport: "h2", MTU: 16001}, wantError: true},
		{name: "negative timeout", options: option.MASQUEOutboundOptions{H3FallbackTimeout: badoption.Duration(-time.Second)}, wantError: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			transport, mtu, timeout, err := resolveTransportOptions(test.options)
			if test.wantError {
				if err == nil {
					t.Fatal("expected error")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if transport != test.wantTransport || mtu != test.wantMTU || timeout != test.wantTimeout {
				t.Fatalf("got (%q, %d, %s), want (%q, %d, %s)", transport, mtu, timeout, test.wantTransport, test.wantMTU, test.wantTimeout)
			}
		})
	}
}
