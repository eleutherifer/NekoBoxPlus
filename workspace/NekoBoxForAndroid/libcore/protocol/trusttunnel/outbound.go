package trusttunnel

import (
	"context"
	"net"
	"net/netip"
	"os"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	trusttunnel "libcore/protocol/trusttunnel/sing-trusttunnel"
)

const Type = "trusttunnel"

func init() {
	trusttunnel.ErrQUICNotIncluded = C.ErrQUICNotIncluded
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, Type, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	ctx       context.Context
	logger    log.ContextLogger
	dnsRouter adapter.DNSRouter
	client    *trusttunnel.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	if options.TLS == nil || !options.TLS.Enabled {
		return nil, C.ErrTLSRequired
	}
	if options.Username == "" {
		return nil, E.New("require auth")
	}
	tlsOptions := *options.TLS
	if options.UseCronetHTTPS {
		tlsOptions.UTLS = nil
	}
	detour, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	server := options.ServerOptions.Build()
	tlsConfig, err := tls.NewClient(ctx, logger, server.String(), tlsOptions)
	if err != nil {
		return nil, err
	}
	var quicTLSConfig tls.Config
	if options.QUIC && tlsOptions.UTLS != nil && tlsOptions.UTLS.Enabled {
		quicTLSOptions := tlsOptions
		quicTLSOptions.UTLS = nil
		quicTLSConfig, err = tls.NewClient(ctx, logger, server.String(), quicTLSOptions)
		if err != nil {
			return nil, err
		}
		logger.Warn("uTLS is not supported over trusttunnel QUIC; ignoring uTLS for HTTP/3 and preserving it for HTTP/2 fallback")
	}
	dnsRouter := service.FromContext[adapter.DNSRouter](ctx)
	trustedRootCertificates, err := trustedRootCertificates(tlsOptions.Certificate, tlsOptions.CertificatePath)
	if err != nil {
		return nil, err
	}
	client, err := trusttunnel.NewClient(trusttunnel.ClientOptions{
		Ctx:    ctx,
		Logger: logger,
		Detour: detour,
		Server: server,
		Auth: auth.User{
			Username: options.Username,
			Password: options.Password,
		},
		TLSConfig:               tlsConfig,
		QUICTLSConfig:           quicTLSConfig,
		QUIC:                    options.QUIC,
		ForceQUIC:               options.ForceQUIC,
		UseCronetQUIC:           options.UseCronetQUIC,
		UseCronetHTTPS:          options.UseCronetHTTPS,
		QUICCongestionControl:   options.QUICCongestionControl,
		ClientRandomPrefix:      options.ClientRandomPrefix,
		HealthCheck:             options.HealthCheck,
		TLSServerName:           tlsOptions.ServerName,
		TrustedRootCertificates: trustedRootCertificates,
		ResolveFunc: func(fqdn string) (netip.Addr, error) {
			addresses, lookupErr := dnsRouter.Lookup(ctx, fqdn, adapter.DNSQueryOptions{})
			if lookupErr != nil {
				return netip.Addr{}, lookupErr
			}
			return addresses[0], nil
		},
	})
	if err != nil {
		return nil, err
	}
	networks := []string{N.NetworkTCP, N.NetworkUDP}
	if withGvisor {
		networks = append(networks, N.NetworkICMP)
	}
	return &Outbound{
		Adapter:   outbound.NewAdapterWithDialerOptions(Type, tag, networks, options.DialerOptions),
		ctx:       ctx,
		logger:    logger,
		dnsRouter: dnsRouter,
		client:    client,
	}, nil
}

func trustedRootCertificates(certificates []string, certificatePath string) (string, error) {
	if len(certificates) > 0 {
		return strings.Join(certificates, "\n"), nil
	}
	if certificatePath == "" {
		return "", nil
	}
	content, err := os.ReadFile(certificatePath)
	if err != nil {
		return "", E.Cause(err, "read certificate")
	}
	return string(content), nil
}

func (h *Outbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	return h.client.Start()
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch network {
	case N.NetworkTCP:
		ctx, metadata := adapter.ExtendContext(ctx)
		metadata.Outbound = h.Tag()
		metadata.Destination = destination
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
		return h.client.Dial(ctx, destination)
	case N.NetworkUDP:
		if destination.IsDomain() {
			addresses, err := h.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
			if err != nil {
				return nil, err
			}
			destination = M.Socksaddr{
				Addr: addresses[0],
				Port: destination.Port,
			}
		}
		packetConn, err := h.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(packetConn, destination), nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	return h.client.ListenPacket(ctx)
}

func (h *Outbound) InterfaceUpdated() {
	h.client.ResetConnections()
}

func (h *Outbound) Close() error {
	return common.Close(
		common.PtrOrNil(h.client),
	)
}
