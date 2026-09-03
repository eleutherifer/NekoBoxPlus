//go:build with_adblock

package httpconn

import (
	"context"
	"errors"
	"net"
	"testing"

	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type contextCaptureOutbound struct {
	ctx context.Context
}

func (*contextCaptureOutbound) Type() string           { return "test" }
func (*contextCaptureOutbound) Tag() string            { return "test" }
func (*contextCaptureOutbound) Network() []string      { return []string{N.NetworkTCP} }
func (*contextCaptureOutbound) Dependencies() []string { return nil }
func (o *contextCaptureOutbound) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	o.ctx = ctx
	return nil, errors.New("test dial")
}
func (*contextCaptureOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

func TestDialForwarderUsesInterceptedConnectionContext(t *testing.T) {
	connectionCtx, cancelConnection := context.WithCancel(t.Context())
	requestCtx, cancelRequest := context.WithCancel(t.Context())
	outbound := new(contextCaptureOutbound)
	_, _ = dialForwarder(requestCtx, N.NetworkTCP, "example.com:443", &ctx.Conn{
		Ctx:      connectionCtx,
		Outbound: outbound,
	})

	cancelRequest()
	if err := outbound.ctx.Err(); err != nil {
		t.Fatalf("request cancellation reached outbound connection: %v", err)
	}
	cancelConnection()
	if !errors.Is(outbound.ctx.Err(), context.Canceled) {
		t.Fatalf("outbound context error = %v, want connection cancellation", outbound.ctx.Err())
	}
}

func TestDialForwarderFallsBackToRequestContext(t *testing.T) {
	requestCtx, cancelRequest := context.WithCancel(t.Context())
	outbound := new(contextCaptureOutbound)
	_, _ = dialForwarder(requestCtx, N.NetworkTCP, "example.com:443", &ctx.Conn{Outbound: outbound})

	cancelRequest()
	if !errors.Is(outbound.ctx.Err(), context.Canceled) {
		t.Fatalf("outbound context error = %v, want request cancellation", outbound.ctx.Err())
	}
}
