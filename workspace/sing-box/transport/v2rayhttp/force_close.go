package v2rayhttp

import (
	stdTLS "crypto/tls"
	"net"
	"net/http"
	"reflect"
	"sync"
	"sync/atomic"
	"unsafe"

	E "github.com/sagernet/sing/common/exceptions"

	"golang.org/x/net/http2"
)

type efaceWords struct {
	typ  unsafe.Pointer
	data unsafe.Pointer
}

func ResetTransport(rawTransport http.RoundTripper, connTracker *ConnTracker) http.RoundTripper {
	if connTracker != nil {
		_ = connTracker.Close()
	}

	switch transport := rawTransport.(type) {
	case *http.Transport:
		transport.CloseIdleConnections()
		return transport.Clone()
	case *http2.Transport:
		closeHTTP2Connections(transport)
		return transport
	default:
		panic(E.New("unknown transport type: ", reflect.TypeOf(transport)))
	}
}

// ConnTracker is a Tracker to track conn outside of transport, making sure we can force close them.
type ConnTracker struct {
	access sync.Mutex
	conns  map[TrackedConn]struct{}
}

func NewConnTracker() *ConnTracker {
	return &ConnTracker{
		conns: make(map[TrackedConn]struct{}),
	}
}

func (c *ConnTracker) Track(conn net.Conn) TrackedConn {
	tracked := newTrackedConn(conn, c)
	c.access.Lock()
	defer c.access.Unlock()
	c.conns[tracked] = struct{}{}
	return tracked
}

func (c *ConnTracker) Close() error {
	c.access.Lock()
	conns := make([]TrackedConn, 0, len(c.conns))
	for conn := range c.conns {
		conns = append(conns, conn)
	}
	clear(c.conns)
	c.access.Unlock()
	var errs []error
	for _, conn := range conns {
		err := conn.closeFromTracker()
		if err != nil {
			errs = append(errs, err)
		}
	}
	return E.Errors(errs...)
}

func (c *ConnTracker) Untrack(conn TrackedConn) {
	c.access.Lock()
	defer c.access.Unlock()
	delete(c.conns, conn)
}

func newTrackedConn(conn net.Conn, tracker *ConnTracker) TrackedConn {
	if tlsConn, isTLSConn := conn.(duckTLSConn); isTLSConn {
		return &trackedTLSConn{
			duckTLSConn: tlsConn,
			tracker:     tracker,
		}
	}
	return &trackedCommonConn{
		Conn:    conn,
		tracker: tracker,
	}
}

type TrackedConn interface {
	net.Conn
	closeFromTracker() error
}

type trackedCommonConn struct {
	net.Conn
	closed  atomic.Bool
	tracker *ConnTracker
}

func (t *trackedCommonConn) Close() error {
	if t.closed.Swap(true) {
		return net.ErrClosed
	}
	t.tracker.Untrack(t)
	return t.Conn.Close()
}

func (t *trackedCommonConn) closeFromTracker() error {
	t.closed.Store(true)
	return t.Conn.Close()
}

type duckTLSConn interface {
	net.Conn
	ConnectionState() stdTLS.ConnectionState
}

type trackedTLSConn struct {
	duckTLSConn
	closed  atomic.Bool
	tracker *ConnTracker
}

func (t *trackedTLSConn) Close() error {
	if t.closed.Swap(true) {
		return net.ErrClosed
	}
	t.tracker.Untrack(t)
	return t.duckTLSConn.Close()
}

func (t *trackedTLSConn) closeFromTracker() error {
	t.closed.Store(true)
	return t.duckTLSConn.Close()
}
