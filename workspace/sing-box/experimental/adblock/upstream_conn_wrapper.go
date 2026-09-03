//go:build with_adblock

package adblock

import (
	"net"
	"time"
)

type adblockUpstreamConn struct {
	net.Conn
	readIdleTimeout time.Duration
}

func (c *adblockUpstreamConn) Read(p []byte) (int, error) {
	if c.readIdleTimeout > 0 {
		_ = c.Conn.SetReadDeadline(time.Now().Add(c.readIdleTimeout))
	}
	return c.Conn.Read(p)
}
