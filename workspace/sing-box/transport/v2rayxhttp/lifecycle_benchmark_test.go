package xhttp

import (
	"bytes"
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
	"time"

	Xbadoption "github.com/sagernet/sing-box/common/xray/json/badoption"
	"github.com/sagernet/sing-box/option"
	"golang.org/x/net/http2"
)

type xhttpLifecycleCase struct {
	name        string
	options     option.V2RayXHTTPOptions
	connections int
	writes      int
	payloadSize int
	postDelay   time.Duration
}

type xhttpLifecycleStats struct {
	httpClients atomic.Int64
	tlsDials    atomic.Int64
	streams     atomic.Int64
	posts       atomic.Int64
	activePosts atomic.Int64
	maxPosts    atomic.Int64
	bytes       atomic.Int64
	errors      atomic.Int64
}

func TestXHTTPPacketUpLifecycleMatrix(t *testing.T) {
	for _, testCase := range xhttpLifecycleCases() {
		t.Run(testCase.name, func(t *testing.T) {
			stats := runXHTTPPacketUpLifecycle(t, t.Context(), testCase)
			t.Logf("http_clients=%d tls_dials=%d streams=%d posts=%d max_active_posts=%d bytes=%d errors=%d",
				stats.httpClients.Load(),
				stats.tlsDials.Load(),
				stats.streams.Load(),
				stats.posts.Load(),
				stats.maxPosts.Load(),
				stats.bytes.Load(),
				stats.errors.Load(),
			)
			if stats.errors.Load() != 0 {
				t.Fatalf("lifecycle errors = %d", stats.errors.Load())
			}
			if stats.streams.Load() != int64(testCase.connections) {
				t.Fatalf("streams = %d, want %d", stats.streams.Load(), testCase.connections)
			}
			if stats.posts.Load() == 0 {
				t.Fatal("no packet-up POST requests observed")
			}
			if stats.tlsDials.Load() == 0 {
				t.Fatal("no HTTP/2 TLS dials observed")
			}
		})
	}
}

func BenchmarkXHTTPPacketUpLifecycle(b *testing.B) {
	for _, testCase := range xhttpLifecycleCases() {
		b.Run(testCase.name, func(b *testing.B) {
			var lastStats *xhttpLifecycleStats
			for b.Loop() {
				stats := runXHTTPPacketUpLifecycle(b, b.Context(), testCase)
				if stats.errors.Load() != 0 {
					b.Fatalf("lifecycle errors = %d", stats.errors.Load())
				}
				lastStats = stats
			}
			if lastStats != nil {
				b.ReportMetric(float64(lastStats.httpClients.Load()), "http-clients/op")
				b.ReportMetric(float64(lastStats.tlsDials.Load()), "tls-dials/op")
				b.ReportMetric(float64(lastStats.streams.Load()), "streams/op")
				b.ReportMetric(float64(lastStats.posts.Load()), "posts/op")
				b.ReportMetric(float64(lastStats.maxPosts.Load()), "max-active-posts/op")
				b.ReportMetric(float64(lastStats.bytes.Load()), "upload-bytes/op")
			}
		})
	}
}

func xhttpLifecycleCases() []xhttpLifecycleCase {
	return []xhttpLifecycleCase{
		{
			name: "xray-defaults",
			options: option.V2RayXHTTPOptions{
				V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
					Path: "/xhttp",
				},
				Mode: "packet-up",
			},
			connections: 8,
			writes:      8,
			payloadSize: 128 * 1024,
			postDelay:   time.Millisecond,
		},
		{
			name: "old-nb4a-overrides",
			options: option.V2RayXHTTPOptions{
				V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
					Path:                 "/xhttp",
					ScMaxEachPostBytes:   &Xbadoption.Range{From: 65536, To: 917504},
					ScMaxBufferedPosts:   4,
					ScMinPostsIntervalMs: &Xbadoption.Range{From: 30, To: 30},
				},
				Mode: "packet-up",
			},
			connections: 8,
			writes:      8,
			payloadSize: 128 * 1024,
			postDelay:   time.Millisecond,
		},
		{
			name: "single-xmux-connection",
			options: option.V2RayXHTTPOptions{
				V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
					Path: "/xhttp",
					Xmux: &option.V2RayXHTTPXmuxOptions{
						MaxConnections: Xbadoption.Range{From: 1, To: 1},
					},
				},
				Mode: "packet-up",
			},
			connections: 8,
			writes:      8,
			payloadSize: 128 * 1024,
			postDelay:   time.Millisecond,
		},
	}
}

func runXHTTPPacketUpLifecycle(tb testing.TB, ctx context.Context, testCase xhttpLifecycleCase) *xhttpLifecycleStats {
	tb.Helper()

	stats := &xhttpLifecycleStats{}
	server := newXHTTPLifecycleServer(tb, stats, testCase.postDelay)
	defer server.Close()

	requestURL, err := url.Parse(server.URL + testCase.options.GetNormalizedPath())
	if err != nil {
		tb.Fatal(err)
	}

	client := newXHTTPLifecycleClient(tb, server, requestURL, testCase.options, stats)
	defer closeSilently(client)

	payload := bytes.Repeat([]byte{'x'}, testCase.payloadSize)
	for range testCase.connections {
		conn, err := client.DialContext(ctx)
		if err != nil {
			tb.Fatal(err)
		}
		for range testCase.writes {
			if _, err = conn.Write(payload); err != nil {
				_ = conn.Close()
				tb.Fatal(err)
			}
		}
		if closeWriter, ok := conn.(interface{ CloseWrite() error }); ok {
			if err = closeWriter.CloseWrite(); err != nil {
				_ = conn.Close()
				tb.Fatal(err)
			}
		}
		waitXHTTPLifecyclePostsIdle(tb, stats, time.Second)
		if err = conn.Close(); err != nil {
			tb.Fatal(err)
		}
	}
	waitXHTTPLifecyclePostsIdle(tb, stats, time.Second)
	return stats
}

func newXHTTPLifecycleServer(tb testing.TB, stats *xhttpLifecycleStats, postDelay time.Duration) *httptest.Server {
	tb.Helper()
	handler := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.Method {
		case http.MethodGet:
			stats.streams.Add(1)
			writer.Header().Set("Cache-Control", "no-store")
			writer.WriteHeader(http.StatusOK)
			if flusher, ok := writer.(http.Flusher); ok {
				flusher.Flush()
			}
			<-request.Context().Done()
		default:
			active := stats.activePosts.Add(1)
			updateXHTTPLifecycleMax(&stats.maxPosts, active)
			defer stats.activePosts.Add(-1)
			stats.posts.Add(1)
			read, err := io.Copy(io.Discard, request.Body)
			if err != nil {
				stats.errors.Add(1)
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
			stats.bytes.Add(read)
			if postDelay > 0 {
				select {
				case <-time.After(postDelay):
				case <-request.Context().Done():
					stats.errors.Add(1)
					return
				}
			}
			writer.WriteHeader(http.StatusOK)
		}
	})
	server := httptest.NewUnstartedServer(handler)
	server.EnableHTTP2 = true
	server.StartTLS()
	return server
}

func newXHTTPLifecycleClient(tb testing.TB, server *httptest.Server, requestURL *url.URL, options option.V2RayXHTTPOptions, stats *xhttpLifecycleStats) *Client {
	tb.Helper()
	xmuxOptions := option.V2RayXHTTPXmuxOptions{}
	if options.Xmux != nil {
		xmuxOptions = *options.Xmux
		if err := xmuxOptions.Normalize(); err != nil {
			tb.Fatal(err)
		}
	}
	manager := NewXmuxManager(xmuxOptions, func() XmuxConn {
		stats.httpClients.Add(1)
		return newXHTTPLifecycleDialerClient(tb, server, &options.V2RayXHTTPBaseOptions, stats)
	})
	client := &Client{
		options:         &options,
		baseRequestURL:  *requestURL,
		baseRequestURL2: *requestURL,
		xmuxManager:     manager,
	}
	client.getHTTPClient = func() (DialerClient, *XmuxClient, error) {
		xmuxClient := manager.GetXmuxClient(context.Background())
		httpClient, err := dialerClientFromXmux(xmuxClient)
		return httpClient, xmuxClient, err
	}
	client.getHTTPClient2 = client.getHTTPClient
	return client
}

func newXHTTPLifecycleDialerClient(tb testing.TB, server *httptest.Server, options *option.V2RayXHTTPBaseOptions, stats *xhttpLifecycleStats) *DefaultDialerClient {
	tb.Helper()
	serverAddress := server.Listener.Addr().String()
	transport := &http2.Transport{
		DialTLSContext: func(ctx context.Context, network string, addr string, cfg *tls.Config) (net.Conn, error) {
			stats.tlsDials.Add(1)
			dialer := tls.Dialer{
				Config: &tls.Config{
					InsecureSkipVerify: true,
					NextProtos:         []string{http2.NextProtoTLS},
				},
			}
			return dialer.DialContext(ctx, "tcp", serverAddress)
		},
		IdleConnTimeout: time.Minute,
	}
	return &DefaultDialerClient{
		options:       options,
		client:        &http.Client{Transport: transport},
		httpVersion:   "2",
		uploadRawPool: newH1UploadPool(),
	}
}

func updateXHTTPLifecycleMax(target *atomic.Int64, value int64) {
	for {
		current := target.Load()
		if value <= current {
			return
		}
		if target.CompareAndSwap(current, value) {
			return
		}
	}
}

func waitXHTTPLifecyclePostsIdle(tb testing.TB, stats *xhttpLifecycleStats, timeout time.Duration) {
	tb.Helper()
	deadline := time.Now().Add(timeout)
	var stableSince time.Time
	var lastPosts int64 = -1
	for time.Now().Before(deadline) {
		posts := stats.posts.Load()
		if stats.activePosts.Load() == 0 {
			if posts == lastPosts {
				if stableSince.IsZero() {
					stableSince = time.Now()
				}
				if time.Since(stableSince) >= 25*time.Millisecond {
					return
				}
			} else {
				lastPosts = posts
				stableSince = time.Time{}
			}
		} else {
			stableSince = time.Time{}
			lastPosts = posts
		}
		time.Sleep(time.Millisecond)
	}
	tb.Fatalf("packet-up posts did not become idle: active=%d posts=%d", stats.activePosts.Load(), stats.posts.Load())
}
