package trusttunnel

import (
	"context"
	stdTLS "crypto/tls"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"runtime"
	"slices"
	"strconv"
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/baderror"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/common/tls"

	"golang.org/x/net/http2"
)

type RoundTripper interface {
	http.RoundTripper
	CloseIdleConnections()
}

type ClientOptions struct {
	Ctx                     context.Context
	Logger                  logger.ContextLogger
	Detour                  N.Dialer
	Server                  M.Socksaddr
	Auth                    auth.User
	TLSConfig               tls.Config
	QUICTLSConfig           tls.Config
	QUIC                    bool
	ForceQUIC               bool
	UseCronetQUIC           bool
	UseCronetHTTPS          bool
	QUICCongestionControl   string
	ClientRandomPrefix      string
	HealthCheck             bool
	TLSServerName           string
	TrustedRootCertificates string
	// UserAgents is the set of user-agent values to send for each request type.
	// Empty values keep the client anonymous by default.
	UserAgents ClientUserAgents
	// ResolveFunc is the function to resolve FQDN for packet conn.
	// If not set, the packet conn will reject FQDN when writing.
	ResolveFunc func(fqdn string) (netip.Addr, error)
}

type ClientUserAgents struct {
	TCPUserAgent         string
	UDPUserAgent         string
	ICMPUserAgent        string
	HealthCheckUserAgent string
}

func NewUserAgentFromAppName(name string) ClientUserAgents {
	return ClientUserAgents{
		TCPUserAgent:         runtime.GOOS + " " + name + "/" + Version,
		UDPUserAgent:         runtime.GOOS + " " + UDPMagicAddress,
		ICMPUserAgent:        runtime.GOOS + " " + ICMPMagicAddress,
		HealthCheckUserAgent: runtime.GOOS,
	}
}

type Client struct {
	ctx               context.Context
	detour            N.Dialer
	server            M.Socksaddr
	originHost        string
	auth              string
	roundTripper      RoundTripper
	roundTripperMu    sync.RWMutex
	recoveryMu        sync.Mutex
	recoveryRunning   bool
	fallbackOnce      sync.Once
	fallbackRoundTrip RoundTripper
	fallbackWrapError func(error) error
	healthCheckTimer  *time.Timer
	wrapError         func(error) error
	timeFunc          func() time.Time
	resolveFunc       func(fqdn string) (netip.Addr, error)
	clientRandomSpec  *clientRandomSpec
	userAgents        ClientUserAgents
	connTracker       *connTracker
}

func NewClient(options ClientOptions) (client *Client, err error) {
	clientRandomSpec, err := parseClientRandomSpec(options.ClientRandomPrefix)
	if err != nil {
		return nil, err
	}
	err = checkCronetAvailable(options)
	if err != nil {
		return nil, err
	}
	optionsLogger := options.Logger
	if optionsLogger == nil {
		optionsLogger = logger.NOP()
		options.Logger = optionsLogger
	}
	client = &Client{
		ctx:              options.Ctx,
		detour:           options.Detour,
		server:           options.Server,
		originHost:       trustTunnelOriginHost(options.Server, options.TLSServerName),
		auth:             buildAuth(options.Auth),
		resolveFunc:      options.ResolveFunc,
		clientRandomSpec: clientRandomSpec,
		userAgents:       options.UserAgents,
		connTracker:      newConnTracker(),
	}
	if options.QUIC {
		quicTLSConfig := options.QUICTLSConfig
		if quicTLSConfig == nil {
			quicTLSConfig = options.TLSConfig
		}
		if options.ForceQUIC {
			quicTLSConfig.SetNextProtos([]string{"h3"})
		} else {
			normalizeClientALPN(options.Ctx, optionsLogger, options.TLSConfig, "h3")
			normalizeClientALPN(options.Ctx, optionsLogger, quicTLSConfig, "h3")
		}
		err = newQUICClientInterface(options).NewQUICRoundTripper(client, options, quicTLSConfig)
		if err != nil {
			return nil, err
		}
		if !options.ForceQUIC {
			fallbackTLSConfig := options.TLSConfig.Clone()
			fallbackTLSConfig.SetNextProtos([]string{http2.NextProtoTLS})
			client.fallbackRoundTrip, client.fallbackWrapError, err = newHTTPSClientInterface(options).NewHTTPSRoundTripper(client, options, fallbackTLSConfig)
			if err != nil {
				return nil, err
			}
		}
		client.timeFunc = ntp.TimeFuncFromContext(options.Ctx)
		if client.timeFunc == nil {
			client.timeFunc = time.Now
		}
	} else {
		normalizeClientALPN(options.Ctx, optionsLogger, options.TLSConfig, http2.NextProtoTLS)
		client.roundTripper, client.wrapError, err = newHTTPSClientInterface(options).NewHTTPSRoundTripper(client, options, options.TLSConfig)
		if err != nil {
			return nil, err
		}
	}
	if options.HealthCheck {
		client.healthCheckTimer = new(time.Timer)
	}
	return client, nil
}

func trustTunnelOriginHost(server M.Socksaddr, tlsServerName string) string {
	serverName := tlsServerName
	if serverName == "" {
		serverName = server.AddrString()
	}
	return net.JoinHostPort(serverName, strconv.Itoa(int(server.Port)))
}

func normalizeClientALPN(ctx context.Context, logger logger.ContextLogger, tlsConfig tls.Config, required string) {
	nextProtos := tlsConfig.NextProtos()
	if len(nextProtos) == 0 {
		tlsConfig.SetNextProtos([]string{required})
		return
	}
	if slices.Contains(nextProtos, required) {
		return
	}
	logger.WarnContext(ctx, "missing required ALPN ", required, ", appending it to trusttunnel client TLS config")
	tlsConfig.SetNextProtos(append(slices.Clone(nextProtos), required))
}

func (c *Client) newH2RoundTripper(tlsConfig tls.Config) (RoundTripper, func(error) error) {
	stdConfig, stdConfigErr := tlsConfig.STDConfig()
	var transport *http2.Transport
	transport = &http2.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string, cfg *stdTLS.Config) (net.Conn, error) {
			conn, err := c.detour.DialContext(ctx, N.NetworkTCP, c.server)
			if err != nil {
				return nil, err
			}
			if c.clientRandomSpec != nil {
				if stdConfigErr == nil {
					tlsConn, err := c.utlsClient(ctx, conn, stdConfig)
					if err != nil {
						_ = conn.Close()
						return nil, err
					}
					return c.connTracker.track(newPassiveRecoveryConn(tlsConn, func(err error) {
						c.schedulePassiveRoundTripperRecovery(transport, err)
					})), nil
				}
				tlsConn, err := tlsConfig.Client(conn)
				if err != nil {
					_ = conn.Close()
					return nil, err
				}
				err = c.setClientRandom(tlsConn)
				if err != nil {
					_ = tlsConn.Close()
					return nil, err
				}
				return c.connTracker.track(newPassiveRecoveryConn(tlsConn, func(err error) {
					c.schedulePassiveRoundTripperRecovery(transport, err)
				})), nil
			}
			tlsConn, err := tlsConfig.Client(conn)
			if err != nil {
				_ = conn.Close()
				return nil, err
			}
			return c.connTracker.track(newPassiveRecoveryConn(tlsConn, func(err error) {
				c.schedulePassiveRoundTripperRecovery(transport, err)
			})), nil
		},
		AllowHTTP:       false,
		IdleConnTimeout: DefaultSessionTimeout,
	}
	return transport, baderror.WrapH2
}

func (c *Client) utlsClient(ctx context.Context, conn net.Conn, stdConfig *stdTLS.Config) (net.Conn, error) {
	config := utlsConfigFromSTD(stdConfig.Clone())
	tlsConn := utls.UClient(conn, config, utls.HelloGolang)
	err := tlsConn.BuildHandshakeState()
	if err != nil {
		return nil, err
	}
	clientRandom, err := c.clientRandomSpec.Generate()
	if err != nil {
		return nil, err
	}
	err = tlsConn.SetClientRandom(clientRandom)
	if err != nil {
		return nil, err
	}
	err = tlsConn.HandshakeContext(ctx)
	if err != nil {
		return nil, err
	}
	return tlsConn, nil
}

type upstreamConn interface {
	Upstream() any
}

type clientRandomConn interface {
	BuildHandshakeState() error
	SetClientRandom([]byte) error
}

func (c *Client) setClientRandom(conn net.Conn) error {
	target := any(conn)
	if upstream, ok := conn.(upstreamConn); ok {
		target = upstream.Upstream()
	}
	randomConn, ok := target.(clientRandomConn)
	if !ok {
		return E.New("TLS config does not support client random injection")
	}
	err := randomConn.BuildHandshakeState()
	if err != nil {
		return err
	}
	clientRandom, err := c.clientRandomSpec.Generate()
	if err != nil {
		return err
	}
	return randomConn.SetClientRandom(clientRandom)
}

func (c *Client) Start() error {
	if c.healthCheckTimer != nil {
		c.healthCheckTimer = time.NewTimer(DefaultHealthCheckTimeout)
		go c.loopHealthCheck()
	}
	return nil
}

func (c *Client) loopHealthCheck() {
	for {
		select {
		case <-c.healthCheckTimer.C:
		case <-c.ctx.Done():
			c.healthCheckTimer.Stop()
			return
		}
		ctx, cancel := context.WithTimeout(c.ctx, DefaultHealthCheckTimeout)
		_ = c.HealthCheck(ctx)
		cancel()
	}
}

func (c *Client) resetHealthCheckTimer() {
	if c.healthCheckTimer == nil {
		return
	}
	c.healthCheckTimer.Reset(DefaultHealthCheckTimeout)
}

func (c *Client) ensureRoundTripper(ctx context.Context) {
	c.roundTripperMu.RLock()
	hasFallback := c.fallbackRoundTrip != nil
	c.roundTripperMu.RUnlock()
	if !hasFallback {
		return
	}
	c.fallbackOnce.Do(func() {
		checkCtx, cancel := context.WithTimeout(ctx, DefaultQuicFallbackProbeTimeout)
		defer cancel()
		roundTripper, wrapError := c.currentRoundTripper()
		err := c.healthCheck(checkCtx, roundTripper, wrapError)
		if err == nil {
			return
		}
		c.roundTripperMu.Lock()
		defer c.roundTripperMu.Unlock()
		if c.fallbackRoundTrip == nil {
			return
		}
		forceCloseAllConnections(c.roundTripper)
		c.roundTripper = c.fallbackRoundTrip
		c.wrapError = c.fallbackWrapError
		c.fallbackRoundTrip = nil
		c.fallbackWrapError = nil
	})
}

func (c *Client) currentRoundTripper() (RoundTripper, func(error) error) {
	c.roundTripperMu.RLock()
	defer c.roundTripperMu.RUnlock()
	return c.roundTripper, c.wrapError
}

func (c *Client) scheduleRoundTripperRecovery(roundTripper RoundTripper, err error) {
	if roundTripper == nil || !shouldRecoverRoundTripper(err) {
		return
	}
	c.scheduleRoundTripperRecoveryNow(roundTripper)
}

func (c *Client) schedulePassiveRoundTripperRecovery(roundTripper RoundTripper, err error) {
	if roundTripper == nil || !shouldRecoverPassiveRoundTripper(err) {
		return
	}
	c.scheduleRoundTripperRecoveryNow(roundTripper)
}

func (c *Client) scheduleRoundTripperRecoveryNow(roundTripper RoundTripper) {
	c.recoveryMu.Lock()
	if c.recoveryRunning {
		c.recoveryMu.Unlock()
		return
	}
	c.recoveryRunning = true
	c.recoveryMu.Unlock()
	go func() {
		defer func() {
			c.recoveryMu.Lock()
			c.recoveryRunning = false
			c.recoveryMu.Unlock()
		}()
		resetRoundTripperConnections(roundTripper)
		c.resetHealthCheckTimer()
	}()
}

func newRequest(originHost, host string, body io.ReadCloser) *http.Request {
	return &http.Request{
		Method: http.MethodConnect,
		URL: &url.URL{
			Scheme: "https",
			Host:   originHost, // HTTP/2 and HTTP/3 reuse connections based on URL.Host.
		},
		Header: make(http.Header),
		Body:   body,
		Host:   host,
	}
}

func (c *Client) Dial(ctx context.Context, destination M.Socksaddr) (net.Conn, error) {
	c.ensureRoundTripper(ctx)
	roundTripper, wrapError := c.currentRoundTripper()
	pipeReader, pipeWriter := io.Pipe()
	host := destination.String()
	request := newRequest(c.originHost, host, pipeReader)
	request.Header.Add("User-Agent", c.userAgents.TCPUserAgent)
	request.Header.Add("Proxy-Authorization", c.auth)
	conn := &tcpConn{
		httpConn: httpConn{
			writer:       pipeWriter,
			wrapError:    wrapError,
			recoveryHook: func(err error) { c.scheduleRoundTripperRecovery(roundTripper, err) },
			created:      make(chan struct{}),
		},
	}
	requestCtx, cancel := context.WithCancel(c.ctx)
	conn.closeHook = cancel
	go func() {
		timeout := time.AfterFunc(DefaultSessionTimeout, cancel)
		defer timeout.Stop()
		response, err := roundTripper.RoundTrip(request.WithContext(requestCtx))
		if err != nil {
			err = wrapError(err)
			c.scheduleRoundTripperRecovery(roundTripper, err)
			_ = pipeWriter.CloseWithError(err)
			_ = pipeReader.CloseWithError(err)
			conn.setUp(nil, err)
		} else if response.StatusCode != http.StatusOK {
			_ = response.Body.Close()
			err = E.New("unexpected status code: ", response.StatusCode)
			_ = pipeWriter.CloseWithError(err)
			_ = pipeReader.CloseWithError(err)
			conn.setUp(nil, err)
		} else {
			c.resetHealthCheckTimer()
			conn.setUp(response.Body, nil)
		}
	}()
	err := conn.waitCreatedContext(ctx)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	return conn, nil
}

func (c *Client) ListenPacket(ctx context.Context) (net.PacketConn, error) {
	c.ensureRoundTripper(ctx)
	roundTripper, wrapError := c.currentRoundTripper()
	pipeReader, pipeWriter := io.Pipe()
	request := newRequest(c.originHost, UDPMagicAddress, pipeReader)
	request.Header.Add("User-Agent", c.userAgents.UDPUserAgent)
	request.Header.Add("Proxy-Authorization", c.auth)
	conn := &clientPacketConn{
		packetConn: packetConn{
			httpConn: httpConn{
				writer:       pipeWriter,
				wrapError:    wrapError,
				recoveryHook: func(err error) { c.scheduleRoundTripperRecovery(roundTripper, err) },
				created:      make(chan struct{}),
			},
			resolveFunc: c.resolveFunc,
		},
	}
	requestCtx, cancel := context.WithCancel(c.ctx)
	conn.closeHook = cancel
	go func() {
		timeout := time.AfterFunc(DefaultSessionTimeout, cancel)
		defer timeout.Stop()
		response, err := roundTripper.RoundTrip(request.WithContext(requestCtx))
		if err != nil {
			err = wrapError(err)
			c.scheduleRoundTripperRecovery(roundTripper, err)
			_ = pipeWriter.CloseWithError(err)
			_ = pipeReader.CloseWithError(err)
			conn.setUp(nil, err)
		} else if response.StatusCode != http.StatusOK {
			_ = response.Body.Close()
			err = E.New("unexpected status code: ", response.StatusCode)
			_ = pipeWriter.CloseWithError(err)
			_ = pipeReader.CloseWithError(err)
			conn.setUp(nil, err)
		} else {
			c.resetHealthCheckTimer()
			conn.setUp(response.Body, nil)
		}
	}()
	err := conn.waitCreatedContext(ctx)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	return conn, nil
}

func (c *Client) ListenICMP(ctx context.Context) (*IcmpConn, error) {
	c.ensureRoundTripper(ctx)
	roundTripper, wrapError := c.currentRoundTripper()
	pipeReader, pipeWriter := io.Pipe()
	request := newRequest(c.originHost, ICMPMagicAddress, pipeReader)
	request.Header.Add("User-Agent", c.userAgents.ICMPUserAgent)
	request.Header.Add("Proxy-Authorization", c.auth)
	conn := &IcmpConn{
		httpConn{
			writer:       pipeWriter,
			wrapError:    wrapError,
			recoveryHook: func(err error) { c.scheduleRoundTripperRecovery(roundTripper, err) },
			created:      make(chan struct{}),
		},
	}
	requestCtx, cancel := context.WithCancel(c.ctx)
	conn.closeHook = cancel
	go func() {
		timeoutTimer := time.AfterFunc(DefaultSessionTimeout, cancel)
		defer timeoutTimer.Stop()
		response, err := roundTripper.RoundTrip(request.WithContext(requestCtx))
		if err != nil {
			err = wrapError(err)
			c.scheduleRoundTripperRecovery(roundTripper, err)
			_ = pipeWriter.CloseWithError(err)
			_ = pipeReader.CloseWithError(err)
			conn.setUp(nil, err)
		} else if response.StatusCode != http.StatusOK {
			_ = response.Body.Close()
			err = E.New("unexpected status code: ", response.StatusCode)
			_ = pipeWriter.CloseWithError(err)
			_ = pipeReader.CloseWithError(err)
			conn.setUp(nil, err)
		} else {
			c.resetHealthCheckTimer()
			conn.setUp(response.Body, nil)
		}
	}()
	err := conn.waitCreatedContext(ctx)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	return conn, nil
}

func (c *Client) Close() error {
	c.roundTripperMu.RLock()
	roundTripper := c.roundTripper
	fallbackRoundTripper := c.fallbackRoundTrip
	c.roundTripperMu.RUnlock()
	forceCloseAllConnections(roundTripper)
	forceCloseAllConnections(fallbackRoundTripper)
	_ = c.connTracker.Close()
	if c.healthCheckTimer != nil {
		c.healthCheckTimer.Stop()
	}
	return nil
}

func (c *Client) ResetConnections() {
	c.roundTripperMu.RLock()
	roundTripper := c.roundTripper
	c.roundTripperMu.RUnlock()
	resetRoundTripperConnections(roundTripper)
	_ = c.connTracker.Close()
	c.resetHealthCheckTimer()
}

func (c *Client) HealthCheck(ctx context.Context) error {
	c.ensureRoundTripper(ctx)
	roundTripper, wrapError := c.currentRoundTripper()
	return c.healthCheck(ctx, roundTripper, wrapError)
}

func (c *Client) healthCheck(ctx context.Context, roundTripper RoundTripper, wrapError func(error) error) error {
	defer c.resetHealthCheckTimer()
	request := &http.Request{
		Method: http.MethodConnect,
		URL: &url.URL{
			Scheme: "https",
			Host:   c.originHost,
		},
		Header: make(http.Header),
		Host:   HealthCheckMagicAddress,
	}
	request.Header.Add("User-Agent", c.userAgents.HealthCheckUserAgent)
	request.Header.Add("Proxy-Authorization", c.auth)
	response, err := roundTripper.RoundTrip(request.WithContext(ctx))
	if err != nil {
		err = wrapError(err)
		c.scheduleRoundTripperRecovery(roundTripper, err)
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return E.New("unexpected status code: ", response.StatusCode)
	}
	return nil
}
