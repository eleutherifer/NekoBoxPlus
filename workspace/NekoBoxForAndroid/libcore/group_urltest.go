package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
)

const (
	defaultURLTestLink = "https://www.gstatic.com/generate_204"
	probeRetryDelay    = 250 * time.Millisecond
)

var errGroupURLTestStart = errors.New("start group URLTest service")

// GroupURLTester reuses the same test endpoint for every temporary profile
// instance in a group run. Domain endpoints are resolved through each tested
// outbound instead of the underlying network.
type GroupURLTester struct {
	link           string
	host           string
	destination    M.Socksaddr
	timeout        time.Duration
	attempts       int32
	pauseMillis    int32
	hardened       bool
	localTransport LocalDNSTransport
}

func NewGroupURLTester(link string, timeout int32, attempts int32, pause int32, hardened bool, localTransport LocalDNSTransport) (tester *GroupURLTester, err error) {
	return runWithPanicError("NewGroupURLTester", func() (*GroupURLTester, error) {
		return newGroupURLTester(link, timeout, attempts, pause, hardened, localTransport)
	})
}

func newGroupURLTester(link string, timeout int32, attempts int32, pause int32, hardened bool, localTransport LocalDNSTransport) (*GroupURLTester, error) {
	if localTransport == nil {
		return nil, errors.New("group URLTest local DNS transport is unavailable")
	}
	if link == "" {
		link = defaultURLTestLink
	}
	linkURL, err := url.Parse(link)
	if err != nil {
		return nil, fmt.Errorf("parse group URLTest URL: %w", err)
	}
	if linkURL.Scheme != "http" && linkURL.Scheme != "https" {
		return nil, fmt.Errorf("unsupported group URLTest scheme %q", linkURL.Scheme)
	}
	host := linkURL.Hostname()
	if host == "" {
		return nil, errors.New("group URLTest URL has no host")
	}
	port, err := urlTestPort(linkURL)
	if err != nil {
		return nil, err
	}
	probeTimeout := time.Duration(timeout) * time.Millisecond
	if probeTimeout <= 0 {
		return nil, errors.New("group URLTest timeout must be positive")
	}

	return &GroupURLTester{
		link:           link,
		host:           host,
		destination:    M.ParseSocksaddrHostPort(host, port),
		timeout:        probeTimeout,
		attempts:       attempts,
		pauseMillis:    pause,
		hardened:       hardened,
		localTransport: localTransport,
	}, nil
}

func (t *GroupURLTester) Test(config, tag string) (latency int32, err error) {
	return runWithPanicError("GroupURLTester.Test", func() (int32, error) {
		return t.test(config, tag)
	})
}

func (t *GroupURLTester) test(config, tag string) (int32, error) {
	if t == nil || !t.destination.IsValid() {
		return -1, errors.New("group URLTester is not initialized")
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		groupURLTestProfileBudget(t.timeout, t.attempts, t.pauseMillis),
	)
	defer cancel()
	return runGroupURLTestOperation(ctx, func() (int32, error) {
		return runGroupURLTestStartRetry(ctx, func() (int32, error) {
			return t.testProfile(ctx, config, tag)
		})
	})
}

func (t *GroupURLTester) testProfile(ctx context.Context, config, tag string) (latency int32, err error) {
	instance, err := newSingBoxInstanceWithProtectContext(ctx, config, t.localTransport, true, true)
	if err != nil {
		return -1, fmt.Errorf("create group URLTest service: %w", err)
	}
	closeSynchronously := true
	defer func() {
		if closeSynchronously {
			err = errors.Join(err, instance.Close())
		}
	}()

	acquireProtect()
	defer releaseProtect()
	if err = instance.Start(); err != nil {
		return -1, fmt.Errorf("%w: %w", errGroupURLTestStart, err)
	}
	detour, err := instance.urlTestOutbound(tag)
	if err != nil {
		return -1, err
	}
	readyCtx, cancelReady := context.WithTimeout(ctx, t.timeout)
	err = waitURLTestOutboundReady(readyCtx, instance.Outbound(), detour)
	cancelReady()
	if err != nil {
		return -1, err
	}
	latency, err = t.testWithRetry(ctx, instance, detour)
	if errors.Is(err, context.DeadlineExceeded) {
		closeSynchronously = false
		instance.closeURLTestAsync()
	}
	return latency, err
}

func groupURLTestProfileBudget(timeout time.Duration, attempts int32, pauseMillis int32) time.Duration {
	attempts = min(max(attempts, 1), 5)
	pause := time.Duration(max(pauseMillis, 0)) * time.Millisecond
	return timeout*time.Duration(attempts) + pause*time.Duration(attempts-1)
}

func runGroupURLTestOperation(ctx context.Context, test func() (int32, error)) (int32, error) {
	latency, err := runURLTestAsync(ctx, "group URLTest profile", test)
	if err != nil {
		return -1, err
	}
	return latency, nil
}

func runGroupURLTestStartRetry(ctx context.Context, test func() (int32, error)) (int32, error) {
	for attempt := range 2 {
		latency, err := test()
		if err == nil || !errors.Is(err, errGroupURLTestStart) || attempt == 1 {
			return latency, err
		}
		select {
		case <-ctx.Done():
			return -1, context.Cause(ctx)
		case <-time.After(probeRetryDelay):
		}
	}
	panic("unreachable")
}

func (b *BoxInstance) urlTestOutbound(tag string) (adapter.Outbound, error) {
	if tag == "" {
		detour := b.Outbound().Default()
		if detour == nil {
			return nil, errors.New("group URLTest default outbound is unavailable")
		}
		return detour, nil
	}
	detour, loaded := b.Outbound().Outbound(tag)
	if !loaded {
		return nil, fmt.Errorf("group URLTest outbound %q is not found", tag)
	}
	return detour, nil
}

func (t *GroupURLTester) testWithRetry(ctx context.Context, instance *BoxInstance, detour N.Dialer) (int32, error) {
	return runURLTestAttempts(ctx, int32(t.timeout/time.Millisecond), t.attempts, t.pauseMillis, func(ctx context.Context) (int32, error) {
		return t.testOnce(ctx, instance, detour)
	})
}

func (t *GroupURLTester) testOnce(ctx context.Context, instance *BoxInstance, detour N.Dialer) (int32, error) {
	connections := newURLTestConnectionSet()
	defer connections.closeAllSafely()
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			conn, err := dialURLTestDestination(
				ctx,
				network,
				t.destination,
				detour.DialContext,
				t.localTransport,
			)
			if err != nil {
				return nil, err
			}
			return connections.track(conn), nil
		},
		TLSClientConfig: &tls.Config{
			ServerName: t.host,
			Time:       ntp.TimeFuncFromContext(ctx),
			RootCAs:    adapter.RootPoolFromContext(ctx),
		},
	}
	if t.hardened {
		prepared, err := instance.prepareURLTestConnection(ctx, func(ctx context.Context) (net.Conn, error) {
			return transport.DialContext(ctx, "tcp", t.destination.String())
		})
		if err != nil {
			return -1, err
		}
		defer prepared.close()
		wrapPreparedURLTestTransport(transport, prepared)
	}
	remaining, err := remainingURLTestTimeout(ctx, t.timeout)
	if err != nil {
		return -1, err
	}
	client := &http.Client{Transport: transport, Timeout: remaining}
	result, err := runURLTestAsync(ctx, "group HTTP URLTest", func() (int32, error) {
		return speedtest.URLTest(ctx, client, t.link)
	})
	if err != nil {
		return -1, err
	}
	return result, nil
}

func urlTestPort(linkURL *url.URL) (uint16, error) {
	port := linkURL.Port()
	if port == "" {
		if linkURL.Scheme == "http" {
			return 80, nil
		}
		return 443, nil
	}
	value, err := strconv.ParseUint(port, 10, 16)
	if err != nil || value == 0 {
		return 0, fmt.Errorf("invalid group URLTest port %q", port)
	}
	return uint16(value), nil
}

func shouldRetryProbe(err error, elapsed, timeout time.Duration) bool {
	if err == nil || errors.Is(err, context.Canceled) || errors.Is(err, syscall.ECONNREFUSED) {
		return false
	}
	fastLimit := min(timeout/2, time.Second)
	if fastLimit <= 0 || elapsed >= fastLimit {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) ||
		errors.Is(err, syscall.ENETDOWN) ||
		errors.Is(err, syscall.ENETUNREACH) ||
		errors.Is(err, syscall.EHOSTUNREACH) ||
		errors.Is(err, syscall.ECONNRESET) {
		return true
	}
	if _, ok := errors.AsType[*net.DNSError](err); ok {
		return true
	}
	if networkErr, ok := errors.AsType[net.Error](err); ok {
		return networkErr.Timeout() || networkErr.Temporary()
	}
	return false
}

func TcpPing(host, port string, timeout int32, hardened bool, localTransport LocalDNSTransport) (latency int32, err error) {
	result, err := TcpPingWithAddress(host, port, timeout, hardened, localTransport)
	if err != nil {
		return -1, err
	}
	return result.latency, nil
}

func TcpPingWithAddress(host, port string, timeout int32, hardened bool, localTransport LocalDNSTransport) (result *PingResult, err error) {
	return runWithPanicError("TCPPing", func() (*PingResult, error) {
		return tcpPingWithAddress(host, port, timeout, hardened, localTransport)
	})
}

func tcpPingWithAddress(host, port string, timeout int32, hardened bool, localTransport LocalDNSTransport) (*PingResult, error) {
	if host == "" {
		return nil, errors.New("TCP ping host is empty")
	}
	portNumber, parseErr := strconv.ParseUint(port, 10, 16)
	if parseErr != nil || portNumber == 0 {
		return nil, fmt.Errorf("invalid TCP ping port %q", port)
	}
	probeTimeout := time.Duration(timeout) * time.Millisecond
	if probeTimeout <= 0 {
		return nil, errors.New("TCP ping timeout must be positive")
	}
	address := net.JoinHostPort(host, port)
	dialer := &net.Dialer{Control: protectSocketControl}

	acquireProtect()
	defer releaseProtect()
	if hardened {
		ctx, cancel := context.WithTimeout(context.Background(), probeTimeout)
		defer cancel()
		addresses, lookupErr := resolveHardenedTCPPingHost(ctx, host, localTransport)
		if lookupErr != nil {
			return nil, fmt.Errorf("resolve TCP ping host: %w", lookupErr)
		}
		return runHardenedTCPPing(ctx, addresses, port, dialer.DialContext)
	}
	for attempt := range 2 {
		ctx, cancel := context.WithTimeout(context.Background(), probeTimeout)
		started := time.Now()
		latency, pingErr := speedtest.TCPPing(ctx, dialer.DialContext, address)
		cancel()
		if pingErr == nil {
			return &PingResult{latency: latency, address: strings.Trim(host, "[]")}, nil
		}
		if attempt == 1 || !shouldRetryProbe(pingErr, time.Since(started), probeTimeout) {
			return nil, pingErr
		}
		time.Sleep(probeRetryDelay)
	}
	panic("unreachable")
}

func runHardenedTCPPing(
	ctx context.Context,
	addresses []netip.Addr,
	port string,
	dial func(context.Context, string, string) (net.Conn, error),
) (*PingResult, error) {
	var lastErr error
	for {
		for _, resolvedAddress := range addresses {
			dialAddress := net.JoinHostPort(resolvedAddress.String(), port)
			latency, err := speedtest.TCPPing(ctx, dial, dialAddress)
			if err == nil {
				return &PingResult{latency: latency, address: resolvedAddress.String()}, nil
			}
			lastErr = err
			if ctx.Err() != nil {
				return nil, errors.Join(context.Cause(ctx), lastErr)
			}
		}
		select {
		case <-ctx.Done():
			return nil, errors.Join(context.Cause(ctx), lastErr)
		case <-time.After(probeRetryDelay):
		}
	}
}

func resolveHardenedTCPPingHost(ctx context.Context, host string, localTransport LocalDNSTransport) ([]netip.Addr, error) {
	if address, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{address.Unmap()}, nil
	}
	if localTransport == nil {
		return nil, errors.New("TCP ping local DNS transport is unavailable")
	}
	var lastErr error
	for {
		addresses, err := lookupLocalHost(ctx, localTransport, host)
		if err == nil && len(addresses) > 0 {
			return addresses, nil
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = errors.New("local DNS returned no addresses")
		}
		if ctx.Err() != nil {
			return nil, errors.Join(context.Cause(ctx), lastErr)
		}
	}
}

func lookupLocalHost(ctx context.Context, localTransport LocalDNSTransport, host string) ([]netip.Addr, error) {
	done := make(chan struct{})
	exchange := &ExchangeContext{
		context: ctx,
		done:    sync.OnceFunc(func() { close(done) }),
	}
	if err := localTransport.Lookup(exchange, "ip", host); err != nil {
		return nil, err
	}
	select {
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	case <-done:
		if exchange.error != nil {
			return nil, exchange.error
		}
		if len(exchange.addresses) == 0 {
			return nil, errors.New("local DNS returned no addresses")
		}
		return slices.Clone(exchange.addresses), nil
	}
}
