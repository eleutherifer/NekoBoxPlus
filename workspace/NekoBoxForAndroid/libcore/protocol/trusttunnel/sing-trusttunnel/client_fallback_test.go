package trusttunnel

import (
	"context"
	"errors"
	"io"
	"net/http"
	"testing"
	"time"
)

type fallbackTestRoundTripper struct {
	err         error
	status      int
	waitForDone bool
	closed      bool
}

func (r *fallbackTestRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	if r.waitForDone {
		<-request.Context().Done()
		return nil, request.Context().Err()
	}
	if r.err != nil {
		return nil, r.err
	}
	status := r.status
	if status == 0 {
		status = http.StatusOK
	}
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(http.NoBody),
	}, nil
}

func (r *fallbackTestRoundTripper) CloseIdleConnections() {
	r.closed = true
}

func TestEnsureRoundTripperFallsBackWhenQUICProbeFails(t *testing.T) {
	primary := &fallbackTestRoundTripper{err: errors.New("quic timeout")}
	fallback := &fallbackTestRoundTripper{}
	client := &Client{
		roundTripper:      primary,
		wrapError:         func(err error) error { return err },
		fallbackRoundTrip: fallback,
		fallbackWrapError: func(err error) error { return err },
	}

	client.ensureRoundTripper(context.Background())

	roundTripper, _ := client.currentRoundTripper()
	if roundTripper != fallback {
		t.Fatal("expected fallback round tripper")
	}
	if !primary.closed {
		t.Fatal("expected failed primary to be closed")
	}
}

func TestEnsureRoundTripperKeepsQUICWhenProbeSucceeds(t *testing.T) {
	primary := &fallbackTestRoundTripper{}
	fallback := &fallbackTestRoundTripper{}
	client := &Client{
		roundTripper:      primary,
		wrapError:         func(err error) error { return err },
		fallbackRoundTrip: fallback,
		fallbackWrapError: func(err error) error { return err },
	}

	client.ensureRoundTripper(context.Background())

	roundTripper, _ := client.currentRoundTripper()
	if roundTripper != primary {
		t.Fatal("expected primary round tripper")
	}
	if primary.closed {
		t.Fatal("did not expect successful primary to be closed")
	}
}

func TestEnsureRoundTripperUsesShortFallbackProbeTimeout(t *testing.T) {
	primary := &fallbackTestRoundTripper{waitForDone: true}
	fallback := &fallbackTestRoundTripper{}
	client := &Client{
		roundTripper:      primary,
		wrapError:         func(err error) error { return err },
		fallbackRoundTrip: fallback,
		fallbackWrapError: func(err error) error { return err },
	}

	start := time.Now()
	client.ensureRoundTripper(t.Context())

	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("fallback probe took too long: %v", elapsed)
	}
	roundTripper, _ := client.currentRoundTripper()
	if roundTripper != fallback {
		t.Fatal("expected fallback round tripper")
	}
}
