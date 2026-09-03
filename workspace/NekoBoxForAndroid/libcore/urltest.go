package libcore

import (
	"context"
	"errors"
	"fmt"
	"libcore/device"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/urltest"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

func NewInstanceURLTest(config, tag, link string, timeout int32, standard int32, attempts int32, pause int32, hardened bool, localTransport LocalDNSTransport) (latency int32, err error) {
	return runWithPanicError("NewInstanceURLTest", func() (int32, error) {
		return newInstanceURLTest(config, tag, link, timeout, standard, attempts, pause, hardened, localTransport)
	})
}

func newInstanceURLTest(config, tag, link string, timeout int32, standard int32, attempts int32, pause int32, hardened bool, localTransport LocalDNSTransport) (latency int32, err error) {
	instance, err := newSingBoxInstance(config, localTransport, true)
	if err != nil {
		return -1, fmt.Errorf("create service: %w", err)
	}
	closeSynchronously := true
	defer func() {
		if closeSynchronously {
			err = errors.Join(err, instance.Close())
		}
	}()

	acquireProtect()
	defer releaseProtect()

	err = instance.Start()
	if err != nil {
		return -1, fmt.Errorf("start service: %w", err)
	}
	if tag != "" {
		latency, err = instance.urlTest(tag, link, timeout, attempts, pause, hardened)
	} else {
		latency, err = urlTest(instance, link, timeout, standard, attempts, pause, hardened, false)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		closeSynchronously = false
		instance.closeURLTestAsync()
	}
	return latency, err
}

func (b *BoxInstance) urlTest(tag, link string, timeout int32, attempts int32, pause int32, hardened bool) (latency int32, err error) {
	var detour adapter.Outbound
	if tag == "" {
		detour = b.Outbound().Default()
	} else {
		var loaded bool
		detour, loaded = b.Outbound().Outbound(tag)
		if !loaded {
			return -1, fmt.Errorf("%s is not found", tag)
		}
	}

	destination, err := parseURLTestDestination(link)
	if err != nil {
		return -1, err
	}
	return runURLTestAttempts(b.ctx, timeout, attempts, pause, func(ctx context.Context) (int32, error) {
		if err = waitURLTestOutboundReady(ctx, b.Outbound(), detour); err != nil {
			return -1, err
		}
		testDialer := N.Dialer(&fallbackURLTestDialer{
			Dialer:         detour,
			localTransport: b.localDNS,
		})
		var prepared *preparedURLTestConnection
		if hardened {
			prepared, err = b.prepareURLTestConnection(ctx, func(ctx context.Context) (net.Conn, error) {
				return testDialer.DialContext(ctx, "tcp", destination)
			})
			if err != nil {
				return -1, err
			}
			defer prepared.close()
			testDialer = &preparedURLTestDialer{Dialer: testDialer, prepared: prepared}
		}
		result, err := runURLTestAsync(ctx, "sing-box URLTest", func() (uint16, error) {
			return urltest.URLTest(ctx, link, testDialer)
		})
		return int32(result), err
	})
}

func UrlTest(i *BoxInstance, link string, timeout int32, standard int32, attempts int32, pause int32, hardened bool) (latency int32, err error) {
	return runWithPanicError("box.UrlTest", func() (int32, error) {
		return urlTest(i, link, timeout, standard, attempts, pause, hardened, true)
	})
}

func urlTest(i *BoxInstance, link string, timeout int32, standard int32, attempts int32, pause int32, hardened bool, enforceInstanceMinTimeout bool) (latency int32, err error) {
	instance := i
	if i != nil {
		if enforceInstanceMinTimeout && i != mainInstance {
			if timeout < 10000 {
				timeout = 10000
			}
		}
	} else {
		instance = mainInstance
	}

	parentCtx := context.Background()
	if instance != nil {
		parentCtx = instance.ctx
	}
	return runURLTestAttempts(parentCtx, timeout, attempts, pause, func(ctx context.Context) (int32, error) {
		if instance != nil {
			if err = waitURLTestOutboundReady(ctx, instance.Outbound(), instance.Outbound().Default()); err != nil {
				return -1, err
			}
		}
		connections := newURLTestConnectionSet()
		result, err := runURLTestAsync(ctx, "HTTP URLTest", func() (int32, error) {
			return runHTTPURLTest(ctx, instance, connections, link, time.Duration(timeout)*time.Millisecond, urlTestStandard(standard), hardened)
		})
		if errors.Is(err, context.DeadlineExceeded) {
			go connections.closeAllSafely()
		} else {
			connections.closeAllSafely()
		}
		return result, err
	})
}

func runURLTestAttempts(ctx context.Context, timeoutMillis int32, attempts int32, pauseMillis int32, test func(context.Context) (int32, error)) (int32, error) {
	attempts = min(max(attempts, 1), 5)
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	pause := time.Duration(max(pauseMillis, 0)) * time.Millisecond
	operationCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var lastErr error
	for attempt := range attempts {
		latency, err := test(operationCtx)
		if err == nil {
			return latency, nil
		}
		lastErr = err
		if cause := context.Cause(operationCtx); cause != nil {
			return -1, cause
		}
		if attempt == attempts-1 {
			break
		}
		select {
		case <-operationCtx.Done():
			return -1, context.Cause(operationCtx)
		case <-time.After(pause):
		}
	}
	return -1, lastErr
}

func runHTTPURLTest(
	ctx context.Context,
	instance *BoxInstance,
	connections *urlTestConnectionSet,
	link string,
	timeout time.Duration,
	standard int,
	hardened bool,
) (int32, error) {
	var tracker adapter.ConnectionTracker
	if instance != nil && instance.v2api != nil {
		tracker = instance.v2api.StatsService()
	}
	client, err := newURLTestHTTPClient(ctx, instance, tracker, connections, link, timeout, hardened)
	if err != nil {
		return -1, err
	}
	if hardened {
		address, addressErr := urlTestNetworkAddress(link)
		if addressErr != nil {
			return -1, addressErr
		}
		transport := client.Transport.(*http.Transport)
		prepared, prepareErr := instance.prepareURLTestConnection(ctx, func(ctx context.Context) (net.Conn, error) {
			return transport.DialContext(ctx, "tcp", address)
		})
		if prepareErr != nil {
			return -1, prepareErr
		}
		defer prepared.close()
		wrapPreparedURLTestTransport(transport, prepared)
	}
	return speedtest.UrlTest(ctx, client, link, standard)
}

func newURLTestHTTPClient(
	ctx context.Context,
	instance *BoxInstance,
	tracker adapter.ConnectionTracker,
	connections *urlTestConnectionSet,
	link string,
	timeout time.Duration,
	hardened bool,
) (*http.Client, error) {
	if instance == nil {
		addresses, err := lookupURLTestHost(
			ctx,
			link,
			hardened,
			func(context.Context, string) ([]netip.Addr, error) {
				return nil, errors.New("URLTest DNS router is unavailable")
			},
			localURLTestLookup(nil),
		)
		if err != nil {
			return nil, fmt.Errorf("resolve URLTest host: %w", err)
		}
		remaining, err := remainingURLTestTimeout(ctx, timeout)
		if err != nil {
			return nil, err
		}
		client := boxapi.CreateProxyHttpClient(nil, nil, remaining)
		transport := client.Transport.(*http.Transport)
		if len(addresses) > 0 {
			dialContext := transport.DialContext
			transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
				return dialResolvedURLTestAddresses(ctx, network, address, addresses, dialContext)
			}
		}
		trackURLTestConnections(transport, connections, remaining)
		return client, nil
	}

	dnsRouter := service.FromContext[adapter.DNSRouter](instance.ctx)
	var proxyLookup urlTestLookupFunc
	if dnsRouter != nil {
		proxyLookup = func(ctx context.Context, host string) ([]netip.Addr, error) {
			return dnsRouter.Lookup(ctx, host, adapter.DNSQueryOptions{DisableCache: hardened})
		}
	} else {
		proxyLookup = func(context.Context, string) ([]netip.Addr, error) {
			return nil, errors.New("URLTest DNS router is unavailable")
		}
	}
	addresses, err := lookupURLTestHost(
		ctx,
		link,
		hardened,
		proxyLookup,
		localURLTestLookup(instance.localDNS),
	)
	if err != nil {
		return nil, fmt.Errorf("resolve URLTest host: %w", err)
	}
	remaining, err := remainingURLTestTimeout(ctx, timeout)
	if err != nil {
		return nil, err
	}
	client := boxapi.CreateProxyHttpClient(instance.Box, tracker, remaining)
	transport := client.Transport.(*http.Transport)

	if len(addresses) > 0 {
		dialContext := transport.DialContext
		transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			return dialResolvedURLTestAddresses(ctx, network, address, addresses, dialContext)
		}
	}
	trackURLTestConnections(transport, connections, remaining)
	return client, nil
}

func dialResolvedURLTestAddresses(
	ctx context.Context,
	network string,
	address string,
	addresses []netip.Addr,
	dialContext func(context.Context, string, string) (net.Conn, error),
) (net.Conn, error) {
	_, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	var dialErrors []error
	for _, resolvedAddress := range addresses {
		conn, dialErr := dialContext(ctx, network, net.JoinHostPort(resolvedAddress.String(), port))
		if dialErr == nil {
			return conn, nil
		}
		dialErrors = append(dialErrors, dialErr)
	}
	return nil, errors.Join(dialErrors...)
}

type asyncURLTestResult[T any] struct {
	value T
	err   error
}

func runURLTestAsync[T any](ctx context.Context, name string, run func() (T, error)) (T, error) {
	var zero T
	resultChan := make(chan asyncURLTestResult[T], 1)
	go func() {
		value, runErr := runWithPanicError(name, run)
		resultChan <- asyncURLTestResult[T]{value: value, err: runErr}
	}()

	select {
	case <-ctx.Done():
		return zero, context.Cause(ctx)
	case result := <-resultChan:
		if ctx.Err() != nil {
			return zero, context.Cause(ctx)
		}
		return result.value, result.err
	}
}

func remainingURLTestTimeout(ctx context.Context, fallback time.Duration) (time.Duration, error) {
	deadline, hasDeadline := ctx.Deadline()
	if !hasDeadline {
		return fallback, nil
	}
	remaining := time.Until(deadline)
	if remaining <= 0 {
		return 0, context.Cause(ctx)
	}
	return min(remaining, fallback), nil
}

type urlTestLookupFunc func(context.Context, string) ([]netip.Addr, error)

func localURLTestLookup(localTransport LocalDNSTransport) urlTestLookupFunc {
	if localTransport == nil && gLocalDNSTransport != nil {
		localTransport = gLocalDNSTransport.iif
	}
	if localTransport == nil {
		return func(context.Context, string) ([]netip.Addr, error) {
			return nil, errors.New("URLTest system DNS transport is unavailable")
		}
	}
	return func(ctx context.Context, host string) ([]netip.Addr, error) {
		return lookupLocalHost(ctx, localTransport, host)
	}
}

func lookupURLTestHost(
	ctx context.Context,
	link string,
	hardened bool,
	proxyLookup urlTestLookupFunc,
	localLookup urlTestLookupFunc,
) ([]netip.Addr, error) {
	linkURL, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	host := linkURL.Hostname()
	if host == "" {
		return nil, errors.New("URLTest URL has no host")
	}
	if _, err = netip.ParseAddr(host); err == nil {
		return nil, nil
	}
	var lastErr error
	for {
		addresses, proxyErr := runURLTestAsync(ctx, "URLTest proxy DNS lookup", func() ([]netip.Addr, error) {
			return proxyLookup(ctx, host)
		})
		if proxyErr == nil && len(addresses) > 0 {
			return addresses, nil
		}
		if proxyErr == nil {
			proxyErr = errors.New("proxy DNS returned no addresses")
		}
		if ctx.Err() != nil {
			return nil, errors.Join(context.Cause(ctx), proxyErr)
		}

		addresses, localErr := runURLTestAsync(ctx, "URLTest system DNS lookup", func() ([]netip.Addr, error) {
			return localLookup(ctx, host)
		})
		if localErr == nil && len(addresses) > 0 {
			return addresses, nil
		}
		if localErr == nil {
			localErr = errors.New("system DNS returned no addresses")
		}
		lastErr = errors.Join(proxyErr, localErr)
		if !hardened {
			return nil, lastErr
		}
		if ctx.Err() != nil {
			return nil, errors.Join(context.Cause(ctx), lastErr)
		}
	}
}

type fallbackURLTestDialer struct {
	N.Dialer
	localTransport LocalDNSTransport
}

func (d *fallbackURLTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return dialURLTestDestination(ctx, network, destination, d.Dialer.DialContext, d.localTransport)
}

func dialURLTestDestination(
	ctx context.Context,
	network string,
	destination M.Socksaddr,
	dial func(context.Context, string, M.Socksaddr) (net.Conn, error),
	localTransport LocalDNSTransport,
) (net.Conn, error) {
	conn, proxyErr := dial(ctx, network, destination)
	if proxyErr == nil || destination.Fqdn == "" {
		return conn, proxyErr
	}
	if conn != nil {
		_ = conn.Close()
	}
	if ctx.Err() != nil {
		return nil, errors.Join(context.Cause(ctx), proxyErr)
	}

	addresses, localErr := localURLTestLookup(localTransport)(ctx, destination.Fqdn)
	if localErr != nil {
		return nil, errors.Join(proxyErr, fmt.Errorf("resolve URLTest host through system DNS: %w", localErr))
	}

	dialErrors := []error{proxyErr}
	for _, address := range addresses {
		resolvedDestination := M.Socksaddr{
			Addr: address.Unmap(),
			Port: destination.Port,
		}
		conn, dialErr := dial(ctx, network, resolvedDestination)
		if dialErr == nil {
			return conn, nil
		}
		if conn != nil {
			_ = conn.Close()
		}
		dialErrors = append(dialErrors, dialErr)
		if ctx.Err() != nil {
			break
		}
	}
	if len(addresses) == 0 {
		dialErrors = append(dialErrors, errors.New("system DNS returned no addresses"))
	}
	if ctx.Err() != nil {
		dialErrors = append(dialErrors, context.Cause(ctx))
	}
	return nil, errors.Join(dialErrors...)
}

func parseURLTestDestination(link string) (M.Socksaddr, error) {
	if link == "" {
		link = defaultURLTestLink
	}
	linkURL, err := url.Parse(link)
	if err != nil {
		return M.Socksaddr{}, err
	}
	port, err := urlTestPort(linkURL)
	if err != nil {
		return M.Socksaddr{}, err
	}
	host := linkURL.Hostname()
	if host == "" {
		return M.Socksaddr{}, errors.New("URLTest URL has no host")
	}
	return M.ParseSocksaddrHostPort(host, port), nil
}

func urlTestNetworkAddress(link string) (string, error) {
	destination, err := parseURLTestDestination(link)
	if err != nil {
		return "", err
	}
	return destination.String(), nil
}

type preparedURLTestConnection struct {
	access sync.Mutex
	conn   net.Conn
}

func (c *preparedURLTestConnection) take() net.Conn {
	if c == nil {
		return nil
	}
	c.access.Lock()
	conn := c.conn
	c.conn = nil
	c.access.Unlock()
	return conn
}

func (c *preparedURLTestConnection) close() {
	if conn := c.take(); conn != nil {
		_ = conn.Close()
	}
}

type preparedURLTestDialer struct {
	N.Dialer
	prepared *preparedURLTestConnection
}

func (d *preparedURLTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if conn := d.prepared.take(); conn != nil {
		return conn, nil
	}
	return d.Dialer.DialContext(ctx, network, destination)
}

func wrapPreparedURLTestTransport(transport *http.Transport, prepared *preparedURLTestConnection) {
	dialContext := transport.DialContext
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		if conn := prepared.take(); conn != nil {
			return conn, nil
		}
		return dialContext(ctx, network, address)
	}
}

type urlTestDialResult struct {
	conn net.Conn
	err  error
}

func runURLTestDialAsync(ctx context.Context, name string, dial func(context.Context) (net.Conn, error)) (net.Conn, error) {
	resultChan := make(chan urlTestDialResult)
	go func() {
		conn, err := runWithPanicError(name, func() (net.Conn, error) {
			return dial(ctx)
		})
		result := urlTestDialResult{conn: conn, err: err}
		select {
		case resultChan <- result:
		case <-ctx.Done():
			if result.conn != nil {
				_ = result.conn.Close()
			}
		}
	}()

	select {
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	case result := <-resultChan:
		return result.conn, result.err
	}
}

type urlTestReadinessState struct {
	access     sync.Mutex
	ready      bool
	checking   chan struct{}
	generation uint64
}

func (s *urlTestReadinessState) invalidate() {
	s.access.Lock()
	s.ready = false
	s.generation++
	s.access.Unlock()
}

func (b *BoxInstance) prepareURLTestConnection(ctx context.Context, dial func(context.Context) (net.Conn, error)) (*preparedURLTestConnection, error) {
	if b == nil {
		conn, err := waitURLTestConnectionReady(ctx, dial)
		if err != nil {
			return nil, err
		}
		return &preparedURLTestConnection{conn: conn}, nil
	}
	for {
		b.urlTestReady.access.Lock()
		if b.urlTestReady.ready {
			b.urlTestReady.access.Unlock()
			return nil, nil
		}
		if checking := b.urlTestReady.checking; checking != nil {
			b.urlTestReady.access.Unlock()
			select {
			case <-ctx.Done():
				return nil, context.Cause(ctx)
			case <-checking:
				continue
			}
		}
		checking := make(chan struct{})
		generation := b.urlTestReady.generation
		b.urlTestReady.checking = checking
		b.urlTestReady.access.Unlock()

		conn, err := waitURLTestConnectionReady(ctx, dial)
		b.urlTestReady.access.Lock()
		currentGeneration := b.urlTestReady.generation
		if err == nil && currentGeneration == generation {
			b.urlTestReady.ready = true
		}
		b.urlTestReady.checking = nil
		close(checking)
		b.urlTestReady.access.Unlock()
		if err != nil {
			return nil, err
		}
		if currentGeneration != generation {
			_ = conn.Close()
			continue
		}
		return &preparedURLTestConnection{conn: conn}, nil
	}
}

func waitURLTestConnectionReady(ctx context.Context, dial func(context.Context) (net.Conn, error)) (net.Conn, error) {
	var lastErr error
	for {
		conn, err := runURLTestDialAsync(ctx, "URLTest readiness dial", dial)
		if err == nil && conn != nil {
			return conn, nil
		}
		if conn != nil {
			_ = conn.Close()
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = errors.New("URLTest readiness dial returned no connection")
		}
		select {
		case <-ctx.Done():
			return nil, errors.Join(context.Cause(ctx), lastErr)
		case <-time.After(probeRetryDelay):
		}
	}
}

type urlTestConnectionSet struct {
	access      sync.Mutex
	connections map[*urlTestTrackedConn]struct{}
	closed      bool
}

func newURLTestConnectionSet() *urlTestConnectionSet {
	return &urlTestConnectionSet{connections: make(map[*urlTestTrackedConn]struct{})}
}

func (s *urlTestConnectionSet) track(conn net.Conn) net.Conn {
	tracked := &urlTestTrackedConn{Conn: conn, owner: s}
	s.access.Lock()
	if s.closed {
		s.access.Unlock()
		_ = tracked.closeWithoutRemove()
		return tracked
	}
	s.connections[tracked] = struct{}{}
	s.access.Unlock()
	return tracked
}

func (s *urlTestConnectionSet) remove(conn *urlTestTrackedConn) {
	s.access.Lock()
	delete(s.connections, conn)
	s.access.Unlock()
}

func (s *urlTestConnectionSet) closeAll() {
	s.access.Lock()
	s.closed = true
	connections := make([]*urlTestTrackedConn, 0, len(s.connections))
	for conn := range s.connections {
		connections = append(connections, conn)
	}
	clear(s.connections)
	s.access.Unlock()
	for _, conn := range connections {
		_ = conn.closeWithoutRemove()
	}
}

func (s *urlTestConnectionSet) closeAllSafely() {
	defer device.DeferPanicToError("close URLTest connections", nil)
	s.closeAll()
}

type urlTestTrackedConn struct {
	net.Conn
	owner *urlTestConnectionSet
	once  sync.Once
}

func (c *urlTestTrackedConn) Close() error {
	c.owner.remove(c)
	return c.closeWithoutRemove()
}

func (c *urlTestTrackedConn) closeWithoutRemove() (err error) {
	c.once.Do(func() {
		err = c.Conn.Close()
	})
	return err
}

func trackURLTestConnections(transport *http.Transport, connections *urlTestConnectionSet, timeout time.Duration) {
	dialContext := transport.DialContext
	if dialContext == nil {
		dialContext = (&net.Dialer{Timeout: timeout}).DialContext
	}
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		conn, err := dialContext(ctx, network, address)
		if err != nil {
			return nil, err
		}
		return connections.track(conn), nil
	}
}

func (b *BoxInstance) closeURLTestAsync() {
	b.access.Lock()
	cancel := b.cancel
	instanceCtx := b.ctx
	b.access.Unlock()
	if cancel != nil {
		cancel()
	}
	go func() {
		defer device.DeferPanicToError("close timed out URLTest instance", nil)
		if instanceCtx != nil {
			if connectionManager := service.FromContext[adapter.ConnectionManager](instanceCtx); connectionManager != nil {
				go func() {
					defer device.DeferPanicToError("close timed out URLTest connections", nil)
					connectionManager.CloseAll()
				}()
			}
		}
		_ = b.Close()
	}()
}

func urlTestStandard(standard int32) int {
	switch standard {
	case speedtest.UrlTestStandard_Handshake:
		return speedtest.UrlTestStandard_Handshake
	case speedtest.UrlTestStandard_FirstHandshake:
		return speedtest.UrlTestStandard_FirstHandshake
	default:
		return speedtest.UrlTestStandard_RTT
	}
}
