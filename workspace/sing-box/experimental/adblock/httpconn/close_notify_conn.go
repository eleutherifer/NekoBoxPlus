//go:build with_adblock

package httpconn

import "net"

type CloseNotifyConn struct {
	net.Conn
	close func()
}

func (c *CloseNotifyConn) Close() error {
	c.close()
	return c.Conn.Close()
}
