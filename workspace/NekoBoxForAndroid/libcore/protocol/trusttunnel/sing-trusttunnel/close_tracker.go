package trusttunnel

import (
	"io"
	"sync"
	"time"
)

type closeTracker struct {
	mu           sync.Mutex
	closer       map[io.Closer]struct{}
	closed       bool
	waiters      int
	waiterNotify chan struct{}
}

func (t *closeTracker) addCloser(closer io.Closer) func() {
	if closer == nil {
		return func() {}
	}
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		_ = closer.Close()
		return func() {}
	}
	if t.closer == nil {
		t.closer = make(map[io.Closer]struct{})
	}
	t.closer[closer] = struct{}{}
	t.mu.Unlock()
	return func() {
		t.mu.Lock()
		delete(t.closer, closer)
		t.mu.Unlock()
	}
}

func (t *closeTracker) addWaiter() func() {
	t.mu.Lock()
	if t.waiters == 0 {
		t.waiterNotify = make(chan struct{})
	}
	t.waiters++
	t.mu.Unlock()
	var doneOnce sync.Once
	return func() {
		doneOnce.Do(func() {
			t.mu.Lock()
			t.waiters--
			if t.waiters == 0 && t.waiterNotify != nil {
				close(t.waiterNotify)
				t.waiterNotify = nil
			}
			t.mu.Unlock()
		})
	}
}

func (t *closeTracker) closeAll() {
	t.mu.Lock()
	closers := make([]io.Closer, 0, len(t.closer))
	for closer := range t.closer {
		closers = append(closers, closer)
		delete(t.closer, closer)
	}
	t.mu.Unlock()
	for _, closer := range closers {
		_ = closer.Close()
	}
}

func (t *closeTracker) closeAllAndRejectNew() {
	t.mu.Lock()
	t.closed = true
	closers := make([]io.Closer, 0, len(t.closer))
	for closer := range t.closer {
		closers = append(closers, closer)
		delete(t.closer, closer)
	}
	t.mu.Unlock()
	for _, closer := range closers {
		_ = closer.Close()
	}
}

func (t *closeTracker) wait(timeout time.Duration) bool {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		t.mu.Lock()
		if t.waiters == 0 {
			t.mu.Unlock()
			return true
		}
		waiterNotify := t.waiterNotify
		t.mu.Unlock()

		if waiterNotify == nil {
			continue
		}

		select {
		case <-waiterNotify:
		case <-timer.C:
			return false
		}
	}
}

type trackedReadCloser struct {
	io.ReadCloser
	release   func()
	closeOnce sync.Once
}

func (c *trackedReadCloser) Close() (err error) {
	c.closeOnce.Do(func() {
		err = c.ReadCloser.Close()
		c.release()
	})
	return
}
