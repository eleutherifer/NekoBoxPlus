package libcore

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Mahdi-zarei/speedtest-go/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/metadata"
)

const (
	SpeedTestServerAuto int32 = iota
	SpeedTestServerID
	SpeedTestServerSearch
	SpeedTestServerCustom
)

const (
	SpeedTestFinalAverage int32 = iota
	SpeedTestFinalLast
	SpeedTestFinalMinimum
	SpeedTestFinalMaximum
)

const (
	SpeedTestPhaseIdle int32 = iota
	SpeedTestPhaseFindingServer
	SpeedTestPhaseDownload
	SpeedTestPhaseUpload
	SpeedTestPhaseComplete
	SpeedTestPhaseError
	SpeedTestPhaseCancelled
)

const (
	speedTestFetchTimeout       = 8 * time.Second
	speedTestStaticFetchTimeout = 3 * speedTestFetchTimeout
	speedTestUpdatePeriod       = 100 * time.Millisecond
	speedTestWarmupDuration     = time.Second
	speedTestWarmupThreshold    = 3 * time.Second
	speedTestProbeLimit         = 16
	speedTestServersURL         = "https://www.speedtest.net/api/js/servers"
	speedTestServersXML         = "https://www.speedtest.net/speedtest-servers-static.php"
	SpeedTestErrorUnknown       = "unknown"
	SpeedTestErrorInvalidConfig = "invalid_configuration"
	SpeedTestErrorNoServer      = "no_server"
	SpeedTestErrorNoReachable   = "no_reachable_server"
	SpeedTestErrorServerList    = "server_list_failed"
	SpeedTestErrorServerTimeout = "server_list_timeout"
	SpeedTestErrorLatency       = "latency_failed"
	SpeedTestErrorDownload      = "download_failed"
	SpeedTestErrorUpload        = "upload_failed"
)

type SpeedTestListener interface {
	Update(status *SpeedTestStatus)
}

type SpeedTestStatus struct {
	RunID               int64
	Phase               int32
	Progress            int32
	DownloadRate        int64
	UploadRate          int64
	DownloadedBytes     int64
	UploadedBytes       int64
	LatencyMilliseconds int32
	ServerName          string
	ServerCountry       string
	UsingProxy          bool
	ErrorCode           string
	ErrorMessage        string
}

type SpeedTestSession struct {
	runID       int64
	duration    time.Duration
	connections int
	serverMode  int32
	serverValue string
	finalResult int32
	listener    SpeedTestListener
	dialContext func(context.Context, string, string) (net.Conn, error)
	usingProxy  bool

	ctx             context.Context
	cancel          context.CancelFunc
	done            chan struct{}
	runOnce         sync.Once
	once            sync.Once
	access          sync.RWMutex
	status          SpeedTestStatus
	downloadSamples []int64
	uploadSamples   []int64
}

func NewSpeedTestSession(
	boxInstance *BoxInstance,
	runID int64,
	durationMillis int32,
	connections int32,
	serverMode int32,
	serverValue string,
	finalResult int32,
	listener SpeedTestListener,
) (*SpeedTestSession, error) {
	if durationMillis < 1000 || durationMillis > 30000 {
		return nil, errors.New("speed test duration must be between 1 and 30 seconds")
	}
	if connections < 1 || connections > 16 {
		return nil, errors.New("speed test connections must be between 1 and 16")
	}
	if serverMode < SpeedTestServerAuto || serverMode > SpeedTestServerCustom {
		return nil, errors.New("invalid speed test server mode")
	}
	if finalResult < SpeedTestFinalAverage || finalResult > SpeedTestFinalMaximum {
		return nil, errors.New("invalid speed test final result mode")
	}
	serverValue = strings.TrimSpace(serverValue)
	switch serverMode {
	case SpeedTestServerID:
		serverID, err := strconv.ParseUint(serverValue, 10, 64)
		if err != nil || serverID == 0 {
			return nil, errors.New("speed test server ID must be a positive number")
		}
	case SpeedTestServerSearch:
		if serverValue == "" {
			return nil, errors.New("speed test server search cannot be empty")
		}
	case SpeedTestServerCustom:
		parsedURL, err := url.Parse(serverValue)
		if err != nil || parsedURL.Host == "" ||
			(parsedURL.Scheme != "http" && parsedURL.Scheme != "https") {
			return nil, errors.New("custom speed test server must be an HTTP or HTTPS URL")
		}
		if parsedURL.User != nil {
			return nil, errors.New("custom speed test server must not contain credentials")
		}
	}

	var dialContext func(context.Context, string, string) (net.Conn, error)
	usingProxy := boxInstance != nil
	if boxInstance == nil {
		dialer := new(net.Dialer)
		dialContext = dialer.DialContext
	} else {
		outbound := boxInstance.Outbound().Default()
		if outbound == nil {
			return nil, errors.New("active proxy has no default outbound")
		}
		dialContext = outboundDialContext(outbound)
	}

	ctx, cancel := context.WithCancel(context.Background())
	session := &SpeedTestSession{
		runID:       runID,
		duration:    time.Duration(durationMillis) * time.Millisecond,
		connections: int(connections),
		serverMode:  serverMode,
		serverValue: serverValue,
		finalResult: finalResult,
		listener:    listener,
		dialContext: dialContext,
		usingProxy:  usingProxy,
		ctx:         ctx,
		cancel:      cancel,
		done:        make(chan struct{}),
		status: SpeedTestStatus{
			RunID:      runID,
			Phase:      SpeedTestPhaseIdle,
			UsingProxy: usingProxy,
		},
	}
	return session, nil
}

func outboundDialContext(outbound adapter.Outbound) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network string, address string) (net.Conn, error) {
		return outbound.DialContext(ctx, network, metadata.ParseSocksaddr(address))
	}
}

func (s *SpeedTestSession) Start() {
	s.runOnce.Do(s.start)
}

func (s *SpeedTestSession) start() {
	defer close(s.done)
	s.publish(func(status *SpeedTestStatus) {
		status.Phase = SpeedTestPhaseFindingServer
		status.Progress = 0
		status.ErrorCode = ""
		status.ErrorMessage = ""
	})

	client := speedtest.New(speedtest.WithUserConfig(&speedtest.UserConfig{
		DialContextFunc: s.dialContext,
		PingMode:        speedtest.HTTP,
		MaxConnections:  s.connections,
	}))
	server, err := s.selectServer(client)
	if err != nil {
		s.finishWithError(err)
		return
	}
	s.publish(func(status *SpeedTestStatus) {
		status.LatencyMilliseconds = int32(server.Latency.Milliseconds())
		status.ServerName = speedTestServerName(server)
		status.ServerCountry = server.Country
	})

	if err = s.runTransferPhase(client, server, SpeedTestPhaseDownload); err != nil {
		s.finishWithError(&speedTestError{code: SpeedTestErrorDownload, cause: err})
		return
	}
	if len(s.downloadSamples) == 0 {
		s.finishWithError(&speedTestError{code: SpeedTestErrorDownload})
		return
	}
	if err = s.runTransferPhase(client, server, SpeedTestPhaseUpload); err != nil {
		s.finishWithError(&speedTestError{code: SpeedTestErrorUpload, cause: err})
		return
	}
	if len(s.uploadSamples) == 0 {
		s.finishWithError(&speedTestError{code: SpeedTestErrorUpload})
		return
	}
	s.publish(func(status *SpeedTestStatus) {
		status.Phase = SpeedTestPhaseComplete
		status.Progress = 100
		status.DownloadRate = aggregateSpeedTestSamples(s.downloadSamples, s.finalResult)
		status.UploadRate = aggregateSpeedTestSamples(s.uploadSamples, s.finalResult)
		status.DownloadedBytes = server.Context.GetTotalDownload()
		status.UploadedBytes = server.Context.GetTotalUpload()
	})
}

func (s *SpeedTestSession) Close() {
	s.once.Do(s.cancel)
	s.runOnce.Do(func() {
		close(s.done)
	})
	<-s.done
}

func (s *SpeedTestSession) Status() *SpeedTestStatus {
	s.access.RLock()
	defer s.access.RUnlock()
	status := s.status
	return &status
}

func (s *SpeedTestSession) runTransferPhase(
	client *speedtest.Speedtest,
	server *speedtest.Server,
	phase int32,
) error {
	phaseCtx, cancel := context.WithTimeout(s.ctx, s.duration)
	defer cancel()
	started := time.Now()
	done := make(chan error, 1)
	go func() {
		if phase == SpeedTestPhaseDownload {
			done <- server.DownloadTestContext(phaseCtx)
		} else {
			done <- server.UploadTestContext(phaseCtx)
		}
	}()
	ticker := time.NewTicker(speedTestUpdatePeriod)
	defer ticker.Stop()
	s.publish(func(status *SpeedTestStatus) {
		status.Phase = phase
		status.Progress = 0
	})

	for {
		select {
		case err := <-done:
			elapsed := time.Since(started)
			s.updateTransferStatus(client, server, phase, 100, elapsed)
			if errors.Is(err, context.Canceled) && s.ctx.Err() != nil {
				return s.ctx.Err()
			}
			return err
		case <-ticker.C:
			elapsed := time.Since(started)
			progress := min(int32(elapsed*100/s.duration), 99)
			s.updateTransferStatus(client, server, phase, progress, elapsed)
		}
	}
}

func (s *SpeedTestSession) updateTransferStatus(
	client *speedtest.Speedtest,
	server *speedtest.Server,
	phase int32,
	progress int32,
	elapsed time.Duration,
) {
	s.publish(func(status *SpeedTestStatus) {
		status.Phase = phase
		status.Progress = progress
		status.DownloadedBytes = client.GetTotalDownload()
		status.UploadedBytes = client.GetTotalUpload()
		switch phase {
		case SpeedTestPhaseDownload:
			rate := int64(max(client.GetEWMADownloadRate(), 0))
			if progress == 100 {
				rate = nonNegativeRate(server.DLSpeed)
			}
			if shouldCollectSpeedTestSample(s.duration, elapsed) {
				s.downloadSamples = appendPositiveSpeedTestSample(s.downloadSamples, rate)
			}
			status.DownloadRate = rate
			if progress == 100 {
				status.DownloadRate = aggregateSpeedTestSamples(s.downloadSamples, s.finalResult)
			}
		case SpeedTestPhaseUpload:
			rate := int64(max(client.GetEWMAUploadRate(), 0))
			if progress == 100 {
				rate = nonNegativeRate(server.ULSpeed)
			}
			if shouldCollectSpeedTestSample(s.duration, elapsed) {
				s.uploadSamples = appendPositiveSpeedTestSample(s.uploadSamples, rate)
			}
			status.UploadRate = rate
			if progress == 100 {
				status.UploadRate = aggregateSpeedTestSamples(s.uploadSamples, s.finalResult)
			}
		}
	})
}

func shouldCollectSpeedTestSample(duration, elapsed time.Duration) bool {
	return duration < speedTestWarmupThreshold || elapsed >= speedTestWarmupDuration
}

func (s *SpeedTestSession) selectServer(client *speedtest.Speedtest) (*speedtest.Server, error) {
	var server *speedtest.Server
	var err error
	switch s.serverMode {
	case SpeedTestServerID:
		ctx, cancel := context.WithTimeout(s.ctx, speedTestFetchTimeout)
		defer cancel()
		server, err = client.FetchServerByIDContext(ctx, s.serverValue)
	case SpeedTestServerCustom:
		server, err = client.CustomServer(s.serverValue)
	default:
		server, err = s.discoverServer(client)
	}
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return nil, err
		}
		if errors.Is(err, speedtest.ErrServerNotFound) {
			return nil, &speedTestError{code: SpeedTestErrorNoServer, cause: err}
		}
		code := SpeedTestErrorServerList
		if errors.Is(err, context.DeadlineExceeded) {
			code = SpeedTestErrorServerTimeout
		}
		return nil, &speedTestError{code: code, cause: err}
	}
	if server.Latency <= 0 {
		ctx, cancel := context.WithTimeout(s.ctx, speedTestFetchTimeout)
		defer cancel()
		server.Latency, err = probeSpeedTestServer(ctx, server)
	}
	if err != nil {
		return nil, &speedTestError{code: SpeedTestErrorLatency, cause: err}
	}
	return server, nil
}

func (s *SpeedTestSession) discoverServer(
	client *speedtest.Speedtest,
) (*speedtest.Server, error) {
	primaryCtx, cancelPrimary := context.WithTimeout(s.ctx, speedTestFetchTimeout)
	servers, err := s.fetchServers(primaryCtx, client, speedTestServersURL, false)
	cancelPrimary()
	if err != nil || len(servers) == 0 {
		staticCtx, cancelStatic := context.WithTimeout(s.ctx, speedTestStaticFetchTimeout)
		servers, err = s.fetchServers(staticCtx, client, speedTestServersXML, true)
		cancelStatic()
	}
	if err != nil {
		code := SpeedTestErrorServerList
		if errors.Is(err, context.DeadlineExceeded) {
			code = SpeedTestErrorServerTimeout
		}
		return nil, &speedTestError{code: code, cause: err}
	}
	if len(servers) == 0 {
		return nil, &speedTestError{code: SpeedTestErrorNoServer}
	}

	ctx, cancelProbe := context.WithTimeout(s.ctx, speedTestFetchTimeout)
	defer cancelProbe()
	probeQueue := make(chan *speedtest.Server)
	results := make(chan *speedtest.Server, len(servers))
	var workers sync.WaitGroup
	for range min(speedTestProbeLimit, len(servers)) {
		workers.Go(func() {
			for server := range probeQueue {
				latency, probeErr := probeSpeedTestServer(ctx, server)
				if probeErr == nil {
					server.Latency = latency
					results <- server
				}
			}
		})
	}
	go func() {
		defer close(probeQueue)
		for _, server := range servers {
			select {
			case probeQueue <- server:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() {
		workers.Wait()
		close(results)
	}()

	var selected *speedtest.Server
	for candidate := range results {
		if selected == nil || candidate.Latency < selected.Latency {
			selected = candidate
		}
	}
	if selected == nil {
		if err := ctx.Err(); err != nil {
			code := SpeedTestErrorNoReachable
			if errors.Is(err, context.DeadlineExceeded) {
				code = SpeedTestErrorLatency
			}
			return nil, &speedTestError{code: code, cause: err}
		}
		return nil, &speedTestError{code: SpeedTestErrorNoReachable}
	}
	return selected, nil
}

func (s *SpeedTestSession) fetchServers(
	ctx context.Context,
	client *speedtest.Speedtest,
	endpoint string,
	xmlPayload bool,
) (speedtest.Servers, error) {
	parsedURL, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if s.serverMode == SpeedTestServerSearch {
		query := parsedURL.Query()
		query.Set("search", s.serverValue)
		parsedURL.RawQuery = query.Encode()
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, parsedURL.String(), nil)
	if err != nil {
		return nil, err
	}
	transport := &http.Transport{DialContext: s.dialContext, ForceAttemptHTTP2: true}
	defer transport.CloseIdleConnections()
	response, err := (&http.Client{Transport: transport}).Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("server list returned HTTP %d", response.StatusCode)
	}

	var servers speedtest.Servers
	if xmlPayload {
		var list speedtest.ServerList
		err = xml.NewDecoder(response.Body).Decode(&list)
		servers = list.Servers
	} else {
		err = json.NewDecoder(response.Body).Decode(&servers)
	}
	if err != nil {
		return nil, err
	}
	if s.serverMode == SpeedTestServerSearch {
		keyword := strings.ToLower(s.serverValue)
		servers = slices.DeleteFunc(servers, func(server *speedtest.Server) bool {
			searchable := strings.ToLower(strings.Join([]string{
				server.Name,
				server.Sponsor,
				server.Country,
				server.Host,
			}, " "))
			return !strings.Contains(searchable, keyword)
		})
	}
	for _, server := range servers {
		server.Context = client
	}
	return servers, nil
}

func (s *SpeedTestSession) finishWithError(err error) {
	if errors.Is(err, context.Canceled) {
		s.publish(func(status *SpeedTestStatus) {
			status.Phase = SpeedTestPhaseCancelled
			status.Progress = 0
		})
		return
	}
	s.publish(func(status *SpeedTestStatus) {
		status.Phase = SpeedTestPhaseError
		status.Progress = 0
		status.ErrorCode = SpeedTestErrorUnknown
		status.ErrorMessage = err.Error()
		if normalized, ok := errors.AsType[*speedTestError](err); ok {
			status.ErrorCode = normalized.code
			if normalized.cause != nil {
				status.ErrorMessage = normalized.cause.Error()
			}
		}
	})
}

type speedTestError struct {
	code  string
	cause error
}

func (e *speedTestError) Error() string {
	if e.cause == nil {
		return e.code
	}
	return fmt.Sprintf("%s: %v", e.code, e.cause)
}

func (e *speedTestError) Unwrap() error {
	return e.cause
}

func appendPositiveSpeedTestSample(samples []int64, sample int64) []int64 {
	if sample <= 0 {
		return samples
	}
	return append(samples, sample)
}

func aggregateSpeedTestSamples(samples []int64, mode int32) int64 {
	if len(samples) == 0 {
		return 0
	}
	switch mode {
	case SpeedTestFinalLast:
		return samples[len(samples)-1]
	case SpeedTestFinalMinimum:
		return slices.Min(samples)
	case SpeedTestFinalMaximum:
		return slices.Max(samples)
	default:
		var total float64
		for _, sample := range samples {
			total += float64(sample)
		}
		return int64(total / float64(len(samples)))
	}
}

func (s *SpeedTestSession) publish(update func(*SpeedTestStatus)) {
	s.access.Lock()
	update(&s.status)
	status := s.status
	s.access.Unlock()
	if s.listener != nil {
		func() {
			defer func() {
				_ = recover()
			}()
			s.listener.Update(&status)
		}()
	}
}

func speedTestServerName(server *speedtest.Server) string {
	parts := make([]string, 0, 2)
	if server.Sponsor != "" && server.Sponsor != "?" {
		parts = append(parts, server.Sponsor)
	}
	if server.Name != "" && server.Name != "?" && server.Name != server.Sponsor {
		parts = append(parts, server.Name)
	}
	if len(parts) == 0 {
		return server.Host
	}
	return strings.Join(parts, " — ")
}

func nonNegativeRate(rate speedtest.ByteRate) int64 {
	return int64(max(float64(rate), 0))
}

func probeSpeedTestServer(ctx context.Context, server *speedtest.Server) (time.Duration, error) {
	latencies, err := server.HTTPPing(ctx, 1, 0, nil)
	if err != nil {
		return 0, err
	}
	if len(latencies) == 0 {
		return 0, errors.New("speed test server returned no latency result")
	}
	var total int64
	for _, latency := range latencies {
		total += latency
	}
	return time.Duration(total / int64(len(latencies))), nil
}
