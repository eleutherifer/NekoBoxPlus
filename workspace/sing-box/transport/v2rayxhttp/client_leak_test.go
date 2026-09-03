//go:build xhttpleak

package xhttp

import (
	"context"
	"io"
	"net/url"
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	Xbadoption "github.com/sagernet/sing-box/common/xray/json/badoption"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func TestClientXmuxLeakMatrix(t *testing.T) {
	duration := 2 * time.Minute
	if durationText := os.Getenv("XHTTP_LEAK_TEST_DURATION"); durationText != "" {
		parsedDuration, err := time.ParseDuration(durationText)
		if err != nil {
			t.Fatal(err)
		}
		duration = parsedDuration
	}

	scenarios := []xhttpLeakScenario{
		{name: "stream-one", mode: "stream-one"},
		{name: "stream-up shared xmux", mode: "stream-up"},
		{name: "stream-up split xmux", mode: "stream-up", splitDownloadXmux: true},
		{name: "packet-up shared xmux", mode: "packet-up", writesPerConnection: 3},
		{name: "packet-up split xmux", mode: "packet-up", splitDownloadXmux: true, writesPerConnection: 3},
		{name: "packet-up shared xmux overlapping posts", mode: "packet-up", writesPerConnection: 8, overlapWrites: true},
		{name: "packet-up split xmux overlapping posts", mode: "packet-up", splitDownloadXmux: true, writesPerConnection: 8, overlapWrites: true},
	}
	caseDuration := duration / time.Duration(len(scenarios))
	if caseDuration < time.Second {
		caseDuration = time.Second
	}

	for _, scenario := range scenarios {
		t.Run(scenario.name, func(t *testing.T) {
			runXHTTPLeakScenario(t, scenario, caseDuration)
		})
	}
}

type xhttpLeakScenario struct {
	name                string
	mode                string
	splitDownloadXmux   bool
	writesPerConnection int
	overlapWrites       bool
}

func runXHTTPLeakScenario(t *testing.T, scenario xhttpLeakScenario, duration time.Duration) {
	t.Helper()
	goroutinesBefore := runtime.NumGoroutine()

	xmuxOptions := option.V2RayXHTTPXmuxOptions{
		MaxConcurrency:   Xbadoption.Range{From: 4, To: 4},
		HMaxRequestTimes: Xbadoption.Range{From: 1, To: 1},
	}
	uploadTracker := newTrackedXmuxClients()
	uploadManager := NewXmuxManager(xmuxOptions, uploadTracker.newConn)
	downloadTracker := uploadTracker
	downloadManager := uploadManager
	if scenario.splitDownloadXmux {
		downloadTracker = newTrackedXmuxClients()
		downloadManager = NewXmuxManager(xmuxOptions, downloadTracker.newConn)
	}
	var downloadDest *M.Socksaddr
	if scenario.splitDownloadXmux {
		downloadDest = &M.Socksaddr{Fqdn: "download.example", Port: 443}
	}

	client := &Client{
		options: &option.V2RayXHTTPOptions{
			V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
				Path: "/xhttp",
			},
			Mode: scenario.mode,
		},
		baseRequestURL:  url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		baseRequestURL2: url.URL{Scheme: "https", Host: "example.com", Path: "/xhttp"},
		downloadDest:    downloadDest,
		logger:          log.NewNOPFactory().Logger(),
		xmuxManager:     uploadManager,
	}
	if scenario.splitDownloadXmux {
		client.xmuxManager2 = downloadManager
	}
	client.getHTTPClient = func() (DialerClient, *XmuxClient, error) {
		xmuxClient := uploadManager.GetXmuxClient(t.Context())
		httpClient, err := dialerClientFromXmux(xmuxClient)
		return httpClient, xmuxClient, err
	}
	client.getHTTPClient2 = func() (DialerClient, *XmuxClient, error) {
		xmuxClient := downloadManager.GetXmuxClient(t.Context())
		httpClient, err := dialerClientFromXmux(xmuxClient)
		return httpClient, xmuxClient, err
	}

	deadline := time.Now().Add(duration)
	var iterations int
	for time.Now().Before(deadline) || iterations == 0 {
		iterationStarted := time.Now()
		conn, err := client.DialContext(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		for i := range scenario.writesPerConnection {
			if _, err = conn.Write([]byte("payload")); err != nil {
				t.Fatalf("write %d: %v", i, err)
			}
			if !scenario.overlapWrites {
				uploadTracker.waitForIdle(t, time.Second)
			}
		}
		uploadTracker.waitForIdle(t, time.Second)
		if err = conn.Close(); err != nil {
			t.Fatal(err)
		}
		uploadTracker.waitForIdle(t, time.Second)
		waitXmuxManagerIdle(t, uploadManager, time.Second)
		if scenario.splitDownloadXmux {
			waitXmuxManagerIdle(t, downloadManager, time.Second)
		}
		iterations++
		if remaining := 10*time.Millisecond - time.Since(iterationStarted); remaining > 0 {
			time.Sleep(remaining)
		}
	}

	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	uploadTracker.assertAllClosed(t)
	if scenario.splitDownloadXmux {
		downloadTracker.assertAllClosed(t)
	}
	uploadTracker.assertIdle(t)
	if scenario.splitDownloadXmux {
		downloadTracker.assertIdle(t)
	}
	assertGoroutinesSettled(t, goroutinesBefore, 5*time.Second)
	t.Logf("completed %d %s iterations in %s", iterations, scenario.name, duration)
}

type trackedXmuxClients struct {
	access      sync.Mutex
	clients     []*stubDialerClient
	activePosts atomic.Int32
}

func newTrackedXmuxClients() *trackedXmuxClients {
	return &trackedXmuxClients{}
}

func (t *trackedXmuxClients) newConn() XmuxConn {
	client := &stubDialerClient{}
	client.postPacket = func(ctx context.Context, rawURL string, sessionID string, seqStr string, body io.Reader, contentLength int64) error {
		t.activePosts.Add(1)
		defer t.activePosts.Add(-1)
		if body != nil {
			if _, err := io.Copy(io.Discard, body); err != nil {
				return err
			}
		}
		if !sleepWithContext(ctx, time.Millisecond) {
			return ctx.Err()
		}
		return nil
	}
	t.access.Lock()
	t.clients = append(t.clients, client)
	t.access.Unlock()
	return client
}

func (t *trackedXmuxClients) waitForIdle(tb testing.TB, timeout time.Duration) {
	tb.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if t.activePosts.Load() == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	tb.Fatal("timed out waiting for packet-up posts to finish")
}

func (t *trackedXmuxClients) assertIdle(tb testing.TB) {
	tb.Helper()
	if activePosts := t.activePosts.Load(); activePosts != 0 {
		tb.Fatalf("active packet-up posts = %d", activePosts)
	}
}

func (t *trackedXmuxClients) assertAllClosed(tb testing.TB) {
	tb.Helper()
	t.access.Lock()
	clients := append([]*stubDialerClient(nil), t.clients...)
	t.access.Unlock()
	for index, client := range clients {
		if !client.closed.Load() {
			tb.Fatalf("xmux client %d was not closed", index)
		}
	}
}

func waitXmuxManagerIdle(tb testing.TB, manager *XmuxManager, timeout time.Duration) {
	tb.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if xmuxManagerUsage(manager) == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	tb.Fatalf("xmux manager still has tracked usage: %d", xmuxManagerUsage(manager))
}

func xmuxManagerUsage(manager *XmuxManager) int32 {
	if manager == nil {
		return 0
	}
	manager.mtx.Lock()
	clients := append([]*XmuxClient(nil), manager.xmuxClients...)
	manager.mtx.Unlock()
	var usage int32
	for _, client := range clients {
		usage += client.GetOpenUsage()
		usage += client.GetPacketUsage()
	}
	return usage
}

func assertGoroutinesSettled(tb testing.TB, baseline int, timeout time.Duration) {
	tb.Helper()
	allowed := baseline + 8
	deadline := time.Now().Add(timeout)
	for {
		runtime.GC()
		current := runtime.NumGoroutine()
		if current <= allowed {
			return
		}
		if time.Now().After(deadline) {
			tb.Fatalf("goroutines did not settle: before=%d current=%d allowed=%d", baseline, current, allowed)
		}
		time.Sleep(25 * time.Millisecond)
	}
}
