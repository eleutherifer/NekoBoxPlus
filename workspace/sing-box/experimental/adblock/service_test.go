//go:build with_adblock

package adblock

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/x509"
	"encoding/asn1"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/klauspost/compress/zstd"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/db"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

type fakeAdblockEngine struct {
	closed          atomic.Bool
	checkResult     bool
	detailedResult  adblockrust.CheckResult
	checkCallCount  atomic.Int64
	detailCallCount atomic.Int64
	exceptionResult bool
	exceptionCount  atomic.Int64
	cspDirectives   string
	cspCallCount    atomic.Int64
	cosmeticResult  adblockrust.CosmeticResources
	hiddenResult    []string
	hiddenCallCount atomic.Int64
	tags            []string
}

func (e *fakeAdblockEngine) Check(url string, sourceURL string, requestType string, method adblockrust.RequestMethod) (bool, error) {
	e.checkCallCount.Add(1)
	return e.checkResult, nil
}

func (e *fakeAdblockEngine) CheckDetailed(url string, sourceURL string, requestType string, method adblockrust.RequestMethod) (adblockrust.CheckResult, error) {
	e.detailCallCount.Add(1)
	return e.detailedResult, nil
}

func (e *fakeAdblockEngine) CheckDetailedNoFilter(url string, sourceURL string, requestType string, method adblockrust.RequestMethod) (adblockrust.CheckResult, error) {
	result, err := e.CheckDetailed(url, sourceURL, requestType, method)
	result.Filter = ""
	return result, err
}

func (e *fakeAdblockEngine) CheckException(url string, sourceURL string, requestType string, method adblockrust.RequestMethod) (bool, error) {
	e.exceptionCount.Add(1)
	return e.exceptionResult, nil
}

func (e *fakeAdblockEngine) CSPDirectives(url string, sourceURL string, requestType string, method adblockrust.RequestMethod) (string, error) {
	e.cspCallCount.Add(1)
	return e.cspDirectives, nil
}

func (e *fakeAdblockEngine) URLCosmeticResources(url string) (adblockrust.CosmeticResources, error) {
	return e.cosmeticResult, nil
}

func (e *fakeAdblockEngine) HiddenClassIDSelectors(classes []string, ids []string, exceptions []string) ([]string, error) {
	e.hiddenCallCount.Add(1)
	return e.hiddenResult, nil
}

func (e *fakeAdblockEngine) UseTags(tags []string) error {
	e.tags = slices.Clone(tags)
	return nil
}

func (e *fakeAdblockEngine) EnableTags(tags []string) error {
	e.tags = append(e.tags, tags...)
	return nil
}

func (e *fakeAdblockEngine) DisableTags(tags []string) error {
	e.tags = slices.DeleteFunc(e.tags, func(tag string) bool {
		return slices.Contains(tags, tag)
	})
	return nil
}

func (e *fakeAdblockEngine) TagExists(tag string) (bool, error) {
	return slices.Contains(e.tags, tag), nil
}

func (e *fakeAdblockEngine) Close() error {
	e.closed.Store(true)
	return nil
}

func newTestService(ctx context.Context, options option.AdblockOptions, engine adblockrust.Engine) *Service {
	var me *managedEngine
	if engine != nil {
		me = newTestManagedEngine(engine)
	}
	service := &Service{
		ctx:          ctx,
		options:      options,
		engine:       me,
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	service.httpServers = NewTypedPool[*http.Server]().SetConstructor(func() *http.Server {
		return &http.Server{}
	})
	service.http2Servers = NewTypedPool[*http.Server]().SetConstructor(func() *http.Server {
		return &http.Server{}
	})
	return service
}

func mustParseURL(t *testing.T, value string) *url.URL {
	t.Helper()
	parsedURL, err := url.Parse(value)
	if err != nil {
		t.Fatal(err)
	}
	return parsedURL
}

func TestWriteForwardRoundTripErrorNegotiatesHTML(t *testing.T) {
	tests := []struct {
		name     string
		accept   string
		nilReq   bool
		wantHTML bool
	}{
		{
			name:     "explicit html",
			accept:   "text/html",
			wantHTML: true,
		},
		{
			name:     "case-insensitive html",
			accept:   "TEXT/HTML;Q=0.8",
			wantHTML: true,
		},
		{
			name:     "explicit xhtml",
			accept:   "application/xhtml+xml",
			wantHTML: true,
		},
		{
			name:   "json",
			accept: "application/json",
		},
		{
			name:   "wildcard",
			accept: "*/*",
		},
		{
			name: "empty accept",
		},
		{
			name:   "html q zero",
			accept: "text/html;q=0,application/json",
		},
		{
			name:   "nil request",
			accept: "text/html",
			nilReq: true,
		},
	}

	service := &Service{}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			writer := httptest.NewRecorder()
			var request *http.Request
			if !test.nilReq {
				request = httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
				if test.accept != "" {
					request.Header.Set("Accept", test.accept)
				}
			}
			requestContext := &adblockRequestContext{
				ctx:        t.Context(),
				writer:     writer,
				request:    request,
				requestURL: mustParseURL(t, "https://example.com/"),
			}

			err := service.writeForwardRoundTripError(requestContext, nil, context.Canceled)
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("returned error = %v, want context.Canceled", err)
			}
			if writer.Code != http.StatusServiceUnavailable {
				t.Fatalf("status = %d, want %d", writer.Code, http.StatusServiceUnavailable)
			}

			body := writer.Body.String()
			contentType := writer.Header().Get("Content-Type")
			if test.wantHTML {
				if contentType != "text/html; charset=UTF-8" {
					t.Fatalf("content type = %q, want HTML", contentType)
				}
				if !strings.Contains(body, "<!doctype html>") || !strings.Contains(body, "Request cancelled") {
					t.Fatalf("unexpected HTML body: %s", body)
				}
				return
			}

			if contentType != "text/plain; charset=UTF-8" {
				t.Fatalf("content type = %q, want text/plain", contentType)
			}
			if strings.Contains(body, "<!doctype html>") {
				t.Fatalf("plain response contains HTML: %s", body)
			}
			if !strings.Contains(body, "Request cancelled") ||
				!strings.Contains(body, "The request was cancelled") ||
				!strings.Contains(body, "Error: context canceled") {
				t.Fatalf("plain response is missing explanation: %s", body)
			}
		})
	}
}

func TestClassifyAdblockRequestTypes(t *testing.T) {
	tests := []struct {
		name        string
		method      string
		path        string
		headers     http.Header
		expected    adblockResourceType
		engineValue string
	}{
		{name: "beacon", path: "/collect", headers: http.Header{"Sec-Fetch-Dest": {"beacon"}}, expected: adblockResourceTypeBeacon, engineValue: "beacon"},
		{name: "csp", method: http.MethodPost, path: "/report", headers: http.Header{"Content-Type": {"application/csp-report"}}, expected: adblockResourceTypeCSP, engineValue: "csp_report"},
		{name: "document", path: "/", headers: http.Header{"Sec-Fetch-Dest": {"document"}}, expected: adblockResourceTypeDocument, engineValue: "document"},
		{name: "dtd", path: "/schema.dtd", expected: adblockResourceTypeDTD, engineValue: "other"},
		{name: "fetch", path: "/api", headers: http.Header{"Sec-Fetch-Dest": {"fetch"}}, expected: adblockResourceTypeFetch, engineValue: "other"},
		{name: "font", path: "/font.woff2", expected: adblockResourceTypeFont, engineValue: "font"},
		{name: "image", path: "/image.png", expected: adblockResourceTypeImage, engineValue: "image"},
		{name: "media", path: "/movie.mp4", expected: adblockResourceTypeMedia, engineValue: "media"},
		{name: "object", path: "/plugin", headers: http.Header{"Sec-Fetch-Dest": {"embed"}}, expected: adblockResourceTypeObject, engineValue: "object"},
		{name: "other", path: "/resource", expected: adblockResourceTypeOther, engineValue: "other"},
		{name: "ping", method: http.MethodPost, path: "/ping", headers: http.Header{"Ping-To": {"https://example.com/target"}}, expected: adblockResourceTypePing, engineValue: "ping"},
		{name: "script worker", path: "/worker", headers: http.Header{"Sec-Fetch-Dest": {"serviceworker"}}, expected: adblockResourceTypeScript, engineValue: "script"},
		{name: "stylesheet", path: "/style", headers: http.Header{"Sec-Fetch-Dest": {"style"}}, expected: adblockResourceTypeStylesheet, engineValue: "stylesheet"},
		{name: "subdocument", path: "/frame", headers: http.Header{"Sec-Fetch-Dest": {"iframe"}}, expected: adblockResourceTypeSubdocument, engineValue: "subdocument"},
		{name: "websocket", path: "/socket", headers: http.Header{"Connection": {"Upgrade"}, "Upgrade": {"websocket"}}, expected: adblockResourceTypeWebSocket, engineValue: "websocket"},
		{name: "xslt", path: "/transform.xslt", expected: adblockResourceTypeXSLT, engineValue: "other"},
		{name: "xhr event source", path: "/events", headers: http.Header{"Accept": {"text/event-stream"}}, expected: adblockResourceTypeXHR, engineValue: "xmlhttprequest"},
		{name: "xhr fallback", method: http.MethodPost, path: "/api", expected: adblockResourceTypeXHR, engineValue: "xmlhttprequest"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			method := test.method
			if method == "" {
				method = http.MethodGet
			}
			request, err := http.NewRequestWithContext(t.Context(), method, "https://example.com"+test.path, nil)
			if err != nil {
				t.Fatal(err)
			}
			request.Header = test.headers.Clone()
			requestType := classifyAdblockRequest(request)
			if requestType != test.expected {
				t.Fatalf("unexpected request type: want %d, got %d", test.expected, requestType)
			}
			if value := requestType.engineValue(); value != test.engineValue {
				t.Fatalf("unexpected engine value: want %q, got %q", test.engineValue, value)
			}
		})
	}
}

func TestAdblockHTTPFilterURLUsesWebSocketScheme(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "https://example.com/socket?token=1", nil)
	if requestURL := adblockHTTPFilterURL("https", request, "websocket"); requestURL.String() != "wss://example.com/socket?token=1" {
		t.Fatalf("unexpected websocket filter URL: %q", requestURL)
	}
	if requestURL := adblockHTTPFilterURL("https", request, "xmlhttprequest"); requestURL.String() != "https://example.com/socket?token=1" {
		t.Fatalf("unexpected HTTP filter URL: %q", requestURL)
	}
}

func TestAdblockHTTPForwardURLUsesHTTPScheme(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected string
	}{
		{name: "websocket", input: "ws://example.com/socket?token=1", expected: "http://example.com/socket?token=1"},
		{name: "secure websocket", input: "wss://example.com/socket?token=1", expected: "https://example.com/socket?token=1"},
		{name: "http", input: "http://example.com/socket?token=1", expected: "http://example.com/socket?token=1"},
		{name: "https", input: "https://example.com/socket?token=1", expected: "https://example.com/socket?token=1"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := mustParseURL(t, test.input)
			if err := adblockHTTPForwardURL(actual); err != nil {
				t.Fatal(err)
			}
			if actual.String() != test.expected {
				t.Fatalf("unexpected forward URL: want %q, got %q", test.expected, actual)
			}
		})
	}
}

func TestWebSocketBlockingUsesWebSocketTypeOnly(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "https://example.com/socket.js", nil)
	request.Header.Set("Connection", "Upgrade")
	request.Header.Set("Upgrade", "websocket")
	requestURL := adblockHTTPFilterURL("https", request, adblockRequestType(request))

	webSocketEngine := newTestAdblockEngine(t, []string{"||example.com/socket.js$websocket"})
	defer webSocketEngine.Close()
	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if !httpRequestBlocked(service, webSocketEngine, request, requestURL, adblockRequestType(request)) {
		t.Fatal("expected websocket rule to block websocket request")
	}

	otherEngine := newTestAdblockEngine(t, []string{
		"||example.com/socket.js$other",
		"||example.com/socket.js$script",
	})
	defer otherEngine.Close()
	service = &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if httpRequestBlocked(service, otherEngine, request, requestURL, adblockRequestType(request)) {
		t.Fatal("expected other and script rules not to block websocket request")
	}
}

func TestForwardHTTPRequestWebSocket(t *testing.T) {
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

	service := &Service{ctx: t.Context()}
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

func TestForwardHTTPRequestFlushesServerSentEvents(t *testing.T) {
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

	service := &Service{ctx: t.Context()}
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

func TestAdblockRequestExceptionForwardsMatchedRequest(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_, _ = writer.Write([]byte("allowed"))
	}))
	defer upstream.Close()

	request := httptest.NewRequest(http.MethodGet, upstream.URL+"/ads.js", nil)
	writer := httptest.NewRecorder()
	service := &Service{ctx: t.Context()}
	err := service.handleAdblockRequest(&adblockRequestContext{
		ctx:         request.Context(),
		engine:      &fakeAdblockEngine{},
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, upstream.URL+"/ads.js"),
		requestType: "script",
		checkResult: adblockrust.CheckResult{
			Matched:   true,
			Exception: "@@||example.com/ads.js$script",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if writer.Code != http.StatusOK || writer.Body.String() != "allowed" {
		t.Fatalf("unexpected forwarded response: code=%d body=%q", writer.Code, writer.Body.String())
	}
}

func TestAdblockDocumentExceptionSkipsResponseFiltering(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/html")
		_, _ = writer.Write([]byte("<html><head></head><body></body></html>"))
	}))
	defer upstream.Close()

	engine := &fakeAdblockEngine{
		cspDirectives: "script-src 'none'",
		cosmeticResult: adblockrust.CosmeticResources{
			HideSelectors: []string{".ad"},
			GenericHide:   true,
		},
	}
	request := httptest.NewRequest(http.MethodGet, upstream.URL+"/", nil)
	writer := httptest.NewRecorder()
	service := &Service{ctx: t.Context()}
	err := service.handleAdblockRequest(&adblockRequestContext{
		ctx:         request.Context(),
		engine:      engine,
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, upstream.URL+"/"),
		requestType: "document",
		checkResult: adblockrust.CheckResult{
			Matched:   true,
			Exception: "@@||example.com^$document",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if writer.Header().Get("Content-Security-Policy") != "" {
		t.Fatalf("expected no injected CSP header, got %q", writer.Header().Get("Content-Security-Policy"))
	}
	if strings.Contains(writer.Body.String(), "sing-box-browser-filter") {
		t.Fatalf("expected unmodified document body, got %q", writer.Body.String())
	}
	if engine.cspCallCount.Load() != 0 {
		t.Fatalf("expected CSP directives not to be checked, got %d calls", engine.cspCallCount.Load())
	}
	if engine.hiddenCallCount.Load() != 0 {
		t.Fatalf("expected HTML filtering not to run, got %d hidden selector calls", engine.hiddenCallCount.Load())
	}
}

func TestHTTPRequestPureDocumentExceptionSkipsResponseFiltering(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/html")
		_, _ = writer.Write([]byte("<html><head></head><body></body></html>"))
	}))
	defer upstream.Close()

	engine := &fakeAdblockEngine{
		exceptionResult: true,
		cspDirectives:   "script-src 'none'",
		cosmeticResult: adblockrust.CosmeticResources{
			HideSelectors: []string{".ad"},
			GenericHide:   true,
		},
	}
	request := httptest.NewRequest(http.MethodGet, upstream.URL+"/", nil)
	writer := httptest.NewRecorder()
	service := &Service{
		ctx:          t.Context(),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	checkResult := service.httpRequestCheck(engine, request, mustParseURL(t, upstream.URL+"/"), "document")
	err := service.handleAdblockRequest(&adblockRequestContext{
		ctx:         request.Context(),
		engine:      engine,
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, upstream.URL+"/"),
		requestType: "document",
		checkResult: checkResult,
	})
	if err != nil {
		t.Fatal(err)
	}
	if writer.Header().Get("Content-Security-Policy") != "" {
		t.Fatalf("expected no injected CSP header, got %q", writer.Header().Get("Content-Security-Policy"))
	}
	if strings.Contains(writer.Body.String(), "sing-box-browser-filter") {
		t.Fatalf("expected unmodified document body, got %q", writer.Body.String())
	}
	if engine.cspCallCount.Load() != 0 {
		t.Fatalf("expected CSP directives not to be checked, got %d calls", engine.cspCallCount.Load())
	}
	if engine.hiddenCallCount.Load() != 0 {
		t.Fatalf("expected HTML filtering not to run, got %d hidden selector calls", engine.hiddenCallCount.Load())
	}
	if engine.exceptionCount.Load() != 1 {
		t.Fatalf("expected one forced exception check, got %d", engine.exceptionCount.Load())
	}
}

func TestAdblockRequestImportantMatchBlocksException(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "https://example.com/ads.js", nil)
	writer := httptest.NewRecorder()
	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{Mode: option.AdblockModeEmptyResponse},
		},
	}
	err := service.handleAdblockRequest(&adblockRequestContext{
		ctx:         request.Context(),
		engine:      &fakeAdblockEngine{},
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/ads.js"),
		requestType: "script",
		checkResult: adblockrust.CheckResult{
			Matched:   true,
			Important: true,
			Exception: "@@||example.com/ads.js$script",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if writer.Code != http.StatusNoContent {
		t.Fatalf("expected empty block response, got %d", writer.Code)
	}
}

func TestAdblockRequestRedirectWritesResource(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "https://example.com/ads.js", nil)
	writer := httptest.NewRecorder()
	service := &Service{ctx: t.Context()}
	err := service.handleAdblockRequest(&adblockRequestContext{
		ctx:         request.Context(),
		engine:      &fakeAdblockEngine{},
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/ads.js"),
		requestType: "script",
		checkResult: adblockrust.CheckResult{
			Matched:  true,
			Redirect: "data:application/javascript;base64,Y29uc29sZS5sb2coMSk7",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if writer.Code != http.StatusOK || writer.Body.String() != "console.log(1);" {
		t.Fatalf("unexpected redirect response: code=%d body=%q", writer.Code, writer.Body.String())
	}
	if contentType := writer.Header().Get("Content-Type"); contentType != "application/javascript" {
		t.Fatalf("unexpected content type: %q", contentType)
	}
}

func TestAdblockRequestRewriteURLForwardsRewrittenTarget(t *testing.T) {
	var path atomic.Value
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		path.Store(request.URL.Path)
		_, _ = writer.Write([]byte("rewritten"))
	}))
	defer upstream.Close()

	request := httptest.NewRequest(http.MethodGet, upstream.URL+"/original.js", nil)
	writer := httptest.NewRecorder()
	service := &Service{ctx: t.Context()}
	err := service.handleAdblockRequest(&adblockRequestContext{
		ctx:         request.Context(),
		engine:      &fakeAdblockEngine{},
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, upstream.URL+"/original.js"),
		requestType: "script",
		checkResult: adblockrust.CheckResult{
			RewrittenURL: upstream.URL + "/rewritten.js",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if writer.Code != http.StatusOK || writer.Body.String() != "rewritten" {
		t.Fatalf("unexpected rewritten response: code=%d body=%q", writer.Code, writer.Body.String())
	}
	if actualPath, _ := path.Load().(string); actualPath != "/rewritten.js" {
		t.Fatalf("expected rewritten path, got %q", actualPath)
	}
}

func TestAdblockRequestContextCachesAndInvalidatesURLValues(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "https://example.com/original.html", nil)
	requestContext := &adblockRequestContext{
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/original.html"),
		requestType: "document",
	}

	if requestContext.requestURLValue() != "https://example.com/original.html" {
		t.Fatalf("unexpected request URL: %q", requestContext.requestURLValue())
	}
	sourceURL, err := requestContext.sourceURLValue()
	if err != nil {
		t.Fatal(err)
	}
	if sourceURL.String() != "https://example.com/original.html" {
		t.Fatalf("unexpected source URL: %q", sourceURL)
	}
	if cachedSourceURL, err := requestContext.sourceURLValue(); err != nil || cachedSourceURL != sourceURL {
		t.Fatalf("expected cached source URL pointer, got url=%v err=%v", cachedSourceURL, err)
	}

	requestContext.setRequestURL(mustParseURL(t, "https://cdn.example.com/rewritten.html"))
	if requestContext.requestURLValue() != "https://cdn.example.com/rewritten.html" {
		t.Fatalf("unexpected rewritten request URL: %q", requestContext.requestURLValue())
	}
	rewrittenSourceURL, err := requestContext.sourceURLValue()
	if err != nil {
		t.Fatal(err)
	}
	if rewrittenSourceURL == sourceURL {
		t.Fatal("expected source URL cache to be invalidated after request URL rewrite")
	}
	if rewrittenSourceURL.String() != "https://cdn.example.com/rewritten.html" {
		t.Fatalf("unexpected rewritten source URL: %q", rewrittenSourceURL)
	}
}

func TestWriteForwardedResponseSkipsForbiddenBodies(t *testing.T) {
	tests := []struct {
		name   string
		method string
		status int
	}{
		{name: "head", method: http.MethodHead, status: http.StatusOK},
		{name: "switching protocols", method: http.MethodGet, status: http.StatusSwitchingProtocols},
		{name: "no content", method: http.MethodGet, status: http.StatusNoContent},
		{name: "not modified", method: http.MethodGet, status: http.StatusNotModified},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			body := &countingReadCloser{Reader: strings.NewReader("must not be read")}
			response := &http.Response{
				StatusCode:    test.status,
				Header:        make(http.Header),
				Body:          body,
				ContentLength: int64(len("must not be read")),
			}
			writer := httptest.NewRecorder()
			service := &Service{}
			if err := service.writeForwardedResponse(writer, response, test.method); err != nil {
				t.Fatal(err)
			}
			if body.reads.Load() != 0 {
				t.Fatalf("response body was read %d times", body.reads.Load())
			}
		})
	}
}

type countingReadCloser struct {
	io.Reader
	reads atomic.Int64
}

func (r *countingReadCloser) Read(content []byte) (int, error) {
	r.reads.Add(1)
	return r.Reader.Read(content)
}

func (r *countingReadCloser) Close() error {
	return nil
}

func TestParseFilterLinesMetadata(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(`
!   tITle: Quick fixes
# Description: Immediate, temporary filters
! Last Modified: 2026-06-18
! Last  Modified: ignored
! Expires: 8 hours
! License: https://example.com/license
! Homepage: https://example.com
! Forums: https://example.com/issues
! Title:value without required separator
||example.com^
`))

	if parsedFilter.Title != "Quick fixes" {
		t.Fatalf("unexpected title: %q", parsedFilter.Title)
	}
	if parsedFilter.Description != "Immediate, temporary filters" {
		t.Fatalf("unexpected description: %q", parsedFilter.Description)
	}
	if parsedFilter.LastModified != "2026-06-18" {
		t.Fatalf("unexpected last modified: %q", parsedFilter.LastModified)
	}
	if parsedFilter.Expires != "8 hours" {
		t.Fatalf("unexpected expires: %q", parsedFilter.Expires)
	}
	if parsedFilter.ExpiresInterval != 8*time.Hour {
		t.Fatalf("unexpected expires interval: %s", parsedFilter.ExpiresInterval)
	}
	if parsedFilter.License != "https://example.com/license" {
		t.Fatalf("unexpected license: %q", parsedFilter.License)
	}
	if parsedFilter.Homepage != "https://example.com" {
		t.Fatalf("unexpected homepage: %q", parsedFilter.Homepage)
	}
	if parsedFilter.Forums != "https://example.com/issues" {
		t.Fatalf("unexpected forums: %q", parsedFilter.Forums)
	}
	if len(parsedFilter.Rules) != 1 || parsedFilter.Rules[0] != "||example.com^" {
		t.Fatalf("unexpected rules: %#v", parsedFilter.Rules)
	}
}

func TestParseFilterLinesKeepsGlobalCosmeticRules(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		`# Description: cosmetic list`,
		`##.generic-ad`,
		`###sponsor`,
		`#@#.allowed-ad`,
	}, "\n")))

	expected := []string{
		`##.generic-ad`,
		`###sponsor`,
		`#@#.allowed-ad`,
	}
	if !slices.Equal(parsedFilter.Rules, expected) {
		t.Fatalf("global cosmetic rules must stay in Rust rules:\nwant %#v\ngot  %#v", expected, parsedFilter.Rules)
	}
	if parsedFilter.Description != "cosmetic list" {
		t.Fatalf("metadata comment was not parsed: %q", parsedFilter.Description)
	}
}

func TestParsedGlobalCosmeticRulesFeedDynamicSelectors(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(`##.generic-ad`))
	engine := newTestAdblockEngine(t, parsedFilter.Rules)
	defer engine.Close()

	resources, err := engine.URLCosmeticResources("https://example.com/")
	if err != nil {
		t.Fatal(err)
	}
	selectors, err := engine.HiddenClassIDSelectors([]string{"generic-ad"}, nil, resources.Exceptions)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(selectors, ".generic-ad") {
		t.Fatalf("global cosmetic selector was not available dynamically: %v", selectors)
	}
}

func TestParseFilterLinesSelectsFirefoxHTMLFilteringBranch(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		"!#if !env_mv3",
		"!#if !cap_html_filtering",
		"!#if env_firefox",
		"youtube.com##+js(json-prune, playerResponse.adPlacements)",
		"!#endif",
		"!#endif",
		"!#endif",
		"!#if !cap_html_filtering",
		"www.youtube.com##+js(trusted-replace-fetch-response, adPlacements)",
		"!#else",
		`||www.youtube.com/playlist?list=$xhr,1p,replace=/"adPlacements"/"no_ads"/`,
		`www.youtube.com##^script[id]:has-text(window,"fetch")`,
		"!#endif",
	}, "\n")))

	if len(parsedFilter.Rules) != 0 {
		t.Fatalf("unexpected Rust rules: %#v", parsedFilter.Rules)
	}
	if len(parsedFilter.Companion.replace) != 1 {
		t.Fatalf("expected replace companion rule, got %#v", parsedFilter.Companion)
	}
	if len(parsedFilter.HTML.rules) != 1 {
		t.Fatalf("expected HTML filter rule, got %#v", parsedFilter.HTML)
	}
}

func TestParseFilterLinesSkipsInactiveNestedBranches(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		"!#if env_mv3",
		"||mv3.example^",
		"!#if env_firefox",
		"||nested.example^",
		"!#endif",
		"!#else",
		"||fallback.example^",
		"!#endif",
	}, "\n")))

	expected := []string{"||fallback.example^"}
	if !slices.Equal(parsedFilter.Rules, expected) {
		t.Fatalf("unexpected preprocessed rules:\nwant %#v\ngot  %#v", expected, parsedFilter.Rules)
	}
}

func TestParseFilterLinesSupportsExpandedPreprocessorExpressions(t *testing.T) {
	environment := &option.AdblockEnvironment{Chromium: true, Firefox: true, Mobile: true, UBO: true, DevBuild: true}
	parsedFilter := parseFilterLinesWithEnvironment([]byte(strings.Join([]string{
		"!#if env_chromium && (env_mobile || env_firefox) && !env_mv3",
		"||chromium-mobile.example^",
		"!#endif",
		"!#if ext_abp || env_safari",
		"||inactive.example^",
		"!#else",
		"||fallback.example^",
		"!#endif",
	}, "\n")), environment)

	expected := []string{"||chromium-mobile.example^", "||fallback.example^"}
	if !slices.Equal(parsedFilter.Rules, expected) {
		t.Fatalf("unexpected preprocessed rules:\nwant %#v\ngot  %#v", expected, parsedFilter.Rules)
	}
}

func TestParseFilterLinesDefaultEnvironmentStaysFirefoxCompatible(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		"!#if env_firefox",
		"||firefox.example^",
		"!#endif",
		"!#if ext_ublock && cap_html_filtering && cap_ipaddress && cap_user_stylesheet && adguard_ext_firefox",
		"||firefox-capabilities.example^",
		"!#endif",
		"!#if env_chromium",
		"||chromium.example^",
		"!#endif",
	}, "\n")))

	expected := []string{"||firefox.example^", "||firefox-capabilities.example^"}
	if !slices.Equal(parsedFilter.Rules, expected) {
		t.Fatalf("unexpected default environment rules:\nwant %#v\ngot  %#v", expected, parsedFilter.Rules)
	}
}

func TestParseFilterLinesExtractsCompanionRules(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		`||api.example/data$xhr,replace=/"ad"/"ok"/`,
		`@@||api.example/data$xhr,replace=/"ad"/"ok"/`,
		`example.com$document,permissions=interest-cohort=()`,
		`example.com$header=Set-Cookie:/track/`,
		`example.com$cookie=session`,
		`||ads.example^`,
	}, "\n")))

	if len(parsedFilter.Rules) != 1 || parsedFilter.Rules[0] != "||ads.example^" {
		t.Fatalf("unexpected Rust rules: %#v", parsedFilter.Rules)
	}
	if len(parsedFilter.Companion.replace) != 2 || len(parsedFilter.Companion.permissions) != 1 || len(parsedFilter.Companion.headers) != 1 || len(parsedFilter.Companion.cookies) != 1 {
		t.Fatalf("unexpected companion rules: %#v", parsedFilter.Companion)
	}
}

func TestParseFilterLinesExtractsAdvancedRules(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		`*$removeparam=/^utm_/`,
		`||tracker.example^$method=post,to=ads.example|/cdn\d+\.example/`,
		`||jump.example^$urlskip=?target -uricomponent +https`,
		`||example.com/path$uritransform=/ads/content/`,
		`||ads.example^`,
	}, "\n")))

	if len(parsedFilter.Rules) != 1 || parsedFilter.Rules[0] != "||ads.example^" {
		t.Fatalf("unexpected Rust rules: %#v", parsedFilter.Rules)
	}
	if len(parsedFilter.Advanced.rules) != 4 {
		t.Fatalf("unexpected advanced rules: %#v", parsedFilter.Advanced)
	}
}

func TestParseFilterLinesKeepsDomainOnlyRulesInRustEngine(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		`*$domain=habr.com`,
		`||ads.example^$script,domain=habr.com`,
		`||tracker.example^$to=ads.example,domain=habr.com`,
	}, "\n")))

	expectedRules := []string{
		`*$domain=habr.com`,
		`||ads.example^$script,domain=habr.com`,
	}
	if !slices.Equal(parsedFilter.Rules, expectedRules) {
		t.Fatalf("domain-only rules must stay in Rust rules:\nwant %#v\ngot  %#v", expectedRules, parsedFilter.Rules)
	}
	if len(parsedFilter.Advanced.rules) != 1 {
		t.Fatalf("unexpected advanced rules: %#v", parsedFilter.Advanced)
	}
}

func TestParseFilterLinesExtractsHTMLFilters(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		`example.com##^script:has-text(/adPlacements/)`,
		`example.com#@#^script:has-text(/allowed/)`,
		`||ads.example^`,
	}, "\n")))

	if len(parsedFilter.Rules) != 1 || parsedFilter.Rules[0] != "||ads.example^" {
		t.Fatalf("unexpected Rust rules: %#v", parsedFilter.Rules)
	}
	if len(parsedFilter.HTML.rules) != 2 {
		t.Fatalf("unexpected HTML filters: %#v", parsedFilter.HTML)
	}
}

func TestParseFilterLinesDiscardsUnsupportedHTMLFilters(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(strings.Join([]string{
		`example.com##^div:matches-css(display:block)`,
		`example.com##^div:style(display:none)`,
		`||ads.example^`,
	}, "\n")))

	if len(parsedFilter.Rules) != 1 || parsedFilter.Rules[0] != "||ads.example^" {
		t.Fatalf("unexpected Rust rules: %#v", parsedFilter.Rules)
	}
	if len(parsedFilter.HTML.rules) != 0 {
		t.Fatalf("unexpected HTML filters: %#v", parsedFilter.HTML)
	}
}

func TestManagedEngineCloseWaitsForRelease(t *testing.T) {
	rawEngine := &fakeAdblockEngine{}
	engine := newManagedEngine(rawEngine)
	retained := engine.retain()
	if retained == nil {
		t.Fatal("expected retained engine")
	}

	closed := make(chan struct{})
	go func() {
		_ = engine.close()
		close(closed)
	}()

	select {
	case <-closed:
		t.Fatal("engine closed before retained user released it")
	case <-time.After(50 * time.Millisecond):
	}
	if rawEngine.closed.Load() {
		t.Fatal("underlying engine was closed before release")
	}

	engine.release()

	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("engine close did not finish after release")
	}
	if !rawEngine.closed.Load() {
		t.Fatal("underlying engine was not closed")
	}
}

func TestReadyEngineRetainsCurrentEngine(t *testing.T) {
	rawEngine := &fakeAdblockEngine{}
	ctx, cancel := context.WithCancel(t.Context())
	service := &Service{
		ctx:    ctx,
		cancel: cancel,
		engine: newManagedEngine(rawEngine),
	}

	engineRef, engine := service.readyEngine()
	if engineRef == nil || engine == nil {
		t.Fatal("expected ready engine")
	}
	closed := make(chan struct{})
	go func() {
		_ = service.engine.close()
		close(closed)
	}()

	select {
	case <-closed:
		t.Fatal("service closed retained engine before release")
	case <-time.After(50 * time.Millisecond):
	}

	engineRef.release()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("service close did not finish after release")
	}
}

func TestHTTPRequestBlockedUsesUnknownSubresourceSourceAsFirstParty(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||ads.example^$third-party"})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/banner.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "ads.example"
	request.Header.Set("Sec-Fetch-Dest", "script")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if httpRequestBlocked(service, engine, request, mustParseURL(t, "https://ads.example/banner.js"), adblockRequestType(request)) {
		t.Fatal("expected subresource without referer or origin to use request URL as source")
	}
}

func TestHTTPRequestBlockedUsesRefererSubresourceSourceAsThirdParty(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||ads.example.com^$third-party"})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/banner.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "ads.example.com"
	request.Header.Set("Sec-Fetch-Dest", "script")
	request.Header.Set("Referer", "https://publisher.example.net/article")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if !httpRequestBlocked(service, engine, request, mustParseURL(t, "https://ads.example.com/banner.js"), adblockRequestType(request)) {
		t.Fatal("expected third-party rule to match subresource with cross-site referer")
	}
}

func TestHTTPRequestBlockedChecksURLInferredType(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||checkadblock.ru/src.fe63b94a.js$script"})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/src.fe63b94a.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "checkadblock.ru"
	request.Header.Set("Sec-Fetch-Dest", "document")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	requestType := adblockRequestType(request)
	if requestType != "document" {
		t.Fatalf("expected Chromium-style direct request to be classified as document, got %q", requestType)
	}
	if !httpRequestBlocked(service, engine, request, mustParseURL(t, "https://checkadblock.ru/src.fe63b94a.js"), requestType) {
		t.Fatal("expected script rule to match URL-inferred request type")
	}
}

func TestHTTPRequestBlockedChecksAdScriptNameWithoutExtension(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||example.com/pagead$script"})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/pagead", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "example.com"
	request.Header.Set("Sec-Fetch-Dest", "document")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if !httpRequestBlocked(service, engine, request, mustParseURL(t, "https://example.com/pagead"), adblockRequestType(request)) {
		t.Fatal("expected pagead path to be checked as script")
	}
}

func TestHTTPRequestBlockedKeepsAdblockExceptions(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{
		"||example.com/ads.js$script",
		"@@||example.com/ads.js$script",
	})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/ads.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "example.com"

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if httpRequestBlocked(service, engine, request, mustParseURL(t, "https://example.com/ads.js"), adblockRequestType(request)) {
		t.Fatal("expected exception rule to suppress blocking")
	}
}

func TestHTTPRequestKeepsRefererScopedException(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{
		"||ads.example/banner.js$script",
		"@@||ads.example/banner.js$script,domain=adblock-tester.com",
	})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/banner.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "ads.example"
	request.Header.Set("Sec-Fetch-Dest", "script")
	request.Header.Set("Referer", "https://adblock-tester.com/")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	result := service.httpRequestCheck(engine, request, mustParseURL(t, "https://ads.example/banner.js"), adblockRequestType(request))
	if checkResultBlocked(result) {
		t.Fatalf("expected referer-scoped exception to suppress blocking: %#v", result)
	}
	if result.Exception == "" {
		t.Fatalf("expected exception result for referer-scoped exception: %#v", result)
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestHTTPRequestKeepsSourceDocumentException(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{
		"||pagead2.googlesyndication.com^",
		"@@||adblock-tester.com^$document",
	})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/pagead/js/adsbygoogle.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "pagead2.googlesyndication.com"
	request.Header.Set("Referer", "https://adblock-tester.com/")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	result := service.httpRequestCheck(engine, request, mustParseURL(t, "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"), adblockRequestType(request))
	if checkResultBlocked(result) {
		t.Fatalf("expected source document exception to suppress blocking: %#v", result)
	}
	if result.Exception == "" {
		t.Fatalf("expected exception result for source document exception: %#v", result)
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestHTTPRequestCheckKeepsDocumentException(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{
		"||example.com^$document",
		"@@||example.com^$document",
	})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "example.com"
	request.Header.Set("Sec-Fetch-Dest", "document")

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	result := service.httpRequestCheck(engine, request, mustParseURL(t, "https://example.com/"), adblockRequestType(request))
	if checkResultBlocked(result) {
		t.Fatalf("expected document exception to suppress blocking: %#v", result)
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestAdblockStatsCounters(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||ads.example^"})
	defer engine.Close()
	request, err := http.NewRequest(http.MethodGet, "/banner.js", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Host = "ads.example"

	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	if !httpRequestBlocked(service, engine, request, mustParseURL(t, "https://ads.example/banner.js"), adblockRequestType(request)) {
		t.Fatal("expected request to be blocked")
	}
	if service.Stats().TotalRequests() != 1 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 1 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestHTTPRequestCheckUsesMethodAndSeparatesCacheEntries(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||api.example^$method=post"})
	defer engine.Close()
	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	requestURL := mustParseURL(t, "https://api.example/collect")

	postRequest := httptest.NewRequest(http.MethodPost, requestURL.String(), nil)
	if !httpRequestBlocked(service, engine, postRequest, requestURL, "xmlhttprequest") {
		t.Fatal("expected POST request to match method filter")
	}
	getRequest := httptest.NewRequest(http.MethodGet, requestURL.String(), nil)
	if httpRequestBlocked(service, engine, getRequest, requestURL, "xmlhttprequest") {
		t.Fatal("expected GET request not to reuse cached POST match")
	}
}

func TestRequestCheckCachesDetailedResults(t *testing.T) {
	engine := &fakeAdblockEngine{
		detailedResult: adblockrust.CheckResult{
			Matched:  true,
			Redirect: "data:text/plain;base64,eA",
		},
	}
	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	for range 2 {
		result, err := service.requestCheck(engine, "https://ads.example/banner.js", "https://example.com/", "script", adblockrust.RequestMethodGet)
		if err != nil {
			t.Fatal(err)
		}
		if !result.Matched || result.Redirect == "" {
			t.Fatalf("unexpected result: %#v", result)
		}
	}
	if engine.detailCallCount.Load() != 1 {
		t.Fatalf("expected one detailed bridge call, got %d", engine.detailCallCount.Load())
	}
}

func TestRequestCheckUsesDetailedCheck(t *testing.T) {
	engine := &fakeAdblockEngine{
		checkResult: true,
		detailedResult: adblockrust.CheckResult{
			Matched:  true,
			Redirect: "data:text/plain;base64,eA",
		},
	}
	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	result, err := service.requestCheck(engine, "https://ads.example/banner.js", "", "document", adblockrust.RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if result.Redirect == "" {
		t.Fatalf("expected detailed check to upgrade cached boolean result: %#v", result)
	}
	if engine.checkCallCount.Load() != 0 {
		t.Fatalf("expected no boolean bridge calls, got %d", engine.checkCallCount.Load())
	}
	if engine.detailCallCount.Load() != 1 {
		t.Fatalf("expected one detailed bridge call, got %d", engine.detailCallCount.Load())
	}
}

func TestClearCheckCacheDropsCachedResults(t *testing.T) {
	engine := &fakeAdblockEngine{detailedResult: adblockrust.CheckResult{Matched: true}}
	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	for range 2 {
		result, err := service.requestCheck(engine, "https://ads.example/", "", "document", adblockrust.RequestMethodGet)
		if err != nil {
			t.Fatal(err)
		}
		if !checkResultBlocked(result) {
			t.Fatal("expected request to match")
		}
	}
	service.clearCheckCache()
	if _, err := service.requestCheck(engine, "https://ads.example/", "", "document", adblockrust.RequestMethodGet); err != nil {
		t.Fatal(err)
	}
	if engine.detailCallCount.Load() != 2 {
		t.Fatalf("expected cache clear to force a second bridge call, got %d", engine.detailCallCount.Load())
	}
}

func TestHandleTCPSkipsDisabledPlainHTTP(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||example.com^"})
	defer engine.Close()
	service := newTestService(t.Context(), option.AdblockOptions{}, engine)
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	handled, err := service.HandleTCP(t.Context(), serverConn, adapter.InboundContext{
		Protocol: C.ProtocolHTTP,
		Domain:   "example.com",
	}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if handled {
		t.Fatal("expected disabled HTTP filtering to skip TCP handling")
	}
}

func TestHandleTCPSkipsHTTPSWithoutEnabledTLS(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||example.com^"})
	defer engine.Close()
	tests := []struct {
		name    string
		options option.AdblockOptions
	}{
		{
			name: "missing TLS",
			options: option.AdblockOptions{
				Filtering: option.AdblockFiltering{HTTPS: true},
			},
		},
		{
			name: "disabled TLS",
			options: option.AdblockOptions{
				Filtering: option.AdblockFiltering{HTTPS: true},
				TLS:       &option.AdblockTLSOptions{},
			},
		},
		{
			name: "disabled HTTPS",
			options: option.AdblockOptions{
				Filtering: option.AdblockFiltering{HTTPS: false},
				TLS:       &option.AdblockTLSOptions{Enabled: true},
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			service := newTestService(t.Context(), test.options, engine)
			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()

			handled, err := service.HandleTCP(t.Context(), serverConn, adapter.InboundContext{
				Protocol: C.ProtocolTLS,
				Domain:   "example.com",
			}, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			if handled {
				t.Fatal("expected HTTPS filtering without enabled TLS and flag to skip TCP handling")
			}
		})
	}
}

func TestHandleTCPSkipsTLSInterceptionForDocumentException(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{
		"||example.com^$document",
		"@@||example.com^$document",
	})
	defer engine.Close()
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{HTTPS: true},
		TLS:       &option.AdblockTLSOptions{Enabled: true},
	}, engine)
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	handled, err := service.HandleTCP(t.Context(), serverConn, adapter.InboundContext{
		Protocol: C.ProtocolTLS,
		Domain:   "example.com",
	}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if handled {
		t.Fatal("expected document exception to skip TLS interception")
	}
}

func TestHandleTCPSkipsTLSInterceptionForPureDocumentException(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"@@||adblock-tester.com^$document"})
	defer engine.Close()
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{HTTPS: true},
		TLS:       &option.AdblockTLSOptions{Enabled: true},
	}, engine)
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	handled, err := service.HandleTCP(t.Context(), serverConn, adapter.InboundContext{
		Protocol: C.ProtocolTLS,
		Domain:   "adblock-tester.com",
	}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if handled {
		t.Fatal("expected pure document exception to skip TLS interception")
	}
}

func TestHandleTCPDoesNotPreBlockTLSDomainBeforeHeaders(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||pagead2.googlesyndication.com^"})
	defer engine.Close()
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{HTTPS: true},
		TLS:       &option.AdblockTLSOptions{Enabled: true},
	}, engine)
	service.tlsCA = &tlsCertificateAuthority{}
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	done := make(chan error, 1)
	go func() {
		handled, err := service.HandleTCP(t.Context(), serverConn, adapter.InboundContext{
			Protocol: C.ProtocolTLS,
			Domain:   "pagead2.googlesyndication.com",
		}, nil, nil)
		if !handled {
			done <- errors.New("expected TLS connection to be handled for HTTP inspection")
			return
		}
		done <- err
	}()
	_ = clientConn.Close()
	if err := <-done; err != nil && !errors.Is(err, net.ErrClosed) && !strings.Contains(err.Error(), "use of closed network connection") {
		t.Fatalf("unexpected handler error: %v", err)
	}
}

func TestHandleUDPSkipsDisabledDNSAndQUIC(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||example.com^"})
	defer engine.Close()
	tests := []struct {
		name     string
		protocol string
		options  option.AdblockOptions
	}{
		{
			name:     "DNS disabled",
			protocol: C.ProtocolDNS,
			options:  option.AdblockOptions{},
		},
		{
			name:     "QUIC without TLS",
			protocol: C.ProtocolQUIC,
			options: option.AdblockOptions{
				Filtering: option.AdblockFiltering{QUIC: true},
			},
		},
		{
			name:     "QUIC disabled with TLS",
			protocol: C.ProtocolQUIC,
			options: option.AdblockOptions{
				Filtering: option.AdblockFiltering{QUIC: false},
				TLS:       &option.AdblockTLSOptions{Enabled: true},
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			conn := newTestPacketConn()
			service := newTestService(t.Context(), test.options, engine)
			handled, err := service.HandleUDP(t.Context(), conn, adapter.InboundContext{
				Protocol: test.protocol,
				Domain:   "example.com",
			}, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			if handled {
				t.Fatal("expected disabled UDP protocol filtering to skip handling")
			}
			if conn.closed {
				t.Fatal("expected skipped UDP handling to leave connection open")
			}
		})
	}
}

func TestHandleUDPSkipsQUICWithIncompletePacketMetadata(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{QUIC: true},
		TLS:       &option.AdblockTLSOptions{Enabled: true},
	}, &fakeAdblockEngine{})
	tests := []struct {
		name     string
		metadata adapter.InboundContext
	}{
		{
			name: "missing source",
			metadata: adapter.InboundContext{
				Protocol:    C.ProtocolQUIC,
				Domain:      "example.com",
				Destination: M.ParseSocksaddrHostPort("example.com", 443),
			},
		},
		{
			name: "missing destination",
			metadata: adapter.InboundContext{
				Protocol: C.ProtocolQUIC,
				Domain:   "example.com",
				Source:   M.ParseSocksaddrHostPort("192.0.2.10", 55000),
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			conn := newTestPacketConn()
			handled, err := service.HandleUDP(t.Context(), conn, test.metadata, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			if handled {
				t.Fatal("expected QUIC handling to be skipped")
			}
			if conn.closed {
				t.Fatal("expected skipped UDP handling to leave connection open")
			}
		})
	}
}

func TestHandleUDPFiltersEnabledDNS(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||example.com^"})
	defer engine.Close()
	conn := newTestPacketConn()
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{DNS: true},
	}, engine)
	handled, err := service.HandleUDP(t.Context(), conn, adapter.InboundContext{
		Protocol: C.ProtocolDNS,
		Domain:   "example.com",
	}, nil, nil)
	if !handled {
		t.Fatal("expected enabled DNS filtering to handle matching domain")
	}
	var blocked interface{ IsAdblockBlocked() }
	if !errors.As(err, &blocked) {
		t.Fatalf("expected adblock blocked error, got %T: %v", err, err)
	}
	if !conn.closed {
		t.Fatal("expected blocked DNS packet connection to be closed")
	}
}

func TestCheckDNSResponseBlocksCNAMEWhenUncloakingEnabled(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||tracker.example^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:             true,
				CNAMEUncloaking: true,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	// Use a third-level domain. Second-level domains intentionally skip
	// CNAME uncloaking with the new logic.
	message, response := newTestCNameExchange("www.first-party.example.", "tracker.example.")

	service.CheckDNSResponse(t.Context(), message, response)
	assertBlockedDNSResponse(t, response, mDNS.TypeA)

	// One recorded decision for the original DNS query domain, one for the CNAME.
	if service.Stats().TotalRequests() != 2 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 1 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestCheckDNSResponseSkipsCNAMEWhenUncloakingDisabled(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||tracker.example^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:             true,
				CNAMEUncloaking: false,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	message, response := newTestCNameExchange("www.first-party.example.", "tracker.example.")

	service.CheckDNSResponse(t.Context(), message, response)
	if len(response.Answer) != 1 {
		t.Fatalf("expected allowed response to remain unchanged, got %d answers", len(response.Answer))
	}
	if _, isCNAME := response.Answer[0].(*mDNS.CNAME); !isCNAME {
		t.Fatalf("expected allowed CNAME response to remain unchanged, got %T", response.Answer[0])
	}

	// DNS filtering still checks the queried domain.
	// It should not check or block the CNAME target.
	if service.Stats().TotalRequests() != 2 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestCheckDNSResponseSkipsCNAMEForSecondLevelDomain(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||tracker.example^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:             true,
				CNAMEUncloaking: true,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	message, response := newTestCNameExchange("first-party.example.", "tracker.example.")

	service.CheckDNSResponse(t.Context(), message, response)

	// Only the original query domain should be checked.
	if service.Stats().TotalRequests() != 2 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestCheckDNSResponseBlocksOriginalDomainWhenUncloakingDisabled(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||first-party.example^", "||tracker.example^"})
	defer engine.Close()
	var logOutput bytes.Buffer
	logFactory, err := log.New(log.Options{
		Context:       t.Context(),
		DefaultWriter: &logOutput,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer logFactory.Close()

	service := &Service{
		ctx:    t.Context(),
		logger: logFactory.Logger(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:             true,
				CNAMEUncloaking: false,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	message, response := newTestCNameExchange("first-party.example.", "tracker.example.")

	service.CheckDNSResponse(t.Context(), message, response)
	assertBlockedDNSResponse(t, response, mDNS.TypeA)
	if !strings.Contains(logOutput.String(), "blocked by adblock") || !strings.Contains(logOutput.String(), "domain=first-party.example") {
		t.Fatalf("missing blocked DNS log: %q", logOutput.String())
	}

	if service.Stats().TotalRequests() != 1 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 1 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestCheckDNSResponseSkipsSameSiteCNAME(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||edge.first-party.example^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:             true,
				CNAMEUncloaking: true,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	message, response := newTestCNameExchange("www.first-party.example.", "edge.first-party.example.")

	service.CheckDNSResponse(t.Context(), message, response)

	// Only the original query domain should be checked.
	// Same-site CNAME targets should be ignored.
	if service.Stats().TotalRequests() != 2 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestCheckDNSResponseSkipsKnownInfrastructureCNAME(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||example.cloudfront.net^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:             true,
				CNAMEUncloaking: true,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}

	message, response := newTestCNameExchange("www.first-party.example.", "example.cloudfront.net.")

	service.CheckDNSResponse(t.Context(), message, response)

	// Only the original query domain should be checked.
	// Known CDN / infrastructure targets should be ignored.
	if service.Stats().TotalRequests() != 2 {
		t.Fatalf("unexpected total requests: %d", service.Stats().TotalRequests())
	}
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("unexpected blocked requests: %d", service.Stats().BlockedRequests())
	}
}

func TestCheckDNSResponseRewritesBlockedResponseByQueryType(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||blocked.example^"})
	defer engine.Close()

	tests := []struct {
		name  string
		qtype uint16
	}{
		{name: "A", qtype: mDNS.TypeA},
		{name: "AAAA", qtype: mDNS.TypeAAAA},
		{name: "TXT", qtype: mDNS.TypeTXT},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			service := &Service{
				ctx: t.Context(),
				options: option.AdblockOptions{
					Filtering: option.AdblockFiltering{DNS: true},
				},
				engine:       newTestManagedEngine(engine),
				requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
				stats:        newServiceStats(nil),
			}
			message, response := newTestDNSExchange("blocked.example.", test.qtype)

			service.CheckDNSResponse(t.Context(), message, response)

			assertBlockedDNSResponse(t, response, test.qtype)
		})
	}
}

func TestCheckDNSResponseSupportsNXDOMAINAndCustomTTL(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||blocked.example^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:          true,
				DNSBlockMode: option.AdblockDNSBlockModeNXDOMAIN,
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	message, response := newTestDNSExchange("blocked.example.", mDNS.TypeA)
	service.CheckDNSResponse(t.Context(), message, response)
	if response.Rcode != mDNS.RcodeNameError {
		t.Fatalf("expected NXDOMAIN, got %s", mDNS.RcodeToString[response.Rcode])
	}
	if len(response.Answer) != 0 || len(response.Ns) != 0 || len(response.Extra) != 0 {
		t.Fatalf("expected empty NXDOMAIN sections: answer=%d ns=%d extra=%d", len(response.Answer), len(response.Ns), len(response.Extra))
	}

	service.options.Filtering.DNSBlockMode = option.AdblockDNSBlockModeZeroIP
	service.options.Filtering.DNSBlockTTL = badoption.Duration(5 * time.Minute)
	message, response = newTestDNSExchange("blocked.example.", mDNS.TypeA)
	service.CheckDNSResponse(t.Context(), message, response)
	if len(response.Answer) != 1 || response.Answer[0].Header().Ttl != 300 {
		t.Fatalf("expected custom TTL sinkhole answer, got answers=%d ttl=%d", len(response.Answer), response.Answer[0].Header().Ttl)
	}
}

func TestCheckDNSResponseSkipsConfiguredInfrastructureCNAME(t *testing.T) {
	engine := newTestAdblockEngine(t, []string{"||tracker.customcdn.test^"})
	defer engine.Close()

	service := &Service{
		ctx: t.Context(),
		options: option.AdblockOptions{
			Filtering: option.AdblockFiltering{
				DNS:                         true,
				CNAMEUncloaking:             true,
				CNAMEInfrastructureSuffixes: []string{"customcdn.test"},
			},
		},
		engine:       newTestManagedEngine(engine),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	message, response := newTestCNameExchange("www.first-party.example.", "tracker.customcdn.test.")
	service.CheckDNSResponse(t.Context(), message, response)
	if service.Stats().BlockedRequests() != 0 {
		t.Fatalf("configured infrastructure CNAME should not be blocked")
	}
}

func TestGetFilterMetadataFetchesRemoteList(t *testing.T) {
	filterServer := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_, _ = writer.Write([]byte("! Title: Remote list\n! Expires: 2 hours\n||example.com^\n"))
	}))
	defer filterServer.Close()

	service := &Service{ctx: context.Background()}
	metadata, err := service.GetFilterMetadata(filterServer.URL)
	if err != nil {
		t.Fatal(err)
	}
	if metadata.URI != filterServer.URL {
		t.Fatalf("unexpected uri: %q", metadata.URI)
	}
	if metadata.Title != "Remote list" {
		t.Fatalf("unexpected title: %q", metadata.Title)
	}
	if metadata.RuleCount != 1 {
		t.Fatalf("unexpected rule count: %d", metadata.RuleCount)
	}
	if metadata.ExpiresInterval != 2*time.Hour {
		t.Fatalf("unexpected expires interval: %s", metadata.ExpiresInterval)
	}
}

func TestPreCacheFilterWritesIndependentAdblockDB(t *testing.T) {
	filterServer := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Etag", "test-etag")
		_, _ = writer.Write([]byte("! Title: Cached\n||example.com^\n"))
	}))
	defer filterServer.Close()

	databasePath := t.TempDir() + "/adblock.db"
	service := &Service{ctx: context.Background()}
	if _, err := service.PreCacheFilter(filterServer.URL, databasePath); err != nil {
		t.Fatal(err)
	}

	store := db.New(context.Background(), databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	saved := store.LoadFilterList(cacheTag(filterServer.URL))
	if saved == nil {
		t.Fatal("expected cached filter")
	}
	if !strings.Contains(string(saved.Content), "||example.com^") {
		t.Fatalf("unexpected cached content: %q", saved.Content)
	}
	if saved.LastEtag != "test-etag" {
		t.Fatalf("unexpected etag: %q", saved.LastEtag)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}

	stored, err := service.GetStoredFilterMetadata(filterServer.URL, databasePath)
	if err != nil {
		t.Fatalf("GetStoredFilterMetadata: %v", err)
	}
	if stored == nil {
		t.Fatal("expected stored metadata after PreCacheFilter")
	}
	if stored.Title != "Cached" {
		t.Fatalf("unexpected stored title: %q", stored.Title)
	}
	if stored.LastUpdated.IsZero() {
		t.Fatal("expected non-zero stored LastUpdated")
	}

	if err := service.DeleteCachedFilter(filterServer.URL, databasePath); err != nil {
		t.Fatalf("DeleteCachedFilter: %v", err)
	}
	saved = store.LoadFilterList(cacheTag(filterServer.URL))
	if saved != nil {
		t.Fatal("expected cached filter to be removed")
	}
}

func TestPreCacheFiltersWritesAdblockDBConcurrently(t *testing.T) {
	filterServer := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Etag", "etag-"+request.URL.Query().Get("id"))
		_, _ = writer.Write([]byte("! Title: Cached " + request.URL.Query().Get("id") + "\n||example.com^\n"))
	}))
	defer filterServer.Close()

	uris := []string{
		filterServer.URL + "?id=1",
		filterServer.URL + "?id=2",
		filterServer.URL + "?id=3",
		filterServer.URL + "?id=4",
	}
	databasePath := t.TempDir() + "/adblock.db"
	service := &Service{ctx: context.Background()}
	results := service.PreCacheFilters(uris, databasePath)
	for result := range results {
		if result.Error() != nil {
			t.Fatalf("PreCacheFilters(%s): %v", result.URL(), result.Error())
		}
		if result.LastUpdated() == "" {
			t.Fatalf("missing update time for %s", result.URL())
		}
	}

	store := db.New(context.Background(), databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for _, uri := range uris {
		saved := store.LoadFilterList(cacheTag(uri))
		if saved == nil {
			t.Fatalf("missing cached filter for %s", uri)
		}
		if !strings.Contains(string(saved.Content), "||example.com^") {
			t.Fatalf("unexpected cached content for %s: %q", uri, saved.Content)
		}
	}
}

func TestCertificateHasEVPolicy(t *testing.T) {
	if !certificateHasEVPolicy(&x509.Certificate{
		PolicyIdentifiers: []asn1.ObjectIdentifier{{2, 23, 140, 1, 1}},
	}) {
		t.Fatal("expected EV policy to match")
	}
	if certificateHasEVPolicy(&x509.Certificate{
		PolicyIdentifiers: []asn1.ObjectIdentifier{{1, 2, 3, 4}},
	}) {
		t.Fatal("expected unknown policy not to match")
	}
}

func TestAdblockUpstreamConnReadIdleTimeout(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()
	upstreamConn := &adblockUpstreamConn{
		Conn:            clientConn,
		readIdleTimeout: 10 * time.Millisecond,
	}

	_, err := upstreamConn.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("expected read timeout")
	}
	var netErr net.Error
	if !errors.As(err, &netErr) || !netErr.Timeout() {
		t.Fatalf("expected net timeout, got %T: %v", err, err)
	}
}

func TestHandlePlainHTTPForwardsWithHTTPServer(t *testing.T) {
	t.Skip("TODO: fix")

	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/css")
		_, _ = writer.Write([]byte("body{}"))
	}))
	defer upstream.Close()
	engine := newTestAdblockEngine(t, []string{"||blocked.example^"})
	defer engine.Close()

	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	service := newTestHTTPService()
	errCh := make(chan error, 1)
	go func() {
		errCh <- service.handleHTTP(ctx.NewConn(t.Context(), engine, serverConn, nil, adapter.InboundContext{}, nil))
	}()

	_, err := io.WriteString(clientConn, "GET "+upstream.URL+"/style.css HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(clientConn), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "body{}" {
		t.Fatalf("unexpected body: %q", content)
	}
	if err := <-errCh; err != nil {
		t.Fatal(err)
	}
}

func TestWriteHTTPResponseDoesNotTerminateChunkedBodyAfterReadError(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	responseErr := errors.New("upstream read failed")
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Status:        "200 OK",
		Header:        make(http.Header),
		ContentLength: -1,
		Body: &errorAfterReadCloser{
			content: []byte("hello"),
			err:     responseErr,
		},
	}
	errCh := make(chan error, 1)
	go func() {
		errCh <- writeHTTPResponse(serverConn, response)
		_ = serverConn.Close()
	}()

	raw, err := io.ReadAll(clientConn)
	if err != nil {
		t.Fatal(err)
	}
	if err := <-errCh; !errors.Is(err, responseErr) {
		t.Fatalf("expected response write error %v, got %v", responseErr, err)
	}
	if strings.Contains(string(raw), "\r\n0\r\n") {
		t.Fatalf("unexpected successful chunk terminator in response: %q", raw)
	}
}

type errorAfterReadCloser struct {
	content []byte
	err     error
	read    bool
}

func (r *errorAfterReadCloser) Read(p []byte) (int, error) {
	if r.read {
		return 0, r.err
	}
	r.read = true
	return copy(p, r.content), nil
}

func (r *errorAfterReadCloser) Close() error {
	return nil
}

func newTestAdblockEngine(t *testing.T, rules []string) adblockrust.Engine {
	t.Helper()
	engine, err := adblockrust.NewEngine(rules, "")
	if err != nil {
		t.Fatal(err)
	}
	return engine
}

func newTestManagedEngine(engine adblockrust.Engine) *managedEngine {
	return newManagedEngine(engine)
}

func newTestCNameExchange(query string, target string) (*mDNS.Msg, *mDNS.Msg) {
	message := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{Id: 1},
		Question: []mDNS.Question{{
			Name:   query,
			Qtype:  mDNS.TypeA,
			Qclass: mDNS.ClassINET,
		}},
	}
	response := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Id:       message.Id,
			Response: true,
			Rcode:    mDNS.RcodeSuccess,
		},
		Question: message.Question,
		Answer: []mDNS.RR{
			&mDNS.CNAME{
				Hdr: mDNS.RR_Header{
					Name:   query,
					Rrtype: mDNS.TypeCNAME,
					Class:  mDNS.ClassINET,
					Ttl:    60,
				},
				Target: target,
			},
		},
	}
	return message, response
}

func newTestDNSExchange(query string, qtype uint16) (*mDNS.Msg, *mDNS.Msg) {
	message := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{Id: 1},
		Question: []mDNS.Question{{
			Name:   query,
			Qtype:  qtype,
			Qclass: mDNS.ClassINET,
		}},
	}
	upstreamRecord := &mDNS.TXT{
		Hdr: mDNS.RR_Header{
			Name:   query,
			Rrtype: mDNS.TypeTXT,
			Class:  mDNS.ClassINET,
			Ttl:    300,
		},
		Txt: []string{"upstream"},
	}
	response := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Id:       message.Id,
			Response: true,
			Rcode:    mDNS.RcodeSuccess,
		},
		Question: message.Question,
		Answer:   []mDNS.RR{upstreamRecord},
		Ns:       []mDNS.RR{mDNS.Copy(upstreamRecord)},
		Extra:    []mDNS.RR{mDNS.Copy(upstreamRecord)},
	}
	return message, response
}

func assertBlockedDNSResponse(t *testing.T, response *mDNS.Msg, qtype uint16) {
	t.Helper()
	if response.Rcode != mDNS.RcodeSuccess {
		t.Fatalf("unexpected response code: %s", mDNS.RcodeToString[response.Rcode])
	}
	if len(response.Ns) != 0 || len(response.Extra) != 0 {
		t.Fatalf("expected authority and additional records to be cleared: ns=%d extra=%d", len(response.Ns), len(response.Extra))
	}
	if qtype != mDNS.TypeA && qtype != mDNS.TypeAAAA {
		if len(response.Answer) != 0 {
			t.Fatalf("expected empty answer for query type %d, got %d records", qtype, len(response.Answer))
		}
		return
	}
	if len(response.Answer) != 1 {
		t.Fatalf("expected one sinkhole answer, got %d", len(response.Answer))
	}
	if response.Answer[0].Header().Ttl != blockedDNSResponseTTL {
		t.Fatalf("unexpected sinkhole TTL: %d", response.Answer[0].Header().Ttl)
	}
	switch record := response.Answer[0].(type) {
	case *mDNS.A:
		if qtype != mDNS.TypeA || !record.A.Equal(net.IPv4zero) {
			t.Fatalf("unexpected A sinkhole response: qtype=%d address=%s", qtype, record.A)
		}
	case *mDNS.AAAA:
		if qtype != mDNS.TypeAAAA || !record.AAAA.Equal(net.IPv6zero) {
			t.Fatalf("unexpected AAAA sinkhole response: qtype=%d address=%s", qtype, record.AAAA)
		}
	default:
		t.Fatalf("unexpected sinkhole record type: %T", response.Answer[0])
	}
}

type testPacketConn struct {
	closed bool
}

func newTestPacketConn() *testPacketConn {
	return &testPacketConn{}
}

func (c *testPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	return M.Socksaddr{}, io.ErrClosedPipe
}

func (c *testPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	return io.ErrClosedPipe
}

func (c *testPacketConn) Close() error {
	c.closed = true
	return nil
}

func (c *testPacketConn) LocalAddr() net.Addr {
	return nil
}

func (c *testPacketConn) SetDeadline(time.Time) error {
	return nil
}

func (c *testPacketConn) SetReadDeadline(time.Time) error {
	return nil
}

func (c *testPacketConn) SetWriteDeadline(time.Time) error {
	return nil
}

func TestPatchCSPPolicyAddsNonceSourcesWithoutRemovingDirectives(t *testing.T) {
	styleSource := "'nonce-style'"
	scriptSource := "'nonce-script'"
	policy := "default-src 'self'; img-src https://images.example; script-src 'self'"

	patched := patchCSPPolicy(policy, styleSource, scriptSource)

	if !strings.Contains(patched, "default-src 'self'") {
		t.Fatalf("default-src was not preserved: %s", patched)
	}
	if !strings.Contains(patched, "img-src https://images.example") {
		t.Fatalf("unrelated directive was not preserved: %s", patched)
	}
	if !strings.Contains(patched, "script-src 'self' "+scriptSource) {
		t.Fatalf("script nonce was not added minimally: %s", patched)
	}
	if !strings.Contains(patched, "style-src 'self' "+styleSource) {
		t.Fatalf("style-src fallback was not added: %s", patched)
	}
}

func TestPatchCSPPolicyDoesNotAddMissingDirectiveWithoutFallback(t *testing.T) {
	styleSource := "'nonce-style'"
	scriptSource := "'nonce-script'"
	policy := "object-src 'none'; base-uri 'self'; script-src 'nonce-page' 'strict-dynamic' 'unsafe-eval' 'unsafe-inline' https: http:; report-uri https://csp.withgoogle.com/csp/gws/cdt1"

	patched := patchCSPPolicy(policy, styleSource, scriptSource)

	if !strings.Contains(patched, "script-src 'nonce-page' 'strict-dynamic' 'unsafe-eval' 'unsafe-inline' https: http: "+scriptSource) {
		t.Fatalf("script nonce was not added to existing directive: %s", patched)
	}
	if strings.Contains(patched, "style-src") || strings.Contains(patched, styleSource) {
		t.Fatalf("style directive was added without an existing directive or default-src fallback: %s", patched)
	}
}

func TestPatchCSPPolicyDoesNotTightenUnsafeInlineScriptPolicy(t *testing.T) {
	scriptSource := "'nonce-script'"
	policy := "script-src 'unsafe-eval' 'self' 'unsafe-inline' https://www.google.com https://www.youtube.com; report-uri https://csp.withgoogle.com/csp/youtube_main/allowlist, base-uri 'self'; object-src 'none'; script-src 'report-sample' 'nonce-page' 'unsafe-inline' 'strict-dynamic' https: http: 'unsafe-eval'; report-uri https://csp.withgoogle.com/csp/youtube_main/strict"

	patched := patchCSPPolicy(policy, "", scriptSource)

	if strings.Contains(patched, "script-src 'unsafe-eval' 'self' 'unsafe-inline' https://www.google.com https://www.youtube.com "+scriptSource) {
		t.Fatalf("nonce was added to unsafe-inline allowlist policy: %s", patched)
	}
	if !strings.Contains(patched, "script-src 'report-sample' 'nonce-page' 'unsafe-inline' 'strict-dynamic' https: http: 'unsafe-eval' "+scriptSource) {
		t.Fatalf("nonce was not added to existing nonce policy: %s", patched)
	}
	if strings.Count(patched, scriptSource) != 1 {
		t.Fatalf("expected nonce once, got %d in %s", strings.Count(patched, scriptSource), patched)
	}
}

func TestPatchCSPHeadersPreservesReportOnly(t *testing.T) {
	header := http.Header{}
	header.Add("Content-Security-Policy", "default-src 'none'")
	header.Add("Content-Security-Policy-Report-Only", "default-src 'self'")

	patchCSPHeaders(header, "'nonce-style'", "'nonce-script'")

	if len(header.Values("Content-Security-Policy")) != 1 {
		t.Fatalf("unexpected enforcing CSP headers: %v", header.Values("Content-Security-Policy"))
	}
	if len(header.Values("Content-Security-Policy-Report-Only")) != 1 {
		t.Fatalf("unexpected report-only CSP headers: %v", header.Values("Content-Security-Policy-Report-Only"))
	}
	if !strings.Contains(header.Get("Content-Security-Policy-Report-Only"), "script-src") {
		t.Fatalf("report-only policy was not patched: %s", header.Get("Content-Security-Policy-Report-Only"))
	}
}

func TestPatchMetaCSP(t *testing.T) {
	content := []byte(`<html><head><meta http-equiv="Content-Security-Policy" content="default-src 'self'"></head></html>`)
	patched := string(patchMetaCSP(content, "'nonce-style'", "'nonce-script'"))

	if !strings.Contains(patched, "default-src &#39;self&#39;") {
		t.Fatalf("meta CSP default-src was not preserved/escaped: %s", patched)
	}
	if !strings.Contains(patched, "style-src") || !strings.Contains(patched, "script-src") {
		t.Fatalf("meta CSP nonce sources were not added: %s", patched)
	}
}

func TestInjectIntoHTML(t *testing.T) {
	content := []byte("<html><head><title>x</title></head><body></body></html>")
	rewritten := string(injectIntoHTML(content, []byte("<style>.ad{}</style>")))

	if !strings.Contains(rewritten, "<head><style>.ad{}</style><title>") {
		t.Fatalf("injection not placed after head open: %s", rewritten)
	}
}

func TestBuildBrowserInjectionAddsNonce(t *testing.T) {
	injection := buildBrowserInjection(".ad{}\n", "console.log(1);", "abc123")

	if !strings.Contains(injection, `<style data-`+runBlockID()+` nonce="abc123">`) {
		t.Fatalf("style nonce missing: %s", injection)
	}
	if !strings.Contains(injection, `<script data-`+runBlockID()+` nonce="abc123">`) {
		t.Fatalf("script nonce missing: %s", injection)
	}
}

func TestStreamingHTMLFilterInjectsAcrossChunks(t *testing.T) {
	body := newStreamingHTMLFilterReadCloser(
		newChunkedReadCloser("<ht", "ml><he", "ad><title>x</title></head></html>"),
		[]byte("<style>.ad{display:none}</style>"),
		"",
		"",
	)
	content, err := io.ReadAll(body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(content), "<head><style>.ad{display:none}</style><title>") {
		t.Fatalf("injection was not streamed at the head marker: %s", string(content))
	}
}

func TestStreamingHTMLFilterFallsBackAfterLookahead(t *testing.T) {
	sourceReader, sourceWriter := io.Pipe()
	body := newStreamingHTMLFilterReadCloser(
		sourceReader,
		[]byte("<style>.ad{display:none}</style>"),
		"",
		"",
	)
	defer body.Close()
	done := make(chan []byte, 1)
	errCh := make(chan error, 1)
	go func() {
		buffer := make([]byte, len("<style>.ad{display:none}</style>"))
		_, err := io.ReadFull(body, buffer)
		if err != nil {
			errCh <- err
			return
		}
		done <- buffer
	}()
	go func() {
		_, _ = sourceWriter.Write(bytes.Repeat([]byte("x"), streamingHTMLInjectionLookahead+1))
	}()

	select {
	case content := <-done:
		if string(content) != "<style>.ad{display:none}</style>" {
			t.Fatalf("unexpected streamed prefix: %q", string(content))
		}
	case err := <-errCh:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("streaming filter waited for EOF before emitting fallback injection")
	}
	_ = sourceWriter.Close()
}

func TestStreamingHTMLFilterPatchesMetaCSPAcrossChunks(t *testing.T) {
	body := newStreamingHTMLFilterReadCloser(
		newChunkedReadCloser(
			"<html><head><me",
			`ta http-equiv="Content-Security-Policy" content="default-src 'self'">`,
			"</head></html>",
		),
		[]byte("<style>.ad{display:none}</style>"),
		"'nonce-style'",
		"",
	)
	content, err := io.ReadAll(body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(content), "style-src") || !strings.Contains(string(content), "&#39;nonce-style&#39;") {
		t.Fatalf("meta CSP was not patched while streaming: %s", string(content))
	}
}

func TestRewriteHTMLResponseStreamsWhenGenericHideDisablesGenericScan(t *testing.T) {
	var service Service
	engine := &fakeAdblockEngine{
		cosmeticResult: adblockrust.CosmeticResources{
			HideSelectors: []string{".ad"},
			GenericHide:   true,
		},
	}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Length": {"42"}, "Content-Security-Policy": {"default-src 'self'"}},
		Body:          io.NopCloser(strings.NewReader("<html><head><title>x</title></head><body></body></html>")),
		ContentLength: 42,
	}

	if _, err := service.rewriteHTMLResponse(engine, "https://example.com/", response); err != nil {
		t.Fatal(err)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if engine.hiddenCallCount.Load() != 0 {
		t.Fatal("streaming path should not call HiddenClassIDSelectors")
	}
	if response.ContentLength != -1 || response.Header.Get("Content-Length") != "" {
		t.Fatalf("streamed rewritten response must not keep content length: length=%d header=%q", response.ContentLength, response.Header.Get("Content-Length"))
	}
	if !strings.Contains(string(content), ".ad{display:none!important;}") {
		t.Fatalf("missing streamed cosmetic injection: %s", string(content))
	}
	if !strings.Contains(response.Header.Get("Content-Security-Policy"), "style-src") {
		t.Fatalf("CSP header was not patched: %s", response.Header.Get("Content-Security-Policy"))
	}
}

func TestRewriteHTMLResponseInjectsDynamicCosmeticObserver(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"text/html"}},
		Body:          io.NopCloser(strings.NewReader("<html><head></head><body></body></html>")),
		ContentLength: int64(len("<html><head></head><body></body></html>")),
	}

	if _, err := service.rewriteHTMLResponse(service.engine.engine, "https://example.com/", response); err != nil {
		t.Fatal(err)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	text := string(content)
	if !strings.Contains(text, "DynamicObserver") || !strings.Contains(text, cosmeticSelectorEndpoint) {
		t.Fatalf("missing dynamic cosmetic observer: %s", text)
	}
	if strings.Contains(text, "self.__"+runBlockHash()+"Dynamic(") && len(service.cosmeticSessions) == 0 {
		t.Fatal("dynamic observer was injected without creating a cosmetic session")
	}
}

func TestRewriteHTMLResponseSkipsKnownOverLimitBodyWithoutReading(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{ReplaceMaxBody: 8},
	}, &fakeAdblockEngine{})
	body := &countingReadCloser{Reader: strings.NewReader("<html><head></head><body></body></html>")}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"text/html"}, "Content-Length": {"39"}},
		Body:          body,
		ContentLength: 39,
	}

	handled, err := service.rewriteHTMLResponse(service.engine.engine, "https://example.com/", response)
	if err != nil {
		t.Fatal(err)
	}
	if handled {
		t.Fatal("over-limit response should not be rewritten")
	}
	if body.reads.Load() != 0 {
		t.Fatalf("known over-limit response body was read %d times", body.reads.Load())
	}
	if response.ContentLength != 39 || response.Header.Get("Content-Length") != "39" {
		t.Fatalf("over-limit metadata changed: header=%v length=%d", response.Header, response.ContentLength)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "<html><head></head><body></body></html>" {
		t.Fatalf("over-limit body changed: %q", string(content))
	}
}

func TestRewriteHTMLResponseSkipsUnknownOverLimitBodyAndReplaysBufferedBytes(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{ReplaceMaxBody: 8},
	}, &fakeAdblockEngine{})
	const original = "<html><head></head><body></body></html>"
	body := &countingReadCloser{Reader: strings.NewReader(original)}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"text/html"}},
		Body:          body,
		ContentLength: -1,
	}

	handled, err := service.rewriteHTMLResponse(service.engine.engine, "https://example.com/", response)
	if err != nil {
		t.Fatal(err)
	}
	if handled {
		t.Fatal("unknown over-limit response should not be rewritten")
	}
	if len(service.cosmeticSessions) != 0 {
		t.Fatal("dynamic cosmetic session was created for skipped response")
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != original {
		t.Fatalf("unknown over-limit body was not replayed: %q", string(content))
	}
}

func TestRewriteHTMLResponseRestoresCompressedBodyWhenDecodedContentExceedsLimit(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{ReplaceMaxBody: 64},
	}, &fakeAdblockEngine{})
	plain := "<html><head></head><body>" + strings.Repeat("a", 128) + "</body></html>"
	var compressed bytes.Buffer
	gzipWriter := gzip.NewWriter(&compressed)
	if _, err := gzipWriter.Write([]byte(plain)); err != nil {
		t.Fatal(err)
	}
	if err := gzipWriter.Close(); err != nil {
		t.Fatal(err)
	}
	response := &http.Response{
		StatusCode: http.StatusOK,
		Header: http.Header{
			"Content-Type":     {"text/html"},
			"Content-Encoding": {"gzip"},
			"Content-Length":   {strconv.Itoa(compressed.Len())},
		},
		Body:          io.NopCloser(bytes.NewReader(compressed.Bytes())),
		ContentLength: int64(compressed.Len()),
	}

	handled, err := service.rewriteHTMLResponse(service.engine.engine, "https://example.com/", response)
	if err != nil {
		t.Fatal(err)
	}
	if handled {
		t.Fatal("decoded over-limit response should not be rewritten")
	}
	if response.Header.Get("Content-Encoding") != "gzip" || response.Header.Get("Content-Length") != strconv.Itoa(compressed.Len()) {
		t.Fatalf("compressed metadata was not restored: header=%v", response.Header)
	}
	gzipReader, err := gzip.NewReader(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	defer gzipReader.Close()
	content, err := io.ReadAll(gzipReader)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != plain {
		t.Fatalf("compressed body was not restored: %q", string(content))
	}
}

func TestDynamicCosmeticObserverDeduplicatesStylesAndRequests(t *testing.T) {
	script := singBoxAdblockRunner
	for _, want := range []string{
		"const receivedClasses = new Set();",
		"const receivedIds = new Set();",
		"const appliedStyleHashes = new Set();",
		"const styleHash = async css => {",
		`self.crypto.subtle.digest("SHA-256", data)`,
		"Array.from(pendingClasses).filter(className => !receivedClasses.has(className))",
		"Array.from(pendingIds).filter(id => !receivedIds.has(id))",
		"if (classes.length === 0 && ids.length === 0) { return; }",
		"for (const className of classes) { receivedClasses.add(className); }",
		"for (const id of ids) { receivedIds.add(id); }",
		"if (appliedStyleHashes.has(hash)) { return; }",
		"appliedStyleHashes.add(hash);",
		"ensureStyle().appendChild(document.createTextNode(result.css));",
	} {
		if !strings.Contains(script, want) {
			t.Fatalf("dynamic cosmetic observer script is missing %q", want)
		}
	}
}

func TestCosmeticSelectorEndpointReturnsDynamicCSS(t *testing.T) {
	engine := &fakeAdblockEngine{hiddenResult: []string{".generic-ad"}}
	service := newTestService(t.Context(), option.AdblockOptions{}, engine)
	token, err := service.newCosmeticSession(nil)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "https://example.com"+cosmeticSelectorEndpoint, strings.NewReader(`{"token":"`+token+`","classes":["generic-ad"],"ids":[]}`))
	writer := httptest.NewRecorder()
	hiddenCallsBefore := engine.hiddenCallCount.Load()

	if !service.handleCosmeticSelectorRequest(writer, request) {
		t.Fatal("cosmetic selector endpoint was not handled")
	}
	if writer.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d body=%s", writer.Code, writer.Body.String())
	}
	if !strings.Contains(writer.Body.String(), `.generic-ad{display:none!important;}`) {
		t.Fatalf("missing dynamic CSS response: %s", writer.Body.String())
	}
	if hiddenCallsAfter := engine.hiddenCallCount.Load(); hiddenCallsAfter != hiddenCallsBefore+1 {
		t.Fatalf("expected one HiddenClassIDSelectors call, before=%d after=%d", hiddenCallsBefore, hiddenCallsAfter)
	}
}

func TestCosmeticSelectorEndpointUsesParsedGlobalCosmeticRules(t *testing.T) {
	parsedFilter := parseFilterLines([]byte(`##.generic-ad`))
	engine := newTestAdblockEngine(t, parsedFilter.Rules)
	defer engine.Close()
	service := newTestService(t.Context(), option.AdblockOptions{}, engine)
	token, err := service.newCosmeticSession(nil)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "https://example.com"+cosmeticSelectorEndpoint, strings.NewReader(`{"token":"`+token+`","classes":["generic-ad"],"ids":[]}`))
	writer := httptest.NewRecorder()

	if !service.handleCosmeticSelectorRequest(writer, request) {
		t.Fatal("cosmetic selector endpoint was not handled")
	}
	if writer.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d body=%s", writer.Code, writer.Body.String())
	}
	if !strings.Contains(writer.Body.String(), `.generic-ad{display:none!important;}`) {
		t.Fatalf("missing parsed global dynamic CSS response: %s", writer.Body.String())
	}
}

func TestCosmeticSelectorEndpointRejectsInvalidToken(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{hiddenResult: []string{".generic-ad"}})
	request := httptest.NewRequest(http.MethodPost, "https://example.com"+cosmeticSelectorEndpoint, strings.NewReader(`{"token":"missing","classes":["generic-ad"],"ids":[]}`))
	writer := httptest.NewRecorder()

	if !service.handleCosmeticSelectorRequest(writer, request) {
		t.Fatal("cosmetic selector endpoint was not handled")
	}
	if writer.Code != http.StatusForbidden {
		t.Fatalf("unexpected status: %d body=%s", writer.Code, writer.Body.String())
	}
}

func TestCosmeticSelectorEndpointRejectsExpiredToken(t *testing.T) {
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{hiddenResult: []string{".generic-ad"}})
	service.cosmeticSessions = map[string]cosmeticSession{
		"expired": {expires: time.Now().Add(-time.Second)},
	}
	request := httptest.NewRequest(http.MethodPost, "https://example.com"+cosmeticSelectorEndpoint, strings.NewReader(`{"token":"expired","classes":["generic-ad"],"ids":[]}`))
	writer := httptest.NewRecorder()

	if !service.handleCosmeticSelectorRequest(writer, request) {
		t.Fatal("cosmetic selector endpoint was not handled")
	}
	if writer.Code != http.StatusForbidden {
		t.Fatalf("unexpected status: %d body=%s", writer.Code, writer.Body.String())
	}
}

func TestNewForwardedHTTPRequestPreservesAcceptEncoding(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	request.Header.Set("Accept-Encoding", "gzip, deflate, br, zstd")
	requestContext := &adblockRequestContext{
		ctx:         t.Context(),
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/"),
		requestType: "document",
	}

	outRequest := newForwardedHTTPRequest(requestContext)
	if got := outRequest.Header.Get("Accept-Encoding"); got != "gzip, deflate, br, zstd" {
		t.Fatalf("Accept-Encoding = %q, want browser value preserved", got)
	}
}

func TestNormalizeFakeIPRequestURLUsesRecoveredDomain(t *testing.T) {
	tests := []struct {
		name     string
		rawURL   string
		host     string
		metadata adapter.InboundContext
		wantURL  string
		wantHost string
		changed  bool
	}{
		{
			name:   "fake ip host",
			rawURL: "https://198.18.0.94/generate_204",
			metadata: adapter.InboundContext{
				FakeIP:            true,
				Destination:       M.ParseSocksaddrHostPort("google.com", 443),
				OriginDestination: M.ParseSocksaddrHostPort("198.18.0.94", 443),
			},
			wantURL:  "https://google.com/generate_204",
			changed:  true,
			wantHost: "google.com",
		},
		{
			name:   "origin form fake host",
			rawURL: "/generate_204",
			host:   "198.18.0.94",
			metadata: adapter.InboundContext{
				FakeIP:            true,
				Destination:       M.ParseSocksaddrHostPort("www.gstatic.com", 443),
				OriginDestination: M.ParseSocksaddrHostPort("198.18.0.94", 443),
			},
			wantURL:  "https://www.gstatic.com/generate_204",
			changed:  true,
			wantHost: "www.gstatic.com",
		},
		{
			name:   "fake ip host preserves explicit port",
			rawURL: "https://198.18.0.94:8443/path",
			metadata: adapter.InboundContext{
				FakeIP:            true,
				Domain:            "example.com",
				Destination:       M.ParseSocksaddrHostPort("example.com", 443),
				OriginDestination: M.ParseSocksaddrHostPort("198.18.0.94", 443),
			},
			wantURL:  "https://example.com:8443/path",
			changed:  true,
			wantHost: "example.com:8443",
		},
		{
			name:   "existing real host is left alone",
			rawURL: "https://google.com/search",
			metadata: adapter.InboundContext{
				FakeIP:            true,
				Destination:       M.ParseSocksaddrHostPort("google.com", 443),
				OriginDestination: M.ParseSocksaddrHostPort("198.18.0.94", 443),
			},
			wantURL:  "https://google.com/search",
			wantHost: "google.com",
		},
		{
			name:   "direct ip traffic is left alone",
			rawURL: "https://203.0.113.10/path",
			metadata: adapter.InboundContext{
				Destination: M.ParseSocksaddrHostPort("203.0.113.10", 443),
			},
			wantURL:  "https://203.0.113.10/path",
			wantHost: "203.0.113.10",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodGet, test.rawURL, nil)
			if test.host != "" {
				request.Host = test.host
			}
			requestURL := httpRequestURL("https", request)
			if got := normalizeFakeIPRequestURL(requestURL, request, test.metadata); got != test.changed {
				t.Fatalf("normalizeFakeIPRequestURL changed = %v, want %v", got, test.changed)
			}
			if got := requestURL.String(); got != test.wantURL {
				t.Fatalf("request URL = %q, want %q", got, test.wantURL)
			}
			requestContext := &adblockRequestContext{
				ctx:         t.Context(),
				request:     request,
				requestURL:  requestURL,
				requestType: "document",
			}
			outRequest := newForwardedHTTPRequest(requestContext)
			if got := outRequest.Host; got != test.wantHost {
				t.Fatalf("forwarded host = %q, want %q", got, test.wantHost)
			}
		})
	}
}

func TestRewriteHTMLResponseDecodesZstd(t *testing.T) {
	var service Service
	engine := &fakeAdblockEngine{
		cosmeticResult: adblockrust.CosmeticResources{
			HideSelectors: []string{".ad"},
			GenericHide:   true,
		},
	}
	var compressed bytes.Buffer
	writer, err := zstd.NewWriter(&compressed)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = writer.Write([]byte("<html><head></head><body></body></html>")); err != nil {
		t.Fatal(err)
	}
	if err = writer.Close(); err != nil {
		t.Fatal(err)
	}
	response := &http.Response{
		StatusCode: http.StatusOK,
		Header: http.Header{
			"Content-Encoding": {"zstd"},
			"Content-Length":   {strconv.Itoa(compressed.Len())},
			"Content-Type":     {"text/html"},
		},
		Body:          io.NopCloser(bytes.NewReader(compressed.Bytes())),
		ContentLength: int64(compressed.Len()),
	}

	if _, err = service.rewriteHTMLResponse(engine, "https://example.com/", response); err != nil {
		t.Fatal(err)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.Header.Get("Content-Encoding") != "" || response.Header.Get("Content-Length") != "" || response.ContentLength != -1 {
		t.Fatalf("rewritten zstd response kept stale encoding/length headers: header=%v length=%d", response.Header, response.ContentLength)
	}
	if !strings.Contains(string(content), ".ad{display:none!important;}") {
		t.Fatalf("missing cosmetic injection after zstd decode: %s", string(content))
	}
}

func TestCompanionRulesApplyResponseMutations(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`||example.com/api$xhr,replace=/"ad"/"ok"/`,
		`||example.com/api$xhr,permissions=interest-cohort=()`,
		`||example.com/api$xhr,header=X-Tracker:/yes/`,
		`||example.com/api$xhr,cookie=session`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.companion = parsed.Companion
	request := httptest.NewRequest(http.MethodGet, "https://example.com/api", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/api"),
		requestType: "xmlhttprequest",
	}
	response := &http.Response{
		StatusCode: http.StatusOK,
		Header: http.Header{
			"Content-Type": {"application/json"},
			"X-Tracker":    {"yes"},
			"Set-Cookie":   {"session=abc; Path=/", "keep=1; Path=/"},
		},
		Body:          io.NopCloser(strings.NewReader(`{"ad":true}`)),
		ContentLength: int64(len(`{"ad":true}`)),
	}

	if err := service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != `{"ok":true}` {
		t.Fatalf("unexpected replaced body: %s", string(body))
	}
	if response.Header.Get("X-Tracker") != "" {
		t.Fatalf("header rule did not remove X-Tracker: %v", response.Header)
	}
	if got := response.Header.Values("Set-Cookie"); !slices.Equal(got, []string{"keep=1; Path=/"}) {
		t.Fatalf("cookie rule result = %#v", got)
	}
	if response.Header.Get("Permissions-Policy") != "" {
		t.Fatalf("permissions should not apply to xhr: %v", response.Header)
	}
}

func TestCompanionReplaceSkipsOverLimitResponseWithoutConsumingBody(t *testing.T) {
	parsed := parseFilterLines([]byte(`||example.com/archive.zip$document,replace=/zip/bad/`))
	service := newTestService(t.Context(), option.AdblockOptions{
		Filtering: option.AdblockFiltering{ReplaceMaxBody: 8},
	}, &fakeAdblockEngine{})
	service.companion = parsed.Companion
	request := httptest.NewRequest(http.MethodGet, "https://example.com/archive.zip", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/archive.zip"),
		requestType: "document",
	}
	body := &countingReadCloser{Reader: strings.NewReader("zip payload")}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"application/zip"}, "Content-Length": {"11"}},
		Body:          body,
		ContentLength: 11,
	}

	if err := service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		t.Fatal(err)
	}
	if body.reads.Load() != 0 {
		t.Fatalf("over-limit response body was read %d times", body.reads.Load())
	}
	if response.ContentLength != 11 || response.Header.Get("Content-Length") != "11" {
		t.Fatalf("over-limit response metadata changed: header=%v length=%d", response.Header, response.ContentLength)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "zip payload" {
		t.Fatalf("over-limit response body changed: %q", string(content))
	}
}

func TestCompanionReplaceSkipsNotModifiedResponseWithoutConsumingBody(t *testing.T) {
	parsed := parseFilterLines([]byte(`||github.com/assets-cdn/worker/*$script,replace=/worker/bad/`))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.companion = parsed.Companion
	request := httptest.NewRequest(http.MethodGet, "https://github.com/assets-cdn/worker/service-worker.js", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://github.com/assets-cdn/worker/service-worker.js"),
		requestType: "script",
	}
	body := &countingReadCloser{Reader: strings.NewReader("")}
	response := &http.Response{
		StatusCode:    http.StatusNotModified,
		Header:        http.Header{"Content-Encoding": {"gzip"}},
		Body:          body,
		ContentLength: -1,
	}

	if err := service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		t.Fatal(err)
	}
	if body.reads.Load() != 0 {
		t.Fatalf("304 response body was read %d times", body.reads.Load())
	}
	if response.Header.Get("Content-Encoding") != "gzip" || response.ContentLength != -1 {
		t.Fatalf("304 response metadata changed: header=%v length=%d", response.Header, response.ContentLength)
	}
}

func TestAdvancedRulesRemoveParamsAndRecheck(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`*$removeparam=/^utm_/`,
		`||example.com/api$method=get,to=example.com`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced
	request := httptest.NewRequest(http.MethodGet, "https://example.com/api?utm_source=x&keep=1", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/api?utm_source=x&keep=1"),
		requestType: "xmlhttprequest",
	}

	changed, redirected := service.advanced.mutateRequest(requestContext, false)
	if redirected || !changed {
		t.Fatalf("expected URL mutation, changed=%v redirected=%v", changed, redirected)
	}
	if got := requestContext.requestURL.String(); got != "https://example.com/api?keep=1" {
		t.Fatalf("unexpected mutated URL: %s", got)
	}
	result := service.advanced.applyCheck(requestContext, adblockrust.CheckResult{})
	if !result.Matched || result.Filter == "" {
		t.Fatalf("expected local method/to rule to block: %#v", result)
	}
}

func TestAdvancedRulesURLSkipRedirectsDocuments(t *testing.T) {
	parsed := parseFilterLines([]byte(`||jump.example^$document,urlskip=?target -uricomponent +https`))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced
	writer := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "https://jump.example/out?target=dest.example%2Fpage", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		writer:      writer,
		request:     request,
		requestURL:  mustParseURL(t, "https://jump.example/out?target=dest.example%2Fpage"),
		requestType: "document",
	}

	_, redirected := service.advanced.mutateRequest(requestContext, false)
	if !redirected {
		t.Fatal("expected document URL skip to redirect")
	}
	if writer.Code != http.StatusFound || writer.Header().Get("Location") != "https://dest.example/page" {
		t.Fatalf("unexpected redirect: code=%d headers=%v", writer.Code, writer.Header())
	}
}

func TestAdvancedRulesStrictParty(t *testing.T) {
	parsed := parseFilterLines([]byte(`||cdn.example.com^$strict3p`))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced
	request := httptest.NewRequest(http.MethodGet, "https://cdn.example.com/script.js", nil)
	request.Header.Set("Referer", "https://www.example.com/")
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://cdn.example.com/script.js"),
		requestType: "script",
	}

	result := service.advanced.applyCheck(requestContext, adblockrust.CheckResult{})
	if !result.Matched {
		t.Fatalf("expected strict3p to match same registrable-domain subdomain: %#v", result)
	}
}

func TestCompanionReplaceExceptionSuppressesReplacement(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`||example.com/api$xhr,replace=/"ad"/"ok"/`,
		`@@||example.com/api$xhr,replace=/"ad"/"ok"/`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.companion = parsed.Companion
	request := httptest.NewRequest(http.MethodGet, "https://example.com/api", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/api"),
		requestType: "xmlhttprequest",
	}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"application/json"}},
		Body:          io.NopCloser(strings.NewReader(`{"ad":true}`)),
		ContentLength: int64(len(`{"ad":true}`)),
	}

	if err := service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != `{"ad":true}` {
		t.Fatalf("exception did not suppress replacement: %s", string(body))
	}
}

func TestHTMLFilterRemovesMatchingNodes(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`example.com##^script:has-text(/adPlacements/)`,
		`example.com##^.ad-slot[data-kind="banner"]`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.htmlFilters = parsed.HTML
	request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/"),
		requestType: "document",
	}
	response := &http.Response{
		StatusCode: http.StatusOK,
		Header:     http.Header{"Content-Type": {"text/html"}},
		Body: io.NopCloser(strings.NewReader(`<!doctype html><html><head><script>window.adPlacements = [];</script><script>keep()</script></head>` +
			`<body><div class="ad-slot" data-kind="banner">ad</div><main>content</main></body></html>`)),
		ContentLength: -1,
	}

	if err := service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	text := string(body)
	if strings.Contains(text, "adPlacements") || strings.Contains(text, "ad-slot") {
		t.Fatalf("HTML filter did not remove matched nodes: %s", text)
	}
	if !strings.Contains(text, "keep()") || !strings.Contains(text, "<main>content</main>") {
		t.Fatalf("HTML filter removed unrelated content: %s", text)
	}
	if response.Header.Get("Content-Length") != "" {
		t.Fatalf("rewritten HTML kept stale content length: %v", response.Header)
	}
}

func TestHTMLFilterExceptionSuppressesSelector(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`example.com##^script:has-text(/adPlacements/)`,
		`example.com#@#^script:has-text(/adPlacements/)`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.htmlFilters = parsed.HTML
	request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/"),
		requestType: "document",
	}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"text/html"}},
		Body:          io.NopCloser(strings.NewReader(`<html><head><script>window.adPlacements = [];</script></head></html>`)),
		ContentLength: -1,
	}

	if err := service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(body), "adPlacements") {
		t.Fatalf("HTML exception did not suppress selector: %s", string(body))
	}
}

func TestHTMLFilterResponseHeaderRemovesAllowedHeaders(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`example.com##^responseheader(refresh)`,
		`example.com##^responseheader(set-cookie)`,
		`example.com##^responseheader(content-security-policy)`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.htmlFilters = parsed.HTML
	request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/"),
		requestType: "document",
	}
	response := &http.Response{
		StatusCode: http.StatusOK,
		Header: http.Header{
			"Refresh":                 {"1; url=https://ads.example/"},
			"Set-Cookie":              {"track=1"},
			"Content-Security-Policy": {"default-src 'self'"},
		},
	}

	service.htmlFilters.applyHeaders(requestContext, response)
	if response.Header.Get("Refresh") != "" || response.Header.Get("Set-Cookie") != "" {
		t.Fatalf("expected allowed response headers to be removed: %v", response.Header)
	}
	if response.Header.Get("Content-Security-Policy") == "" {
		t.Fatalf("CSP must not be removed by responseheader rule: %v", response.Header)
	}
}

func TestHTMLFilterProceduralSelectors(t *testing.T) {
	tests := []struct {
		name     string
		selector string
		body     string
		removed  string
		kept     string
	}{
		{
			name:     "has descendant",
			selector: `#phf #a1 .fail:has(b)`,
			body:     `<div id="phf"><div id="a1"><div class="pass"><div class="fail"><a><b></b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "has child chain",
			selector: `#phf #a2 .fail:has(> a > b)`,
			body:     `<div id="phf"><div id="a2"><div class="pass"><div class="fail"><a><b></b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "not has",
			selector: `#phf #a3 .fail:not(:has(c))`,
			body:     `<div id="phf"><div id="a3"><div class="pass"><div class="fail"><a><b></b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "has text regex",
			selector: `#phf #a5 .fail:has-text(/NEEDLE/i)`,
			body:     `<div id="phf"><div id="a5"><div class="pass"><div class="fail"><a><b>I am a needle!!!</b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "relative sibling inside has",
			selector: `#phf #a13 .fail:has(~ a:has(b))`,
			body:     `<div id="phf"><div id="a13"><div class="pass"><div class="fail"></div><a><b></b></a></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "upward count",
			selector: `#phf #a14 b:upward(2)`,
			body:     `<div id="phf"><div id="a14"><div class="pass"><div class="fail"><a><b></b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "upward selector",
			selector: `#phf #a15 b:upward(.fail)`,
			body:     `<div id="phf"><div id="a15"><div class="pass"><div class="fail"><a><b></b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "xpath",
			selector: `#phf #a8:xpath(.//b/../..)`,
			body:     `<div id="phf"><div id="a8" class="tile"><div class="pass"><div class="fail"><a><b></b></a></div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
		{
			name:     "min text length",
			selector: `#phf #a9 .fail:min-text-length(30)`,
			body:     `<div id="phf"><div id="a9"><div class="pass"><div class="fail">Lorem ipsum dolor sit amet, consectetur adipiscing elit.</div></div></div></div>`,
			removed:  `class="fail"`,
			kept:     `class="pass"`,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			selector, ok := parseHTMLSelector(test.selector)
			if !ok {
				t.Fatalf("failed to parse selector %q", test.selector)
			}
			rewritten, changed, err := applyHTMLSelectors([]byte(`<!doctype html><html><body>`+test.body+`</body></html>`), []htmlSelector{selector})
			if err != nil {
				t.Fatal(err)
			}
			if !changed {
				t.Fatal("expected HTML filter to change document")
			}
			text := string(rewritten)
			if strings.Contains(text, test.removed) {
				t.Fatalf("HTML filter did not remove matched node: %s", text)
			}
			if !strings.Contains(text, test.kept) {
				t.Fatalf("HTML filter removed unrelated content: %s", text)
			}
		})
	}
}

func TestShouldSkipResponseFilteringForCloudflareChallenges(t *testing.T) {
	tests := []struct {
		name string
		url  string
		want bool
	}{
		{
			name: "challenge host",
			url:  "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/turnstile/f",
			want: true,
		},
		{
			name: "challenge subdomain",
			url:  "https://brunhild.challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/peek/token",
			want: true,
		},
		{
			name: "site challenge path",
			url:  "https://example.com/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1",
			want: true,
		},
		{
			name: "ordinary cloudflare cdn cgi",
			url:  "https://example.com/cdn-cgi/trace",
			want: false,
		},
		{
			name: "ordinary cloudflare domain",
			url:  "https://cloudflare.com/",
			want: false,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			requestContext := &adblockRequestContext{requestURL: mustParseURL(t, test.url)}
			if got := shouldSkipResponseFiltering(requestContext); got != test.want {
				t.Fatalf("shouldSkipResponseFiltering(%s) = %v, want %v", test.url, got, test.want)
			}
		})
	}
}

func TestRewriteHTMLResponseBuffersWhenGenericScanNeeded(t *testing.T) {
	var service Service
	engine := &fakeAdblockEngine{
		cosmeticResult: adblockrust.CosmeticResources{
			HideSelectors: []string{".specific"},
		},
		hiddenResult: []string{".generic"},
	}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{},
		Body:          io.NopCloser(strings.NewReader(`<html><head></head><body class="generic"></body></html>`)),
		ContentLength: int64(len(`<html><head></head><body class="generic"></body></html>`)),
	}

	if _, err := service.rewriteHTMLResponse(engine, "https://example.com/", response); err != nil {
		t.Fatal(err)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if engine.hiddenCallCount.Load() != 1 {
		t.Fatalf("expected buffered generic selector scan, got %d calls", engine.hiddenCallCount.Load())
	}
	if !strings.Contains(string(content), ".specific{display:none!important;}") || !strings.Contains(string(content), ".generic{display:none!important;}") {
		t.Fatalf("buffered rewrite did not include specific and generic selectors: %s", string(content))
	}
}

func TestRewriteHTMLResponseBuffersForGenericSelectorsWithoutSpecificCosmetics(t *testing.T) {
	var service Service
	engine := &fakeAdblockEngine{
		cosmeticResult: adblockrust.CosmeticResources{},
		hiddenResult:   []string{".generic"},
	}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{},
		Body:          io.NopCloser(strings.NewReader(`<html><head></head><body><div class="generic"></div></body></html>`)),
		ContentLength: int64(len(`<html><head></head><body><div class="generic"></div></body></html>`)),
	}

	if _, err := service.rewriteHTMLResponse(engine, "https://example.com/", response); err != nil {
		t.Fatal(err)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if engine.hiddenCallCount.Load() != 1 {
		t.Fatalf("expected generic selector scan, got %d calls", engine.hiddenCallCount.Load())
	}
	if !strings.Contains(string(content), ".generic{display:none!important;}") {
		t.Fatalf("buffered rewrite did not include generic selector: %s", string(content))
	}
}

func TestRewriteHTMLResponseSkipsGenericScanWithoutClassOrID(t *testing.T) {
	var service Service
	engine := &fakeAdblockEngine{
		cosmeticResult: adblockrust.CosmeticResources{
			HideSelectors: []string{".specific"},
		},
		hiddenResult: []string{".generic"},
	}
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{},
		Body:          io.NopCloser(strings.NewReader(`<html><head></head><body><main></main></body></html>`)),
		ContentLength: int64(len(`<html><head></head><body><main></main></body></html>`)),
	}

	if _, err := service.rewriteHTMLResponse(engine, "https://example.com/", response); err != nil {
		t.Fatal(err)
	}
	if engine.hiddenCallCount.Load() != 0 {
		t.Fatalf("expected generic selector scan to be skipped, got %d calls", engine.hiddenCallCount.Load())
	}
}

func TestDecodeRedirectResource(t *testing.T) {
	mediaType, content, err := decodeRedirectResource("data:application/javascript;base64,Y29uc29sZS5sb2coMSk7")
	if err != nil {
		t.Fatal(err)
	}
	if mediaType != "application/javascript" {
		t.Fatalf("unexpected media type: %q", mediaType)
	}
	if string(content) != "console.log(1);" {
		t.Fatalf("unexpected content: %q", string(content))
	}
}

func TestCosmeticStyleEmitsIndependentRules(t *testing.T) {
	style := cosmeticStyle([]string{".ad", ".bad:matches-media((min-width: 1px))"})

	if !strings.Contains(style, ".ad{display:none!important;}") {
		t.Fatalf("expected first selector rule: %s", style)
	}
	if strings.Contains(style, ",\n") {
		t.Fatalf("expected selectors not to be grouped: %s", style)
	}
}

func TestCosmeticScriptProvidesUBlockScriptletGlobals(t *testing.T) {
	script, err := cosmeticScript(adblockrust.CosmeticResources{
		InjectedScript: "scriptletGlobals.safeSelf = {};",
	})
	if err != nil {
		t.Fatal(err)
	}

	globalsIndex := strings.Index(script, "const scriptletGlobals=")
	if globalsIndex < 0 {
		t.Fatalf("missing scriptletGlobals prelude: %s", script)
	}
	injectedIndex := strings.Index(script, "scriptletGlobals.safeSelf = {};")
	if injectedIndex < 0 {
		t.Fatalf("missing injected script: %s", script)
	}
	if globalsIndex > injectedIndex {
		t.Fatalf("scriptletGlobals prelude must come before injected script: %s", script)
	}
	if !strings.Contains(script, "warOrigin:self.location.origin+") || !strings.Contains(script, "warSecret:") {
		t.Fatalf("scriptletGlobals prelude is missing uBlock web-accessible-resource fields: %s", script)
	}
	if !strings.Contains(script, "canDebug") {
		t.Fatalf("scriptletGlobals prelude is missing supported uBlock fields: %s", script)
	}
}

func TestWebAccessibleResourceHandler(t *testing.T) {
	secret, err := webAccessibleResourceSecret()
	if err != nil {
		t.Fatal(err)
	}
	service := &Service{}

	t.Run("valid", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodGet, "https://example.com"+webAccessibleResourceEndpoint+"/noop.js?secret="+url.QueryEscape(secret), nil)
		writer := httptest.NewRecorder()
		if !service.handleWebAccessibleResourceRequest(writer, request) {
			t.Fatal("WAR request was not handled")
		}
		if writer.Code != http.StatusOK || writer.Body.String() == "" {
			t.Fatalf("unexpected WAR response: status=%d body=%q", writer.Code, writer.Body.String())
		}
		if writer.Header().Get("Content-Type") != "application/javascript" {
			t.Fatalf("unexpected WAR content type: %s", writer.Header().Get("Content-Type"))
		}
	})

	t.Run("head", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodHead, "https://example.com"+webAccessibleResourceEndpoint+"/1x1.gif?secret="+url.QueryEscape(secret), nil)
		writer := httptest.NewRecorder()
		if !service.handleWebAccessibleResourceRequest(writer, request) {
			t.Fatal("WAR request was not handled")
		}
		if writer.Code != http.StatusOK || writer.Body.Len() != 0 || writer.Header().Get("Content-Length") == "" {
			t.Fatalf("unexpected WAR HEAD response: status=%d length=%q body=%d", writer.Code, writer.Header().Get("Content-Length"), writer.Body.Len())
		}
	})

	for _, test := range []struct {
		name   string
		path   string
		method string
		status int
	}{
		{name: "bad secret", path: "/noop.js?secret=wrong", method: http.MethodGet, status: http.StatusNotFound},
		{name: "unknown", path: "/missing.js?secret=" + url.QueryEscape(secret), method: http.MethodGet, status: http.StatusNotFound},
		{name: "traversal", path: "/%2e%2e%2fnoop.js?secret=" + url.QueryEscape(secret), method: http.MethodGet, status: http.StatusNotFound},
		{name: "method", path: "/noop.js?secret=" + url.QueryEscape(secret), method: http.MethodPost, status: http.StatusMethodNotAllowed},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(test.method, "https://example.com"+webAccessibleResourceEndpoint+test.path, nil)
			writer := httptest.NewRecorder()
			if !service.handleWebAccessibleResourceRequest(writer, request) {
				t.Fatal("WAR request was not handled")
			}
			if writer.Code != test.status {
				t.Fatalf("unexpected status: want=%d got=%d", test.status, writer.Code)
			}
		})
	}
}

func TestParseFilterMetadataMatchesFullParser(t *testing.T) {
	content := []byte(strings.Join([]string{
		"! Title: Metadata parity",
		"! Description: lightweight parser",
		"! Last Modified: 2026-07-11",
		"! Expires: 4 hours",
		"! License: https://example.com/license",
		"! Homepage: https://example.com",
		"! Forums: https://example.com/issues",
		"!#if env_mobile",
		"||mobile.example^",
		"!#else",
		"||desktop.example^",
		"!#endif",
		"example.org##.cosmetic",
		"example.org##^div",
		"||params.example^$removeparam=utm_source",
		"||network.example^",
	}, "\n"))
	environment := &option.AdblockEnvironment{Mobile: true}

	full := parseFilterLinesWithEnvironment(content, environment)
	metadata := parseFilterMetadataWithEnvironment(content, environment)
	expectedMetadata := full.AdblockFilterMetadata
	expectedMetadata.RuleCount = len(full.Rules)

	if metadata.AdblockFilterMetadata != expectedMetadata {
		t.Fatalf("metadata mismatch:\nlightweight: %#v\nfull: %#v", metadata.AdblockFilterMetadata, expectedMetadata)
	}
	if metadata.RuleCount != len(full.Rules) {
		t.Fatalf("rule count mismatch: lightweight=%d full=%d", metadata.RuleCount, len(full.Rules))
	}
	if len(metadata.Rules) != 0 || !metadata.Companion.empty() || !metadata.HTML.empty() || !metadata.Advanced.empty() {
		t.Fatal("metadata parser retained filter rule collections")
	}
}

func TestParseFilterMetadataDoesNotRetainLargeRuleList(t *testing.T) {
	const ruleCount = 20_000
	var content strings.Builder
	content.WriteString("! Title: Large list\n")
	for index := range ruleCount {
		content.WriteString("||ads")
		content.WriteString(strconv.Itoa(index))
		content.WriteString(".example^\n")
	}

	metadata := parseFilterMetadataWithEnvironment([]byte(content.String()), nil)
	if metadata.Title != "Large list" {
		t.Fatalf("unexpected title: %q", metadata.Title)
	}
	if metadata.RuleCount != ruleCount {
		t.Fatalf("unexpected rule count: %d", metadata.RuleCount)
	}
	if len(metadata.Rules) != 0 {
		t.Fatalf("metadata parser retained %d rules", len(metadata.Rules))
	}
}

func TestGetFilterMetadataWaitsForEngineRebuild(t *testing.T) {
	const listURL = "https://filters.example/metadata.txt"
	service := newTestService(t.Context(), option.AdblockOptions{}, nil)
	service.filterLists = []filterList{{
		option: mustFilterListOption(t, listURL),
		metadata: adapter.AdblockFilterMetadata{
			URI:   listURL,
			Title: "Existing metadata",
		},
	}}

	service.rebuildMu.Lock()
	done := make(chan adapter.AdblockFilterMetadata, 1)
	go func() {
		metadata, _ := service.GetFilterMetadata(listURL)
		done <- metadata
	}()
	select {
	case <-done:
		t.Fatal("metadata read overlapped an engine rebuild")
	case <-time.After(50 * time.Millisecond):
	}
	service.rebuildMu.Unlock()

	select {
	case metadata := <-done:
		if metadata.Title != "Existing metadata" {
			t.Fatalf("unexpected metadata: %#v", metadata)
		}
	case <-time.After(time.Second):
		t.Fatal("metadata read did not resume after engine rebuild")
	}
}

func TestReloadEngineChecksCancellationAfterRebuildLock(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	service := newTestService(ctx, option.AdblockOptions{}, nil)
	service.rebuildMu.Lock()
	done := make(chan error, 1)
	go func() {
		done <- service.reloadEngineFromStore(ctx)
	}()
	cancel()
	service.rebuildMu.Unlock()

	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected context cancellation, got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("reload did not stop after cancellation")
	}
}

func TestParseFilterLinesMetadataExactSpacing(t *testing.T) {
	for _, content := range []string{
		"! Last  Modified: 2026-06-18",
		"! Last Modified:2026-06-18",
	} {
		parsedFilter := parseFilterLines([]byte(content))
		if parsedFilter.LastModified != "" {
			t.Fatalf("unexpected last modified for %q: %q", content, parsedFilter.LastModified)
		}
	}

	parsedFilter := parseFilterLines([]byte("! Last Modified: 2026-06-18"))
	if parsedFilter.LastModified != "2026-06-18" {
		t.Fatalf("unexpected last modified: %q", parsedFilter.LastModified)
	}

	parsedFilter = parseFilterLines([]byte("!Title: zero spaces after marker"))
	if parsedFilter.Title != "zero spaces after marker" {
		t.Fatalf("unexpected title: %q", parsedFilter.Title)
	}
}

func TestParseFilterExpires(t *testing.T) {
	tests := []struct {
		name     string
		value    string
		expected time.Duration
	}{
		{
			name:     "hours",
			value:    "8 hours",
			expected: 8 * time.Hour,
		},
		{
			name:     "decimal days",
			value:    "1.5 days",
			expected: 36 * time.Hour,
		},
		{
			name:     "abbreviated minutes",
			value:    "30 min",
			expected: 30 * time.Minute,
		},
		{
			name:     "weeks without space",
			value:    "2weeks",
			expected: 14 * 24 * time.Hour,
		},
		{
			name:     "invalid unit",
			value:    "8 lightyears",
			expected: 0,
		},
		{
			name:     "zero",
			value:    "0 hours",
			expected: 0,
		},
		{
			name:     "missing amount",
			value:    "hours",
			expected: 0,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := parseFilterExpires(test.value)
			if actual != test.expected {
				t.Fatalf("expected %s, got %s", test.expected, actual)
			}
		})
	}
}

type chunkedReadCloser struct {
	chunks []string
}

func newChunkedReadCloser(chunks ...string) io.ReadCloser {
	return &chunkedReadCloser{chunks: chunks}
}

func (r *chunkedReadCloser) Read(p []byte) (int, error) {
	if len(r.chunks) == 0 {
		return 0, io.EOF
	}
	chunk := r.chunks[0]
	r.chunks = r.chunks[1:]
	return copy(p, chunk), nil
}

func (r *chunkedReadCloser) Close() error {
	return nil
}

func newTestHTTPService() *Service {
	return &Service{
		ctx: context.Background(),
		httpServers: NewTypedPool[*http.Server]().SetConstructor(func() *http.Server {
			return &http.Server{}
		}),
		http2Servers: NewTypedPool[*http.Server]().SetConstructor(func() *http.Server {
			return &http.Server{}
		}),
	}
}

func TestFilterListShouldUpdate(t *testing.T) {
	tests := []struct {
		name     string
		list     filterList
		expected bool
	}{
		{
			name:     "missing content",
			list:     filterList{lastUpdated: time.Now(), interval: time.Hour},
			expected: true,
		},
		{
			name:     "missing timestamp",
			list:     filterList{content: []byte("||example.com^"), interval: time.Hour},
			expected: true,
		},
		{
			name: "fresh cached content",
			list: filterList{
				content:     []byte("||example.com^"),
				lastUpdated: time.Now().Add(-30 * time.Minute),
				interval:    time.Hour,
			},
			expected: false,
		},
		{
			name: "stale cached content",
			list: filterList{
				content:     []byte("||example.com^"),
				lastUpdated: time.Now().Add(-2 * time.Hour),
				interval:    time.Hour,
			},
			expected: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if actual := test.list.shouldUpdate(); actual != test.expected {
				t.Fatalf("expected %t, got %t", test.expected, actual)
			}
		})
	}
}

func TestFilterListApplyParsedInterval(t *testing.T) {
	list := filterList{
		content:     []byte("! Expires: 8 hours\n||example.com^"),
		lastUpdated: time.Now().Add(-30 * time.Minute),
	}
	parsedFilter := parseFilterLines(list.content)
	list.applyParsedInterval(parsedFilter)

	if list.interval != 8*time.Hour {
		t.Fatalf("expected interval 8h, got %s", list.interval)
	}
	if list.shouldUpdate() {
		t.Fatal("expected cached list to be fresh")
	}
}

func TestFilterListApplyParsedIntervalKeepsConfiguredInterval(t *testing.T) {
	options := option.AdblockFilterList{}
	options.UpdateInterval = badoption.Duration(time.Hour)
	list := filterList{
		option:   options,
		interval: time.Hour,
		content:  []byte("! Expires: 8 hours\n||example.com^"),
	}
	parsedFilter := parseFilterLines(list.content)
	list.applyParsedInterval(parsedFilter)

	if list.interval != time.Hour {
		t.Fatalf("expected configured interval to stay 1h, got %s", list.interval)
	}
}

func httpRequestBlocked(s *Service, engine adblockrust.Engine, request *http.Request, requestURL *url.URL, requestType string) bool {
	sourceURL, err := adblockSourceURL(request, requestURL, requestType)
	if err != nil {
		s.stats.recordRequest(false)
		return false
	}
	requestURLString, sourceURLString := requestURL.String(), sourceURL.String()
	method := adblockrust.ParseRequestMethod(request.Method)
	result, err := s.requestCheck(engine, requestURLString, sourceURLString, requestType, method)
	if err == nil && checkResultActionable(result) {
		blocked := checkResultBlocked(result)
		s.debugContext(request.Context(), "HTTP request blocked: ", requestURL, ", type: ", requestType)
		s.stats.recordRequest(blocked)
		return blocked
	}
	inferredRequestType := adblockRequestTypeFromURL(requestURLString)
	if !shouldCheckInferredRequestType(requestType, inferredRequestType) {
		s.debugContext(request.Context(), "HTTP request allowed: ", requestURL, ", type: ", requestType)
		s.stats.recordRequest(false)
		return false
	}
	result, err = s.requestCheck(engine, requestURLString, sourceURLString, inferredRequestType, method)
	blocked := err == nil && checkResultBlocked(result)
	s.debugContext(request.Context(), "HTTP request inferred check: ", requestURL, ", type: ", inferredRequestType, ", blocked: ", blocked)
	s.stats.recordRequest(blocked)
	return blocked
}
