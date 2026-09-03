package mieru

import (
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestActiveMieruConnectionsCloseAllConcurrent(t *testing.T) {
	tracker := newActiveMieruConnections()
	entered := make(chan struct{}, 3)
	release := make(chan struct{})
	for range 3 {
		tracker.TrackConn(&blockingConn{
			onClose: func() {
				entered <- struct{}{}
				<-release
			},
		})
	}

	done := make(chan struct{})
	go func() {
		tracker.closeAll()
		close(done)
	}()

	for range 3 {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("timed out waiting for tracked connections to close concurrently")
		}
	}
	close(release)

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for closeAll to finish")
	}
}

func TestActiveMieruConnectionsCloseIsIdempotentAndUntracks(t *testing.T) {
	tracker := newActiveMieruConnections()
	conn := &countingConn{}
	trackedConn := tracker.TrackConn(conn)

	if err := trackedConn.Close(); err != nil {
		t.Fatal(err)
	}
	if err := trackedConn.Close(); err != nil {
		t.Fatal(err)
	}
	tracker.closeAll()

	if count := conn.closeCount.Load(); count != 1 {
		t.Fatalf("close count = %d, want 1", count)
	}
	if count := tracker.count(); count != 0 {
		t.Fatalf("tracked connection count = %d, want 0", count)
	}
}

func TestActiveMieruConnectionsTrackAfterCloseAllClosesImmediately(t *testing.T) {
	tracker := newActiveMieruConnections()
	tracker.closeAll()

	conn := &countingConn{}
	trackedConn := tracker.TrackConn(conn)

	if count := conn.closeCount.Load(); count != 1 {
		t.Fatalf("close count = %d, want 1", count)
	}
	if err := trackedConn.Close(); err != nil {
		t.Fatal(err)
	}
	if count := conn.closeCount.Load(); count != 1 {
		t.Fatalf("close count after second close = %d, want 1", count)
	}
	if count := tracker.count(); count != 0 {
		t.Fatalf("tracked connection count = %d, want 0", count)
	}
}

func (c *activeMieruConnections) count() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.closers)
}

type blockingConn struct {
	countingConn
	onClose func()
}

func (c *blockingConn) Close() error {
	c.closeCount.Add(1)
	c.onClose()
	return nil
}

type countingConn struct {
	closeCount atomic.Int32
}

func (c *countingConn) Read(_ []byte) (int, error) {
	return 0, nil
}

func (c *countingConn) Write(p []byte) (int, error) {
	return len(p), nil
}

func (c *countingConn) Close() error {
	c.closeCount.Add(1)
	return nil
}

func (c *countingConn) LocalAddr() net.Addr {
	return testAddr("local")
}

func (c *countingConn) RemoteAddr() net.Addr {
	return testAddr("remote")
}

func (c *countingConn) SetDeadline(_ time.Time) error {
	return nil
}

func (c *countingConn) SetReadDeadline(_ time.Time) error {
	return nil
}

func (c *countingConn) SetWriteDeadline(_ time.Time) error {
	return nil
}

type testAddr string

func (a testAddr) Network() string {
	return string(a)
}

func (a testAddr) String() string {
	return string(a)
}
