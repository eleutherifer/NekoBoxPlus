//go:build with_adblock

package adblock

import (
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func TestConstraintsMatch(t *testing.T) {
	constraints, err := compileConstraints(option.AdblockConstraints{
		{Inbound: []string{"lan"}, SourceIPIsNotLoopback: true},
		{ProcessName: []string{"browser"}},
	})
	if err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		name     string
		metadata adapter.InboundContext
		match    bool
	}{
		{
			name: "matching LAN inbound",
			metadata: adapter.InboundContext{
				Inbound: "lan",
				Source:  M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.1")},
			},
			match: true,
		},
		{
			name: "empty inbound does not bypass inbound constraint",
			metadata: adapter.InboundContext{
				Source: M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.1")},
			},
		},
		{
			name: "loopback source does not match",
			metadata: adapter.InboundContext{
				Inbound: "lan",
				Source:  M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1")},
			},
		},
		{
			name: "IPv4-mapped loopback source does not match",
			metadata: adapter.InboundContext{
				Inbound: "lan",
				Source:  M.Socksaddr{Addr: netip.MustParseAddr("::ffff:127.0.0.1")},
			},
		},
		{
			name: "missing source does not count as non-loopback",
			metadata: adapter.InboundContext{
				Inbound: "lan",
			},
		},
		{
			name: "another constraint can match",
			metadata: adapter.InboundContext{
				ProcessInfo: &adapter.ConnectionOwner{ProcessPath: "/usr/bin/browser"},
			},
			match: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := constraints.Match(&test.metadata); got != test.match {
				t.Fatalf("Match() = %v, want %v", got, test.match)
			}
		})
	}
}

func TestCompileConstraintsInvalidRegex(t *testing.T) {
	_, err := compileConstraints(option.AdblockConstraints{{ProcessPathRegex: []string{"["}}})
	if err == nil {
		t.Fatal("expected invalid regular expression error")
	}
}

func TestServiceHasProcessConstraintsWithoutConstraints(t *testing.T) {
	if (&Service{}).HasProcessConstraints() {
		t.Fatal("empty service must not report process constraints")
	}
}
