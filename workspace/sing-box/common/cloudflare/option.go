package cloudflare

import (
	"context"
	"net"
	"net/http"
	"time"
)

type CloudflareApiOption func(api *CloudflareApi)

func WithDialContext(dialContext func(ctx context.Context, network, addr string) (net.Conn, error)) CloudflareApiOption {
	return func(api *CloudflareApi) {
		api.client.Timeout = 30 * time.Second
		api.client.Transport = &http.Transport{
			DialContext: dialContext,
		}
	}
}
