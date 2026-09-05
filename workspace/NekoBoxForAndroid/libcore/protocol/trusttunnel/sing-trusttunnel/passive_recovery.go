package trusttunnel

import (
	"context"
	stdTLS "crypto/tls"
	"errors"
	"io"
	"net"
	"os"
	"sync/atomic"
)

type passiveRecoveryConn struct {
	net.Conn
	localClosed atomic.Bool
	recover     func(error)
}

func newPassiveRecoveryConn(conn net.Conn, recover func(error)) net.Conn {
	if recover == nil {
		return conn
	}
	return &passiveRecoveryConn{
		Conn:    conn,
		recover: recover,
	}
}

func (c *passiveRecoveryConn) Read(p []byte) (n int, err error) {
	n, err = c.Conn.Read(p)
	c.maybeRecover(err)
	return
}

func (c *passiveRecoveryConn) Write(p []byte) (n int, err error) {
	n, err = c.Conn.Write(p)
	c.maybeRecover(err)
	return
}

func (c *passiveRecoveryConn) Close() error {
	c.localClosed.Store(true)
	return c.Conn.Close()
}

func (c *passiveRecoveryConn) ConnectionState() stdTLS.ConnectionState {
	if conn, ok := c.Conn.(interface{ ConnectionState() stdTLS.ConnectionState }); ok {
		return conn.ConnectionState()
	}
	return stdTLS.ConnectionState{}
}

func (c *passiveRecoveryConn) maybeRecover(err error) {
	if c.localClosed.Load() || !shouldRecoverPassiveRoundTripper(err) {
		return
	}
	c.recover(err)
}

func shouldRecoverPassiveRoundTripper(err error) bool {
	if err == nil {
		return false
	}
	return !errors.Is(err, net.ErrClosed) &&
		!errors.Is(err, context.Canceled) &&
		!errors.Is(err, os.ErrClosed) &&
		!errors.Is(err, os.ErrDeadlineExceeded)
}

func shouldRecoverRoundTripper(err error) bool {
	if err == nil {
		return false
	}
	return !errors.Is(err, io.EOF) &&
		shouldRecoverPassiveRoundTripper(err)
}
