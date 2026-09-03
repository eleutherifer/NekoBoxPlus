//go:build with_adblock && !with_quic

package adblock

import (
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
)

func (s *Service) handleQUICHTTP(ctx *ctx.Conn) error {
	return C.ErrQUICNotIncluded
}

func (s *Service) forwardHTTP3RequestURL(requestContext *adblockRequestContext) error {
	return C.ErrQUICNotIncluded
}
