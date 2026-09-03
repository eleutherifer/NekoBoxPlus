//go:build with_gvisor

package tun

import (
	"context"
	"net/netip"
	"time"
	"unsafe"

	gvisorstack "github.com/sagernet/gvisor/pkg/tcpip/stack"
	singtun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
)

type GVisorUnsafe struct {
	ctx                  context.Context
	tun                  singtun.GVisorTun
	inet4Address         netip.Addr
	inet6Address         netip.Addr
	inet4LoopbackAddress []netip.Addr
	inet6LoopbackAddress []netip.Addr
	icmpTimeout          time.Duration
	udpNATOptions        singtun.UDPNatOptions
	broadcastAddr        netip.Addr
	handler              singtun.Handler
	logger               logger.Logger
	stack                *gvisorstack.Stack
	endpoint             gvisorstack.LinkEndpoint
	dispatcher           *singtun.ForwardDispatcher
	icmpForwarder        *singtun.ICMPForwarder
	udpForwarder         *singtun.UDPForwarder
}

const (
	expectedGVisorSize      = int(unsafe.Sizeof(GVisorUnsafe{}))
	actualSingtunGVisorSize = int(unsafe.Sizeof(singtun.GVisor{}))
)

var _ [actualSingtunGVisorSize - expectedGVisorSize]byte
var _ [expectedGVisorSize - actualSingtunGVisorSize]byte

func forceCloseGVisorStack(stack singtun.Stack) {
	gvs, ok := stack.(*singtun.GVisor)
	if !ok {
		return
	}

	p := (*GVisorUnsafe)(unsafe.Pointer(gvs))
	if p.stack == nil {
		return
	}

	ipStack := p.stack
	endpoint := p.endpoint

	p.stack = nil
	p.endpoint = nil

	if filter, ok := endpoint.(*singtun.LinkEndpointFilter); ok {
		if filter.LinkEndpoint != nil {
			filter.LinkEndpoint.Attach(nil)
		}
	} else if endpoint != nil {
		endpoint.Attach(nil)
	}

	ipStack.Close()

	for _, ep := range ipStack.CleanupEndpoints() {
		ep.Abort()
	}
}
