package masque

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestDefaultQuicConfig(t *testing.T) {
	config := DefaultQuicConfig(30*time.Second, 0, false)
	if config.InitialPacketSize != 1242 {
		t.Fatalf("InitialPacketSize = %d, want 1242", config.InitialPacketSize)
	}
	if config.DisablePathMTUDiscovery {
		t.Fatal("path MTU discovery disabled by default")
	}
	custom := DefaultQuicConfig(time.Second, 1300, true)
	if custom.InitialPacketSize != 1300 || !custom.DisablePathMTUDiscovery {
		t.Fatalf("custom QUIC config = %#v", custom)
	}
}

func TestAutomaticFallbackAfterH3Timeout(t *testing.T) {
	h2Session := &tunnelSession{}
	h2Called := false
	tunnel := &Tunnel{
		options: TunnelOptions{Transport: "auto", H3FallbackTimeout: 10 * time.Millisecond},
		dialH3: func(ctx context.Context) (*tunnelSession, error) {
			<-ctx.Done()
			return nil, context.Cause(ctx)
		},
		dialH2: func(context.Context) (*tunnelSession, error) {
			h2Called = true
			return h2Session, nil
		},
	}
	started := time.Now()
	session, err := tunnel.connectSession(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if session != h2Session || !h2Called {
		t.Fatal("HTTP/2 fallback was not selected")
	}
	if time.Since(started) > time.Second {
		t.Fatal("HTTP/3 fallback was not bounded")
	}
}

func TestAutomaticFallbackDoesNotMaskAuthenticationError(t *testing.T) {
	h2Called := false
	tunnel := &Tunnel{
		options: TunnelOptions{Transport: "auto", H3FallbackTimeout: time.Second},
		dialH3: func(context.Context) (*tunnelSession, error) {
			return nil, errors.New("tls: access denied")
		},
		dialH2: func(context.Context) (*tunnelSession, error) {
			h2Called = true
			return &tunnelSession{}, nil
		},
	}
	if _, err := tunnel.connectSession(t.Context()); err == nil {
		t.Fatal("expected authentication error")
	}
	if h2Called {
		t.Fatal("authentication error triggered HTTP/2 fallback")
	}
}

func TestCanFallbackToH2(t *testing.T) {
	for _, err := range []error{contextDeadlineError{}, errors.New("network unreachable"), &ConnectResponseError{StatusCode: 503}} {
		if !canFallbackToH2(err) {
			t.Fatalf("expected fallback for %v", err)
		}
	}
	for _, err := range []error{errors.New("tls: access denied"), errors.New("remote certificate public key mismatch"), &ConnectResponseError{StatusCode: 403}} {
		if canFallbackToH2(err) {
			t.Fatalf("unexpected fallback for %v", err)
		}
	}
}

type contextDeadlineError struct{}

func (contextDeadlineError) Error() string   { return "deadline exceeded" }
func (contextDeadlineError) Timeout() bool   { return true }
func (contextDeadlineError) Temporary() bool { return true }

type blockingTestIPConn struct {
	closed chan struct{}
	once   sync.Once
}

func (c *blockingTestIPConn) ReadPacket() ([]byte, error) {
	<-c.closed
	return nil, net.ErrClosed
}

func (c *blockingTestIPConn) WritePacket([]byte) ([]byte, error) {
	return nil, nil
}

func (c *blockingTestIPConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
}

func TestCloseSessionGenerationGuard(t *testing.T) {
	current := &tunnelSession{}
	stale := &tunnelSession{}
	tunnel := &Tunnel{session: current}
	if tunnel.closeSession(stale) {
		t.Fatal("closing stale session reported current session loss")
	}
	if tunnel.session != current {
		t.Fatal("closing stale session cleared current session")
	}
	if !tunnel.closeSession(current) {
		t.Fatal("closing current session did not report session loss")
	}
	if tunnel.session != nil {
		t.Fatal("closing current session did not clear it")
	}
}

func TestIdleSessionLossReconnectsWithoutNewDial(t *testing.T) {
	tunnelCtx, cancelTunnel := context.WithCancel(t.Context())
	t.Cleanup(cancelTunnel)
	firstConn := &blockingTestIPConn{closed: make(chan struct{})}
	secondConn := &blockingTestIPConn{closed: make(chan struct{})}
	t.Cleanup(func() {
		_ = firstConn.Close()
		_ = secondConn.Close()
	})
	firstSession := &tunnelSession{ipConn: firstConn}
	secondSession := &tunnelSession{ipConn: secondConn}
	connected := make(chan *tunnelSession, 2)
	var attempts atomic.Int32
	tunnel := &Tunnel{
		ctx:     tunnelCtx,
		options: TunnelOptions{Transport: "h3", ReconnectDelay: time.Millisecond},
		dialH3: func(context.Context) (*tunnelSession, error) {
			var session *tunnelSession
			if attempts.Add(1) == 1 {
				session = firstSession
			} else {
				session = secondSession
			}
			connected <- session
			return session, nil
		},
	}

	session, err := tunnel.ensureSession(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if session != firstSession || <-connected != firstSession {
		t.Fatal("first MASQUE session was not established")
	}
	if err = firstConn.Close(); err != nil {
		t.Fatal(err)
	}

	select {
	case session = <-connected:
		if session != secondSession {
			t.Fatal("idle reconnect installed unexpected session")
		}
	case <-time.After(time.Second):
		t.Fatal("idle MASQUE session loss did not reconnect")
	}
	tunnel.mu.Lock()
	current := tunnel.session
	tunnel.mu.Unlock()
	if current != secondSession {
		t.Fatal("reconnected MASQUE session was not retained")
	}
	if attempts.Load() != 2 {
		t.Fatalf("connection attempts = %d, want 2", attempts.Load())
	}
}

func TestIdleSessionReconnectRetriesAfterFailure(t *testing.T) {
	tunnelCtx, cancelTunnel := context.WithCancel(t.Context())
	t.Cleanup(cancelTunnel)
	firstConn := &blockingTestIPConn{closed: make(chan struct{})}
	secondConn := &blockingTestIPConn{closed: make(chan struct{})}
	t.Cleanup(func() {
		_ = firstConn.Close()
		_ = secondConn.Close()
	})
	firstSession := &tunnelSession{ipConn: firstConn}
	secondSession := &tunnelSession{ipConn: secondConn}
	reconnected := make(chan struct{})
	var attempts atomic.Int32
	tunnel := &Tunnel{
		ctx:     tunnelCtx,
		options: TunnelOptions{Transport: "h3", ReconnectDelay: time.Millisecond},
		dialH3: func(context.Context) (*tunnelSession, error) {
			switch attempts.Add(1) {
			case 1:
				return firstSession, nil
			case 2:
				return nil, errors.New("temporary reconnect failure")
			default:
				close(reconnected)
				return secondSession, nil
			}
		},
	}

	if _, err := tunnel.ensureSession(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := firstConn.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-reconnected:
	case <-time.After(time.Second):
		t.Fatal("MASQUE reconnect did not retry after a temporary failure")
	}
	if attempts.Load() != 3 {
		t.Fatalf("connection attempts = %d, want 3", attempts.Load())
	}
}

func TestTunnelCancellationStopsIdleReconnect(t *testing.T) {
	tunnelCtx, cancelTunnel := context.WithCancel(t.Context())
	firstConn := &blockingTestIPConn{closed: make(chan struct{})}
	t.Cleanup(func() { _ = firstConn.Close() })
	firstSession := &tunnelSession{ipConn: firstConn}
	redialed := make(chan struct{}, 1)
	var attempts atomic.Int32
	tunnel := &Tunnel{
		ctx:     tunnelCtx,
		options: TunnelOptions{Transport: "h3", ReconnectDelay: 50 * time.Millisecond},
		dialH3: func(context.Context) (*tunnelSession, error) {
			if attempts.Add(1) > 1 {
				redialed <- struct{}{}
			}
			return firstSession, nil
		},
	}

	if _, err := tunnel.ensureSession(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := firstConn.Close(); err != nil {
		t.Fatal(err)
	}
	cancelTunnel()
	select {
	case <-redialed:
		t.Fatal("MASQUE session reconnected after tunnel cancellation")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestEnsureSessionCallerCancellationDoesNotCancelSharedAttempt(t *testing.T) {
	tunnelCtx, cancelTunnel := context.WithCancel(t.Context())
	t.Cleanup(cancelTunnel)
	started := make(chan context.Context, 1)
	release := make(chan struct{})
	wantSession := &tunnelSession{ipConn: &blockingTestIPConn{closed: make(chan struct{})}}
	t.Cleanup(wantSession.Close)
	var attempts atomic.Int32
	tunnel := &Tunnel{
		ctx:     tunnelCtx,
		options: TunnelOptions{Transport: "h3"},
		dialH3: func(ctx context.Context) (*tunnelSession, error) {
			attempts.Add(1)
			started <- ctx
			select {
			case <-release:
				return wantSession, nil
			case <-ctx.Done():
				return nil, context.Cause(ctx)
			}
		},
	}

	callerCtx, cancelCaller := context.WithCancel(t.Context())
	firstResult := make(chan error, 1)
	go func() {
		_, err := tunnel.ensureSession(callerCtx)
		firstResult <- err
	}()
	attemptCtx := <-started
	cancelCaller()
	if err := <-firstResult; !errors.Is(err, context.Canceled) {
		t.Fatalf("first caller error = %v, want context canceled", err)
	}
	select {
	case <-attemptCtx.Done():
		t.Fatal("caller cancellation canceled the shared connection attempt")
	default:
	}

	type sessionResult struct {
		session *tunnelSession
		err     error
	}
	secondResult := make(chan sessionResult, 1)
	go func() {
		session, err := tunnel.ensureSession(t.Context())
		secondResult <- sessionResult{session: session, err: err}
	}()
	close(release)
	result := <-secondResult
	if result.err != nil {
		t.Fatal(result.err)
	}
	if result.session != wantSession {
		t.Fatal("second caller did not receive the shared session")
	}
	if attempts.Load() != 1 {
		t.Fatalf("connection attempts = %d, want 1", attempts.Load())
	}
}

func TestEnsureSessionTunnelCancellationStopsSharedAttempt(t *testing.T) {
	tunnelCtx, cancelTunnel := context.WithCancel(t.Context())
	started := make(chan struct{})
	tunnel := &Tunnel{
		ctx:     tunnelCtx,
		options: TunnelOptions{Transport: "h3"},
		dialH3: func(ctx context.Context) (*tunnelSession, error) {
			close(started)
			<-ctx.Done()
			return nil, context.Cause(ctx)
		},
	}
	result := make(chan error, 1)
	go func() {
		_, err := tunnel.ensureSession(t.Context())
		result <- err
	}()
	<-started
	cancelTunnel()
	if err := <-result; !errors.Is(err, context.Canceled) {
		t.Fatalf("connection attempt error = %v, want context canceled", err)
	}
}
