package trusttunnel

import (
	"io"
	"net/http"
	"sync/atomic"
	"testing"
	"time"
)

type lifecycleTestRoundTripper struct {
	resetCount atomic.Int32
	idleCount  atomic.Int32
	closeCount atomic.Int32
}

func (r *lifecycleTestRoundTripper) RoundTrip(*http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(http.NoBody)}, nil
}

func (r *lifecycleTestRoundTripper) CloseIdleConnections() {
	r.idleCount.Add(1)
}

func (r *lifecycleTestRoundTripper) ResetConnections() {
	r.resetCount.Add(1)
}

func (r *lifecycleTestRoundTripper) Close() error {
	r.closeCount.Add(1)
	return nil
}

func TestResetRoundTripperConnectionsUsesReusableReset(t *testing.T) {
	roundTripper := new(lifecycleTestRoundTripper)

	resetRoundTripperConnections(roundTripper)

	if roundTripper.resetCount.Load() != 1 {
		t.Fatal("expected reset hook to be called")
	}
	if roundTripper.closeCount.Load() != 0 {
		t.Fatal("did not expect reset to close transport")
	}
	if roundTripper.idleCount.Load() != 0 {
		t.Fatal("did not expect CloseIdleConnections when reset hook exists")
	}
}

func TestForceCloseAllConnectionsClosesTransport(t *testing.T) {
	roundTripper := new(lifecycleTestRoundTripper)

	forceCloseAllConnections(roundTripper)

	if roundTripper.resetCount.Load() != 0 {
		t.Fatal("did not expect full close to use reusable reset hook")
	}
	if roundTripper.closeCount.Load() != 1 {
		t.Fatal("expected full close to close transport")
	}
}

type countedCloser struct {
	count atomic.Int32
}

func (c *countedCloser) Close() error {
	c.count.Add(1)
	return nil
}

func TestCloseTrackerClosesAndClearsTrackedClosers(t *testing.T) {
	var tracker closeTracker
	closer := new(countedCloser)
	release := tracker.addCloser(closer)

	tracker.closeAll()
	release()
	tracker.closeAll()

	if closer.count.Load() != 1 {
		t.Fatalf("expected closer to be closed once, got %d", closer.count.Load())
	}
}

func TestCloseTrackerRejectsNewClosersAfterClose(t *testing.T) {
	var tracker closeTracker

	tracker.closeAllAndRejectNew()
	closer := new(countedCloser)
	tracker.addCloser(closer)

	if closer.count.Load() != 1 {
		t.Fatal("expected new closer to be closed immediately")
	}
}

func TestCloseTrackerWaitCanTimeout(t *testing.T) {
	var tracker closeTracker
	done := tracker.addWaiter()

	if tracker.wait(10 * time.Millisecond) {
		t.Fatal("expected wait to time out while waiter is active")
	}
	done()
	if !tracker.wait(time.Second) {
		t.Fatal("expected wait to finish after waiter is done")
	}
}
