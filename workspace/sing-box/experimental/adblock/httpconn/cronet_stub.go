//go:build with_adblock && !with_adblock_cronet

package httpconn

import (
	"context"
	"net/http"

	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	E "github.com/sagernet/sing/common/exceptions"
)

const SupportsCronet = false

type unsupportedCronetForwarder struct{}

func NewCronetForwarder(context.Context, *ctx.Conn) ClosableRoundTripper {
	return unsupportedCronetForwarder{}
}

func (unsupportedCronetForwarder) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, E.New("adblock TLS Cronet is configured but Cronet is not included in this build, rebuild with -tags with_adblock_cronet")
}

func (unsupportedCronetForwarder) Close() {}
