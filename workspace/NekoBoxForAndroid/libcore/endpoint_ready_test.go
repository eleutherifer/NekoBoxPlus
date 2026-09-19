package libcore

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

type fakeOpenVPNEndpoint struct {
	adapter.OpenVPNEndpoint
	status  atomic.Pointer[adapter.OpenVPNStatus]
	updated chan struct{}
}

func (e *fakeOpenVPNEndpoint) OpenVPNStatus() adapter.OpenVPNStatus { return *e.status.Load() }
func (e *fakeOpenVPNEndpoint) StatusUpdated() <-chan struct{}       { return e.updated }

type fakeOpenConnectEndpoint struct {
	adapter.OpenConnectEndpoint
	status  atomic.Pointer[adapter.OpenConnectStatus]
	updated chan struct{}
}

func (e *fakeOpenConnectEndpoint) OpenConnectStatus() adapter.OpenConnectStatus {
	return *e.status.Load()
}
func (e *fakeOpenConnectEndpoint) StatusUpdated() <-chan struct{} { return e.updated }

func TestWaitOpenVPNReadyWaitsForConnection(t *testing.T) {
	endpoint := &fakeOpenVPNEndpoint{updated: make(chan struct{})}
	endpoint.status.Store(&adapter.OpenVPNStatus{State: adapter.OpenVPNStateConnecting})
	go func() {
		time.Sleep(10 * time.Millisecond)
		endpoint.status.Store(&adapter.OpenVPNStatus{State: adapter.OpenVPNStateConnected})
		close(endpoint.updated)
	}()
	if err := waitOpenVPNReady(t.Context(), endpoint); err != nil {
		t.Fatal(err)
	}
}

func TestWaitOpenConnectReadyWaitsForConnection(t *testing.T) {
	endpoint := &fakeOpenConnectEndpoint{updated: make(chan struct{})}
	endpoint.status.Store(&adapter.OpenConnectStatus{State: adapter.OpenConnectStateConnecting})
	go func() {
		time.Sleep(10 * time.Millisecond)
		endpoint.status.Store(&adapter.OpenConnectStatus{State: adapter.OpenConnectStateConnected})
		close(endpoint.updated)
	}()
	if err := waitOpenConnectReady(t.Context(), endpoint); err != nil {
		t.Fatal(err)
	}
}

func TestWaitOpenVPNReadyHonorsContext(t *testing.T) {
	endpoint := &fakeOpenVPNEndpoint{updated: make(chan struct{})}
	endpoint.status.Store(&adapter.OpenVPNStatus{State: adapter.OpenVPNStateConnecting})
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Millisecond)
	defer cancel()
	if err := waitOpenVPNReady(ctx, endpoint); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v", err)
	}
}

func TestWaitOpenConnectReadyReturnsTerminalError(t *testing.T) {
	endpoint := &fakeOpenConnectEndpoint{updated: make(chan struct{})}
	endpoint.status.Store(&adapter.OpenConnectStatus{
		State: adapter.OpenConnectStateError,
		Error: "authentication failed",
	})
	if err := waitOpenConnectReady(t.Context(), endpoint); err == nil || err.Error() != "OpenConnect client failed: authentication failed" {
		t.Fatalf("error = %v", err)
	}
}
