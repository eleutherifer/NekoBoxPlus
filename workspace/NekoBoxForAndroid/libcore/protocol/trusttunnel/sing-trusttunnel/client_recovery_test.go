package trusttunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type recoveryTestRoundTripper struct {
	err          error
	resetStarted chan struct{}
	resetRelease chan struct{}
	resetCount   atomic.Int32
}

func (r *recoveryTestRoundTripper) RoundTrip(*http.Request) (*http.Response, error) {
	if r.err != nil {
		return nil, r.err
	}
	return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(http.NoBody)}, nil
}

func (r *recoveryTestRoundTripper) CloseIdleConnections() {}

func (r *recoveryTestRoundTripper) ResetConnections() {
	r.resetCount.Add(1)
	if r.resetStarted != nil {
		select {
		case <-r.resetStarted:
		default:
			close(r.resetStarted)
		}
	}
	if r.resetRelease != nil {
		<-r.resetRelease
	}
}

func TestDialRoundTripErrorSchedulesOneBackgroundRecovery(t *testing.T) {
	t.Parallel()

	roundTripper := &recoveryTestRoundTripper{
		err:          errors.New("defunct stream"),
		resetStarted: make(chan struct{}),
		resetRelease: make(chan struct{}),
	}
	client := &Client{
		ctx:          t.Context(),
		roundTripper: roundTripper,
		wrapError:    func(err error) error { return err },
	}

	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			conn, err := client.Dial(t.Context(), M.Socksaddr{})
			if conn != nil {
				_ = conn.Close()
			}
			if err == nil {
				t.Error("expected dial error")
			}
		})
	}

	select {
	case <-roundTripper.resetStarted:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for recovery")
	}
	if got := roundTripper.resetCount.Load(); got != 1 {
		t.Fatalf("expected one coalesced reset while recovery is running, got %d", got)
	}
	close(roundTripper.resetRelease)
	wg.Wait()
}

func TestHealthCheckErrorSchedulesBackgroundRecovery(t *testing.T) {
	t.Parallel()

	roundTripper := &recoveryTestRoundTripper{err: errors.New("health check failed")}
	client := &Client{
		ctx:          t.Context(),
		roundTripper: roundTripper,
		wrapError:    func(err error) error { return err },
	}

	err := client.HealthCheck(t.Context())
	if err == nil {
		t.Fatal("expected health check error")
	}
	waitForReset(t, &roundTripper.resetCount, 1)
}

func TestPassiveRoundTripperErrorSchedulesBackgroundRecovery(t *testing.T) {
	t.Parallel()

	roundTripper := &recoveryTestRoundTripper{}
	client := &Client{ctx: t.Context()}

	client.schedulePassiveRoundTripperRecovery(roundTripper, io.EOF)
	waitForReset(t, &roundTripper.resetCount, 1)
}

func TestPassiveRoundTripperLocalCloseErrorsDoNotScheduleRecovery(t *testing.T) {
	t.Parallel()

	for _, testCase := range []struct {
		name string
		err  error
	}{
		{name: "net closed", err: net.ErrClosed},
		{name: "context canceled", err: context.Canceled},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			roundTripper := &recoveryTestRoundTripper{}
			client := &Client{ctx: t.Context()}

			client.schedulePassiveRoundTripperRecovery(roundTripper, testCase.err)
			time.Sleep(10 * time.Millisecond)
			if got := roundTripper.resetCount.Load(); got != 0 {
				t.Fatalf("expected no passive recovery for %v, got %d", testCase.err, got)
			}
		})
	}
}

func TestPassiveRecoveryConnRemoteEOFSchedulesRecovery(t *testing.T) {
	t.Parallel()

	var recoveries atomic.Int32
	conn := newPassiveRecoveryConn(errNetConn{readErr: io.EOF}, func(error) {
		recoveries.Add(1)
	})

	_, _ = conn.Read(make([]byte, 1))
	if got := recoveries.Load(); got != 1 {
		t.Fatalf("expected passive recovery for remote EOF, got %d", got)
	}
}

func TestPassiveRecoveryConnLocalCloseSuppressesRecovery(t *testing.T) {
	t.Parallel()

	var recoveries atomic.Int32
	conn := newPassiveRecoveryConn(errNetConn{readErr: io.EOF}, func(error) {
		recoveries.Add(1)
	})

	_ = conn.Close()
	_, _ = conn.Read(make([]byte, 1))
	if got := recoveries.Load(); got != 0 {
		t.Fatalf("expected no passive recovery after local close, got %d", got)
	}
}

func TestClientHTTPConnIOErrorsScheduleRecovery(t *testing.T) {
	t.Parallel()

	var recoveries atomic.Int32
	conn := &tcpConn{httpConn: httpConn{
		writer:       errWriter{err: errors.New("write failed")},
		body:         errReadCloser{err: errors.New("read failed")},
		created:      closedCreated(),
		recoveryHook: func(error) { recoveries.Add(1) },
	}}

	if _, err := conn.Write([]byte("hello")); err == nil {
		t.Fatal("expected write error")
	}
	if _, err := conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected read error")
	}
	if got := recoveries.Load(); got != 2 {
		t.Fatalf("expected write and read recoveries, got %d", got)
	}
}

func TestClientHTTPConnNormalCloseErrorsDoNotScheduleRecovery(t *testing.T) {
	t.Parallel()

	for _, testCase := range []struct {
		name string
		err  error
	}{
		{name: "eof", err: io.EOF},
		{name: "net closed", err: net.ErrClosed},
		{name: "context canceled", err: context.Canceled},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			var recoveries atomic.Int32
			conn := &tcpConn{httpConn: httpConn{
				writer:       errWriter{err: testCase.err},
				body:         errReadCloser{err: testCase.err},
				created:      closedCreated(),
				recoveryHook: func(error) { recoveries.Add(1) },
			}}

			_, _ = conn.Write([]byte("hello"))
			_, _ = conn.Read(make([]byte, 1))
			if got := recoveries.Load(); got != 0 {
				t.Fatalf("expected no recoveries, got %d", got)
			}
		})
	}
}

func TestHTTPConnWithoutRecoveryHookDoesNotPanic(t *testing.T) {
	t.Parallel()

	conn := &tcpConn{httpConn: httpConn{
		writer:  errWriter{err: errors.New("write failed")},
		body:    errReadCloser{err: errors.New("read failed")},
		created: closedCreated(),
	}}

	if _, err := conn.Write([]byte("hello")); err == nil {
		t.Fatal("expected write error")
	}
	if _, err := conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected read error")
	}
}

func waitForReset(t *testing.T, count *atomic.Int32, want int32) {
	t.Helper()
	deadline := time.After(time.Second)
	for {
		if count.Load() >= want {
			return
		}
		select {
		case <-deadline:
			t.Fatalf("timed out waiting for reset count %d, got %d", want, count.Load())
		case <-time.After(time.Millisecond):
		}
	}
}

func closedCreated() chan struct{} {
	created := make(chan struct{})
	close(created)
	return created
}

type errWriter struct {
	err error
}

func (w errWriter) Write([]byte) (int, error) {
	return 0, w.err
}

type errReadCloser struct {
	err error
}

func (r errReadCloser) Read([]byte) (int, error) {
	return 0, r.err
}

func (r errReadCloser) Close() error {
	return nil
}

type errNetConn struct {
	readErr error
}

func (c errNetConn) Read([]byte) (int, error) {
	return 0, c.readErr
}

func (c errNetConn) Write(p []byte) (int, error) {
	return len(p), nil
}

func (c errNetConn) Close() error {
	return nil
}

func (c errNetConn) LocalAddr() net.Addr {
	return nil
}

func (c errNetConn) RemoteAddr() net.Addr {
	return nil
}

func (c errNetConn) SetDeadline(time.Time) error {
	return nil
}

func (c errNetConn) SetReadDeadline(time.Time) error {
	return nil
}

func (c errNetConn) SetWriteDeadline(time.Time) error {
	return nil
}
