//go:build with_adblock

package httpconn

import (
	"context"

	"github.com/sagernet/sing-box/experimental/adblock/ctx"
)

// outboundContext matches normal routed browser connections: the outbound
// socket belongs to the intercepted connection, not to whichever HTTP request
// happened to make the transport dial it.
func outboundContext(requestCtx context.Context, conn *ctx.Conn) context.Context {
	if conn != nil && conn.Ctx != nil {
		return conn.Ctx
	}
	return requestCtx
}
