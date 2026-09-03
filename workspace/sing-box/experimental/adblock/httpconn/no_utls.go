//go:build with_adblock && !with_utls

package httpconn

import (
	"crypto/tls"

	"github.com/sagernet/sing-box/experimental/adblock/ctx"
)

const SupportsUTLS = false

func makeDialTLS(_ *ctx.Conn, _ *tls.Config, _ []string) DialTLSContextFunc {
	return nil
}

func makeHTTP2DialTLS(_ *ctx.Conn, _ *tls.Config, _ []string) DialHTTP2TLSContextFunc {
	return nil
}
