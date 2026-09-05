package mieru

import (
	"io"
	"net"
	"sync"
)

type activeMieruConnections struct {
	mu      sync.Mutex
	closing bool
	closers map[*activeMieruConn]struct{}
}

func newActiveMieruConnections() *activeMieruConnections {
	return &activeMieruConnections{
		closers: make(map[*activeMieruConn]struct{}),
	}
}

func (c *activeMieruConnections) TrackConn(conn net.Conn) net.Conn {
	return &activeMieruNetConn{
		Conn:   conn,
		closer: c.track(conn),
	}
}

func (c *activeMieruConnections) track(closer io.Closer) *activeMieruConn {
	trackedConn := &activeMieruConn{
		Closer:  closer,
		tracker: c,
	}
	c.mu.Lock()
	if c.closing {
		c.mu.Unlock()
		_ = trackedConn.Close()
		return trackedConn
	}
	c.closers[trackedConn] = struct{}{}
	c.mu.Unlock()
	return trackedConn
}

func (c *activeMieruConnections) closeAll() {
	c.mu.Lock()
	c.closing = true
	closers := make([]*activeMieruConn, 0, len(c.closers))
	for closer := range c.closers {
		closers = append(closers, closer)
	}
	c.mu.Unlock()

	var wg sync.WaitGroup
	for _, closer := range closers {
		wg.Go(func() {
			_ = closer.Close()
		})
	}
	wg.Wait()
}

func (c *activeMieruConnections) untrack(conn *activeMieruConn) {
	c.mu.Lock()
	delete(c.closers, conn)
	c.mu.Unlock()
}

type activeMieruConn struct {
	io.Closer
	tracker *activeMieruConnections
	once    sync.Once
	err     error
}

func (c *activeMieruConn) Close() error {
	c.once.Do(func() {
		c.tracker.untrack(c)
		c.err = c.Closer.Close()
	})
	return c.err
}

type activeMieruNetConn struct {
	net.Conn
	closer *activeMieruConn
}

func (c *activeMieruNetConn) Close() error {
	return c.closer.Close()
}
