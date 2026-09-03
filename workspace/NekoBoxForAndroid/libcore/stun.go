package libcore

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"libcore/stun"
)

const (
	StunMaxServers = 8

	StunIPv6Disable int32 = iota
	StunIPv6Enable
	StunIPv6Prefer
	StunIPv6Only
)

const (
	StunBehaviorUnknown        = int32(stun.BehaviorTypeUnknown)
	StunBehaviorEndpoint       = int32(stun.BehaviorTypeEndpoint)
	StunBehaviorAddress        = int32(stun.BehaviorTypeAddr)
	StunBehaviorAddressAndPort = int32(stun.BehaviorTypeAddrAndPort)

	StunNatError                = int32(stun.NATError)
	StunNatUnknown              = int32(stun.NATUnknown)
	StunNatNone                 = int32(stun.NATNone)
	StunNatBlocked              = int32(stun.NATBlocked)
	StunNatFull                 = int32(stun.NATFull)
	StunNatSymmetric            = int32(stun.NATSymmetric)
	StunNatRestricted           = int32(stun.NATRestricted)
	StunNatPortRestricted       = int32(stun.NATPortRestricted)
	StunNatSymmetricUDPFirewall = int32(stun.SymmetricUDPFirewall)
)

const stunTestTimeout = 30 * time.Second

var (
	errStunCancelled = errors.New("STUN test cancelled")
	errStunTimeout   = errors.New("STUN test deadline exceeded")
)

type StunServerResult struct {
	Server               string
	BindingSuccess       bool
	BehaviorSupported    bool
	BehaviorComplete     bool
	NatType              int32
	MappingBehavior      int32
	FilteringBehavior    int32
	ExternalAddress      string
	ExternalPort         int32
	IpFamily             int32
	DurationMilliseconds int64
	ErrorCode            string
	ErrorMessage         string
	WarningCode          string
}

type StunTestResult struct {
	results []*StunServerResult
}

func (r *StunTestResult) Count() int {
	if r == nil {
		return 0
	}
	return len(r.results)
}

func (r *StunTestResult) Get(index int) *StunServerResult {
	if r == nil || index < 0 || index >= len(r.results) {
		return nil
	}
	return r.results[index]
}

type StunTestSession struct {
	ctx           context.Context
	cancel        context.CancelCauseFunc
	cancelTimeout context.CancelFunc
	closeOnce     sync.Once
	runOnce       sync.Once
	access        sync.Mutex
	servers       []string
	connections   map[net.PacketConn]struct{}
	result        *StunTestResult
	ipv6Mode      int32
	running       atomic.Bool
	runServer     func(context.Context, string) *StunServerResult
	lookupIP      func(context.Context, string, string) ([]netip.Addr, error)
}

func NewStunTestSession(ipv6Mode int32) *StunTestSession {
	parentCtx, cancel := context.WithCancelCause(context.Background())
	ctx, cancelTimeout := context.WithTimeoutCause(parentCtx, stunTestTimeout, errStunTimeout)
	if ipv6Mode < StunIPv6Disable || ipv6Mode > StunIPv6Only {
		ipv6Mode = StunIPv6Disable
	}
	session := &StunTestSession{
		ctx:           ctx,
		cancel:        cancel,
		cancelTimeout: cancelTimeout,
		connections:   make(map[net.PacketConn]struct{}),
		ipv6Mode:      ipv6Mode,
		lookupIP:      net.DefaultResolver.LookupNetIP,
	}
	session.runServer = session.testServer
	return session
}

func (s *StunTestSession) AddServer(address string) error {
	if s == nil {
		return errors.New("nil STUN test session")
	}
	address = strings.TrimSpace(address)
	if err := validateStunServer(address); err != nil {
		return err
	}
	s.access.Lock()
	defer s.access.Unlock()
	if s.running.Load() {
		return errors.New("STUN test already started")
	}
	for _, server := range s.servers {
		if strings.EqualFold(server, address) {
			return nil
		}
	}
	if len(s.servers) >= StunMaxServers {
		return errors.New("too many STUN servers")
	}
	s.servers = append(s.servers, address)
	return nil
}

func (s *StunTestSession) Run() *StunTestResult {
	if s == nil {
		return &StunTestResult{}
	}
	s.runOnce.Do(func() {
		s.running.Store(true)
		s.access.Lock()
		servers := slices.Clone(s.servers)
		s.access.Unlock()
		results := make([]*StunServerResult, len(servers))
		var waitGroup sync.WaitGroup
		for index, server := range servers {
			waitGroup.Go(func() {
				results[index] = s.runServer(s.ctx, server)
			})
		}
		waitGroup.Wait()
		s.result = &StunTestResult{results: results}
		s.running.Store(false)
		s.cancelTimeout()
	})
	if s.result == nil {
		return &StunTestResult{}
	}
	return s.result
}

func (s *StunTestSession) Close() {
	if s == nil {
		return
	}
	s.closeOnce.Do(func() {
		s.cancel(errStunCancelled)
		s.cancelTimeout()
		s.access.Lock()
		connections := make([]net.PacketConn, 0, len(s.connections))
		for connection := range s.connections {
			connections = append(connections, connection)
		}
		s.access.Unlock()
		for _, connection := range connections {
			_ = connection.Close()
		}
	})
}

func validateStunServer(address string) error {
	host, portText, err := net.SplitHostPort(address)
	if err != nil || strings.TrimSpace(host) == "" {
		return errors.New("STUN server must use host:port format")
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return errors.New("STUN server port is invalid")
	}
	return nil
}

func (s *StunTestSession) testServer(ctx context.Context, address string) *StunServerResult {
	started := time.Now()
	result := &StunServerResult{
		Server:            address,
		NatType:           StunNatUnknown,
		MappingBehavior:   StunBehaviorUnknown,
		FilteringBehavior: StunBehaviorUnknown,
	}
	defer func() {
		result.DurationMilliseconds = time.Since(started).Milliseconds()
	}()

	network, serverAddress, err := resolveStunServer(ctx, address, s.ipv6Mode, s.lookupIP)
	if err != nil {
		setStunError(result, ctx, err)
		return result
	}
	connection, err := net.ListenUDP(network, nil)
	if err != nil {
		setStunError(result, ctx, err)
		return result
	}
	s.trackConnection(connection)
	stopClose := context.AfterFunc(ctx, func() {
		_ = connection.Close()
	})
	defer func() {
		stopClose()
		s.untrackConnection(connection)
		_ = connection.Close()
	}()

	client := stun.NewClientWithConnection(connection)
	client.SetResponseAddressMismatchAllowed(true)
	defer func() {
		if client.ResponseAddressMismatch() {
			result.WarningCode = "response_address_mismatch"
		}
	}()
	client.SetServerAddr(serverAddress.String())
	natType, host, discoverErr := client.Discover()
	result.NatType = int32(natType)
	if host != nil {
		result.BindingSuccess = true
		result.ExternalAddress = host.IP()
		result.ExternalPort = int32(host.Port())
		result.IpFamily = int32(host.Family())
	}
	if discoverErr != nil || !result.BindingSuccess {
		if discoverErr == nil {
			discoverErr = errors.New("STUN server did not return a mapped address")
		}
		setStunError(result, ctx, discoverErr)
		return result
	}

	behavior, behaviorErr := client.BehaviorTest()
	if behavior != nil {
		result.MappingBehavior = int32(behavior.MappingType)
		result.FilteringBehavior = int32(behavior.FilteringType)
	}
	result.BehaviorSupported = !errors.Is(behaviorErr, stun.ErrBehaviorDiscoveryUnsupported)
	result.BehaviorComplete = behaviorErr == nil &&
		result.MappingBehavior != StunBehaviorUnknown &&
		result.FilteringBehavior != StunBehaviorUnknown
	if behaviorErr != nil {
		setStunError(result, ctx, behaviorErr)
	}
	return result
}

func resolveStunServer(
	ctx context.Context,
	address string,
	ipv6Mode int32,
	lookupIP func(context.Context, string, string) ([]netip.Addr, error),
) (string, *net.UDPAddr, error) {
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		return "", nil, err
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil {
		return "", nil, err
	}
	if literal, parseErr := netip.ParseAddr(host); parseErr == nil {
		literal = literal.Unmap()
		return stunUDPNetwork(literal), net.UDPAddrFromAddrPort(
			netip.AddrPortFrom(literal, uint16(port)),
		), nil
	}
	addresses, err := lookupIP(ctx, "ip", host)
	if err != nil {
		return "", nil, err
	}
	selected, ok := selectStunServerAddress(addresses, ipv6Mode)
	if !ok {
		family := "IPv4"
		if ipv6Mode == StunIPv6Only {
			family = "IPv6"
		}
		return "", nil, &net.DNSError{
			Err:  "no " + family + " address",
			Name: host,
		}
	}
	return stunUDPNetwork(selected), net.UDPAddrFromAddrPort(
		netip.AddrPortFrom(selected, uint16(port)),
	), nil
}

func selectStunServerAddress(addresses []netip.Addr, ipv6Mode int32) (netip.Addr, bool) {
	var firstIPv4 netip.Addr
	var firstIPv6 netip.Addr
	for _, address := range addresses {
		address = address.Unmap()
		switch {
		case address.Is4() && !firstIPv4.IsValid():
			firstIPv4 = address
		case address.Is6() && !firstIPv6.IsValid():
			firstIPv6 = address
		}
	}
	switch ipv6Mode {
	case StunIPv6Only:
		return firstIPv6, firstIPv6.IsValid()
	case StunIPv6Prefer:
		if firstIPv6.IsValid() {
			return firstIPv6, true
		}
		return firstIPv4, firstIPv4.IsValid()
	case StunIPv6Enable:
		if firstIPv4.IsValid() {
			return firstIPv4, true
		}
		return firstIPv6, firstIPv6.IsValid()
	default:
		return firstIPv4, firstIPv4.IsValid()
	}
}

func stunUDPNetwork(address netip.Addr) string {
	if address.Is6() {
		return "udp6"
	}
	return "udp4"
}

func (s *StunTestSession) trackConnection(connection net.PacketConn) {
	s.access.Lock()
	s.connections[connection] = struct{}{}
	s.access.Unlock()
}

func (s *StunTestSession) untrackConnection(connection net.PacketConn) {
	s.access.Lock()
	delete(s.connections, connection)
	s.access.Unlock()
}

func setStunError(result *StunServerResult, ctx context.Context, err error) {
	switch {
	case context.Cause(ctx) != nil:
		if errors.Is(context.Cause(ctx), errStunTimeout) {
			result.ErrorCode = "deadline"
		} else {
			result.ErrorCode = "cancelled"
		}
		result.ErrorMessage = context.Cause(ctx).Error()
	case errors.Is(err, stun.ErrBehaviorDiscoveryUnsupported):
		result.ErrorCode = "behavior_unsupported"
		result.ErrorMessage = err.Error()
	case func() bool {
		_, ok := errors.AsType[*net.DNSError](err)
		return ok
	}():
		result.ErrorCode = "dns"
		result.ErrorMessage = err.Error()
	case func() bool {
		netError, ok := errors.AsType[net.Error](err)
		return ok && netError.Timeout()
	}():
		result.ErrorCode = "timeout"
		result.ErrorMessage = err.Error()
	default:
		result.ErrorCode = "network"
		result.ErrorMessage = err.Error()
	}
}
