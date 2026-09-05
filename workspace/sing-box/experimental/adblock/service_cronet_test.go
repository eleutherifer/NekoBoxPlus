//go:build with_adblock && with_adblock_cronet

package adblock

import (
	"bufio"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/coder/websocket"
	adblockctx "github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
)

func TestCronetForwardHTTPRequestWebSocketFallsBack(t *testing.T) {
	upstreamErrors := make(chan error, 1)
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		connection, err := websocket.Accept(writer, request, nil)
		if err != nil {
			upstreamErrors <- err
			return
		}
		defer connection.CloseNow()
		messageType, content, err := connection.Read(request.Context())
		if err == nil {
			err = connection.Write(request.Context(), messageType, append([]byte("echo:"), content...))
		}
		upstreamErrors <- err
	}))
	defer upstream.Close()

	service := &Service{ctx: t.Context(), cronet: true}
	forwarder := httpconn.NewHTTPForwarder(t.Context(), &adblockctx.Conn{Cronet: true})
	t.Cleanup(forwarder.Close)
	proxy := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstreamRequest := request.Clone(request.Context())
		upstreamRequest.URL, _ = url.Parse(upstream.URL + request.URL.RequestURI())
		upstreamRequest.Host = upstreamRequest.URL.Host
		requestURL := adblockHTTPFilterURL("http", upstreamRequest, "websocket")
		if err := service.forwardHTTPRequestURL(&adblockRequestContext{
			service:     service,
			ctx:         request.Context(),
			engine:      &fakeAdblockEngine{},
			writer:      writer,
			request:     upstreamRequest,
			requestURL:  requestURL,
			requestType: "websocket",
			forwarder:   forwarder,
		}); err != nil {
			t.Errorf("forward websocket: %v", err)
		}
	}))
	defer proxy.Close()

	connection, _, err := websocket.Dial(t.Context(), "ws"+strings.TrimPrefix(proxy.URL, "http")+"/socket", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.CloseNow()
	if err = connection.Write(t.Context(), websocket.MessageText, []byte("hello")); err != nil {
		t.Fatal(err)
	}
	messageType, content, err := connection.Read(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if messageType != websocket.MessageText || string(content) != "echo:hello" {
		t.Fatalf("unexpected websocket response: type=%d content=%q", messageType, content)
	}
	connection.Close(websocket.StatusNormalClosure, "done")
	if err = <-upstreamErrors; err != nil && websocket.CloseStatus(err) != websocket.StatusNormalClosure {
		t.Fatal(err)
	}
}

func TestCronetForwardHTTPRequestFlushesServerSentEvents(t *testing.T) {
	firstEventSent := make(chan struct{})
	releaseUpstream := make(chan struct{}, 1)
	t.Cleanup(func() {
		select {
		case releaseUpstream <- struct{}{}:
		default:
		}
	})
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(writer, "data: first\n\n")
		http.NewResponseController(writer).Flush()
		close(firstEventSent)
		<-releaseUpstream
		_, _ = io.WriteString(writer, "data: second\n\n")
	}))
	defer upstream.Close()

	service := &Service{ctx: t.Context(), cronet: true}
	forwarder := httpconn.NewHTTPForwarder(t.Context(), &adblockctx.Conn{Cronet: true})
	t.Cleanup(forwarder.Close)
	proxy := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstreamRequest := request.Clone(request.Context())
		upstreamRequest.URL, _ = url.Parse(upstream.URL + request.URL.RequestURI())
		upstreamRequest.Host = upstreamRequest.URL.Host
		if err := service.forwardHTTPRequestURL(&adblockRequestContext{
			service:     service,
			ctx:         request.Context(),
			engine:      &fakeAdblockEngine{},
			writer:      writer,
			request:     upstreamRequest,
			requestURL:  httpRequestURL("http", upstreamRequest),
			requestType: "xmlhttprequest",
			forwarder:   forwarder,
		}); err != nil {
			t.Errorf("forward event stream: %v", err)
		}
	}))
	defer proxy.Close()

	request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, proxy.URL+"/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Accept", "text/event-stream")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	<-firstEventSent
	reader := bufio.NewReader(response.Body)
	line, err := reader.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if line != "data: first\n" {
		t.Fatalf("unexpected first event: %q", line)
	}
	releaseUpstream <- struct{}{}
}

func TestCronetForwardHTTPRequestFlushesUnknownLengthRealtimeStream(t *testing.T) {
	firstChunkSent := make(chan struct{})
	releaseUpstream := make(chan struct{}, 1)
	t.Cleanup(func() {
		select {
		case releaseUpstream <- struct{}{}:
		default:
		}
	})
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/x-ndjson")
		_, _ = io.WriteString(writer, "{\"event\":\"first\"}\n")
		http.NewResponseController(writer).Flush()
		close(firstChunkSent)
		<-releaseUpstream
		_, _ = io.WriteString(writer, "{\"event\":\"second\"}\n")
	}))
	defer upstream.Close()

	service := &Service{ctx: t.Context(), cronet: true}
	forwarder := httpconn.NewHTTPForwarder(t.Context(), &adblockctx.Conn{Cronet: true})
	t.Cleanup(forwarder.Close)
	proxy := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstreamRequest := request.Clone(request.Context())
		upstreamRequest.URL, _ = url.Parse(upstream.URL + request.URL.RequestURI())
		upstreamRequest.Host = upstreamRequest.URL.Host
		if err := service.forwardHTTPRequestURL(&adblockRequestContext{
			service:     service,
			ctx:         request.Context(),
			engine:      &fakeAdblockEngine{},
			writer:      writer,
			request:     upstreamRequest,
			requestURL:  httpRequestURL("http", upstreamRequest),
			requestType: "xmlhttprequest",
			forwarder:   forwarder,
		}); err != nil {
			t.Errorf("forward realtime stream: %v", err)
		}
	}))
	defer proxy.Close()

	response, err := http.Get(proxy.URL + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	<-firstChunkSent
	reader := bufio.NewReader(response.Body)
	line, err := reader.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if line != "{\"event\":\"first\"}\n" {
		t.Fatalf("unexpected first stream line: %q", line)
	}
	releaseUpstream <- struct{}{}
}
