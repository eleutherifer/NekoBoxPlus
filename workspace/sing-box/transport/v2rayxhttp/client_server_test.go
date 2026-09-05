package xhttp

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/quic-go/http3"
	boxTLS "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/common/vision"
	xbuf "github.com/sagernet/sing-box/common/xray/buf"
	Xbadoption "github.com/sagernet/sing-box/common/xray/json/badoption"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/http2"
)

type stubDialerClient struct {
	openStream func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error)
	postPacket func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error
	close      func() error
	closed     atomic.Bool
}

type stubXmuxConn struct{}

type fakeTLSConfig struct {
	serverName       string
	nextProtos       []string
	handshakeTimeout time.Duration
	stdConfigErr     error
}

func (f *fakeTLSConfig) ServerName() string {
	return f.serverName
}

func (f *fakeTLSConfig) SetServerName(serverName string) {
	f.serverName = serverName
}

func (f *fakeTLSConfig) NextProtos() []string {
	return f.nextProtos
}

func (f *fakeTLSConfig) SetNextProtos(nextProto []string) {
	f.nextProtos = nextProto
}

func (f *fakeTLSConfig) HandshakeTimeout() time.Duration {
	return f.handshakeTimeout
}

func (f *fakeTLSConfig) SetHandshakeTimeout(timeout time.Duration) {
	f.handshakeTimeout = timeout
}

func (f *fakeTLSConfig) STDConfig() (*boxTLS.STDConfig, error) {
	return nil, f.stdConfigErr
}

func (f *fakeTLSConfig) Client(net.Conn) (boxTLS.Conn, error) {
	return nil, nil
}

func (f *fakeTLSConfig) Clone() boxTLS.Config {
	return &fakeTLSConfig{
		serverName:       f.serverName,
		nextProtos:       slices.Clone(f.nextProtos),
		handshakeTimeout: f.handshakeTimeout,
		stdConfigErr:     f.stdConfigErr,
	}
}

func (stubXmuxConn) IsClosed() bool {
	return false
}

func (stubXmuxConn) Close() error {
	return nil
}

func (s *stubDialerClient) IsClosed() bool {
	return s.closed.Load()
}

func (s *stubDialerClient) OpenStream(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
	if s.openStream != nil {
		return s.openStream(ctx, rawURL, sessionID, body, uploadOnly)
	}
	return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
}

func (s *stubDialerClient) PostPacket(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
	if bodyCloser, ok := body.(io.Closer); ok {
		defer closeSilently(bodyCloser)
	}
	if s.postPacket != nil {
		return s.postPacket(ctx, rawURL, sessionID, seqStr, body, contentLength)
	}
	return nil
}

func (s *stubDialerClient) Close() error {
	s.closed.Store(true)
	if s.close != nil {
		return s.close()
	}
	return nil
}

type captureRoundTripper struct {
	request *http.Request
	body    []byte
}

func (c *captureRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	c.request = req.Clone(req.Context())
	if req.Body != nil {
		body, err := io.ReadAll(req.Body)
		if err != nil {
			return nil, err
		}
		c.body = body
	}
	return &http.Response{
		StatusCode: http.StatusOK,
		Body:       io.NopCloser(strings.NewReader("")),
		Header:     make(http.Header),
		Request:    req,
	}, nil
}

type roundTripFunc func(req *http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

type blockingReadCloser struct {
	read        chan struct{}
	unblockRead chan struct{}
	closed      chan struct{}
	readOnce    sync.Once
	closeOnce   sync.Once
}

type synchronizedBuffer struct {
	access sync.Mutex
	buffer bytes.Buffer
}

func (b *synchronizedBuffer) Write(p []byte) (int, error) {
	b.access.Lock()
	defer b.access.Unlock()
	return b.buffer.Write(p)
}

func (b *synchronizedBuffer) String() string {
	b.access.Lock()
	defer b.access.Unlock()
	return b.buffer.String()
}

type errorReadCloser struct {
	err error
}

func (r *errorReadCloser) Read([]byte) (int, error) {
	return 0, r.err
}

func (r *errorReadCloser) Close() error {
	return nil
}

type countingErrorReadCloser struct {
	err    error
	reads  atomic.Int32
	closed atomic.Bool
}

func (r *countingErrorReadCloser) Read([]byte) (int, error) {
	r.reads.Add(1)
	if r.closed.Load() {
		return 0, net.ErrClosed
	}
	return 0, r.err
}

func (r *countingErrorReadCloser) Close() error {
	r.closed.Store(true)
	return nil
}

type discardWriteCloser struct{}

func (discardWriteCloser) Write(p []byte) (int, error) {
	return len(p), nil
}

func (discardWriteCloser) Close() error {
	return nil
}

type emptyReadConn struct {
	net.Conn
}

func (c *emptyReadConn) Read([]byte) (int, error) {
	return 0, nil
}

type stubNetworkDialer struct {
	dial func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error)
}

func (d *stubNetworkDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d.dial(ctx, network, destination)
}

func (d *stubNetworkDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

func TestGenerateSessionIDUsesConfiguredTable(t *testing.T) {
	options := &option.V2RayXHTTPBaseOptions{
		SessionIDTable:  "ab",
		SessionIDLength: Xbadoption.Range{From: 32, To: 32},
	}
	sessionID := generateSessionID(options)
	if len(sessionID) != 32 {
		t.Fatalf("session id length = %d", len(sessionID))
	}
	for _, char := range sessionID {
		if char != 'a' && char != 'b' {
			t.Fatalf("session id contains unexpected character %q in %q", char, sessionID)
		}
	}
}

func TestGenerateSessionIDFallsBackToUUID(t *testing.T) {
	sessionID := generateSessionID(&option.V2RayXHTTPBaseOptions{})
	if len(sessionID) != 36 || strings.Count(sessionID, "-") != 4 {
		t.Fatalf("session id = %q, want UUID-shaped value", sessionID)
	}
}

func TestClientDialContextUsesCustomSessionID(t *testing.T) {
	sessionSeen := make(chan string, 1)
	dialerClient := &stubDialerClient{}
	dialerClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		sessionSeen <- sessionID
		return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
	}
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path:            "/xhttp",
				SessionIDTable:  "ab",
				SessionIDLength: Xbadoption.Range{From: 32, To: 32},
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
	}
	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	select {
	case sessionID := <-sessionSeen:
		if len(sessionID) != 32 {
			t.Fatalf("session id length = %d", len(sessionID))
		}
		for _, char := range sessionID {
			if char != 'a' && char != 'b' {
				t.Fatalf("session id contains unexpected character %q in %q", char, sessionID)
			}
		}
	case <-time.After(time.Second):
		t.Fatal("OpenStream was not called")
	}
}

func TestClientDialContextStreamOneKeepsEmptySessionID(t *testing.T) {
	sessionSeen := make(chan string, 1)
	dialerClient := &stubDialerClient{}
	dialerClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		sessionSeen <- sessionID
		return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
	}
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path:            "/xhttp",
				SessionIDTable:  "ab",
				SessionIDLength: Xbadoption.Range{From: 32, To: 32},
			},
			Mode: "stream-one",
		},
		baseRequestURL: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
	}
	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	select {
	case sessionID := <-sessionSeen:
		if sessionID != "" {
			t.Fatalf("session id = %q, want empty", sessionID)
		}
	case <-time.After(time.Second):
		t.Fatal("OpenStream was not called")
	}
}

func TestXHTTPProfileOptionsPrimaryAndLegacyMerge(t *testing.T) {
	base := option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
			SessionIDTable:     "ab",
			SessionIDLength:    Xbadoption.Range{From: 32, To: 32},
		},
		Mode: "packet-up",
	}
	primary := xhttpProfileOptions(base, xhttpSessionProfilePrimary)
	if primary.GetNormalizedSessionKey() != "X-New-Session" {
		t.Fatalf("primary session key = %q", primary.GetNormalizedSessionKey())
	}
	if primary.SessionKey != "" {
		t.Fatalf("primary legacy session key = %q", primary.SessionKey)
	}
	legacy := xhttpProfileOptions(base, xhttpSessionProfileLegacy)
	if legacy.GetNormalizedSessionPlacement() != option.PlacementHeader {
		t.Fatalf("legacy placement = %q", legacy.GetNormalizedSessionPlacement())
	}
	if legacy.GetNormalizedSessionKey() != "X-Legacy-Session" {
		t.Fatalf("legacy key = %q", legacy.GetNormalizedSessionKey())
	}
	if legacy.SessionIDTable != "ab" || legacy.SessionIDLength != (Xbadoption.Range{From: 32, To: 32}) {
		t.Fatalf("legacy id generation = table %q length %+v", legacy.SessionIDTable, legacy.SessionIDLength)
	}
}

func TestXHTTPFallbackProfileKindsSessionCombinations(t *testing.T) {
	options := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementCookie,
			SessionKey:         "legacy_session",
		},
		Mode: "packet-up",
	}
	kinds := xhttpFallbackProfileKinds(options)
	expectedKinds := []xhttpSessionProfileKind{
		xhttpSessionProfileLegacy,
		xhttpSessionProfileLegacyKeySessionIDPlacement,
		xhttpSessionProfileSessionIDKeyLegacyPlacement,
	}
	if !slices.Equal(kinds, expectedKinds) {
		t.Fatalf("fallback kinds = %v, want %v", kinds, expectedKinds)
	}
	expectedPairs := []xhttpSessionPair{
		{key: "legacy_session", placement: option.PlacementCookie},
		{key: "legacy_session", placement: option.PlacementHeader},
		{key: "X-New-Session", placement: option.PlacementCookie},
	}
	for index, kind := range kinds {
		profile := xhttpProfileOptions(*options, kind)
		if profile.GetNormalizedSessionKey() != expectedPairs[index].key ||
			profile.GetNormalizedSessionPlacement() != expectedPairs[index].placement {
			t.Fatalf("profile %s pair = %q/%q, want %q/%q",
				kind,
				profile.GetNormalizedSessionKey(),
				profile.GetNormalizedSessionPlacement(),
				expectedPairs[index].key,
				expectedPairs[index].placement,
			)
		}
	}
}

func TestXHTTPFallbackProfileKindsDisabledWhenPairsUnavailable(t *testing.T) {
	tests := map[string]option.V2RayXHTTPOptions{
		"new only": {
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				SessionIDPlacement: option.PlacementHeader,
				SessionIDKey:       "X-New-Session",
			},
			Mode: "packet-up",
		},
		"legacy only": {
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				SessionPlacement: option.PlacementHeader,
				SessionKey:       "X-Legacy-Session",
			},
			Mode: "packet-up",
		},
		"neither": {
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{},
			Mode:                  "packet-up",
		},
		"missing legacy placement with new placement": {
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				SessionIDPlacement: option.PlacementHeader,
				SessionIDKey:       "X-New-Session",
				SessionKey:         "X-Legacy-Session",
			},
			Mode: "packet-up",
		},
	}
	for name, options := range tests {
		t.Run(name, func(t *testing.T) {
			if kinds := xhttpFallbackProfileKinds(&options); len(kinds) != 0 {
				t.Fatalf("fallback kinds = %v, want none", kinds)
			}
		})
	}
}

func TestXHTTPProfileOptionsPrimaryKeepsLegacyOnlySession(t *testing.T) {
	base := option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			SessionPlacement: option.PlacementHeader,
			SessionKey:       "X-Legacy-Session",
		},
		Mode: "packet-up",
	}
	primary := xhttpProfileOptions(base, xhttpSessionProfilePrimary)
	if primary.GetNormalizedSessionPlacement() != option.PlacementHeader {
		t.Fatalf("primary placement = %q", primary.GetNormalizedSessionPlacement())
	}
	if primary.GetNormalizedSessionKey() != "X-Legacy-Session" {
		t.Fatalf("primary key = %q", primary.GetNormalizedSessionKey())
	}
}

func testClientProfile(name string, options *option.V2RayXHTTPOptions, dialerClient DialerClient, xmux *XmuxClient) *xhttpClientProfile {
	if xmux == nil {
		xmux = &XmuxClient{XmuxConn: dialerClient}
		xmux.LeftRequests.Store(10)
	}
	return &xhttpClientProfile{
		name:            name,
		options:         options,
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, xmux, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, xmux, nil
		},
	}
}

func TestClientDialContextFallsBackToLegacyProfile(t *testing.T) {
	var output bytes.Buffer
	factory := newBufferLogger(t, &output)
	primaryOptions := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			Path:               "/xhttp",
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
		},
		Mode: "packet-up",
	}
	fallbackOptionsValue := xhttpProfileOptions(*primaryOptions, xhttpSessionProfileLegacy)
	fallbackOptions := &fallbackOptionsValue
	primaryClient := &stubDialerClient{}
	primaryClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		return nil, nil, nil, io.ErrClosedPipe
	}
	var fallbackCalls atomic.Int32
	fallbackClient := &stubDialerClient{}
	fallbackClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		fallbackCalls.Add(1)
		return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
	}
	fallbackClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		fallbackCalls.Add(1)
		return nil
	}
	client := &Client{
		options: primaryOptions,
		logger:  factory.Logger(),
		profiles: []*xhttpClientProfile{
			testClientProfile("primary", primaryOptions, primaryClient, nil),
			testClientProfile(string(xhttpSessionProfileLegacy), fallbackOptions, fallbackClient, nil),
		},
	}
	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	if client.selectedProfile == nil || client.selectedProfile.name != string(xhttpSessionProfileLegacy) {
		t.Fatalf("selected profile = %v", client.selectedProfile)
	}
	if calls := fallbackCalls.Load(); calls != 2 {
		t.Fatalf("fallback calls = %d, want probe + final dial", calls)
	}
	logOutput := output.String()
	for _, expected := range []string{
		"XHTTP session fallback initial profile primary failed",
		"XHTTP session fallback probing profile: legacy",
		"XHTTP session fallback profile accepted: legacy",
		"XHTTP session fallback selected profile: legacy",
	} {
		if !strings.Contains(logOutput, expected) {
			t.Fatalf("expected fallback debug log %q, got %q", expected, logOutput)
		}
	}
}

func TestClientDialContextReturnsFailureWhenFallbackProbesFail(t *testing.T) {
	var output bytes.Buffer
	factory := newBufferLogger(t, &output)
	primaryErr := errors.New("primary failed")
	fallbackErr := errors.New("fallback failed")
	primaryOptions := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			Path:               "/xhttp",
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
		},
		Mode: "packet-up",
	}
	fallbackOptionsValue := xhttpProfileOptions(*primaryOptions, xhttpSessionProfileLegacy)
	fallbackOptions := &fallbackOptionsValue
	rejectingClient := &stubDialerClient{}
	rejectingClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		return fallbackErr
	}
	primaryClient := &stubDialerClient{}
	primaryClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		return nil, nil, nil, primaryErr
	}
	client := &Client{
		options: primaryOptions,
		logger:  factory.Logger(),
		profiles: []*xhttpClientProfile{
			testClientProfile("primary", primaryOptions, primaryClient, nil),
			testClientProfile(string(xhttpSessionProfileLegacy), fallbackOptions, rejectingClient, nil),
		},
	}
	conn, err := client.DialContext(t.Context())
	if err == nil {
		closeSilently(conn)
		t.Fatal("expected dial failure")
	}
	if !errors.Is(err, primaryErr) {
		t.Fatalf("dial error = %v, want original primary error %v", err, primaryErr)
	}
	if errors.Is(err, fallbackErr) {
		t.Fatalf("dial error includes fallback probe error: %v", err)
	}
	if client.selectedProfile != nil {
		t.Fatalf("selected profile = %v, want nil", client.selectedProfile)
	}
	logOutput := output.String()
	for _, expected := range []string{
		"XHTTP session fallback profile legacy failed",
		"XHTTP session fallback no profile accepted; returning original error",
	} {
		if !strings.Contains(logOutput, expected) {
			t.Fatalf("expected fallback debug log %q, got %q", expected, logOutput)
		}
	}
}

func TestClientDialContextConcurrentFallbackProbesOnce(t *testing.T) {
	var output synchronizedBuffer
	factory := newBufferLogger(t, &output)
	primaryOptions := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			Path:               "/xhttp",
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
		},
		Mode: "packet-up",
	}
	fallbackOptionsValue := xhttpProfileOptions(*primaryOptions, xhttpSessionProfileLegacy)
	fallbackOptions := &fallbackOptionsValue
	primaryClient := &stubDialerClient{}
	primaryClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		return nil, nil, nil, io.ErrClosedPipe
	}
	var fallbackCalls atomic.Int32
	releaseProbe := make(chan struct{})
	fallbackClient := &stubDialerClient{}
	fallbackClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		fallbackCalls.Add(1)
		return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
	}
	fallbackClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		if fallbackCalls.Add(1) == 1 {
			<-releaseProbe
		}
		return nil
	}
	client := &Client{
		options: primaryOptions,
		logger:  factory.Logger(),
		profiles: []*xhttpClientProfile{
			testClientProfile("primary", primaryOptions, primaryClient, nil),
			testClientProfile(string(xhttpSessionProfileLegacy), fallbackOptions, fallbackClient, nil),
		},
	}
	const dialCount = 4
	var wg sync.WaitGroup
	errs := make(chan error, dialCount)
	for range dialCount {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := client.DialContext(t.Context())
			if err == nil {
				closeSilently(conn)
			}
			errs <- err
		}()
	}
	eventually(t, time.Second, func() bool {
		return fallbackCalls.Load() == 1
	}, "fallback probe did not start")
	close(releaseProbe)
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	if calls := fallbackCalls.Load(); calls != dialCount+1 {
		t.Fatalf("fallback calls = %d, want one probe plus one final dial per waiter", calls)
	}
	logOutput := output.String()
	for _, expected := range []string{
		"XHTTP session fallback waiting for in-progress probe",
		"XHTTP session fallback using selected profile: legacy",
	} {
		if !strings.Contains(logOutput, expected) {
			t.Fatalf("expected fallback debug log %q, got %q", expected, logOutput)
		}
	}
}

func TestClientDialContextFallbackProbeIgnoresRequestTimeout(t *testing.T) {
	primaryOptions := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			Path:               "/xhttp",
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
		},
		Mode: "packet-up",
	}
	fallbackOptionsValue := xhttpProfileOptions(*primaryOptions, xhttpSessionProfileLegacy)
	fallbackOptions := &fallbackOptionsValue
	primaryClient := &stubDialerClient{}
	primaryClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		return nil, nil, nil, io.ErrClosedPipe
	}
	releaseProbe := make(chan struct{})
	var fallbackCalls atomic.Int32
	fallbackClient := &stubDialerClient{}
	fallbackClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		if fallbackCalls.Add(1) == 1 {
			select {
			case <-releaseProbe:
			case <-ctx.Done():
				t.Error("fallback probe used request context")
				return ctx.Err()
			}
		}
		return nil
	}
	client := &Client{
		ctx:     context.Background(),
		options: primaryOptions,
		profiles: []*xhttpClientProfile{
			testClientProfile("primary", primaryOptions, primaryClient, nil),
			testClientProfile(string(xhttpSessionProfileLegacy), fallbackOptions, fallbackClient, nil),
		},
	}
	reqCtx, cancel := context.WithTimeout(t.Context(), 10*time.Millisecond)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		conn, err := client.DialContext(reqCtx)
		if err == nil {
			closeSilently(conn)
		}
		done <- err
	}()
	eventually(t, time.Second, func() bool {
		return fallbackCalls.Load() == 1
	}, "fallback probe did not start")
	<-reqCtx.Done()
	select {
	case err := <-done:
		t.Fatalf("dial completed before fallback probe resolved: %v", err)
	default:
	}
	close(releaseProbe)
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("dial did not resume after fallback probe resolved")
	}
	if client.selectedProfile == nil || client.selectedProfile.name != string(xhttpSessionProfileLegacy) {
		t.Fatalf("selected profile = %v", client.selectedProfile)
	}
}

func TestClientDialContextStallsNewDialsDuringFallbackProbe(t *testing.T) {
	primaryOptions := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			Path:               "/xhttp",
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
		},
		Mode: "packet-up",
	}
	fallbackOptionsValue := xhttpProfileOptions(*primaryOptions, xhttpSessionProfileLegacy)
	fallbackOptions := &fallbackOptionsValue
	var primaryCalls atomic.Int32
	primaryClient := &stubDialerClient{}
	primaryClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		primaryCalls.Add(1)
		return nil, nil, nil, io.ErrClosedPipe
	}
	releaseProbe := make(chan struct{})
	var fallbackCalls atomic.Int32
	fallbackClient := &stubDialerClient{}
	fallbackClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		fallbackCalls.Add(1)
		return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
	}
	fallbackClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		if fallbackCalls.Add(1) == 1 {
			<-releaseProbe
		}
		return nil
	}
	client := &Client{
		ctx:     context.Background(),
		options: primaryOptions,
		profiles: []*xhttpClientProfile{
			testClientProfile("primary", primaryOptions, primaryClient, nil),
			testClientProfile(string(xhttpSessionProfileLegacy), fallbackOptions, fallbackClient, nil),
		},
	}
	firstDone := make(chan error, 1)
	go func() {
		conn, err := client.DialContext(t.Context())
		if err == nil {
			closeSilently(conn)
		}
		firstDone <- err
	}()
	eventually(t, time.Second, func() bool {
		return fallbackCalls.Load() == 1
	}, "fallback probe did not start")
	secondDone := make(chan error, 1)
	go func() {
		conn, err := client.DialContext(t.Context())
		if err == nil {
			closeSilently(conn)
		}
		secondDone <- err
	}()
	time.Sleep(20 * time.Millisecond)
	if calls := primaryCalls.Load(); calls != 1 {
		t.Fatalf("primary calls during in-progress probe = %d, want 1", calls)
	}
	select {
	case err := <-secondDone:
		t.Fatalf("second dial completed before fallback probe resolved: %v", err)
	default:
	}
	close(releaseProbe)
	for _, done := range []chan error{firstDone, secondDone} {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(time.Second):
			t.Fatal("dial did not resume after fallback probe resolved")
		}
	}
	if calls := fallbackCalls.Load(); calls != 3 {
		t.Fatalf("fallback calls = %d, want probe plus two final dials", calls)
	}
}

func TestClientPacketUpUsesResolvedFallbackForRotatedPosts(t *testing.T) {
	primaryOptions := &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			Path:               "/xhttp",
			SessionIDPlacement: option.PlacementHeader,
			SessionIDKey:       "X-New-Session",
			SessionPlacement:   option.PlacementHeader,
			SessionKey:         "X-Legacy-Session",
			ScMaxEachPostBytes: &Xbadoption.Range{From: 4, To: 4},
		},
		Mode: "packet-up",
	}
	fallbackOptionsValue := xhttpProfileOptions(*primaryOptions, xhttpSessionProfileLegacy)
	fallbackOptions := &fallbackOptionsValue
	primaryClient := &stubDialerClient{}
	primaryClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		return nil, nil, nil, io.ErrClosedPipe
	}
	var primaryPosts atomic.Int32
	primaryClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		primaryPosts.Add(1)
		return nil
	}
	fallbackDownloadClient := &stubDialerClient{}
	fallbackDownloadClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
		return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
	}
	postSeen := make(chan struct{}, 1)
	fallbackPostClient := &stubDialerClient{}
	fallbackPostClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		if _, err := io.Copy(io.Discard, body); err != nil {
			return err
		}
		postSeen <- struct{}{}
		return nil
	}
	downloadXmux := &XmuxClient{XmuxConn: fallbackDownloadClient}
	downloadXmux.LeftRequests.Store(1)
	postXmux := &XmuxClient{XmuxConn: fallbackPostClient}
	postXmux.LeftRequests.Store(10)
	fallbackSelections := atomic.Int32{}
	fallbackProfile := testClientProfile(string(xhttpSessionProfileLegacy), fallbackOptions, fallbackDownloadClient, downloadXmux)
	fallbackProfile.getHTTPClient = func() (DialerClient, *XmuxClient, error) {
		switch fallbackSelections.Add(1) {
		case 1:
			return fallbackDownloadClient, downloadXmux, nil
		default:
			return fallbackPostClient, postXmux, nil
		}
	}
	fallbackProfile.getHTTPClient2 = fallbackProfile.getHTTPClient
	client := &Client{
		options: primaryOptions,
		profiles: []*xhttpClientProfile{
			testClientProfile("primary", primaryOptions, primaryClient, nil),
			fallbackProfile,
		},
	}
	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	if _, err := conn.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-postSeen:
	case <-time.After(time.Second):
		t.Fatal("fallback packet POST did not start")
	}
	if posts := primaryPosts.Load(); posts != 0 {
		t.Fatalf("primary posts = %d, want 0", posts)
	}
}

type retryingTLSConn struct {
	net.Conn
}

func (c *retryingTLSConn) Read(buffer []byte) (int, error) {
	for {
		read, err := c.Conn.Read(buffer)
		if read != 0 || err != nil {
			return read, err
		}
	}
}

func (c *retryingTLSConn) NetConn() net.Conn {
	return c.Conn
}

func (c *retryingTLSConn) HandshakeContext(context.Context) error {
	return nil
}

func (c *retryingTLSConn) ConnectionState() boxTLS.ConnectionState {
	return boxTLS.ConnectionState{}
}

type recordingTLSConfig struct {
	fakeTLSConfig
	handshakeConn net.Conn
	handshakeErr  error
}

func (c *recordingTLSConfig) ClientHandshake(_ context.Context, conn net.Conn) (boxTLS.Conn, error) {
	c.handshakeConn = conn
	if c.handshakeErr != nil {
		return nil, c.handshakeErr
	}
	return &retryingTLSConn{Conn: conn}, nil
}

func newBlockingReadCloser() *blockingReadCloser {
	return &blockingReadCloser{
		read:        make(chan struct{}),
		unblockRead: make(chan struct{}),
		closed:      make(chan struct{}),
	}
}

func (b *blockingReadCloser) Read(p []byte) (int, error) {
	b.readOnce.Do(func() {
		close(b.read)
	})
	<-b.unblockRead
	return 0, io.EOF
}

func (b *blockingReadCloser) Close() error {
	b.closeOnce.Do(func() {
		close(b.closed)
	})
	return nil
}

func eventually(t *testing.T, timeout time.Duration, condition func() bool, message string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	if condition() {
		return
	}
	t.Fatal(message)
}

func TestClientNextPacketUpHTTPClientRotatesPerRequest(t *testing.T) {
	client1 := &stubDialerClient{}
	client2 := &stubDialerClient{}
	client3 := &stubDialerClient{}
	xmux1 := &XmuxClient{}
	xmux1.LeftRequests.Store(1)
	xmux2 := &XmuxClient{}
	xmux2.LeftRequests.Store(1)
	xmux3 := &XmuxClient{}
	xmux3.LeftRequests.Store(5)
	next := 0
	client := &Client{
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			next++
			switch next {
			case 1:
				return client2, xmux2, nil
			case 2:
				return client3, xmux3, nil
			default:
				t.Fatalf("unexpected getHTTPClient call %d", next)
				return nil, nil, nil
			}
		},
	}

	selectedClient, selectedXmux, err := client.nextPacketUpHTTPClient(client1, xmux1, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if selectedClient != client2 || selectedXmux != xmux2 {
		t.Fatal("first request did not rotate to the next xmux client")
	}

	selectedClient, selectedXmux, err = client.nextPacketUpHTTPClient(selectedClient, selectedXmux, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if selectedClient != client3 || selectedXmux != xmux3 {
		t.Fatal("second request did not keep its own rotated xmux client")
	}
}

func TestDialerClientFromXmuxReturnsErrorForNonDialerClient(t *testing.T) {
	_, err := dialerClientFromXmux(&XmuxClient{XmuxConn: stubXmuxConn{}})
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestHTTPSessionFromAnyReturnsErrorForUnexpectedType(t *testing.T) {
	if session, err := httpSessionFromAny("unexpected"); err == nil || session != nil {
		t.Fatalf("session = %v, err = %v", session, err)
	}
}

func newClientDialLoggingTestClient(logger logger.ContextLogger) *Client {
	dialerClient := &stubDialerClient{}
	return &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "stream-one",
		},
		dest:            M.Socksaddr{Fqdn: "example.com", Port: 443},
		logger:          logger,
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
	}
}

func newBufferLogger(t *testing.T, output io.Writer) log.ObservableFactory {
	t.Helper()
	factory := log.NewDefaultFactory(
		t.Context(),
		log.Formatter{DisableColors: true, DisableTimestamp: true},
		output,
		"",
		nil,
		false,
	)
	factory.SetLevel(log.LevelDebug)
	if err := factory.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = factory.Close() })
	return factory
}

func TestPrepareXHTTPTLSConfigPreservesSupportedHTTP3(t *testing.T) {
	original := &fakeTLSConfig{nextProtos: []string{http3.NextProtoH3, http2.NextProtoTLS, "http/1.1"}}
	prepared, adjustment, err := prepareXHTTPTLSConfig(original)
	if err != nil {
		t.Fatal(err)
	}
	if adjustment != xhttpTLSUnchanged {
		t.Fatalf("adjustment = %v, want unchanged", adjustment)
	}
	if prepared == original {
		t.Fatal("TLS config was not cloned")
	}
	if !slices.Equal(prepared.NextProtos(), original.NextProtos()) {
		t.Fatalf("ALPN = %v, want %v", prepared.NextProtos(), original.NextProtos())
	}
}

func TestPrepareXHTTPTLSConfigFallsBackFromHTTP3ForUnsupportedTLSEngine(t *testing.T) {
	unsupportedTLSEngine := errors.New("standard TLS config is unsupported")
	original := &fakeTLSConfig{
		nextProtos:   []string{http3.NextProtoH3, http2.NextProtoTLS, "http/1.1"},
		stdConfigErr: unsupportedTLSEngine,
	}
	prepared, adjustment, err := prepareXHTTPTLSConfig(original)
	if err != nil {
		t.Fatal(err)
	}
	if adjustment != xhttpTLSFallbackToTCP {
		t.Fatalf("adjustment = %v, want TCP fallback", adjustment)
	}
	wantProtos := []string{http2.NextProtoTLS, "http/1.1"}
	if !slices.Equal(prepared.NextProtos(), wantProtos) {
		t.Fatalf("ALPN = %v, want %v", prepared.NextProtos(), wantProtos)
	}
	if !slices.Equal(original.NextProtos(), []string{http3.NextProtoH3, http2.NextProtoTLS, "http/1.1"}) {
		t.Fatalf("original ALPN was modified: %v", original.NextProtos())
	}
}

func TestPrepareXHTTPTLSConfigRejectsHTTP3OnlyUnsupportedTLSEngine(t *testing.T) {
	original := &fakeTLSConfig{
		nextProtos:   []string{http3.NextProtoH3},
		stdConfigErr: errors.New("standard TLS config is unsupported"),
	}
	_, _, err := prepareXHTTPTLSConfig(original)
	if err == nil || !strings.Contains(err.Error(), "no TCP ALPN fallback") {
		t.Fatalf("error = %v", err)
	}
}

func TestPrepareXHTTPTLSConfigDoesNotAdvertiseHTTP3OverTCP(t *testing.T) {
	original := &fakeTLSConfig{nextProtos: []string{http2.NextProtoTLS, http3.NextProtoH3, "http/1.1"}}
	prepared, adjustment, err := prepareXHTTPTLSConfig(original)
	if err != nil {
		t.Fatal(err)
	}
	if adjustment != xhttpTLSUnchanged {
		t.Fatalf("adjustment = %v, want unchanged", adjustment)
	}
	wantProtos := []string{http2.NextProtoTLS, "http/1.1"}
	if !slices.Equal(prepared.NextProtos(), wantProtos) {
		t.Fatalf("ALPN = %v, want %v", prepared.NextProtos(), wantProtos)
	}
}

func TestClientDialContextUsesConfiguredLogger(t *testing.T) {
	var output bytes.Buffer
	factory := newBufferLogger(t, &output)
	client := newClientDialLoggingTestClient(factory.Logger())

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}

	if !strings.Contains(output.String(), "XHTTP is dialing to tcp:example.com:443") {
		t.Fatalf("expected XHTTP dial debug log, got %q", output.String())
	}
}

func TestClientDialContextDoesNotUseStdLoggerFallback(t *testing.T) {
	var output bytes.Buffer
	factory := newBufferLogger(t, &output)
	oldLogger := log.StdLogger()
	log.SetStdLogger(factory.Logger())
	defer log.SetStdLogger(oldLogger)
	client := newClientDialLoggingTestClient(nil)

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}

	if output.Len() > 0 {
		t.Fatalf("expected no std logger fallback, got %q", output.String())
	}
}

func TestCreateHTTPClientInheritsDefaultHTTP2FrameSizeForPacketUp(t *testing.T) {
	client := createHTTPClient(
		t.Context(),
		M.Socksaddr{Fqdn: "example.com", Port: 443},
		nil,
		&option.V2RayXHTTPBaseOptions{Mode: "packet-up"},
		&fakeTLSConfig{nextProtos: []string{http2.NextProtoTLS}},
	)
	defaultClient, ok := client.(*DefaultDialerClient)
	if !ok {
		t.Fatalf("client type = %T, want *DefaultDialerClient", client)
	}
	transport, ok := defaultClient.client.Transport.(*http2.Transport)
	if !ok {
		t.Fatalf("transport type = %T, want *http2.Transport", defaultClient.client.Transport)
	}
	if transport.MaxReadFrameSize != 0 {
		t.Fatalf("MaxReadFrameSize = %d, want inherited default", transport.MaxReadFrameSize)
	}
}

func TestClientPacketUpZeroPostSizeDoesNotPanic(t *testing.T) {
	dialerClient := &stubDialerClient{}
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path:               "/xhttp",
				ScMaxEachPostBytes: &Xbadoption.Range{},
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
	}
	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	writeDone := make(chan error, 1)
	go func() {
		_, err := conn.Write([]byte("payload"))
		writeDone <- err
	}()
	select {
	case err := <-writeDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("write did not finish after zero post size")
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestClientPacketUpPostPacketErrorClosesBodyAndReleasesXmux(t *testing.T) {
	bodySeen := make(chan *xbuf.MultiBufferContainer, 4)
	dialerClient := &stubDialerClient{}
	dialerClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		container, ok := body.(*xbuf.MultiBufferContainer)
		if !ok {
			t.Fatalf("body type = %T", body)
		}
		bodySeen <- container
		return io.ErrClosedPipe
	}
	xmux := &XmuxClient{XmuxConn: dialerClient}
	xmux.LeftRequests.Store(10)
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path:               "/xhttp",
				ScMaxEachPostBytes: &Xbadoption.Range{From: 4, To: 4},
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, xmux, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, xmux, nil
		},
	}
	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	if _, err = conn.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-bodySeen:
	case <-time.After(time.Second):
		t.Fatal("packet-up POST did not start")
	}
	eventually(t, time.Second, func() bool {
		return xmux.GetOpenUsage() == 1
	}, "packet-up logical xmux usage changed before connection close")
	eventually(t, time.Second, func() bool {
		return xmux.GetPacketUsage() == 0
	}, "packet-up packet usage was not released")
	for done := false; !done; {
		select {
		case body := <-bodySeen:
			if body.MultiBuffer != nil {
				t.Fatalf("POST body was not closed: %v", body.MultiBuffer)
			}
		default:
			done = true
		}
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	eventually(t, time.Second, func() bool {
		return xmux.GetOpenUsage() == 0
	}, "download xmux usage was not released")
}

func TestClientPacketUpDoesNotUseBufferedPostsAsClientPostLimit(t *testing.T) {
	postStarted := make(chan string, 2)
	releasePosts := make(chan struct{})
	defer close(releasePosts)
	dialerClient := &stubDialerClient{}
	dialerClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		if _, err := io.Copy(io.Discard, body); err != nil {
			return err
		}
		postStarted <- seqStr
		select {
		case <-releasePosts:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path:                 "/xhttp",
				ScMaxEachPostBytes:   &Xbadoption.Range{From: 1, To: 1},
				ScMinPostsIntervalMs: &Xbadoption.Range{From: 1, To: 1},
				ScMaxBufferedPosts:   1,
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, nil, nil
		},
	}

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)

	if _, err = conn.Write([]byte("ab")); err != nil {
		t.Fatal(err)
	}
	seen := make(map[string]bool)
	for i := range 2 {
		select {
		case seqStr := <-postStarted:
			seen[seqStr] = true
		case <-time.After(time.Second):
			t.Fatalf("timed out waiting for post %d to start", i)
		}
	}
	if !seen["0"] || !seen["1"] {
		t.Fatalf("started post sequences = %v, want 0 and 1", seen)
	}
}

func TestClientPacketUpPostUsageDoesNotConsumeXmuxConcurrency(t *testing.T) {
	postStarted := make(chan struct{})
	releasePost := make(chan struct{})
	defer close(releasePost)

	var created atomic.Int32
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{
		MaxConcurrency: Xbadoption.Range{From: 2, To: 2},
	}, func() XmuxConn {
		created.Add(1)
		dialerClient := &stubDialerClient{}
		dialerClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
			if _, err := io.Copy(io.Discard, body); err != nil {
				return err
			}
			close(postStarted)
			select {
			case <-releasePost:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		return dialerClient
	})
	var firstXmux *XmuxClient
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		xmuxManager:     xmuxManager,
	}
	client.getHTTPClient = func() (DialerClient, *XmuxClient, error) {
		xmuxClient := xmuxManager.GetXmuxClient(t.Context())
		if firstXmux == nil {
			firstXmux = xmuxClient
		}
		httpClient, err := dialerClientFromXmux(xmuxClient)
		return httpClient, xmuxClient, err
	}
	client.getHTTPClient2 = client.getHTTPClient

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	if _, err = conn.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-postStarted:
	case <-time.After(time.Second):
		t.Fatal("packet-up POST did not start")
	}

	if usage := firstXmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("logical usage = %d, want 1", usage)
	}
	if usage := firstXmux.GetPacketUsage(); usage != 1 {
		t.Fatalf("packet usage = %d, want 1", usage)
	}
	selectedXmux := xmuxManager.GetXmuxClient(t.Context())
	if selectedXmux != firstXmux {
		t.Fatal("packet POST usage consumed XMUX max concurrency")
	}
	if got := created.Load(); got != 1 {
		t.Fatalf("created xmux clients = %d, want 1", got)
	}
}

func TestClientPacketUpWithoutDownloadUsesSingleXmuxSelection(t *testing.T) {
	dialerClient := &stubDialerClient{}
	xmux := &XmuxClient{XmuxConn: dialerClient}
	xmux.LeftRequests.Store(10)

	var uploadGets atomic.Int32
	var downloadGets atomic.Int32
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			uploadGets.Add(1)
			return dialerClient, xmux, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			downloadGets.Add(1)
			return dialerClient, xmux, nil
		},
	}

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)

	if got := uploadGets.Load(); got != 1 {
		t.Fatalf("upload xmux selections = %d, want 1", got)
	}
	if got := downloadGets.Load(); got != 0 {
		t.Fatalf("download xmux selections = %d, want 0", got)
	}
	if left := xmux.LeftRequests.Load(); left != 9 {
		t.Fatalf("left requests = %d, want 9", left)
	}
}

func TestClientPacketUpWithDownloadUsesSeparateXmuxSelection(t *testing.T) {
	uploadClient := &stubDialerClient{}
	downloadClient := &stubDialerClient{}
	uploadXmux := &XmuxClient{XmuxConn: uploadClient}
	uploadXmux.LeftRequests.Store(10)
	downloadXmux := &XmuxClient{XmuxConn: downloadClient}
	downloadXmux.LeftRequests.Store(10)
	downloadDest := M.Socksaddr{Fqdn: "download.example", Port: 443}

	var uploadGets atomic.Int32
	var downloadGets atomic.Int32
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "packet-up",
		},
		downloadDest:    &downloadDest,
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "download.example", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			uploadGets.Add(1)
			return uploadClient, uploadXmux, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			downloadGets.Add(1)
			return downloadClient, downloadXmux, nil
		},
	}

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)

	if got := uploadGets.Load(); got != 1 {
		t.Fatalf("upload xmux selections = %d, want 1", got)
	}
	if got := downloadGets.Load(); got != 1 {
		t.Fatalf("download xmux selections = %d, want 1", got)
	}
}

func TestXmuxManagerClosesRetiredIdleClient(t *testing.T) {
	closed := make(chan *stubDialerClient, 2)
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{
		HMaxRequestTimes: Xbadoption.Range{From: 1, To: 1},
	}, func() XmuxConn {
		client := &stubDialerClient{}
		client.close = func() error {
			closed <- client
			return nil
		}
		return client
	})
	firstXmux := xmuxManager.GetXmuxClient(t.Context())
	firstXmux.LeftRequests.Store(0)

	secondXmux := xmuxManager.GetXmuxClient(t.Context())
	if secondXmux == firstXmux {
		t.Fatal("retired xmux client was reused")
	}

	select {
	case closedClient := <-closed:
		if closedClient != firstXmux.XmuxConn {
			t.Fatal("closed an unexpected xmux client")
		}
	case <-time.After(time.Second):
		t.Fatal("retired idle xmux client was not closed")
	}
}

func TestXmuxManagerClosesRetiredActiveClientAfterRelease(t *testing.T) {
	closed := make(chan *stubDialerClient, 2)
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{
		HMaxRequestTimes: Xbadoption.Range{From: 1, To: 1},
	}, func() XmuxConn {
		client := &stubDialerClient{}
		client.close = func() error {
			closed <- client
			return nil
		}
		return client
	})
	firstXmux := xmuxManager.GetXmuxClient(t.Context())
	firstXmux.AddOpenUsage(1)
	firstXmux.LeftRequests.Store(0)

	secondXmux := xmuxManager.GetXmuxClient(t.Context())
	if secondXmux == firstXmux {
		t.Fatal("retired xmux client was reused")
	}
	select {
	case <-closed:
		t.Fatal("retired active xmux client was closed before release")
	default:
	}

	firstXmux.ReleaseUsage()
	select {
	case closedClient := <-closed:
		if closedClient != firstXmux.XmuxConn {
			t.Fatal("closed an unexpected xmux client")
		}
	case <-time.After(time.Second):
		t.Fatal("retired active xmux client was not closed after release")
	}
}

func TestXmuxManagerRetiresSurplusIdleClients(t *testing.T) {
	closed := make(chan *stubDialerClient, 3)
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{
		MaxConcurrency: Xbadoption.Range{From: 1, To: 1},
	}, func() XmuxConn {
		client := &stubDialerClient{}
		client.close = func() error {
			closed <- client
			return nil
		}
		return client
	})

	firstXmux := xmuxManager.GetXmuxClient(t.Context())
	firstXmux.AddOpenUsage(1)
	secondXmux := xmuxManager.GetXmuxClient(t.Context())
	secondXmux.AddOpenUsage(1)
	if secondXmux == firstXmux {
		t.Fatal("active xmux client was reused past max concurrency")
	}

	firstXmux.ReleaseUsage()
	select {
	case <-closed:
		t.Fatal("first idle xmux client should be retained while it is the only idle client")
	default:
	}

	secondXmux.ReleaseUsage()
	select {
	case closedClient := <-closed:
		if closedClient != firstXmux.XmuxConn {
			t.Fatal("oldest surplus idle xmux client was not retired")
		}
	case <-time.After(time.Second):
		t.Fatal("surplus idle xmux client was not retired")
	}
	select {
	case <-closed:
		t.Fatal("idle retention should keep one warm xmux client")
	default:
	}
}

func TestXmuxClientCloseWaitsForOpenUsage(t *testing.T) {
	closed := make(chan struct{}, 1)
	xmuxClient := &XmuxClient{
		XmuxConn: &stubDialerClient{
			close: func() error {
				closed <- struct{}{}
				return nil
			},
		},
	}
	xmuxClient.AddOpenUsage(1)

	if err := xmuxClient.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
		t.Fatal("active xmux client was closed before release")
	default:
	}

	xmuxClient.ReleaseUsage()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("xmux client was not closed after release")
	}
}

func TestXmuxClientCloseWaitsForPacketUsage(t *testing.T) {
	closed := make(chan struct{}, 1)
	xmuxClient := &XmuxClient{
		XmuxConn: &stubDialerClient{
			close: func() error {
				closed <- struct{}{}
				return nil
			},
		},
	}
	xmuxClient.AddPacketUsage(1)

	if err := xmuxClient.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
		t.Fatal("active packet xmux client was closed before release")
	default:
	}

	xmuxClient.AddPacketUsage(-1)
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("xmux client was not closed after packet release")
	}
}

func TestClientPacketUpReleasesRotatedXmuxAfterPost(t *testing.T) {
	postStarted := make(chan struct{})
	unblockPost := make(chan struct{})
	closed := make(chan *stubDialerClient, 1)

	initialClient := &stubDialerClient{}
	downloadClient := &stubDialerClient{}
	rotatedClient := &stubDialerClient{}
	rotatedClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		close(postStarted)
		select {
		case <-unblockPost:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	rotatedClient.close = func() error {
		closed <- rotatedClient
		return nil
	}

	initialXmux := &XmuxClient{XmuxConn: initialClient}
	initialXmux.LeftRequests.Store(1)
	downloadXmux := &XmuxClient{XmuxConn: downloadClient}
	downloadXmux.LeftRequests.Store(10)
	rotatedXmux := &XmuxClient{XmuxConn: rotatedClient}
	rotatedXmux.LeftRequests.Store(10)
	downloadDest := M.Socksaddr{Fqdn: "download.example", Port: 443}

	var uploadGets atomic.Int32
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "packet-up",
		},
		downloadDest:    &downloadDest,
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "download.example", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			switch uploadGets.Add(1) {
			case 1:
				return initialClient, initialXmux, nil
			case 2:
				return rotatedClient, rotatedXmux, nil
			default:
				t.Fatal("unexpected extra upload xmux selection")
				return nil, nil, nil
			}
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return downloadClient, downloadXmux, nil
		},
	}

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)

	if _, err = conn.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-postStarted:
	case <-time.After(time.Second):
		t.Fatal("packet-up POST did not start")
	}

	if usage := initialXmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("initial upload xmux usage = %d", usage)
	}
	if usage := downloadXmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("download xmux usage = %d", usage)
	}
	if usage := rotatedXmux.GetOpenUsage(); usage != 0 {
		t.Fatalf("rotated upload xmux usage = %d", usage)
	}
	if usage := rotatedXmux.GetPacketUsage(); usage != 1 {
		t.Fatalf("rotated upload packet usage = %d", usage)
	}

	rotatedXmux.Retire()
	select {
	case <-closed:
		t.Fatal("retired upload xmux closed before POST completed")
	default:
	}

	close(unblockPost)
	select {
	case closedClient := <-closed:
		if closedClient != rotatedClient {
			t.Fatal("closed an unexpected xmux client")
		}
	case <-time.After(time.Second):
		t.Fatal("retired upload xmux was not closed after POST completed")
	}
	if usage := downloadXmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("download xmux usage after upload release = %d", usage)
	}
	if usage := initialXmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("initial upload xmux usage after upload release = %d", usage)
	}
}

func TestClientPacketUpSharedXmuxKeepsDownloadUsageUntilConnectionClose(t *testing.T) {
	postStarted := make(chan struct{})
	unblockPost := make(chan struct{})
	closed := make(chan *stubDialerClient, 1)

	dialerClient := &stubDialerClient{}
	dialerClient.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		close(postStarted)
		select {
		case <-unblockPost:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	dialerClient.close = func() error {
		closed <- dialerClient
		return nil
	}
	xmux := &XmuxClient{XmuxConn: dialerClient}
	xmux.LeftRequests.Store(10)

	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "packet-up",
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		getHTTPClient: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, xmux, nil
		},
		getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
			return dialerClient, xmux, nil
		},
	}

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	if usage := xmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("shared xmux download usage = %d", usage)
	}

	if _, err = conn.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-postStarted:
	case <-time.After(time.Second):
		t.Fatal("packet-up POST did not start")
	}
	if usage := xmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("shared xmux logical usage during POST = %d", usage)
	}
	if usage := xmux.GetPacketUsage(); usage != 1 {
		t.Fatalf("shared xmux packet usage during POST = %d", usage)
	}

	xmux.Retire()
	close(unblockPost)
	select {
	case <-closed:
		t.Fatal("shared xmux closed while download stream was still active")
	case <-time.After(50 * time.Millisecond):
	}
	if usage := xmux.GetOpenUsage(); usage != 1 {
		t.Fatalf("shared xmux usage after POST = %d", usage)
	}
	if usage := xmux.GetPacketUsage(); usage != 0 {
		t.Fatalf("shared xmux packet usage after POST = %d", usage)
	}

	if err = conn.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case closedClient := <-closed:
		if closedClient != dialerClient {
			t.Fatal("closed an unexpected xmux client")
		}
	case <-time.After(time.Second):
		t.Fatal("shared xmux was not closed after connection close")
	}
}

func TestClientStreamModesReleaseRetiredXmuxOnConnectionClose(t *testing.T) {
	tests := []struct {
		name           string
		mode           string
		sharedXmux     bool
		expectDownload bool
	}{
		{
			name: "stream-one",
			mode: "stream-one",
		},
		{
			name:           "stream-up shared xmux",
			mode:           "stream-up",
			sharedXmux:     true,
			expectDownload: true,
		},
		{
			name:           "stream-up split xmux",
			mode:           "stream-up",
			expectDownload: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			closed := make(chan *stubDialerClient, 2)
			uploadClient := &stubDialerClient{}
			uploadClient.close = func() error {
				closed <- uploadClient
				return nil
			}
			downloadClient := uploadClient
			if !test.sharedXmux {
				downloadClient = &stubDialerClient{}
				downloadClient.close = func() error {
					closed <- downloadClient
					return nil
				}
			}

			uploadXmux := &XmuxClient{XmuxConn: uploadClient}
			uploadXmux.LeftRequests.Store(10)
			downloadXmux := uploadXmux
			if !test.sharedXmux {
				downloadXmux = &XmuxClient{XmuxConn: downloadClient}
				downloadXmux.LeftRequests.Store(10)
			}
			var downloadDest *M.Socksaddr
			if !test.sharedXmux {
				downloadDest = &M.Socksaddr{Fqdn: "download.example", Port: 443}
			}

			client := &Client{
				options: &option.V2RayXHTTPOptions{
					V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
						Path: "/xhttp",
					},
					Mode: test.mode,
				},
				downloadDest:    downloadDest,
				baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
				baseRequestURL2: url.URL{Scheme: "https", Host: "download.example", Path: "/xhttp"},
				getHTTPClient: func() (DialerClient, *XmuxClient, error) {
					return uploadClient, uploadXmux, nil
				},
				getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
					return downloadClient, downloadXmux, nil
				},
			}

			conn, err := client.DialContext(t.Context())
			if err != nil {
				t.Fatal(err)
			}

			if usage := uploadXmux.GetOpenUsage(); usage != 1 {
				t.Fatalf("upload xmux usage = %d", usage)
			}
			if usage := uploadXmux.GetPacketUsage(); usage != 0 {
				t.Fatalf("upload xmux packet usage = %d", usage)
			}
			if test.expectDownload && !test.sharedXmux {
				if usage := downloadXmux.GetOpenUsage(); usage != 1 {
					t.Fatalf("download xmux usage = %d", usage)
				}
				if usage := downloadXmux.GetPacketUsage(); usage != 0 {
					t.Fatalf("download xmux packet usage = %d", usage)
				}
			}
			if test.expectDownload && test.sharedXmux {
				if usage := uploadXmux.GetOpenUsage(); usage != 1 {
					t.Fatalf("shared xmux usage = %d", usage)
				}
				if usage := uploadXmux.GetPacketUsage(); usage != 0 {
					t.Fatalf("shared xmux packet usage = %d", usage)
				}
			}

			uploadXmux.Retire()
			if test.expectDownload && !test.sharedXmux {
				downloadXmux.Retire()
			}
			select {
			case <-closed:
				t.Fatal("retired xmux closed before connection close")
			default:
			}

			if err = conn.Close(); err != nil {
				t.Fatal(err)
			}
			expectedCloses := 1
			if test.expectDownload && !test.sharedXmux {
				expectedCloses = 2
			}
			for range expectedCloses {
				select {
				case <-closed:
				case <-time.After(time.Second):
					t.Fatal("retired xmux was not closed after connection close")
				}
			}
		})
	}
}

func TestClientStreamCloseWriteEndsUploadBodyOnly(t *testing.T) {
	tests := []struct {
		name string
		mode string
	}{
		{
			name: "stream-one",
			mode: "stream-one",
		},
		{
			name: "stream-up",
			mode: "stream-up",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			uploadBodyDone := make(chan struct{})
			downloadBody := newBlockingReadCloser()
			defer close(downloadBody.unblockRead)
			dialerClient := &stubDialerClient{}
			dialerClient.openStream = func(ctx context.Context, rawURL string, sessionID string, body io.Reader, uploadOnly bool) (io.ReadCloser, net.Addr, net.Addr, error) {
				if body != nil {
					go func() {
						_, _ = io.Copy(io.Discard, body)
						close(uploadBodyDone)
					}()
					if uploadOnly {
						return io.NopCloser(strings.NewReader("")), &net.TCPAddr{}, &net.TCPAddr{}, nil
					}
				}
				return downloadBody, &net.TCPAddr{}, &net.TCPAddr{}, nil
			}
			client := &Client{
				options: &option.V2RayXHTTPOptions{
					V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
						Path: "/xhttp",
					},
					Mode: test.mode,
				},
				baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
				baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
				getHTTPClient: func() (DialerClient, *XmuxClient, error) {
					return dialerClient, nil, nil
				},
				getHTTPClient2: func() (DialerClient, *XmuxClient, error) {
					return dialerClient, nil, nil
				},
			}

			conn, err := client.DialContext(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			writeCloser, ok := conn.(interface{ CloseWrite() error })
			if !ok {
				t.Fatal("xhttp stream connection does not support CloseWrite")
			}
			if _, err = conn.Write([]byte("payload")); err != nil {
				t.Fatal(err)
			}
			if err = writeCloser.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			if err = writeCloser.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			select {
			case <-uploadBodyDone:
			case <-time.After(time.Second):
				t.Fatal("upload body did not finish after CloseWrite")
			}
			select {
			case <-downloadBody.closed:
				t.Fatal("download body was closed by CloseWrite")
			default:
			}

			if err = conn.Close(); err != nil {
				t.Fatal(err)
			}
			select {
			case <-downloadBody.closed:
			case <-time.After(time.Second):
				t.Fatal("download body was not closed by full Close")
			}
		})
	}
}

func TestIdleTimeoutWriteCloserClosesIdleWriter(t *testing.T) {
	reader, writer := io.Pipe()
	wrapped := newIdleTimeoutWriteCloser(writer, 20*time.Millisecond)
	defer closeSilently(reader)

	readErr := make(chan error, 1)
	go func() {
		_, err := reader.Read(make([]byte, 1))
		readErr <- err
	}()

	select {
	case err := <-readErr:
		if err == nil {
			t.Fatal("idle writer closed without reporting an error to the reader")
		}
	case <-time.After(time.Second):
		t.Fatal("idle writer was not closed")
	}

	if _, err := wrapped.Write([]byte("payload")); err == nil {
		t.Fatal("expected write to fail after idle close")
	}
}

func TestStreamUploadIdleTimeoutUsesServerPaddingWindow(t *testing.T) {
	if timeout := streamUploadIdleTimeout(&option.V2RayXHTTPBaseOptions{}); timeout != 80*time.Second {
		t.Fatalf("unexpected default timeout: %v", timeout)
	}

	if timeout := streamUploadIdleTimeout(&option.V2RayXHTTPBaseOptions{
		ScStreamUpServerSecs: &Xbadoption.Range{From: 1, To: 2},
	}); timeout != C.TCPTimeout {
		t.Fatalf("timeout should not be shorter than TCP timeout, got %v", timeout)
	}

	if timeout := streamUploadIdleTimeout(&option.V2RayXHTTPBaseOptions{
		ScStreamUpServerSecs: &Xbadoption.Range{From: 90, To: 90},
	}); timeout != 90*time.Second {
		t.Fatalf("timeout should follow longer stream-up padding window, got %v", timeout)
	}
}

func TestDefaultDialerClientOpenStreamUploadOnlyDoesNotDrainResponse(t *testing.T) {
	responseBody := newBlockingReadCloser()
	requestBodyReader, requestBodyWriter := io.Pipe()
	defer close(responseBody.unblockRead)
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if trace := httptrace.ContextClientTrace(req.Context()); trace != nil && trace.GotConn != nil {
			clientConn, serverConn := net.Pipe()
			trace.GotConn(httptrace.GotConnInfo{Conn: clientConn})
			closeSilently(clientConn)
			closeSilently(serverConn)
		}
		go func() {
			if _, err := io.Copy(io.Discard, req.Body); err != nil {
				return
			}
		}()
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       responseBody,
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	client := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}

	readCloser, _, _, err := client.OpenStream(t.Context(), "https://example.com/upload", "session", requestBodyReader, true)
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(readCloser)

	select {
	case <-responseBody.read:
		t.Fatal("upload-only response body was drained")
	case <-time.After(50 * time.Millisecond):
	}

	if err := requestBodyWriter.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-responseBody.closed:
	case <-time.After(time.Second):
		t.Fatal("response body was not closed after upload body ended")
	}
}

func TestDefaultDialerClientOpenStreamCloseWithoutRequestBody(t *testing.T) {
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if trace := httptrace.ContextClientTrace(req.Context()); trace != nil && trace.GotConn != nil {
			clientConn, serverConn := net.Pipe()
			trace.GotConn(httptrace.GotConnInfo{Conn: clientConn})
			closeSilently(clientConn)
			closeSilently(serverConn)
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("payload")),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	client := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}

	readCloser, _, _, err := client.OpenStream(t.Context(), "https://example.com/download", "session", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 1)
	if _, err := readCloser.Read(buffer); err != nil {
		t.Fatal(err)
	}
	if err := readCloser.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestDefaultDialerClientOpenStreamIgnoresLateGotConn(t *testing.T) {
	requestErr := errors.New("request failed before connection was reported")
	releaseGotConn := make(chan struct{})
	gotConnDone := make(chan struct{})
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		trace := httptrace.ContextClientTrace(req.Context())
		if trace == nil || trace.GotConn == nil {
			return nil, errors.New("missing GotConn trace")
		}
		go func() {
			defer close(gotConnDone)
			<-releaseGotConn
			clientConn, serverConn := net.Pipe()
			trace.GotConn(httptrace.GotConnInfo{Conn: clientConn})
			closeSilently(clientConn)
			closeSilently(serverConn)
		}()
		return nil, requestErr
	})
	client := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}
	var hookCalls atomic.Int32
	ctx := vision.WithHook(t.Context(), func(net.Conn) {
		hookCalls.Add(1)
	})

	readCloser, remoteAddr, localAddr, err := client.OpenStream(ctx, "https://example.com/download", "session", nil, false)
	if !errors.Is(err, requestErr) {
		t.Fatalf("expected request error, got %v", err)
	}
	if remoteAddr != nil || localAddr != nil {
		t.Fatalf("unexpected connection addresses after request failure: remote=%v local=%v", remoteAddr, localAddr)
	}
	if readCloser != nil {
		closeSilently(readCloser)
	}
	close(releaseGotConn)
	<-gotConnDone
	if hookCalls.Load() != 0 {
		t.Fatalf("Vision hook called %d times for a late connection", hookCalls.Load())
	}
}

func TestDefaultDialerClientOpenStreamPublishesFirstConnection(t *testing.T) {
	var firstConn net.Conn
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		trace := httptrace.ContextClientTrace(req.Context())
		if trace == nil || trace.GotConn == nil {
			return nil, errors.New("missing GotConn trace")
		}
		clientConn1, serverConn1 := net.Pipe()
		clientConn2, serverConn2 := net.Pipe()
		firstConn = clientConn1
		trace.GotConn(httptrace.GotConnInfo{Conn: clientConn1})
		trace.GotConn(httptrace.GotConnInfo{Conn: clientConn2})
		closeSilently(serverConn1)
		closeSilently(clientConn2)
		closeSilently(serverConn2)
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("payload")),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	client := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}
	var hookCalls atomic.Int32
	var hookedConn net.Conn
	ctx := vision.WithHook(t.Context(), func(conn net.Conn) {
		hookedConn = conn
		hookCalls.Add(1)
	})

	readCloser, remoteAddr, localAddr, err := client.OpenStream(ctx, "https://example.com/download", "session", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(readCloser)
	if remoteAddr == nil || localAddr == nil {
		t.Fatalf("missing connection addresses: remote=%v local=%v", remoteAddr, localAddr)
	}
	if hookCalls.Load() != 1 {
		t.Fatalf("Vision hook called %d times, want 1", hookCalls.Load())
	}
	if hookedConn != firstConn {
		t.Fatal("Vision hook did not receive the first published connection")
	}
	closeSilently(firstConn)
}

func TestDefaultDialerClientOpenStreamWrapsNestedHTTP2StreamError(t *testing.T) {
	streamError := http2.StreamError{
		StreamID: 1,
		Code:     http2.ErrCodeCancel,
	}
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if trace := httptrace.ContextClientTrace(req.Context()); trace != nil && trace.GotConn != nil {
			clientConn, serverConn := net.Pipe()
			trace.GotConn(httptrace.GotConnInfo{Conn: clientConn})
			closeSilently(clientConn)
			closeSilently(serverConn)
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       &errorReadCloser{err: streamError},
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	client := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}

	readCloser, _, _, err := client.OpenStream(t.Context(), "https://example.com/download", "session", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(readCloser)

	_, err = readCloser.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("expected stream error")
	}
	if _, leaked := err.(http2.StreamError); leaked {
		t.Fatal("nested HTTP/2 stream error leaked through XHTTP response")
	}
	var recoveredError http2.StreamError
	if !errors.As(err, &recoveredError) {
		t.Fatalf("wrapped error does not contain HTTP/2 stream error: %v", err)
	}
	if recoveredError.StreamID != streamError.StreamID || recoveredError.Code != streamError.Code {
		t.Fatalf("recovered stream error = %+v, want %+v", recoveredError, streamError)
	}
}

func TestDefaultDialerClientOpenStreamStopsNestedHTTP2ReadLoop(t *testing.T) {
	streamError := http2.StreamError{
		StreamID: 1,
		Code:     http2.ErrCodeCancel,
	}
	responseBody := &countingErrorReadCloser{err: streamError}
	outerTransport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if trace := httptrace.ContextClientTrace(req.Context()); trace != nil && trace.GotConn != nil {
			clientConn, serverConn := net.Pipe()
			trace.GotConn(httptrace.GotConnInfo{Conn: clientConn})
			closeSilently(clientConn)
			closeSilently(serverConn)
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       responseBody,
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	outerClient := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: outerTransport},
		httpVersion: "2",
	}

	outerReader, _, _, err := outerClient.OpenStream(t.Context(), "https://example.com/download", "session", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	outerConn := &splitConn{
		reader:     outerReader,
		writer:     discardWriteCloser{},
		remoteAddr: &net.TCPAddr{},
		localAddr:  &net.TCPAddr{},
	}
	defer closeSilently(outerConn)

	innerTransport := &http2.Transport{
		DialTLSContext: func(context.Context, string, string, *tls.Config) (net.Conn, error) {
			return outerConn, nil
		},
	}
	defer innerTransport.CloseIdleConnections()
	innerClient := &http.Client{Transport: innerTransport}
	result := make(chan error, 1)
	go func() {
		_, requestErr := innerClient.Get("https://dns.example/dns-query")
		result <- requestErr
	}()

	select {
	case err = <-result:
		if err == nil {
			t.Fatal("expected nested HTTP/2 request error")
		}
	case <-time.After(time.Second):
		closeSilently(outerConn)
		t.Fatal("nested HTTP/2 read loop did not stop after XHTTP stream error")
	}
	if reads := responseBody.reads.Load(); reads != 1 {
		t.Fatalf("XHTTP response reads = %d, want 1", reads)
	}
}

func TestDefaultDialerClientPostPacketHonorsContextCancellation(t *testing.T) {
	started := make(chan struct{})
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		close(started)
		<-req.Context().Done()
		return nil, req.Context().Err()
	})
	client := &DefaultDialerClient{
		options: &option.V2RayXHTTPBaseOptions{
			UplinkDataPlacement: option.PlacementBody,
			XPaddingBytes:       Xbadoption.Range{From: 1, To: 1},
		},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}
	ctx, cancel := context.WithCancel(t.Context())
	errCh := make(chan error, 1)
	go func() {
		errCh <- client.PostPacket(ctx, "https://example.com/upload", "session", "1", strings.NewReader("payload"), int64(len("payload")))
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("request did not start")
	}
	cancel()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected cancellation error")
		}
	case <-time.After(time.Second):
		t.Fatal("PostPacket did not honor context cancellation")
	}
}

func TestDefaultDialerClientPostPacketHTTP1DrainsPooledResponse(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	pool := newH1UploadPool()
	h1Conn := NewH1Conn(clientConn)
	h1Conn.UnreadedResponsesCount = 1
	pool.Put(h1Conn)

	client := &DefaultDialerClient{
		options: &option.V2RayXHTTPBaseOptions{
			UplinkDataPlacement: option.PlacementBody,
			XPaddingBytes:       Xbadoption.Range{From: 1, To: 1},
		},
		httpVersion:   "1.1",
		uploadRawPool: pool,
		dialUploadConn: func(ctx context.Context) (net.Conn, error) {
			t.Fatal("unexpected new upload connection")
			return nil, nil
		},
	}

	serverErr := make(chan error, 1)
	go func() {
		if _, err := serverConn.Write([]byte("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")); err != nil {
			serverErr <- err
			return
		}
		request, err := http.ReadRequest(bufio.NewReader(serverConn))
		if err != nil {
			serverErr <- err
			return
		}
		if request.Body != nil {
			_, copyErr := io.Copy(io.Discard, request.Body)
			copyErr = errors.Join(copyErr, request.Body.Close())
			if copyErr != nil {
				serverErr <- copyErr
				return
			}
		}
		serverErr <- nil
	}()

	if err := client.PostPacket(t.Context(), "https://example.com/upload", "session", "1", strings.NewReader("payload"), int64(len("payload"))); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-serverErr:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("server did not receive the next request")
	}
	pooledConn := pool.Get()
	if pooledConn == nil {
		t.Fatal("upload connection was not returned to the pool")
	}
	if pooledConn.UnreadedResponsesCount != 1 {
		t.Fatalf("unread response count = %d", pooledConn.UnreadedResponsesCount)
	}
	if err := pooledConn.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestDefaultDialerClientOpenStreamNonOKDoesNotDrainResponse(t *testing.T) {
	responseBody := newBlockingReadCloser()
	defer close(responseBody.unblockRead)
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if trace := httptrace.ContextClientTrace(req.Context()); trace != nil && trace.GotConn != nil {
			clientConn, serverConn := net.Pipe()
			trace.GotConn(httptrace.GotConnInfo{Conn: clientConn})
			closeSilently(clientConn)
			closeSilently(serverConn)
		}
		return &http.Response{
			Status:     "503 Service Unavailable",
			StatusCode: http.StatusServiceUnavailable,
			Body:       responseBody,
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	client := &DefaultDialerClient{
		options:     &option.V2RayXHTTPBaseOptions{},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}

	readCloser, _, _, err := client.OpenStream(t.Context(), "https://example.com/download", "session", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = readCloser.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected status error")
	}
	select {
	case <-responseBody.closed:
	case <-time.After(time.Second):
		t.Fatal("non-200 response body was not closed")
	}
	select {
	case <-responseBody.read:
		t.Fatal("non-200 response body was drained")
	default:
	}
}

func TestDefaultDialerClientPostPacketNonOKDoesNotDrainResponse(t *testing.T) {
	responseBody := newBlockingReadCloser()
	defer close(responseBody.unblockRead)
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		return &http.Response{
			Status:     "503 Service Unavailable",
			StatusCode: http.StatusServiceUnavailable,
			Body:       responseBody,
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	client := &DefaultDialerClient{
		options: &option.V2RayXHTTPBaseOptions{
			UplinkDataPlacement: option.PlacementBody,
			XPaddingBytes:       Xbadoption.Range{From: 1, To: 1},
		},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}

	err := client.PostPacket(t.Context(), "https://example.com/upload", "session", "1", strings.NewReader("payload"), int64(len("payload")))
	if err == nil {
		t.Fatal("expected status error")
	}
	select {
	case <-responseBody.closed:
	case <-time.After(time.Second):
		t.Fatal("non-200 response body was not closed")
	}
	select {
	case <-responseBody.read:
		t.Fatal("non-200 response body was drained")
	default:
	}
}

func TestDefaultDialerClientCloseClosesPooledHTTP1UploadConnections(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	pool := newH1UploadPool()
	pool.Put(NewH1Conn(clientConn))
	client := &DefaultDialerClient{
		client:        &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) { return nil, nil })},
		uploadRawPool: pool,
	}

	if err := client.Close(); err != nil {
		t.Fatal(err)
	}

	errCh := make(chan error, 1)
	go func() {
		var b [1]byte
		_, err := serverConn.Read(b[:])
		errCh <- err
	}()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected pooled connection to be closed")
		}
	case <-time.After(time.Second):
		t.Fatal("pooled connection was not closed")
	}
}

func TestDefaultDialerClientCloseClosesActiveHTTP1UploadConnections(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	pool := newH1UploadPool()
	if !pool.Track(NewH1Conn(clientConn)) {
		t.Fatal("failed to track active upload connection")
	}
	client := &DefaultDialerClient{
		uploadRawPool: pool,
	}

	if err := client.Close(); err != nil {
		t.Fatal(err)
	}

	errCh := make(chan error, 1)
	go func() {
		var b [1]byte
		_, err := serverConn.Read(b[:])
		errCh <- err
	}()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected active connection to be closed")
		}
	case <-time.After(time.Second):
		t.Fatal("active connection was not closed")
	}
}

func TestDefaultDialerClientCloseClosesActiveTransportConnections(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	tracker := newRawConnTracker()
	if _, err := tracker.Track(clientConn); err != nil {
		t.Fatal(err)
	}
	client := &DefaultDialerClient{rawConns: tracker}

	if err := client.Close(); err != nil {
		t.Fatal(err)
	}

	var buffer [1]byte
	if _, err := serverConn.Read(buffer[:]); err == nil {
		t.Fatal("expected active transport connection to be closed")
	}
}

func TestTrackedRawConnStopsConsecutiveEmptyReads(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	tracker := newRawConnTracker()
	tracked, err := tracker.Track(&emptyReadConn{Conn: clientConn})
	if err != nil {
		t.Fatal(err)
	}
	var buffer [1]byte
	for range maxConsecutiveEmptyReads - 1 {
		if read, readErr := tracked.Read(buffer[:]); read != 0 || readErr != nil {
			t.Fatalf("unexpected early result: read=%d err=%v", read, readErr)
		}
	}
	if read, readErr := tracked.Read(buffer[:]); read != 0 || !errors.Is(readErr, io.ErrNoProgress) {
		t.Fatalf("final result: read=%d err=%v", read, readErr)
	}
}

func TestCreateHTTPClientTracksRawConnBeforeTLSHandshake(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	dialer := &stubNetworkDialer{dial: func(context.Context, string, M.Socksaddr) (net.Conn, error) {
		return &emptyReadConn{Conn: clientConn}, nil
	}}
	tlsConfig := &recordingTLSConfig{fakeTLSConfig: fakeTLSConfig{nextProtos: []string{http2.NextProtoTLS}}}
	client := createHTTPClient(
		t.Context(),
		M.Socksaddr{Fqdn: "example.com", Port: 443},
		dialer,
		&option.V2RayXHTTPBaseOptions{Mode: "packet-up"},
		tlsConfig,
	).(*DefaultDialerClient)
	transport := client.client.Transport.(*http2.Transport)

	conn, err := transport.DialTLSContext(t.Context(), "tcp", "example.com:443", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer closeSilently(conn)
	if _, ok := tlsConfig.handshakeConn.(*trackedRawConn); !ok {
		t.Fatalf("TLS handshake conn type = %T, want *trackedRawConn", tlsConfig.handshakeConn)
	}

	var buffer [1]byte
	if read, readErr := conn.Read(buffer[:]); read != 0 || !errors.Is(readErr, io.ErrNoProgress) {
		t.Fatalf("read through TLS retry loop: read=%d err=%v", read, readErr)
	}
	client.rawConns.access.Lock()
	trackedCount := len(client.rawConns.conns)
	client.rawConns.access.Unlock()
	if trackedCount != 0 {
		t.Fatalf("tracked raw connections after read failure = %d, want 0", trackedCount)
	}
}

func TestCreateHTTPClientReleasesRawConnAfterTLSHandshakeFailure(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer closeSilently(serverConn)

	dialer := &stubNetworkDialer{dial: func(context.Context, string, M.Socksaddr) (net.Conn, error) {
		return clientConn, nil
	}}
	handshakeErr := errors.New("handshake failed")
	tlsConfig := &recordingTLSConfig{
		fakeTLSConfig: fakeTLSConfig{nextProtos: []string{http2.NextProtoTLS}},
		handshakeErr:  handshakeErr,
	}
	client := createHTTPClient(
		t.Context(),
		M.Socksaddr{Fqdn: "example.com", Port: 443},
		dialer,
		&option.V2RayXHTTPBaseOptions{Mode: "packet-up"},
		tlsConfig,
	).(*DefaultDialerClient)
	transport := client.client.Transport.(*http2.Transport)

	if _, err := transport.DialTLSContext(t.Context(), "tcp", "example.com:443", nil); !errors.Is(err, handshakeErr) {
		t.Fatalf("handshake error = %v, want %v", err, handshakeErr)
	}
	client.rawConns.access.Lock()
	trackedCount := len(client.rawConns.conns)
	client.rawConns.access.Unlock()
	if trackedCount != 0 {
		t.Fatalf("tracked raw connections after handshake failure = %d, want 0", trackedCount)
	}
}

func TestXmuxManagerCloseForcesActiveClientClosed(t *testing.T) {
	closed := make(chan struct{}, 1)
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		return &stubDialerClient{close: func() error {
			closed <- struct{}{}
			return nil
		}}
	})
	xmuxClient := xmuxManager.GetXmuxClient(t.Context())
	xmuxClient.AddOpenUsage(1)

	if err := xmuxManager.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("active xmux client survived manager close")
	}
}

func TestClientCloseClosesXmuxManagers(t *testing.T) {
	closed := make(chan struct{}, 1)
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		return &stubDialerClient{
			close: func() error {
				closed <- struct{}{}
				return nil
			},
		}
	})
	_ = xmuxManager.GetXmuxClient(t.Context())

	client := &Client{
		xmuxManager: xmuxManager,
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("xmux client was not closed")
	}
	if _, err := client.DialContext(t.Context()); err == nil {
		t.Fatal("expected closed client to reject new dials")
	}
}

func TestClientResetClosesXmuxManagersWithoutClosingClient(t *testing.T) {
	var created atomic.Int32
	var firstClient *stubDialerClient
	var secondClient *stubDialerClient
	closed := make(chan *stubDialerClient, 2)
	xmuxManager := NewXmuxManager(option.V2RayXHTTPXmuxOptions{}, func() XmuxConn {
		var client *stubDialerClient
		client = &stubDialerClient{
			close: func() error {
				closed <- client
				return nil
			},
		}
		switch created.Add(1) {
		case 1:
			firstClient = client
		case 2:
			secondClient = client
		default:
			t.Fatal("unexpected extra xmux client")
		}
		return client
	})
	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: "stream-one",
		},
		baseRequestURL: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		xmuxManager:    xmuxManager,
	}
	client.getHTTPClient = func() (DialerClient, *XmuxClient, error) {
		xmuxClient := xmuxManager.GetXmuxClient(t.Context())
		httpClient, err := dialerClientFromXmux(xmuxClient)
		return httpClient, xmuxClient, err
	}

	conn, err := client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	if firstClient == nil {
		t.Fatal("first xmux client was not created")
	}

	if err := client.Reset(); err != nil {
		t.Fatal(err)
	}
	select {
	case resetClient := <-closed:
		if resetClient != firstClient {
			t.Fatal("reset closed an unexpected xmux client")
		}
	case <-time.After(time.Second):
		t.Fatal("reset did not close the cached xmux client")
	}

	conn, err = client.DialContext(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	if secondClient == nil {
		t.Fatal("second xmux client was not created after reset")
	}
	if firstClient == secondClient {
		t.Fatal("reset reused the stale xmux client")
	}
}

func TestDefaultDialerClientPostPacketAutoUsesBody(t *testing.T) {
	transport := &captureRoundTripper{}
	client := &DefaultDialerClient{
		options: &option.V2RayXHTTPBaseOptions{
			UplinkDataPlacement: option.PlacementAuto,
			XPaddingBytes:       Xbadoption.Range{From: 1, To: 1},
		},
		client:      &http.Client{Transport: transport},
		httpVersion: "2",
	}

	err := client.PostPacket(context.Background(), "https://example.com/upload", "session", "1", strings.NewReader("payload"), int64(len("payload")))
	if err != nil {
		t.Fatal(err)
	}
	if string(transport.body) != "payload" {
		t.Fatalf("body = %q", string(transport.body))
	}
	if got := transport.request.ContentLength; got != int64(len("payload")) {
		t.Fatalf("content length = %d", got)
	}
	if transport.request.Header.Get("X-Data-0") != "" {
		t.Fatalf("unexpected header payload: %q", transport.request.Header.Get("X-Data-0"))
	}
	for _, cookie := range transport.request.Cookies() {
		if strings.HasPrefix(cookie.Name, "X-Data_") || strings.HasPrefix(cookie.Name, "x_data_") {
			t.Fatalf("unexpected cookie payload: %s", cookie.Name)
		}
	}
}

func TestExtractPacketPayloadAutoConcatenatesSources(t *testing.T) {
	requestURL, err := url.Parse("https://example.com/upload")
	if err != nil {
		t.Fatal(err)
	}
	request := &http.Request{
		Method: "POST",
		URL:    requestURL,
		Header: make(http.Header),
		Body:   io.NopCloser(strings.NewReader("cc")),
	}
	request.Header.Set("X-Data-0", base64.RawURLEncoding.EncodeToString([]byte("aa")))
	request.AddCookie(&http.Cookie{
		Name:  "X-Data_0",
		Value: base64.RawURLEncoding.EncodeToString([]byte("bb")),
	})

	payload, bodyPayload, err := extractPacketPayload(request, option.PlacementAuto, "X-Data", 16)
	if err != nil {
		t.Fatal(err)
	}
	if string(payload) != "aabbcc" {
		t.Fatalf("payload = %q", string(payload))
	}
	if string(bodyPayload) != "cc" {
		t.Fatalf("body payload = %q", string(bodyPayload))
	}
}

func TestExtractPacketPayloadAutoRejectsInvalidHeaderBase64(t *testing.T) {
	requestURL, err := url.Parse("https://example.com/upload")
	if err != nil {
		t.Fatal(err)
	}
	request := &http.Request{
		Method: "POST",
		URL:    requestURL,
		Header: make(http.Header),
		Body:   io.NopCloser(strings.NewReader("cc")),
	}
	request.Header.Set("X-Data-0", "%%%")

	_, _, err = extractPacketPayload(request, option.PlacementAuto, "X-Data", 16)
	if err == nil {
		t.Fatal("expected error")
	}
}
