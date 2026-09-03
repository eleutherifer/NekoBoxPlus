package libcore

import (
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync"
	"testing"
	"time"
)

type recordingSpeedTestListener struct {
	access sync.Mutex
	phases []int32
	update chan struct{}
}

func newRecordingSpeedTestListener() *recordingSpeedTestListener {
	return &recordingSpeedTestListener{update: make(chan struct{}, 32)}
}

func (l *recordingSpeedTestListener) Update(status *SpeedTestStatus) {
	l.access.Lock()
	l.phases = append(l.phases, status.Phase)
	l.access.Unlock()
	select {
	case l.update <- struct{}{}:
	default:
	}
}

func (l *recordingSpeedTestListener) snapshot() []int32 {
	l.access.Lock()
	defer l.access.Unlock()
	return slices.Clone(l.phases)
}

func TestSpeedTestSessionRunsDownloadBeforeUpload(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch {
		case request.URL.Path == "/speedtest/latency.txt":
			_, _ = io.WriteString(writer, "test=test")
		case request.Method == http.MethodPost:
			_, _ = io.Copy(io.Discard, request.Body)
		default:
			block := make([]byte, 64*1024)
			for range 64 {
				if _, err := writer.Write(block); err != nil {
					return
				}
			}
		}
	}))
	defer server.Close()

	listener := newRecordingSpeedTestListener()
	session, err := NewSpeedTestSession(
		nil,
		42,
		1000,
		2,
		SpeedTestServerCustom,
		server.URL,
		SpeedTestFinalAverage,
		listener,
	)
	if err != nil {
		t.Fatal(err)
	}
	session.Start()

	status := session.Status()
	if status.Phase != SpeedTestPhaseComplete {
		t.Fatalf(
			"expected complete phase, got %d: %s (%s)",
			status.Phase,
			status.ErrorCode,
			status.ErrorMessage,
		)
	}
	if status.DownloadedBytes <= 0 {
		t.Fatal("expected downloaded bytes")
	}
	if status.UploadedBytes <= 0 {
		t.Fatal("expected uploaded bytes")
	}
	phases := listener.snapshot()
	downloadIndex := slices.Index(phases, SpeedTestPhaseDownload)
	uploadIndex := slices.Index(phases, SpeedTestPhaseUpload)
	if downloadIndex < 0 || uploadIndex <= downloadIndex {
		t.Fatalf("expected download before upload, got %v", phases)
	}
}

func TestSpeedTestSessionCloseCancelsServerProbe(t *testing.T) {
	probeStarted := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/speedtest/latency.txt" {
			select {
			case <-probeStarted:
			default:
				close(probeStarted)
			}
			<-request.Context().Done()
		}
	}))
	defer server.Close()

	session, err := NewSpeedTestSession(
		nil,
		43,
		1000,
		2,
		SpeedTestServerCustom,
		server.URL,
		SpeedTestFinalAverage,
		nil,
	)
	if err != nil {
		t.Fatal(err)
	}
	go session.Start()
	select {
	case <-probeStarted:
	case <-time.After(time.Second):
		t.Fatal("speed test did not begin probing")
	}

	started := time.Now()
	session.Close()
	if elapsed := time.Since(started); elapsed > 500*time.Millisecond {
		t.Fatalf("cancellation took too long: %s", elapsed)
	}
	if phase := session.Status().Phase; phase != SpeedTestPhaseCancelled {
		t.Fatalf("expected cancelled phase, got %d", phase)
	}
}

func TestNewSpeedTestSessionRejectsInvalidOptions(t *testing.T) {
	tests := []struct {
		name        string
		duration    int32
		connections int32
		mode        int32
		value       string
	}{
		{"short duration", 999, 8, SpeedTestServerAuto, ""},
		{"too many connections", 5000, 17, SpeedTestServerAuto, ""},
		{"empty search", 5000, 8, SpeedTestServerSearch, ""},
		{"invalid server ID", 5000, 8, SpeedTestServerID, "zero"},
		{"invalid custom URL", 5000, 8, SpeedTestServerCustom, "file:///tmp/server"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := NewSpeedTestSession(
				nil,
				1,
				test.duration,
				test.connections,
				test.mode,
				test.value,
				SpeedTestFinalAverage,
				nil,
			); err == nil {
				t.Fatal("expected validation error")
			}
		})
	}
}

func TestAggregateSpeedTestSamples(t *testing.T) {
	samples := []int64{30, 10, 20}
	tests := []struct {
		name string
		mode int32
		want int64
	}{
		{"average", SpeedTestFinalAverage, 20},
		{"last", SpeedTestFinalLast, 20},
		{"minimum", SpeedTestFinalMinimum, 10},
		{"maximum", SpeedTestFinalMaximum, 30},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := aggregateSpeedTestSamples(samples, test.mode); got != test.want {
				t.Fatalf("expected %d, got %d", test.want, got)
			}
		})
	}
}

func TestAppendPositiveSpeedTestSampleIgnoresUninitializedRates(t *testing.T) {
	samples := appendPositiveSpeedTestSample(nil, 0)
	samples = appendPositiveSpeedTestSample(samples, -1)
	samples = appendPositiveSpeedTestSample(samples, 25)
	if !slices.Equal(samples, []int64{25}) {
		t.Fatalf("unexpected samples: %v", samples)
	}
}

func TestShouldCollectSpeedTestSampleSkipsWarmupForLongTests(t *testing.T) {
	tests := []struct {
		name     string
		duration time.Duration
		elapsed  time.Duration
		want     bool
	}{
		{"short test has no warmup", 2 * time.Second, 100 * time.Millisecond, true},
		{"three second test skips early sample", 3 * time.Second, 999 * time.Millisecond, false},
		{"long test skips early sample", 30 * time.Second, 500 * time.Millisecond, false},
		{"sample at warmup boundary is collected", 3 * time.Second, time.Second, true},
		{"sample after warmup is collected", 3 * time.Second, 1100 * time.Millisecond, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := shouldCollectSpeedTestSample(test.duration, test.elapsed); got != test.want {
				t.Fatalf("expected %t, got %t", test.want, got)
			}
		})
	}
}

func TestSpeedTestStaticServerListTimeout(t *testing.T) {
	if speedTestStaticFetchTimeout != 3*speedTestFetchTimeout {
		t.Fatalf(
			"expected static server timeout %s, got %s",
			3*speedTestFetchTimeout,
			speedTestStaticFetchTimeout,
		)
	}
}
