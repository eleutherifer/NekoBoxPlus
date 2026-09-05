//go:build with_adblock

package httpconn

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
)

type ClosableRoundTripper interface {
	RoundTrip(*http.Request) (*http.Response, error)
	Close()
}

type DialTLSContextFunc func(ctx context.Context, network, addr string) (net.Conn, error)

type DialHTTP2TLSContextFunc func(ctx context.Context, network, addr string, config *tls.Config) (net.Conn, error)
