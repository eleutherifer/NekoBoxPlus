//go:build with_trusttunnel_cronet

package trusttunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/sagernet/cronet-go"
	_ "github.com/sagernet/cronet-go/all"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"libcore/protocol/trusttunnel/sing-trusttunnel/internal/cronetbidistream"
)

const cronetCloseTimeout = 5 * time.Second

var cronetRuntimeGOOS = runtime.GOOS

type cronetRoundTripper struct {
	engine       cronet.Engine
	streamEngine cronet.StreamEngine
	client       *Client
	originURL    string
	forceQUIC    bool
	ctx          context.Context
	cancel       context.CancelFunc
	logger       logger.ContextLogger
	closeOnce    sync.Once
	access       sync.Mutex
	closed       bool
	tracker      closeTracker
}

func checkCronetAvailable(options ClientOptions) error {
	return nil
}

func (t *cronetRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	conn, releaseConn, err := t.newTrackedCronetConnection(request.Context())
	if err != nil {
		return nil, err
	}

	t.monitorConnRecovery(conn)
	connTransferred := false
	releaseConnOnce := sync.OnceFunc(releaseConn)
	defer func() {
		if !connTransferred {
			releaseConnOnce()
		}
	}()
	headers := make(map[string]string, len(request.Header)+2)
	for key, values := range request.Header {
		if len(values) > 0 {
			headers[key] = values[0]
		}
	}
	if request.Host != "" {
		headers["-connect-authority"] = request.Host
	}
	if t.forceQUIC {
		headers["-force-quic"] = "true"
	}
	err = conn.Start(request.Method, t.originURL, headers, 0, false)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	responseHeaders, err := conn.WaitForHeadersContext(request.Context())
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	if request.Body != nil {
		releaseBody := t.tracker.addCloser(request.Body)
		doneWaiter := t.tracker.addWaiter()
		go func() {
			defer doneWaiter()
			defer releaseBody()
			_, copyErr := io.Copy(conn, request.Body)
			_ = request.Body.Close()
			if copyErr != nil {
				_ = conn.Close()
				return
			}
			_ = conn.CloseWrite()
		}()
	}
	status := http.StatusOK
	if statusValue := responseHeaders[":status"]; statusValue != "" {
		if parsedStatus, parseErr := strconv.Atoi(statusValue); parseErr == nil {
			status = parsedStatus
		}
	}
	response := &http.Response{
		StatusCode: status,
		Status:     strconv.Itoa(status) + " " + http.StatusText(status),
		Header:     make(http.Header),
		Body: &trackedReadCloser{
			ReadCloser: conn,
			release:    releaseConnOnce,
		},
		Request:    request,
		Proto:      "HTTP/2.0",
		ProtoMajor: 2,
		ProtoMinor: 0,
	}
	for key, value := range responseHeaders {
		if strings.HasPrefix(key, ":") {
			continue
		}
		response.Header.Set(key, value)
	}
	connTransferred = true
	return response, nil
}

func (t *cronetRoundTripper) newTrackedCronetConnection(ctx context.Context) (*cronetbidistream.BidirectionalConn, func(), error) {
	t.access.Lock()
	defer t.access.Unlock()
	if t.closed {
		return nil, nil, net.ErrClosed
	}
	conn := cronetbidistream.CreateConn(t.streamEngine, ctx, t.logger, true, true)
	return conn, t.trackCronetConnection(conn), nil
}

func (t *cronetRoundTripper) monitorConnRecovery(conn *cronetbidistream.BidirectionalConn) {
	if t.client == nil || conn == nil {
		return
	}
	go func() {
		<-conn.Done()
		err := conn.Err()
		if t.closedForRecovery() || !shouldRecoverCronetPassiveRoundTripper(err) {
			return
		}
		t.client.scheduleRoundTripperRecoveryNow(t)
	}()
}

func (t *cronetRoundTripper) trackCronetConnection(conn *cronetbidistream.BidirectionalConn) func() {
	return t.trackCronetCloser(conn, conn.Destroyed())
}

func (t *cronetRoundTripper) trackCronetCloser(closer io.Closer, destroyed <-chan struct{}) func() {
	releaseConn := t.tracker.addCloser(closer)
	doneWaiter := t.tracker.addWaiter()
	go func() {
		<-destroyed
		doneWaiter()
	}()
	return releaseConn
}

func (t *cronetRoundTripper) closedForRecovery() bool {
	t.access.Lock()
	defer t.access.Unlock()
	return t.closed
}

func (t *cronetRoundTripper) CloseIdleConnections() {
	t.closeAllCronetConnections()
}

func (t *cronetRoundTripper) ResetConnections() {
	t.tracker.closeAll()
	t.closeAllCronetConnections()
	if !t.tracker.wait(cronetCloseTimeout) {
		t.logger.WarnContext(t.ctx, "timeout waiting for TrustTunnel Cronet streams to reset")
	}
}

func (t *cronetRoundTripper) Close() error {
	t.closeOnce.Do(func() {
		t.access.Lock()
		t.closed = true
		t.access.Unlock()
		t.cancel()
		t.tracker.closeAllAndRejectNew()
		t.closeAllCronetConnections()
		if !t.tracker.wait(cronetCloseTimeout) {
			t.logger.WarnContext(t.ctx, "timeout waiting for TrustTunnel Cronet streams to close")
			return
		}
		shutdownDone := make(chan cronetShutdownResult, 1)
		go func() {
			result, ok := t.shutdownCronetEngine()
			if ok && result == cronet.ResultSuccess && shouldDestroyCronetEngineAfterShutdown() {
				t.destroyCronetEngine()
			}
			shutdownDone <- cronetShutdownResult{result: result, ok: ok}
		}()
		select {
		case result := <-shutdownDone:
			if result.ok && result.result != cronet.ResultSuccess {
				t.logger.WarnContext(t.ctx, "failed to shutdown TrustTunnel Cronet engine: ", int(result.result))
			}
		case <-time.After(cronetCloseTimeout):
			t.logger.WarnContext(t.ctx, "timeout shutting down TrustTunnel Cronet engine")
		}
	})
	return nil
}

type cronetShutdownResult struct {
	result cronet.Result
	ok     bool
}

func shouldDestroyCronetEngineAfterShutdown() bool {
	return cronetRuntimeGOOS != "android"
}

func (t *cronetRoundTripper) closeAllCronetConnections() {
	defer t.recoverCronetEnginePanic("close TrustTunnel Cronet connections")
	if t.engine != (cronet.Engine{}) {
		t.engine.CloseAllConnections()
	}
}

func (t *cronetRoundTripper) shutdownCronetEngine() (result cronet.Result, ok bool) {
	defer func() {
		if value := recover(); value != nil {
			ok = false
			t.warnCronetEnginePanic("shutdown TrustTunnel Cronet engine", value)
		}
	}()
	if t.engine == (cronet.Engine{}) {
		return 0, false
	}
	return t.engine.Shutdown(), true
}

func (t *cronetRoundTripper) destroyCronetEngine() {
	defer t.recoverCronetEnginePanic("destroy TrustTunnel Cronet engine")
	if t.engine != (cronet.Engine{}) {
		t.engine.Destroy()
	}
}

func (t *cronetRoundTripper) recoverCronetEnginePanic(action string) {
	if value := recover(); value != nil {
		t.warnCronetEnginePanic(action, value)
	}
}

func (t *cronetRoundTripper) warnCronetEnginePanic(action string, value any) {
	if t.logger != nil {
		t.logger.WarnContext(t.ctx, action, " panic: ", value)
	}
}

func (c *Client) newCronetRoundTripper(options ClientOptions, quic bool) (RoundTripper, error) {
	if c.clientRandomSpec != nil {
		options.Logger.WarnContext(options.Ctx, "client_random_prefix is ignored by TrustTunnel Cronet")
	}
	engine := cronet.NewEngine()
	if options.TrustedRootCertificates != "" && !engine.SetTrustedRootCertificates(options.TrustedRootCertificates) {
		engine.Destroy()
		return nil, E.New("failed to set trusted CA certificates")
	}

	ctx, cancel := context.WithCancel(options.Ctx)
	transport := &cronetRoundTripper{
		ctx:    ctx,
		cancel: cancel,
		engine: engine,
		client: c,
		logger: options.Logger,
	}
	transport.originURL = c.cronetOriginURL(options)
	transport.forceQUIC = quic && options.ForceQUIC
	engine.SetDialer(transport.tcpDialer(ctx, c))
	if quic {
		engine.SetUDPDialer(transport.udpDialer(ctx, c))
	}

	params := cronet.NewEngineParams()
	params.SetUserAgent(c.userAgents.TCPUserAgent)

	if quic && options.ForceQUIC {
		params.SetEnableHTTP2(false)
	} else {
		params.SetEnableHTTP2(true)
	}

	params.SetEnableQuic(quic)
	params.SetEnableBrotli(true)
	if hostResolverRules := c.cronetHostResolverRules(options); hostResolverRules != "" {
		if err := params.SetHostResolverRules(hostResolverRules); err != nil {
			params.Destroy()
			cancel()
			engine.Destroy()
			return nil, err
		}
	}
	if quic {
		if err := params.SetQUICOptions("", cronetCongestionControl(options.QUICCongestionControl), DefaultQuicMaxStreamWindow, DefaultQuicConnectionWindow); err != nil {
			params.Destroy()
			cancel()
			engine.Destroy()
			return nil, err
		}
	} else if err := params.SetHTTP2Options(DefaultQuicConnectionWindow, DefaultQuicConnectionWindow/2); err != nil {
		params.Destroy()
		cancel()
		engine.Destroy()
		return nil, err
	}
	result := engine.StartWithParams(params)
	params.Destroy()
	if result != cronet.ResultSuccess {
		cancel()
		engine.Destroy()
		return nil, E.New("failed to start Cronet engine: ", int(result))
	}

	transport.streamEngine = engine.StreamEngine()
	return transport, nil
}

func (c *Client) cronetOriginURL(options ClientOptions) string {
	return (&url.URL{
		Scheme: "https",
		Host:   c.originHost,
	}).String()
}

func (c *Client) cronetHostResolverRules(options ClientOptions) string {
	serverName := options.TLSServerName
	if serverName == "" || strings.EqualFold(serverName, c.server.AddrString()) {
		return ""
	}
	return "MAP " + serverName + " " + c.server.AddrString()
}

func cronetCongestionControl(name string) string {
	switch name {
	case "bbr":
		return string(cronet.QUICCongestionControlBBR)
	case "bbr2":
		return string(cronet.QUICCongestionControlBBRv2)
	case "cubic":
		return string(cronet.QUICCongestionControlCubic)
	case "reno":
		return string(cronet.QUICCongestionControlReno)
	default:
		return string(cronet.QUICCongestionControlDefault)
	}
}

func (t *cronetRoundTripper) tcpDialer(ctx context.Context, c *Client) cronet.Dialer {
	return func(address string, port uint16) int {
		destination := M.ParseSocksaddrHostPort(address, port)
		conn, err := c.detour.DialContext(ctx, N.NetworkTCP, destination)
		if err != nil {
			return cronetNetError(err).Code()
		}
		if tcpConn, ok := N.CastReader[*net.TCPConn](conn); ok {
			fd, duplicateErr := dupSocketFD(tcpConn)
			if duplicateErr == nil {
				conn.Close()
				return fd
			}
		}
		fd, pipeConn, err := createSocketPair()
		if err != nil {
			conn.Close()
			return cronet.NetErrorConnectionFailed.Code()
		}
		releaseConn := t.tracker.addCloser(conn)
		releasePipeConn := t.tracker.addCloser(pipeConn)
		doneWaiter := t.tracker.addWaiter()
		go func() {
			defer doneWaiter()
			defer releaseConn()
			defer releasePipeConn()
			if err := bufio.CopyConn(ctx, conn, pipeConn); shouldRecoverCronetBridgeRoundTripper(err) && !t.closedForRecovery() {
				c.scheduleRoundTripperRecoveryNow(t)
			}
			_ = conn.Close()
			_ = pipeConn.Close()
		}()
		return fd
	}
}

func (t *cronetRoundTripper) udpDialer(ctx context.Context, c *Client) cronet.UDPDialer {
	return func(address string, port uint16) (int, string, uint16) {
		destination := M.ParseSocksaddrHostPort(address, port)
		conn, err := c.detour.DialContext(ctx, N.NetworkUDP, destination)
		if err != nil {
			return cronetNetError(err).Code(), "", 0
		}
		localAddr := M.SocksaddrFromNet(conn.LocalAddr())
		var localAddress string
		var localPort uint16
		if localAddr.IsValid() {
			localAddress = localAddr.AddrString()
			localPort = localAddr.Port
		}
		if udpConn, ok := N.CastReader[*net.UDPConn](conn); ok {
			fd, duplicateErr := dupSocketFD(udpConn)
			if duplicateErr == nil {
				conn.Close()
				return fd, localAddress, localPort
			}
		}
		fd, pipeConn, err := createPacketSocketPair(false)
		if err != nil {
			conn.Close()
			return cronet.NetErrorConnectionFailed.Code(), "", 0
		}
		remoteAddress := M.SocksaddrFromNet(conn.RemoteAddr())
		packetConn := bufio.NewUnbindPacketConn(conn)
		pipePacketConn := bufio.NewUnbindPacketConnWithAddr(pipeConn.(net.Conn), remoteAddress)
		releaseConn := t.tracker.addCloser(conn)
		releasePipeConn := t.tracker.addCloser(pipeConn)
		doneWaiter := t.tracker.addWaiter()
		go func() {
			defer doneWaiter()
			defer releaseConn()
			defer releasePipeConn()
			if err := bufio.CopyPacketConn(ctx, packetConn, pipePacketConn); shouldRecoverCronetBridgeRoundTripper(err) && !t.closedForRecovery() {
				c.scheduleRoundTripperRecoveryNow(t)
			}
		}()
		return fd, localAddress, localPort
	}
}

func shouldRecoverCronetPassiveRoundTripper(err error) bool {
	if err == nil ||
		errors.Is(err, io.EOF) ||
		errors.Is(err, context.Canceled) {
		return false
	}
	var netError cronet.NetError
	if errors.As(err, &netError) {
		return netError == cronet.NetErrorConnectionClosed ||
			netError == cronet.NetErrorConnectionReset ||
			netError == cronet.NetErrorConnectionAborted ||
			netError == cronet.NetErrorConnectionFailed ||
			netError == cronet.NetErrorConnectionTimedOut ||
			netError == cronet.NetErrorNetworkChanged ||
			netError == cronet.NetErrorInternetDisconnected ||
			netError == cronet.NetErrorQUICProtocolError
	}
	var cronetError *cronet.ErrorGo
	if errors.As(err, &cronetError) {
		switch cronetError.ErrorCode {
		case cronet.ErrorCodeErrorConnectionClosed,
			cronet.ErrorCodeErrorConnectionReset,
			cronet.ErrorCodeErrorConnectionTimedOut,
			cronet.ErrorCodeErrorNetworkChanged,
			cronet.ErrorCodeErrorInternetDisconnected,
			cronet.ErrorCodeErrorQuicProtocolFailed:
			return true
		}
		return cronetError.Retryable
	}
	if errors.Is(err, net.ErrClosed) {
		return false
	}
	return shouldRecoverPassiveRoundTripper(err)
}

func shouldRecoverCronetBridgeRoundTripper(err error) bool {
	if errors.Is(err, io.EOF) {
		return true
	}
	return shouldRecoverCronetPassiveRoundTripper(err)
}

func cronetNetError(err error) cronet.NetError {
	if err == nil {
		return 0
	}
	if urlErr, ok := err.(*url.Error); ok {
		err = urlErr.Err
	}
	switch {
	case strings.Contains(err.Error(), "refused"):
		return cronet.NetErrorConnectionRefused
	case strings.Contains(err.Error(), "timeout"):
		return cronet.NetErrorConnectionTimedOut
	case strings.Contains(err.Error(), "network is unreachable"), strings.Contains(err.Error(), "no route"):
		return cronet.NetErrorAddressUnreachable
	default:
		return cronet.NetErrorConnectionFailed
	}
}

func dupSocketFD(syscallConn syscall.Conn) (int, error) {
	rawConn, err := syscallConn.SyscallConn()
	if err != nil {
		return -1, E.Cause(err, "get syscall conn")
	}
	var fd int
	var controlError error
	err = rawConn.Control(func(fdPtr uintptr) {
		newFD, dupError := syscall.Dup(int(fdPtr))
		if dupError != nil {
			controlError = E.Cause(dupError, "dup socket fd")
			return
		}
		syscall.CloseOnExec(newFD)
		fd = newFD
	})
	if err != nil {
		return -1, E.Cause(err, "control raw conn")
	}
	if controlError != nil {
		return -1, controlError
	}
	return fd, nil
}
