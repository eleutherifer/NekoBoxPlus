package vless

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	boxTLS "github.com/sagernet/sing-box/common/tls"
	M "github.com/sagernet/sing/common/metadata"
)

func TestRetryRealityDialSucceeds(t *testing.T) {
	temporaryErr := errors.New("temporary failure")
	var attempts int
	var retries []int
	result, err := retryRealityDial(t.Context(), realityDialAttempts, 0, func() (string, error) {
		attempts++
		if attempts < 3 {
			return "", temporaryErr
		}
		return "connected", nil
	}, func(attempt int, err error) {
		if !errors.Is(err, temporaryErr) {
			t.Fatalf("retry error = %v, expected %v", err, temporaryErr)
		}
		retries = append(retries, attempt)
	})
	if err != nil {
		t.Fatal(err)
	}
	if result != "connected" {
		t.Fatalf("result = %q, expected connected", result)
	}
	if attempts != 3 {
		t.Fatalf("attempts = %d, expected 3", attempts)
	}
	if len(retries) != 2 || retries[0] != 1 || retries[1] != 2 {
		t.Fatalf("retry callbacks = %v, expected [1 2]", retries)
	}
}

func TestRetryRealityDialExhaustsAttempts(t *testing.T) {
	temporaryErr := errors.New("temporary failure")
	var attempts int
	_, err := retryRealityDial(t.Context(), realityDialAttempts, 0, func() (any, error) {
		attempts++
		return nil, temporaryErr
	}, nil)
	if !errors.Is(err, temporaryErr) {
		t.Fatalf("error = %v, expected wrapped %v", err, temporaryErr)
	}
	if attempts != realityDialAttempts {
		t.Fatalf("attempts = %d, expected %d", attempts, realityDialAttempts)
	}
}

func TestRetryRealityDialStopsOnCancellation(t *testing.T) {
	cancelErr := errors.New("cancel retry")
	ctx, cancel := context.WithCancelCause(t.Context())
	var attempts int
	_, err := retryRealityDial(ctx, realityDialAttempts, time.Hour, func() (any, error) {
		attempts++
		if attempts == 2 {
			cancel(cancelErr)
		}
		return nil, errors.New("temporary failure")
	}, nil)
	if !errors.Is(err, cancelErr) {
		t.Fatalf("error = %v, expected cancellation cause %v", err, cancelErr)
	}
	if attempts != 2 {
		t.Fatalf("attempts = %d, expected 2", attempts)
	}
}

func TestDialTLSContextDoesNotRetryNonReality(t *testing.T) {
	dialErr := errors.New("dial failure")
	tlsDialer := &failingTLSDialer{err: dialErr}
	dialer := (*vlessDialer)(&Outbound{tlsDialer: tlsDialer})
	_, err := dialer.dialTLSContext(t.Context())
	if !errors.Is(err, dialErr) {
		t.Fatalf("error = %v, expected %v", err, dialErr)
	}
	if tlsDialer.calls != 1 {
		t.Fatalf("attempts = %d, expected 1", tlsDialer.calls)
	}
}

type failingTLSDialer struct {
	calls int
	err   error
}

func (d *failingTLSDialer) DialTLSContext(context.Context, M.Socksaddr) (boxTLS.Conn, error) {
	d.calls++
	return nil, d.err
}

func (d *failingTLSDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, d.err
}

func (d *failingTLSDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, d.err
}
