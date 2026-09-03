//go:build with_adblock && with_utls

package httpconn

import (
	"bytes"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	adblockctx "github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing/service"
)

type testCertificateStore struct {
	pool *x509.CertPool
}

func (s testCertificateStore) Name() string {
	return "test-certificate-store"
}

func (s testCertificateStore) Start(adapter.StartStage) error {
	return nil
}

func (s testCertificateStore) Close() error {
	return nil
}

func (s testCertificateStore) Pool() *x509.CertPool {
	return s.pool
}

func (testCertificateStore) ExclusiveAnchors() bool {
	return false
}

func TestUTLSForwarderUsesHTTP2WhenNegotiated(t *testing.T) {
	protoMajor := make(chan int, 1)
	upstream := httptest.NewUnstartedServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		protoMajor <- request.ProtoMajor
		_, _ = writer.Write([]byte("ok"))
	}))
	upstream.EnableHTTP2 = true
	upstream.StartTLS()
	defer upstream.Close()

	response := roundTripUTLSTestRequest(t, upstream)
	defer response.Body.Close()
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "ok" {
		t.Fatalf("unexpected response body: %q", content)
	}
	if major := <-protoMajor; major != 2 {
		t.Fatalf("unexpected upstream protocol: HTTP/%d, want HTTP/2", major)
	}
}

func TestUTLSForwarderFallsBackToHTTP1(t *testing.T) {
	protoMajor := make(chan int, 1)
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		protoMajor <- request.ProtoMajor
		_, _ = writer.Write([]byte("ok"))
	}))
	defer upstream.Close()

	response := roundTripUTLSTestRequest(t, upstream)
	defer response.Body.Close()
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "ok" {
		t.Fatalf("unexpected response body: %q", content)
	}
	select {
	case major := <-protoMajor:
		if major != 1 {
			t.Fatalf("unexpected upstream protocol: HTTP/%d, want HTTP/1", major)
		}
	case <-time.After(time.Second):
		t.Fatal("upstream did not receive fallback request")
	}
}

func TestUTLSForwarderCachesHTTP1OnlyOrigin(t *testing.T) {
	var connections atomic.Int32
	var requests atomic.Int32
	upstream := httptest.NewUnstartedServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requests.Add(1)
		if request.ProtoMajor != 1 {
			t.Errorf("unexpected upstream protocol: HTTP/%d", request.ProtoMajor)
		}
		_, _ = writer.Write([]byte("ok"))
	}))
	upstream.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			connections.Add(1)
		}
	}
	upstream.StartTLS()
	defer upstream.Close()

	forwarder := newUTLSTestForwarder(t, upstream)
	for range 2 {
		request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, upstream.URL, nil)
		if err != nil {
			t.Fatal(err)
		}
		response, err := forwarder.RoundTrip(request)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = io.ReadAll(response.Body); err != nil {
			response.Body.Close()
			t.Fatal(err)
		}
		response.Body.Close()
	}

	if requests.Load() != 2 {
		t.Fatalf("upstream requests = %d, want 2", requests.Load())
	}
	if connections.Load() != 2 {
		t.Fatalf("connections = %d, want 2: first h2 probe plus cached HTTP/1.1 connection", connections.Load())
	}
}

func TestUTLSForwarderFallbackPreservesRequestBody(t *testing.T) {
	const payload = "fallback upload"

	protoMajor := make(chan int, 1)
	receivedBody := make(chan string, 1)
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		protoMajor <- request.ProtoMajor
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Errorf("read request body: %v", err)
			return
		}
		receivedBody <- string(body)
		_, _ = writer.Write([]byte("ok"))
	}))
	defer upstream.Close()

	pool := x509.NewCertPool()
	pool.AddCert(upstream.Certificate())
	ctx := service.ContextWithDefaultRegistry(t.Context())
	ctx = service.ContextWith[adapter.CertificateStore](ctx, testCertificateStore{pool: pool})

	forwarder := NewHTTPForwarder(ctx, &adblockctx.Conn{
		UseTLS: true,
		UTLS:   consts.Chrome,
	})
	t.Cleanup(forwarder.Close)

	request, err := http.NewRequestWithContext(t.Context(), http.MethodPost, upstream.URL, bytes.NewReader([]byte(payload)))
	if err != nil {
		t.Fatal(err)
	}
	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if _, err = io.ReadAll(response.Body); err != nil {
		t.Fatal(err)
	}
	select {
	case major := <-protoMajor:
		if major != 1 {
			t.Fatalf("unexpected upstream protocol: HTTP/%d, want HTTP/1", major)
		}
	case <-time.After(time.Second):
		t.Fatal("upstream did not receive fallback request")
	}
	select {
	case body := <-receivedBody:
		if body != payload {
			t.Fatalf("upstream body = %q, want %q", body, payload)
		}
	case <-time.After(time.Second):
		t.Fatal("upstream did not receive request body")
	}
}

func TestHTTP2Capable(t *testing.T) {
	cases := []struct {
		name   string
		header http.Header
		want   bool
	}{
		{"plain request", http.Header{}, true},
		{"upgrade websocket", http.Header{"Upgrade": []string{"websocket"}}, false},
		{"connection upgrade", http.Header{"Connection": []string{"Upgrade"}}, false},
		{"connection close", http.Header{"Connection": []string{"close"}}, true},
		{"connection keep-alive", http.Header{"Connection": []string{"keep-alive"}}, true},
		{"transfer chunked", http.Header{"Transfer-Encoding": []string{"chunked"}}, true},
		{"transfer identity", http.Header{"Transfer-Encoding": []string{"identity"}}, false},
		{"upgrade chunked passthrough", http.Header{"Upgrade": []string{"chunked"}}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := &http.Request{Header: tc.header}
			if got := http2Capable(req); got != tc.want {
				t.Fatalf("http2Capable(%v) = %v, want %v", tc.header, got, tc.want)
			}
		})
	}
}

// TestUTLSForwarderRoutesUpgradeRequestToHTTP1 reproduces the WebSocket bug:
// against an HTTP/2-capable upstream, a WebSocket Upgrade request must be routed
// straight to HTTP/1.1 instead of failing inside the HTTP/2 transport with
// "http2: invalid Upgrade request header".
func TestUTLSForwarderRoutesUpgradeRequestToHTTP1(t *testing.T) {
	protoMajor := make(chan int, 1)
	upstream := httptest.NewUnstartedServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		protoMajor <- request.ProtoMajor
		_, _ = writer.Write([]byte("ok"))
	}))
	upstream.EnableHTTP2 = true
	upstream.StartTLS()
	defer upstream.Close()

	pool := x509.NewCertPool()
	pool.AddCert(upstream.Certificate())
	ctx := service.ContextWithDefaultRegistry(t.Context())
	ctx = service.ContextWith[adapter.CertificateStore](ctx, testCertificateStore{pool: pool})

	forwarder := NewHTTPForwarder(ctx, &adblockctx.Conn{
		UseTLS: true,
		UTLS:   consts.Chrome,
	})
	t.Cleanup(forwarder.Close)

	request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, upstream.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Connection", "Upgrade")
	request.Header.Set("Upgrade", "websocket")

	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatalf("upgrade request should downgrade to HTTP/1.1, got error: %v", err)
	}
	defer response.Body.Close()

	if major := <-protoMajor; major != 1 {
		t.Fatalf("upgrade request should be served over HTTP/1.1, got HTTP/%d", major)
	}
}

func roundTripUTLSTestRequest(t *testing.T, upstream *httptest.Server) *http.Response {
	t.Helper()

	forwarder := newUTLSTestForwarder(t, upstream)
	request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, upstream.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func newUTLSTestForwarder(t *testing.T, upstream *httptest.Server) ClosableRoundTripper {
	t.Helper()

	pool := x509.NewCertPool()
	pool.AddCert(upstream.Certificate())
	ctx := service.ContextWithDefaultRegistry(t.Context())
	ctx = service.ContextWith[adapter.CertificateStore](ctx, testCertificateStore{pool: pool})

	forwarder := NewHTTPForwarder(ctx, &adblockctx.Conn{
		UseTLS: true,
		UTLS:   consts.Chrome,
	})
	t.Cleanup(forwarder.Close)
	return forwarder
}

func TestUTLSForwarderInsecureSkipVerify(t *testing.T) {
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		UseTLS:             true,
		UTLS:               consts.Chrome,
		InsecureSkipVerify: true,
	})
	defer forwarder.Close()
	request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, upstream.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusNoContent)
	}
}
