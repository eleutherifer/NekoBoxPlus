package awg

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/awg"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/format"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	"go4.org/netipx"
)

func RegisterEndpoint(registry *endpoint.Registry) {
	endpoint.Register(registry, constant.TypeAwg, NewEndpoint)
}

type Endpoint struct {
	endpoint.Adapter
	deviceAccess   sync.RWMutex
	device         *awg.Device
	deferredDevice func() (*awg.Device, error)
	address        []netip.Prefix
	router         adapter.Router
	logger         log.ContextLogger
	dnsRouter      adapter.DNSRouter
}

const (
	awgMaxJunkPacketCount = 512
	awgMaxJunkPacketSize  = 4096
	awgMaxHandshakePad    = 4096
	awgMaxTransportPad    = 1024
	awgHeaderKeySize      = 32
	awgHeaderNonceSize    = 12
)

func NewEndpoint(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.AwgEndpointOptions) (adapter.Endpoint, error) {
	if options.MTU == 0 {
		options.MTU = 1408
	}
	if err := validateObfuscationOptions(options); err != nil {
		return nil, err
	}

	options.UDPFragmentDefault = true
	// Check if any peer has a domain address
	remoteIsDomain := common.Any(options.Peers, func(peer option.AwgPeerOptions) bool {
		return !M.ParseAddr(peer.Address).IsValid()
	})
	dial, err := dialer.NewWithOptions(dialer.Options{
		Context:          ctx,
		Options:          options.DialerOptions,
		RemoteIsDomain:   remoteIsDomain,
		ResolverOnDetour: true,
		DirectOutbound:   true,
	})
	if err != nil {
		return nil, err
	}

	var allowedPrefixBuilder netipx.IPSetBuilder
	var excludedPrefixBuilder netipx.IPSetBuilder
	for _, peer := range options.Peers {
		for _, prefix := range peer.AllowedIPs {
			allowedPrefixBuilder.AddPrefix(prefix)
		}

		if addr, err := netip.ParseAddr(peer.Address); err == nil {
			excludedPrefixBuilder.Add(addr)
		}
	}
	allowedIps, err := allowedPrefixBuilder.IPSet()
	if err != nil {
		return nil, err
	}
	excludedIps, err := excludedPrefixBuilder.IPSet()
	if err != nil {
		return nil, err
	}

	result := &Endpoint{
		Adapter:   endpoint.NewAdapterWithDialerOptions("awg", tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		address:   options.Address,
		router:    router,
		logger:    logger,
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
	}
	buildDevice := func(resolvePeer func(domain string) (netip.Addr, error)) (*awg.Device, error) {
		return newEndpointDevice(
			ctx,
			logger,
			dial,
			options,
			allowedIps.Prefixes(),
			excludedIps.Prefixes(),
			resolvePeer,
		)
	}
	if remoteIsDomain && options.Detour != "" {
		result.deferredDevice = func() (*awg.Device, error) {
			resolveDialer, loaded := dial.(dialer.ResolveDialer)
			if !loaded {
				return nil, E.New("missing resolver for detoured AmneziaWG domain peer")
			}
			queryOptions, resolveErr := dialer.PeerDomainQueryOptions(
				service.FromContext[adapter.DNSTransportManager](ctx),
				tag,
				options.Detour,
				resolveDialer.QueryOptions(),
			)
			if resolveErr != nil {
				return nil, resolveErr
			}
			return buildDevice(cachedPeerResolver(func(domain string) (netip.Addr, error) {
				addresses, lookupErr := result.dnsRouter.Lookup(ctx, domain, queryOptions)
				if lookupErr != nil {
					return netip.Addr{}, lookupErr
				}
				if len(addresses) == 0 {
					return netip.Addr{}, E.New("empty DNS response for ", domain)
				}
				return addresses[0], nil
			}))
		}
		return result, nil
	}

	var resolvePeer func(domain string) (netip.Addr, error)
	if remoteIsDomain {
		resolvePeer = cachedPeerResolver(func(domain string) (netip.Addr, error) {
			addrs, lookupErr := net.DefaultResolver.LookupNetIP(ctx, "ip4", domain)
			if lookupErr != nil || len(addrs) == 0 {
				addrs, lookupErr = net.DefaultResolver.LookupNetIP(ctx, "ip6", domain)
			}
			if lookupErr != nil {
				return netip.Addr{}, lookupErr
			}
			if len(addrs) == 0 {
				return netip.Addr{}, fmt.Errorf("could not resolve peer: %s", domain)
			}
			return addrs[0], nil
		})
	}
	result.device, err = buildDevice(resolvePeer)
	if err != nil {
		return nil, err
	}
	return result, nil
}

func newEndpointDevice(
	ctx context.Context,
	logger log.ContextLogger,
	dial N.Dialer,
	options option.AwgEndpointOptions,
	allowedIPs []netip.Prefix,
	excludedIPs []netip.Prefix,
	resolvePeer func(domain string) (netip.Addr, error),
) (*awg.Device, error) {
	singlePeerEndpoint, err := resolveClientBindSinglePeerEndpoint(options, resolvePeer)
	if err != nil {
		return nil, err
	}
	clientBindReserved, reservedForEndpoint, err := resolveReservedForPeers(options, resolvePeer)
	if err != nil {
		return nil, err
	}
	ipc, err := genIpcConfig(options, resolvePeer)
	if err != nil {
		return nil, err
	}
	logger.Debug("AWG IPC config:\n", ipc)
	return awg.NewDevice(ctx, logger, dial, ipc, awg.DeviceOpts{
		UseIntegratedTun:    options.UseIntegratedTun,
		LazyBind:            options.Detour != "",
		PeerEndpoint:        singlePeerEndpoint,
		Reserved:            clientBindReserved,
		ReservedForEndpoint: reservedForEndpoint,
		Address:             options.Address,
		AllowedIps:          allowedIPs,
		ExcludedIps:         excludedIPs,
		MTU:                 options.MTU,
	})
}

func cachedPeerResolver(resolve func(domain string) (netip.Addr, error)) func(domain string) (netip.Addr, error) {
	addresses := make(map[string]netip.Addr)
	return func(domain string) (netip.Addr, error) {
		if address, loaded := addresses[domain]; loaded {
			return address, nil
		}
		address, err := resolve(domain)
		if err != nil {
			return netip.Addr{}, err
		}
		addresses[domain] = address
		return address, nil
	}
}

func resolveClientBindSinglePeerEndpoint(opts option.AwgEndpointOptions, resolvePeer func(domain string) (netip.Addr, error)) (netip.AddrPort, error) {
	if opts.Detour == "" {
		return netip.AddrPort{}, nil
	}
	return resolveSinglePeerEndpoint(opts, resolvePeer)
}

func resolveSinglePeerEndpoint(opts option.AwgEndpointOptions, resolvePeer func(domain string) (netip.Addr, error)) (netip.AddrPort, error) {
	if len(opts.Peers) != 1 {
		return netip.AddrPort{}, nil
	}
	peer := opts.Peers[0]
	if peer.Port == 0 || peer.Address == "" {
		return netip.AddrPort{}, nil
	}
	if addr := M.ParseAddr(peer.Address); addr.IsValid() {
		return netip.AddrPortFrom(addr, peer.Port), nil
	}
	if resolvePeer == nil {
		return netip.AddrPort{}, E.New("single peer client bind requires a resolvable peer endpoint")
	}
	resolvedAddr, err := resolvePeer(peer.Address)
	if err != nil {
		return netip.AddrPort{}, E.Cause(err, "resolve client bind peer endpoint ", peer.Address)
	}
	return netip.AddrPortFrom(resolvedAddr, peer.Port), nil
}

func resolveReservedForPeers(opts option.AwgEndpointOptions, resolvePeer func(domain string) (netip.Addr, error)) ([3]uint8, map[netip.AddrPort][3]uint8, error) {
	var reserved [3]uint8
	reservedForEndpoint := make(map[netip.AddrPort][3]uint8)
	for index, peer := range opts.Peers {
		if len(peer.Reserved) == 0 {
			continue
		}
		if len(peer.Reserved) != 3 {
			return [3]uint8{}, nil, E.New("invalid reserved value for peer ", index, ", required 3 bytes, got ", len(peer.Reserved))
		}
		var peerReserved [3]uint8
		copy(peerReserved[:], peer.Reserved)
		if len(opts.Peers) == 1 {
			reserved = peerReserved
		}
		if peer.Port == 0 || peer.Address == "" {
			continue
		}
		if addr := M.ParseAddr(peer.Address); addr.IsValid() {
			reservedForEndpoint[netip.AddrPortFrom(addr, peer.Port)] = peerReserved
			continue
		}
		if resolvePeer == nil {
			return [3]uint8{}, nil, E.New("peer reserved requires a resolvable peer endpoint: ", peer.Address)
		}
		resolvedAddr, err := resolvePeer(peer.Address)
		if err != nil {
			return [3]uint8{}, nil, E.Cause(err, "resolve peer endpoint for reserved ", peer.Address)
		}
		reservedForEndpoint[netip.AddrPortFrom(resolvedAddr, peer.Port)] = peerReserved
	}
	return reserved, reservedForEndpoint, nil
}

func validateObfuscationOptions(opts option.AwgEndpointOptions) error {
	if opts.Jc < 0 {
		return E.New("jc must be non-negative")
	}
	if opts.Jc > awgMaxJunkPacketCount {
		return E.New("jc must be at most ", awgMaxJunkPacketCount)
	}
	if opts.Jmin < 0 {
		return E.New("jmin must be non-negative")
	}
	if opts.Jmax < 0 {
		return E.New("jmax must be non-negative")
	}
	if opts.Jc > 0 && (opts.Jmin == 0 || opts.Jmax == 0) {
		return E.New("jmin and jmax are required when jc is set")
	}
	if opts.Jmin > 0 || opts.Jmax > 0 {
		if opts.Jmin == 0 || opts.Jmax == 0 {
			return E.New("jmin and jmax must be set together")
		}
		if opts.Jmin > opts.Jmax {
			return E.New("jmin must be less than or equal to jmax")
		}
		if opts.Jmax > awgMaxJunkPacketSize {
			return E.New("jmax must be at most ", awgMaxJunkPacketSize)
		}
	}
	if err := validatePaddingOption("s1", opts.S1, awgMaxHandshakePad); err != nil {
		return err
	}
	if err := validatePaddingOption("s2", opts.S2, awgMaxHandshakePad); err != nil {
		return err
	}
	if err := validatePaddingOption("s3", opts.S3, awgMaxHandshakePad); err != nil {
		return err
	}
	if err := validatePaddingOption("s4", opts.S4, awgMaxTransportPad); err != nil {
		return err
	}
	ranges := []struct {
		name  string
		value option.AwgUint32Range
	}{
		{"content_padding_addition", opts.ContentPaddingAddition},
		{"rekey_after_time", opts.RekeyAfterTime},
		{"rekey_timeout", opts.RekeyTimeout},
		{"reject_after_time", opts.RejectAfterTime},
		{"keepalive_timeout", opts.KeepaliveTimeout},
		{"max_handshake_attempts", opts.MaxHandshakeAttempts},
	}
	for _, valueRange := range ranges {
		if err := valueRange.value.Validate(); err != nil {
			return E.Cause(err, "invalid ", valueRange.name)
		}
	}
	for index, peer := range opts.Peers {
		if err := peer.PersistentKeepaliveInterval.Validate(); err != nil {
			return E.Cause(err, "invalid persistent_keepalive_interval for peer ", index)
		}
	}
	if opts.HeaderProtectionKey != "" {
		headerKey, err := base64.StdEncoding.DecodeString(opts.HeaderProtectionKey)
		if err != nil {
			return E.Cause(err, "decode header_protection_key")
		}
		if len(headerKey) != awgHeaderKeySize {
			return E.New("header_protection_key must decode to ", awgHeaderKeySize, " bytes")
		}
		for index, padding := range []int{opts.S1, opts.S2, opts.S3, opts.S4} {
			if padding < awgHeaderNonceSize {
				return E.New("s", index+1, " must be at least ", awgHeaderNonceSize, " when header_protection_key is set")
			}
		}
	}
	return nil
}

func validatePaddingOption(name string, value int, maxValue int) error {
	if value < 0 {
		return E.New(name, " must be non-negative")
	}
	if value > maxValue {
		return E.New(name, " must be at most ", maxValue)
	}
	return nil
}

func genIpcConfig(opts option.AwgEndpointOptions, resolvePeer func(domain string) (netip.Addr, error)) (string, error) {
	privateKeyBytes, err := base64.StdEncoding.DecodeString(opts.PrivateKey)
	if err != nil {
		return "", err
	}
	s := "private_key=" + hex.EncodeToString(privateKeyBytes)
	if opts.ListenPort != 0 {
		s += "\nlisten_port=" + format.ToString(opts.ListenPort)
	}
	if opts.Jc != 0 {
		s += "\njc=" + format.ToString(opts.Jc)
	}
	if opts.Jmin != 0 {
		s += "\njmin=" + format.ToString(opts.Jmin)
	}
	if opts.Jmax != 0 {
		s += "\njmax=" + format.ToString(opts.Jmax)
	}
	if opts.S1 != 0 {
		s += "\ns1=" + format.ToString(opts.S1)
	}
	if opts.S2 != 0 {
		s += "\ns2=" + format.ToString(opts.S2)
	}
	if opts.S3 != 0 {
		s += "\ns3=" + format.ToString(opts.S3)
	}
	if opts.S4 != 0 {
		s += "\ns4=" + format.ToString(opts.S4)
	}
	if opts.H1 != "" {
		s += "\nh1=" + opts.H1
	}
	if opts.H2 != "" {
		s += "\nh2=" + opts.H2
	}
	if opts.H3 != "" {
		s += "\nh3=" + opts.H3
	}
	if opts.H4 != "" {
		s += "\nh4=" + opts.H4
	}
	if opts.I1 != "" {
		s += "\ni1=" + opts.I1
	}
	if opts.I2 != "" {
		s += "\ni2=" + opts.I2
	}
	if opts.I3 != "" {
		s += "\ni3=" + opts.I3
	}
	if opts.I4 != "" {
		s += "\ni4=" + opts.I4
	}
	if opts.I5 != "" {
		s += "\ni5=" + opts.I5
	}
	if opts.HeaderProtectionKey != "" {
		headerKeyBytes, err := base64.StdEncoding.DecodeString(opts.HeaderProtectionKey)
		if err != nil {
			return "", E.Cause(err, "decode header_protection_key")
		}
		if len(headerKeyBytes) != awgHeaderKeySize {
			return "", E.New("header_protection_key must decode to ", awgHeaderKeySize, " bytes")
		}
		s += "\nheader_protection_key=" + hex.EncodeToString(headerKeyBytes)
	}
	appendRange := func(name string, value option.AwgUint32Range) {
		if !value.IsZero() {
			s += "\n" + name + "=" + value.String()
		}
	}
	appendRange("content_padding_addition", opts.ContentPaddingAddition)
	appendRange("rekey_after_time", opts.RekeyAfterTime)
	appendRange("rekey_timeout", opts.RekeyTimeout)
	appendRange("reject_after_time", opts.RejectAfterTime)
	appendRange("keepalive_timeout", opts.KeepaliveTimeout)
	appendRange("max_handshake_attempts", opts.MaxHandshakeAttempts)

	for _, peer := range opts.Peers {
		publicKeyBytes, err := base64.StdEncoding.DecodeString(peer.PublicKey)
		if err != nil {
			return "", err
		}
		s += "\npublic_key=" + hex.EncodeToString(publicKeyBytes)
		if peer.PresharedKey != "" {
			presharedKeyBytes, err := base64.StdEncoding.DecodeString(peer.PresharedKey)
			if err != nil {
				return "", err
			}
			s += "\npreshared_key=" + hex.EncodeToString(presharedKeyBytes)
		}
		if peer.Address != "" && peer.Port != 0 {
			// Resolve domain to IP if necessary
			endpointAddr := peer.Address
			if addr := M.ParseAddr(peer.Address); !addr.IsValid() {
				// It's a domain, resolve it
				if resolvePeer == nil {
					return "", E.New("peer address is a domain but no resolver provided: ", peer.Address)
				}
				resolvedAddr, resolveErr := resolvePeer(peer.Address)
				if resolveErr != nil {
					return "", E.Cause(resolveErr, "resolve peer endpoint ", peer.Address)
				}
				endpointAddr = resolvedAddr.String()
			}
			s += "\nendpoint=" + formatAWGEndpoint(endpointAddr, peer.Port)
		}
		if !peer.PersistentKeepaliveInterval.IsZero() {
			s += "\npersistent_keepalive_interval=" + peer.PersistentKeepaliveInterval.String()
		}
		for _, allowedIp := range peer.AllowedIPs {
			s += "\nallowed_ip=" + allowedIp.String()
		}
	}
	return s, nil
}

func formatAWGEndpoint(host string, port uint16) string {
	unwrappedHost := host
	if len(unwrappedHost) >= 2 && unwrappedHost[0] == '[' && unwrappedHost[len(unwrappedHost)-1] == ']' {
		unwrappedHost = unwrappedHost[1 : len(unwrappedHost)-1]
	}
	if addr, err := netip.ParseAddr(unwrappedHost); err == nil {
		return netip.AddrPortFrom(addr, port).String()
	}
	return net.JoinHostPort(unwrappedHost, strconv.Itoa(int(port)))
}

func (e *Endpoint) Start(stage adapter.StartStage) error {
	e.deviceAccess.Lock()
	defer e.deviceAccess.Unlock()
	if e.deferredDevice != nil {
		if stage != adapter.StartStatePostStart {
			return nil
		}
		device, err := e.deferredDevice()
		if err != nil {
			return err
		}
		if err = device.Start(adapter.StartStateStart); err != nil {
			_ = device.Close()
			return err
		}
		e.device = device
		e.deferredDevice = nil
		return nil
	}
	if e.device == nil {
		return E.New("AmneziaWG device is not initialized")
	}
	return e.device.Start(stage)
}

func (e *Endpoint) Close() error {
	e.deviceAccess.Lock()
	device := e.device
	e.device = nil
	e.deferredDevice = nil
	e.deviceAccess.Unlock()
	if device == nil {
		return nil
	}
	return device.Close()
}

func (e *Endpoint) InterfaceUpdated() {
	e.deviceAccess.RLock()
	device := e.device
	e.deviceAccess.RUnlock()
	if device != nil {
		device.InterfaceUpdated()
	}
}

func (e *Endpoint) currentDevice() (*awg.Device, error) {
	e.deviceAccess.RLock()
	device := e.device
	e.deviceAccess.RUnlock()
	if device == nil {
		return nil, E.New("AmneziaWG is not ready yet")
	}
	return device, nil
}

func (e *Endpoint) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	var metadata adapter.InboundContext
	metadata.Inbound = e.Tag()
	metadata.InboundType = e.Type()
	metadata.Source = source
	metadata.Destination = destination
	for _, addr := range e.address {
		if addr.Contains(destination.Addr) {
			metadata.OriginDestination = destination
			if destination.Addr.Is4() {
				metadata.Destination.Addr = netip.AddrFrom4([4]uint8{127, 0, 0, 1})
			} else {
				metadata.Destination.Addr = netip.IPv6Loopback()
			}
			conn = bufio.NewNATPacketConn(bufio.NewNetPacketConn(conn), metadata.OriginDestination, metadata.Destination)
		}
	}
	e.logger.InfoContext(ctx, "inbound packet connection from ", source)
	e.logger.InfoContext(ctx, "inbound packet connection to ", destination)
	e.router.RoutePacketConnectionEx(ctx, conn, metadata, onClose)
}

func (e *Endpoint) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch network {
	case N.NetworkTCP:
		e.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		e.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	}
	device, err := e.currentDevice()
	if err != nil {
		return nil, err
	}
	if destination.IsFqdn() {
		destinationAddresses, err := e.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		return N.DialSerial(ctx, device, network, destination, destinationAddresses)
	} else if !destination.Addr.IsValid() {
		return nil, E.New("invalid destination: ", destination)
	}
	return device.DialContext(ctx, network, destination)
}

func (e *Endpoint) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	e.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	device, err := e.currentDevice()
	if err != nil {
		return nil, err
	}
	if destination.IsFqdn() {
		destinationAddresses, err := e.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		packetConn, _, err := N.ListenSerial(ctx, device, destination, destinationAddresses)
		if err != nil {
			return nil, err
		}
		return packetConn, nil
	}
	return device.ListenPacket(ctx, destination)
}

func (w *Endpoint) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	var metadata adapter.InboundContext
	metadata.Inbound = w.Tag()
	metadata.InboundType = w.Type()
	metadata.Source = source
	for _, addr := range w.address {
		if addr.Contains(destination.Addr) {
			metadata.OriginDestination = destination
			if destination.Addr.Is4() {
				destination.Addr = netip.AddrFrom4([4]uint8{127, 0, 0, 1})
			} else {
				destination.Addr = netip.IPv6Loopback()
			}
			break
		}
	}
	metadata.Destination = destination
	w.logger.InfoContext(ctx, "inbound connection from ", source)
	w.logger.InfoContext(ctx, "inbound connection to ", metadata.Destination)
	w.router.RouteConnectionEx(ctx, conn, metadata, onClose)
}
