package xhttp

import (
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
)

const maxConsecutiveEmptyReads = 100

type rawConnTracker struct {
	access sync.Mutex
	conns  map[*trackedRawConn]struct{}
	closed bool
}

func newRawConnTracker() *rawConnTracker {
	return &rawConnTracker{conns: make(map[*trackedRawConn]struct{})}
}

func (t *rawConnTracker) Track(conn net.Conn) (net.Conn, error) {
	tracked := &trackedRawConn{Conn: conn, tracker: t}
	t.access.Lock()
	if t.closed {
		t.access.Unlock()
		return nil, errors.Join(net.ErrClosed, conn.Close())
	}
	t.conns[tracked] = struct{}{}
	t.access.Unlock()
	return tracked, nil
}

func (t *rawConnTracker) remove(conn *trackedRawConn) {
	t.access.Lock()
	delete(t.conns, conn)
	t.access.Unlock()
}

func (t *rawConnTracker) Close() error {
	t.access.Lock()
	if t.closed {
		t.access.Unlock()
		return nil
	}
	t.closed = true
	connections := make([]*trackedRawConn, 0, len(t.conns))
	for conn := range t.conns {
		connections = append(connections, conn)
	}
	t.conns = nil
	t.access.Unlock()

	var err error
	for _, conn := range connections {
		err = errors.Join(err, conn.Close())
	}
	return err
}

type trackedRawConn struct {
	net.Conn
	tracker   *rawConnTracker
	closeOnce sync.Once
	closeErr  error
	emptyRead atomic.Int32
}

func (c *trackedRawConn) Read(buffer []byte) (int, error) {
	read, err := c.Conn.Read(buffer)
	if len(buffer) == 0 || read > 0 || err != nil {
		c.emptyRead.Store(0)
		return read, err
	}
	if c.emptyRead.Add(1) < maxConsecutiveEmptyReads {
		return 0, nil
	}
	return 0, errors.Join(io.ErrNoProgress, c.Close())
}

func (c *trackedRawConn) Close() error {
	c.closeOnce.Do(func() {
		c.tracker.remove(c)
		c.closeErr = c.Conn.Close()
	})
	return c.closeErr
}
