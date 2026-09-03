//go:build with_trusttunnel_cronet

package trusttunnel

import (
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/cronet-go"
)

type cronetTrackingTestCloser struct {
	closed atomic.Bool
}

func (c *cronetTrackingTestCloser) Close() error {
	c.closed.Store(true)
	return nil
}

func TestCronetPassiveRecoveryClassifiesConnectionClose(t *testing.T) {
	t.Parallel()

	if !shouldRecoverCronetPassiveRoundTripper(cronet.NetErrorConnectionClosed) {
		t.Fatal("expected Cronet connection close to schedule recovery")
	}
	if shouldRecoverCronetPassiveRoundTripper(net.ErrClosed) {
		t.Fatal("expected local net close to be ignored")
	}
	if shouldRecoverCronetPassiveRoundTripper(io.EOF) {
		t.Fatal("expected active stream EOF to be ignored")
	}
}

func TestCronetBridgeRecoveryClassifiesEOF(t *testing.T) {
	t.Parallel()

	if !shouldRecoverCronetBridgeRoundTripper(io.EOF) {
		t.Fatal("expected bridge EOF to schedule recovery")
	}
}

func TestCronetEngineDestroyAfterShutdownSkipsAndroid(t *testing.T) {
	originalGOOS := cronetRuntimeGOOS
	t.Cleanup(func() {
		cronetRuntimeGOOS = originalGOOS
	})

	cronetRuntimeGOOS = "android"
	if shouldDestroyCronetEngineAfterShutdown() {
		t.Fatal("expected Android Cronet engine handle to be left alive after shutdown")
	}

	cronetRuntimeGOOS = "linux"
	if !shouldDestroyCronetEngineAfterShutdown() {
		t.Fatal("expected non-Android Cronet engine handle to be destroyed after shutdown")
	}
}

func TestCronetConnectionTrackingWaitsForStreamDestruction(t *testing.T) {
	t.Parallel()

	transport := new(cronetRoundTripper)
	closer := new(cronetTrackingTestCloser)
	destroyed := make(chan struct{})
	transport.trackCronetCloser(closer, destroyed)

	transport.tracker.closeAllAndRejectNew()
	if !closer.closed.Load() {
		t.Fatal("expected tracked Cronet connection to be closed")
	}
	if transport.tracker.wait(10 * time.Millisecond) {
		t.Fatal("expected teardown to wait for native stream destruction")
	}

	close(destroyed)
	if !transport.tracker.wait(time.Second) {
		t.Fatal("expected teardown to finish after native stream destruction")
	}
}
