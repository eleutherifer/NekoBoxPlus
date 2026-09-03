package v2rayhttp

import (
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
)

type testRoundTripper func(*http.Request) (*http.Response, error)

func (f testRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

type readTrackingBody struct {
	read   bool
	closed bool
	mu     sync.Mutex
}

func (b *readTrackingBody) Read([]byte) (int, error) {
	b.mu.Lock()
	b.read = true
	b.mu.Unlock()
	return 0, io.EOF
}

func (b *readTrackingBody) Close() error {
	b.mu.Lock()
	b.closed = true
	b.mu.Unlock()
	return nil
}

func TestHTTP2NonOKResponseIsClosedWithoutDrain(t *testing.T) {
	body := new(readTrackingBody)
	client := &Client{
		http2:   true,
		method:  http.MethodPut,
		host:    []string{"example.com"},
		headers: make(http.Header),
		transport: testRoundTripper(func(request *http.Request) (*http.Response, error) {
			return &http.Response{
				Status:     "503 Service Unavailable",
				StatusCode: http.StatusServiceUnavailable,
				Body:       body,
				Header:     make(http.Header),
				Request:    request,
			}, nil
		}),
	}

	conn, err := client.dialHTTP2(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_, err = conn.Read(make([]byte, 1))
	if err == nil || !strings.Contains(err.Error(), "unexpected status") {
		t.Fatalf("expected status error, got %v", err)
	}
	body.mu.Lock()
	defer body.mu.Unlock()
	if body.read {
		t.Fatal("non-200 response body was drained")
	}
	if !body.closed {
		t.Fatal("non-200 response body was not closed")
	}
}
