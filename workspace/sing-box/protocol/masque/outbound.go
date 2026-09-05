package masque

import (
	"context"
	"encoding/base64"
	"github.com/goccy/go-json"
	"net"
	"net/netip"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/cloudflare"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/masque"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"golang.zx2c4.com/wireguard/wgctrl/wgtypes"
)

const defaultSNI = "consumer-masque.cloudflareclient.com"

const (
	defaultMASQUEMTU         = 1280
	defaultH3FallbackTimeout = 5 * time.Second
	maxH2MTU                 = 16000
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.MASQUEOutboundOptions](registry, C.TypeMASQUE, NewOutbound)
}

var _ adapter.FlowOutbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	ctx          context.Context
	dnsRouter    adapter.DNSRouter
	logger       logger.ContextLogger
	options      option.MASQUEOutboundOptions
	tunnel       *masque.Tunnel
	startHandler func()
	initErr      error

	await chan struct{}
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.MASQUEOutboundOptions) (adapter.Outbound, error) {
	transport, mtu, fallbackTimeout, err := resolveTransportOptions(options)
	if err != nil {
		return nil, err
	}
	outbound := &Outbound{
		Adapter:   outbound.NewAdapterWithDialerOptions(C.TypeMASQUE, tag, []string{N.NetworkTCP, N.NetworkUDP, N.NetworkICMP}, options.DialerOptions),
		ctx:       ctx,
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
		logger:    logger,
		options:   options,
		await:     make(chan struct{}),
	}
	outbound.startHandler = func() {
		defer close(outbound.await)
		fail := func(err error) {
			outbound.initErr = err
			logger.ErrorContext(ctx, err)
		}
		cacheFile := service.FromContext[adapter.CacheFile](ctx)
		masqueCache := adapter.GetMASQUECache(cacheFile)
		appConfig := options.Config
		var err error
		if appConfig == nil && !options.Profile.Recreate && cacheFile != nil && masqueCache.StoreMASQUEConfig() {
			savedProfile := masqueCache.LoadMASQUEConfig(tag)
			if savedProfile != nil {
				if err = json.Unmarshal(savedProfile.Content, &appConfig); err != nil {
					fail(err)
					return
				}
			}
		}
		if appConfig == nil {
			appConfig, err = outbound.createConfig()
			if err != nil {
				fail(err)
				return
			}
			if cacheFile != nil && masqueCache.StoreMASQUEConfig() {
				content, err := json.Marshal(appConfig)
				if err != nil {
					fail(err)
					return
				}
				masqueCache.SaveMASQUEConfig(tag, &adapter.SavedBinary{
					LastUpdated: time.Now(),
					Content:     content,
					LastEtag:    "",
				})
			}
		}
		applyMASQUEConfigDefaults(appConfig)
		privKey, err := appConfig.GetEcPrivateKey()
		if err != nil {
			fail(E.New("failed to get private key: ", err))
			return
		}
		peerPubKey, err := appConfig.GetEcEndpointPublicKey()
		if err != nil {
			fail(E.New("failed to get public key: ", err))
			return
		}
		cert, err := masque.GenerateCert(privKey, &privKey.PublicKey)
		if err != nil {
			fail(E.New("failed to generate cert: ", err))
			return
		}
		sni := defaultSNI
		if options.TLS != nil && options.TLS.SNI != "" {
			sni = options.TLS.SNI
		}
		tlsConfig, err := tls.NewMASQUEClient(ctx, logger, sni, cert, privKey, peerPubKey, common.PtrValueOrDefault(options.TLS))
		if err != nil {
			fail(E.New("failed to prepare TLS config: ", err))
			return
		}
		h3EndpointAddr, err := appConfig.SelectEndpointFromConfig(false, options.UseIPv6, 443)
		if err != nil {
			fail(E.New("failed to select HTTP/3 endpoint: ", err))
			return
		}
		h3Endpoint, ok := h3EndpointAddr.(*net.UDPAddr)
		if !ok {
			fail(E.New("invalid HTTP/3 endpoint type"))
			return
		}
		h2EndpointAddr, err := appConfig.SelectEndpointFromConfig(true, options.UseIPv6, 443)
		if err != nil {
			fail(E.New("failed to select HTTP/2 endpoint: ", err))
			return
		}
		h2Endpoint, ok := h2EndpointAddr.(*net.TCPAddr)
		if !ok {
			fail(E.New("invalid HTTP/2 endpoint type"))
			return
		}
		var udpTimeout time.Duration
		if options.UDPTimeout != 0 {
			udpTimeout = time.Duration(options.UDPTimeout)
		} else {
			udpTimeout = C.UDPTimeout
		}
		var udpKeepalivePeriod time.Duration
		if options.UDPKeepalivePeriod != 0 {
			udpKeepalivePeriod = time.Duration(options.UDPKeepalivePeriod)
		} else {
			udpKeepalivePeriod = 30 * time.Second
		}
		outboundDialer, err := dialer.NewWithOptions(dialer.Options{
			Context:          ctx,
			Options:          options.DialerOptions,
			RemoteIsDomain:   false,
			ResolverOnDetour: true,
		})
		if err != nil {
			fail(err)
			return
		}
		tunnel, err := masque.NewTunnel(ctx, logger, masque.TunnelOptions{
			System: options.System,
			Name:   options.Name,
			CreateDialer: func(interfaceName string) N.Dialer {
				return common.Must1(dialer.NewDefault(ctx, option.DialerOptions{
					AbstractDialerOptions: option.AbstractDialerOptions{
						BindInterface: interfaceName,
					},
				}))
			},
			Dialer: outboundDialer,
			Address: []netip.Prefix{
				netip.MustParsePrefix(appConfig.IPv4 + "/32"),
				netip.MustParsePrefix(appConfig.IPv6 + "/128"),
			},
			AllowedAddress:          options.AllowedIPs,
			H3Endpoint:              h3Endpoint,
			H2Endpoint:              h2Endpoint,
			TLSConfig:               tlsConfig,
			Transport:               transport,
			H3FallbackTimeout:       fallbackTimeout,
			MTU:                     mtu,
			UDPTimeout:              udpTimeout,
			UDPKeepalivePeriod:      udpKeepalivePeriod,
			UDPInitialPacketSize:    options.UDPInitialPacketSize,
			DisablePathMTUDiscovery: options.DisablePathMTUDiscovery,
			ReconnectDelay:          options.ReconnectDelay.Build(),
		})
		if err != nil {
			fail(err)
			return
		}
		outbound.tunnel = tunnel
		if err = outbound.tunnel.Start(false); err != nil {
			fail(err)
			return
		}
		if err = outbound.tunnel.Start(true); err != nil {
			fail(err)
		}
	}
	return outbound, nil
}

func resolveTransportOptions(options option.MASQUEOutboundOptions) (string, uint32, time.Duration, error) {
	transport := options.Transport
	if transport == "" {
		if options.UseHTTP2 {
			transport = "h2"
		} else {
			transport = "auto"
		}
	}
	switch transport {
	case "auto", "h3", "h2":
	default:
		return "", 0, 0, E.New("invalid MASQUE transport: ", transport)
	}
	if options.UseHTTP2 && transport != "h2" {
		return "", 0, 0, E.New("MASQUE use_http2 conflicts with transport ", transport)
	}
	mtu := options.MTU
	if mtu == 0 {
		mtu = defaultMASQUEMTU
	}
	if (transport == "auto" || transport == "h2") && mtu > maxH2MTU {
		return "", 0, 0, E.New("MASQUE MTU ", mtu, " exceeds HTTP/2 maximum ", maxH2MTU)
	}
	fallbackTimeout := time.Duration(options.H3FallbackTimeout)
	if fallbackTimeout == 0 {
		fallbackTimeout = defaultH3FallbackTimeout
	}
	if fallbackTimeout < 0 {
		return "", 0, 0, E.New("MASQUE h3_fallback_timeout must not be negative")
	}
	return transport, mtu, fallbackTimeout, nil
}

func (w *Outbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStatePostStart {
		return nil
	}
	go w.startHandler()
	return nil
}

func (w *Outbound) Close() error {
	if err := w.isTunnelInitialized(w.ctx); err != nil {
		return err
	}
	return w.tunnel.Close()
}

func (w *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if err := w.isTunnelInitialized(ctx); err != nil {
		return nil, err
	}
	switch network {
	case N.NetworkTCP:
		w.logger.InfoContext(ctx, "outbound connection to ", destination)
	case N.NetworkUDP:
		w.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	}
	if destination.IsDomain() {
		destinationAddresses, err := w.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		return N.DialSerial(ctx, w.tunnel, network, destination, destinationAddresses)
	} else if !destination.Addr.IsValid() {
		return nil, E.New("invalid destination: ", destination)
	}
	return w.tunnel.DialContext(ctx, network, destination)
}

func (w *Outbound) ListenPacketWithDestination(ctx context.Context, destination M.Socksaddr) (net.PacketConn, netip.Addr, error) {
	if err := w.isTunnelInitialized(ctx); err != nil {
		return nil, netip.Addr{}, err
	}
	w.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	if destination.IsDomain() {
		destinationAddresses, err := w.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, netip.Addr{}, err
		}
		return N.ListenSerial(ctx, w.tunnel, destination, destinationAddresses)
	}
	packetConn, err := w.tunnel.ListenPacket(ctx, destination)
	if err != nil {
		return nil, netip.Addr{}, err
	}
	if destination.IsIP() {
		return packetConn, destination.Addr, nil
	}
	return packetConn, netip.Addr{}, nil
}

func (w *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	packetConn, destinationAddress, err := w.ListenPacketWithDestination(ctx, destination)
	if err != nil {
		return nil, err
	}
	if destinationAddress.IsValid() && destination != M.SocksaddrFrom(destinationAddress, destination.Port) {
		return bufio.NewNATPacketConn(bufio.NewPacketConn(packetConn), M.SocksaddrFrom(destinationAddress, destination.Port), destination), nil
	}
	return packetConn, nil
}

func (w *Outbound) PreMatchFlow(network string, destination netip.Addr) adapter.PreMatchAction {
	select {
	case <-w.await:
		if w.tunnel != nil && destination.IsValid() {
			return adapter.PreMatchFlow
		}
	default:
	}
	return adapter.PreMatchContinue
}

func (w *Outbound) PortAddresses() (netip.Addr, netip.Addr) {
	return w.tunnel.PortAddresses()
}

func (w *Outbound) PortMTU() uint32 {
	return w.tunnel.PortMTU()
}

func (w *Outbound) AttachReturn(returnPath tun.Return) error {
	return w.tunnel.AttachReturn(returnPath)
}

func (w *Outbound) DetachReturn(returnPath tun.Return) error {
	return w.tunnel.DetachReturn(returnPath)
}

func (w *Outbound) WritePackets(packets [][]byte) error {
	return w.tunnel.WritePackets(packets)
}

func (w *Outbound) isTunnelInitialized(ctx context.Context) error {
	select {
	case <-w.await:
	case <-ctx.Done():
		return ctx.Err()
	}
	if w.tunnel == nil {
		if w.initErr != nil {
			return w.initErr
		}
		return E.New("tunnel not initialized")
	}
	return nil
}

func applyMASQUEConfigDefaults(config *option.MASQUEConfig) {
	if config.EndpointH2V4 == "" {
		config.EndpointH2V4 = cloudflare.DefaultEndpointH2V4
	}
}

func (w *Outbound) createConfig() (*option.MASQUEConfig, error) {
	opts := make([]cloudflare.CloudflareApiOption, 0, 1)
	if w.options.Profile.Detour != "" {
		detour, ok := service.FromContext[adapter.OutboundManager](w.ctx).Outbound(w.options.Profile.Detour)
		if !ok {
			return nil, E.New("outbound detour not found: ", w.options.Profile.Detour)
		}
		opts = append(opts, cloudflare.WithDialContext(func(ctx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(ctx, network, M.ParseSocksaddr(addr))
		}))
	}
	api := cloudflare.NewCloudflareApi(opts...)
	var profile *cloudflare.CloudflareProfile
	var err error
	if w.options.Profile.AuthToken != "" && w.options.Profile.ID != "" {
		profile, err = api.GetProfile(w.ctx, w.options.Profile.AuthToken, w.options.Profile.ID)
		if err != nil {
			return nil, err
		}
	} else {
		wgPrivateKey, err := wgtypes.GeneratePrivateKey()
		if err != nil {
			return nil, err
		}
		profile, err = api.CreateProfile(w.ctx, wgPrivateKey.PublicKey().String())
		if err != nil {
			return nil, err
		}
	}
	privateKey, publicKey, err := masque.GenerateEcKeyPair()
	if err != nil {
		return nil, E.New("failed to generate key pair: ", err)
	}
	updatedProfile, err := api.EnrollKey(w.ctx, profile.Token, profile.ID, cloudflare.KeyTypeMasque, cloudflare.TunTypeMasque, base64.StdEncoding.EncodeToString(publicKey))
	if err != nil {
		return nil, err
	}
	return &option.MASQUEConfig{
		PrivateKey:     base64.StdEncoding.EncodeToString(privateKey),
		EndpointV4:     updatedProfile.Config.Peers[0].Endpoint.V4[:len(updatedProfile.Config.Peers[0].Endpoint.V4)-2],
		EndpointV6:     updatedProfile.Config.Peers[0].Endpoint.V6[1 : len(updatedProfile.Config.Peers[0].Endpoint.V6)-3],
		EndpointH2V4:   cloudflare.DefaultEndpointH2V4,
		EndpointH2V6:   cloudflare.DefaultEndpointH2V6,
		EndpointPubKey: updatedProfile.Config.Peers[0].PublicKey,
		License:        updatedProfile.Account.License,
		ID:             updatedProfile.ID,
		AccessToken:    profile.Token,
		IPv4:           updatedProfile.Config.Interface.Addresses.V4,
		IPv6:           updatedProfile.Config.Interface.Addresses.V6,
	}, nil
}
