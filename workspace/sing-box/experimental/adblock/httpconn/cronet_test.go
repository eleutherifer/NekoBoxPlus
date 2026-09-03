//go:build with_adblock && with_adblock_cronet

package httpconn

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	adblockctx "github.com/sagernet/sing-box/experimental/adblock/ctx"
)

func TestCronetForwarderRoundTrip(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("X-Adblock-Cronet-Test", "ok")
		_, _ = writer.Write([]byte("cronet ok"))
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	response := roundTripCronetTestRequest(t, forwarder, upstream.URL)
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status: %d", response.StatusCode)
	}
	if value := response.Header.Get("X-Adblock-Cronet-Test"); value != "ok" {
		t.Fatalf("unexpected test header: %q", value)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "cronet ok" {
		t.Fatalf("unexpected body: %q", body)
	}
}

func TestCronetForwarderUnknownContentLength(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.(http.Flusher).Flush()
		_, _ = writer.Write([]byte("streamed cronet body"))
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	response := roundTripCronetTestRequest(t, forwarder, upstream.URL)
	defer response.Body.Close()

	if response.ContentLength != -1 {
		t.Fatalf("ContentLength = %d, want -1 for missing upstream Content-Length", response.ContentLength)
	}
	if value := response.Header.Get("Content-Length"); value != "" {
		t.Fatalf("Content-Length header = %q, want empty", value)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "streamed cronet body" {
		t.Fatalf("unexpected body: %q", body)
	}
}

func TestCronetForwarderPostFixedContentLength(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.ContentLength != int64(len("cronet upload")) {
			t.Errorf("ContentLength = %d, want %d", request.ContentLength, len("cronet upload"))
		}
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Error(err)
			return
		}
		_, _ = writer.Write(body)
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	response := roundTripCronetTestRequestWithBody(t, forwarder, upstream.URL, io.NopCloser(strings.NewReader("cronet upload")), int64(len("cronet upload")))
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "cronet upload" {
		t.Fatalf("unexpected body: %q", body)
	}
}

func TestCronetForwarderPostUnknownContentLength(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Error(err)
			return
		}
		_, _ = writer.Write(body)
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	response := roundTripCronetTestRequestWithBody(t, forwarder, upstream.URL, io.NopCloser(strings.NewReader("streaming upload")), -1)
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "streaming upload" {
		t.Fatalf("unexpected body: %q", body)
	}
}

func TestCronetForwarderPostDataWithEOF(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Error(err)
			return
		}
		_, _ = writer.Write(body)
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	response := roundTripCronetTestRequestWithBody(t, forwarder, upstream.URL, &dataEOFReadCloser{data: []byte("eof upload")}, -1)
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "eof upload" {
		t.Fatalf("unexpected body: %q", body)
	}
}

func TestCronetForwarderRedirectCloseDoesNotPanic(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		http.Redirect(writer, request, "/next", http.StatusMovedPermanently)
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	response := roundTripCronetTestRequest(t, forwarder, upstream.URL)
	if response.StatusCode != http.StatusMovedPermanently {
		t.Fatalf("unexpected status: %d", response.StatusCode)
	}
	if location := response.Header.Get("Location"); location != "/next" {
		t.Fatalf("unexpected redirect location: %q", location)
	}
	if response.Body != nil {
		_ = response.Body.Close()
	}
}

func TestNormalizeCronetContentLength(t *testing.T) {
	tests := []struct {
		name       string
		header     string
		wantLength int64
		wantHeader string
	}{
		{name: "missing", wantLength: -1},
		{name: "invalid", header: "nope", wantLength: -1},
		{name: "negative", header: "-1", wantLength: -1},
		{name: "zero", header: "0", wantLength: 0, wantHeader: "0"},
		{name: "positive", header: "12", wantLength: 12, wantHeader: "12"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := &http.Response{
				Header:        make(http.Header),
				ContentLength: 0,
			}
			if test.header != "" {
				response.Header.Set("Content-Length", test.header)
			}

			normalizeCronetContentLength(response)

			if response.ContentLength != test.wantLength {
				t.Fatalf("ContentLength = %d, want %d", response.ContentLength, test.wantLength)
			}
			if value := response.Header.Get("Content-Length"); value != test.wantHeader {
				t.Fatalf("Content-Length header = %q, want %q", value, test.wantHeader)
			}
		})
	}
}

func TestCronetForwarderSequentialRoundTrips(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_, _ = writer.Write([]byte(request.URL.Query().Get("n")))
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})
	t.Cleanup(forwarder.Close)

	for _, expected := range []string{"1", "2", "3"} {
		response := roundTripCronetTestRequest(t, forwarder, upstream.URL+"?n="+expected)
		body, err := io.ReadAll(response.Body)
		_ = response.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		if string(body) != expected {
			t.Fatalf("unexpected body for request %s: %q", expected, body)
		}
	}
}

func TestCronetForwarderCloseAfterResponseBody(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_, _ = writer.Write([]byte("close ok"))
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{
		Cronet: true,
	})

	response := roundTripCronetTestRequest(t, forwarder, upstream.URL)
	body, err := io.ReadAll(response.Body)
	_ = response.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "close ok" {
		t.Fatalf("unexpected body: %q", body)
	}
	forwarder.Close()
	forwarder.Close()
}

func roundTripCronetTestRequest(t *testing.T, forwarder ClosableRoundTripper, url string) *http.Response {
	t.Helper()

	request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func roundTripCronetTestRequestWithBody(t *testing.T, forwarder ClosableRoundTripper, url string, body io.ReadCloser, contentLength int64) *http.Response {
	t.Helper()

	request, err := http.NewRequestWithContext(t.Context(), http.MethodPost, url, body)
	if err != nil {
		t.Fatal(err)
	}
	request.ContentLength = contentLength
	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

type dataEOFReadCloser struct {
	data []byte
	read bool
}

func (r *dataEOFReadCloser) Read(p []byte) (int, error) {
	if r.read {
		return 0, io.EOF
	}
	r.read = true
	return copy(p, r.data), io.EOF
}

func (r *dataEOFReadCloser) Close() error {
	return nil
}
