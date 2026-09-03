package speedtest

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestURLTestUsesSingleHEADWithoutFollowingRedirects(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Method != http.MethodHead {
			t.Errorf("method = %s", r.Method)
		}
		http.Redirect(w, r, "/redirected", http.StatusFound)
	}))
	defer server.Close()

	if _, err := URLTest(t.Context(), server.Client(), server.URL); err != nil {
		t.Fatal(err)
	}
	if got := requests.Load(); got != 1 {
		t.Fatalf("requests = %d", got)
	}
}

func TestURLTestHonorsContext(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		time.Sleep(200 * time.Millisecond)
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()

	_, err := URLTest(ctx, server.Client(), server.URL)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v", err)
	}
}

func TestResponseRTTExcludesConnectionSetup(t *testing.T) {
	wroteHeaders := time.Unix(0, 0)
	firstByte := wroteHeaders.Add(57 * time.Millisecond)
	if got := responseRTT(403*time.Millisecond, wroteHeaders, firstByte); got != 57*time.Millisecond {
		t.Fatalf("response RTT = %s", got)
	}
}

func TestResponseRTTFallsBackWithoutCompleteTrace(t *testing.T) {
	const fallback = 403 * time.Millisecond
	if got := responseRTT(fallback, time.Time{}, time.Time{}); got != fallback {
		t.Fatalf("fallback RTT = %s", got)
	}
}

func TestLegacyURLTestRequestCounts(t *testing.T) {
	for _, test := range []struct {
		name     string
		standard int
		requests int32
	}{
		{"first", UrlTestStandard_FirstHandshake, 1},
		{"handshake", UrlTestStandard_Handshake, 2},
		{"rtt", UrlTestStandard_RTT, 2},
	} {
		t.Run(test.name, func(t *testing.T) {
			var requests atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			if _, err := UrlTest(t.Context(), server.Client(), server.URL, test.standard); err != nil {
				t.Fatal(err)
			}
			if got := requests.Load(); got != test.requests {
				t.Fatalf("requests = %d", got)
			}
		})
	}
}

func TestTCPPingClosesConnection(t *testing.T) {
	client, server := net.Pipe()
	serverClosed := make(chan struct{})
	go func() {
		defer close(serverClosed)
		buffer := make([]byte, 1)
		_, _ = server.Read(buffer)
		_ = server.Close()
	}()

	_, err := TCPPing(t.Context(), func(context.Context, string, string) (net.Conn, error) {
		return client, nil
	}, "example.test:443")
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-serverClosed:
	case <-time.After(time.Second):
		t.Fatal("connection was not closed")
	}
}
