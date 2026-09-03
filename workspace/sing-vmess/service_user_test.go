package vmess

import (
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestServiceUpdateUsersClosesOnlyRemovedUsers(t *testing.T) {
	service := NewService[string](nil)
	if err := service.UpdateUsers(
		[]string{"retained", "removed"},
		[]string{"64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1", "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca2"},
		[]int{0, 0},
	); err != nil {
		t.Fatal(err)
	}

	service.userAccess.RLock()
	revision := service.userRevision
	service.userAccess.RUnlock()
	retainedConn := &closeCountingConn{}
	removedConn := &closeCountingConn{}
	if _, loaded := service.trackConnection("retained", revision, retainedConn); !loaded {
		t.Fatal("track retained connection")
	}
	if _, loaded := service.trackConnection("removed", revision, removedConn); !loaded {
		t.Fatal("track removed connection")
	}

	if err := service.UpdateUsers(
		[]string{"retained"},
		[]string{"64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"},
		[]int{0},
	); err != nil {
		t.Fatal(err)
	}
	if got := retainedConn.closeCount.Load(); got != 0 {
		t.Fatalf("retained connection closed %d times", got)
	}
	if got := removedConn.closeCount.Load(); got != 1 {
		t.Fatalf("removed connection closed %d times, want 1", got)
	}
}

func TestServiceRejectsConnectionFromStaleUserSnapshot(t *testing.T) {
	service := NewService[string](nil)
	const userID = "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"
	if err := service.UpdateUsers([]string{"user"}, []string{userID}, []int{0}); err != nil {
		t.Fatal(err)
	}
	service.userAccess.RLock()
	revision := service.userRevision
	service.userAccess.RUnlock()

	if err := service.UpdateUsers([]string{"user"}, []string{userID}, []int{0}); err != nil {
		t.Fatal(err)
	}
	if trackedConn, loaded := service.trackConnection("user", revision, &closeCountingConn{}); loaded || trackedConn != nil {
		t.Fatal("stale user snapshot registered a connection")
	}
}

func TestTrackedConnectionCleanupIsIdempotent(t *testing.T) {
	service := NewService[string](nil)
	if err := service.UpdateUsers(
		[]string{"user"},
		[]string{"64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"},
		[]int{0},
	); err != nil {
		t.Fatal(err)
	}
	service.userAccess.RLock()
	revision := service.userRevision
	service.userAccess.RUnlock()
	trackedConn, loaded := service.trackConnection("user", revision, &closeCountingConn{})
	if !loaded {
		t.Fatal("track connection")
	}

	var callbackCount atomic.Int32
	onClose := trackedConn.onClose(func(error) {
		callbackCount.Add(1)
	})
	onClose(nil)
	onClose(nil)
	trackedConn.remove()

	if got := callbackCount.Load(); got != 1 {
		t.Fatalf("parent callback called %d times, want 1", got)
	}
	service.userAccess.RLock()
	connectionCount := len(service.connections)
	service.userAccess.RUnlock()
	if connectionCount != 0 {
		t.Fatalf("%d tracked user entries remain", connectionCount)
	}
}

func TestServiceConcurrentUserUpdatesAndConnectionTracking(t *testing.T) {
	service := NewService[string](nil)
	const userID = "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"
	if err := service.UpdateUsers([]string{"user"}, []string{userID}, []int{0}); err != nil {
		t.Fatal(err)
	}

	var workers sync.WaitGroup
	workers.Add(2)
	go func() {
		defer workers.Done()
		for i := 0; i < 100; i++ {
			if i%2 == 0 {
				_ = service.UpdateUsers([]string{"user"}, []string{userID}, []int{0})
			} else {
				_ = service.UpdateUsers(nil, nil, nil)
			}
		}
	}()
	go func() {
		defer workers.Done()
		for i := 0; i < 100; i++ {
			service.userAccess.RLock()
			revision := service.userRevision
			_, loaded := service.userKey["user"]
			service.userAccess.RUnlock()
			if !loaded {
				continue
			}
			if connection, tracked := service.trackConnection("user", revision, &closeCountingConn{}); tracked {
				_ = connection.Close()
			}
		}
	}()
	workers.Wait()
	if err := service.UpdateUsers(nil, nil, nil); err != nil {
		t.Fatal(err)
	}
}

type closeCountingConn struct {
	closeCount atomic.Int32
}

func (c *closeCountingConn) Read([]byte) (int, error)         { return 0, io.EOF }
func (c *closeCountingConn) Write(p []byte) (int, error)      { return len(p), nil }
func (c *closeCountingConn) Close() error                     { c.closeCount.Add(1); return nil }
func (c *closeCountingConn) LocalAddr() net.Addr              { return nil }
func (c *closeCountingConn) RemoteAddr() net.Addr             { return nil }
func (c *closeCountingConn) SetDeadline(time.Time) error      { return nil }
func (c *closeCountingConn) SetReadDeadline(time.Time) error  { return nil }
func (c *closeCountingConn) SetWriteDeadline(time.Time) error { return nil }
