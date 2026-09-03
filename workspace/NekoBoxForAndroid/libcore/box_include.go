package libcore

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/dns/transport/hosts"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/dns/transport/mdns"
	"github.com/sagernet/sing-box/dns/transport/quic"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/anytls"
	"github.com/sagernet/sing-box/protocol/awg"
	"github.com/sagernet/sing-box/protocol/block"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/fragmentexclave"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/http"
	"github.com/sagernet/sing-box/protocol/hysteria"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/mieru"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/openconnect"
	"github.com/sagernet/sing-box/protocol/openvpn"
	"github.com/sagernet/sing-box/protocol/redirect"
	"github.com/sagernet/sing-box/protocol/shadowsocks"
	"github.com/sagernet/sing-box/protocol/shadowsocksr"
	"github.com/sagernet/sing-box/protocol/shadowtls"
	snellprotocol "github.com/sagernet/sing-box/protocol/snell"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing-box/protocol/ssh"
	"github.com/sagernet/sing-box/protocol/tailscale"
	"github.com/sagernet/sing-box/protocol/tor"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/tuic"
	"github.com/sagernet/sing-box/protocol/tun"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	"github.com/sagernet/sing-box/protocol/wireguard"

	"libcore/protocol/byedpi"
	"libcore/protocol/juicity"
	"libcore/protocol/masterdnsvpn"
	"libcore/protocol/trusttunnel"

	"github.com/sagernet/sing-box/protocol/masque"

	_ "github.com/sagernet/sing-box/experimental/clashapi"
	_ "github.com/sagernet/sing-box/transport/v2rayquic"
)

func nekoboxAndroidInboundRegistry() *inbound.Registry {
	registry := inbound.NewRegistry()

	tun.RegisterInbound(registry)
	redirect.RegisterRedirect(registry)
	redirect.RegisterTProxy(registry)
	direct.RegisterInbound(registry)

	socks.RegisterInbound(registry)
	http.RegisterInbound(registry)
	mixed.RegisterInbound(registry)

	return registry
}

func nekoboxAndroidOutboundRegistry() *outbound.Registry {
	registry := outbound.NewRegistry()

	direct.RegisterOutbound(registry)
	fragmentexclave.RegisterOutbound(registry)

	block.RegisterOutbound(registry)

	group.RegisterSelector(registry)
	group.RegisterURLTest(registry)

	socks.RegisterOutbound(registry)
	http.RegisterOutbound(registry)
	shadowsocks.RegisterOutbound(registry)
	shadowsocksr.RegisterOutbound(registry)
	vmess.RegisterOutbound(registry)
	trojan.RegisterOutbound(registry)
	tor.RegisterOutbound(registry)
	ssh.RegisterOutbound(registry)
	shadowtls.RegisterOutbound(registry)
	vless.RegisterOutbound(registry)
	mieru.RegisterOutbound(registry)
	anytls.RegisterOutbound(registry)
	snellprotocol.RegisterOutbound(registry)

	hysteria.RegisterOutbound(registry)
	tuic.RegisterOutbound(registry)
	hysteria2.RegisterOutbound(registry)
	byedpi.RegisterOutbound(registry)
	juicity.RegisterOutbound(registry)
	masterdnsvpn.RegisterOutbound(registry)
	trusttunnel.RegisterOutbound(registry)
	registerNaiveOutbound(registry)

	masque.RegisterOutbound(registry)

	return registry
}

func nekoboxAndroidEndpointRegistry() *endpoint.Registry {
	registry := endpoint.NewRegistry()

	wireguard.RegisterEndpoint(registry)
	awg.RegisterEndpoint(registry)
	tailscale.RegisterEndpoint(registry)
	openvpn.RegisterEndpoint(registry)
	openconnect.RegisterEndpoint(registry)

	return registry
}

func nekoboxAndroidDNSTransportRegistry(localTransport LocalDNSTransport) *dns.TransportRegistry {
	registry := dns.NewTransportRegistry()

	transport.RegisterTCP(registry)
	transport.RegisterUDP(registry)
	transport.RegisterTLS(registry)
	transport.RegisterHTTPS(registry)
	transport.RegisterBalancer(registry)
	hosts.RegisterTransport(registry)
	// local.RegisterTransport(registry)
	fakeip.RegisterTransport(registry)

	quic.RegisterTransport(registry)
	quic.RegisterHTTP3Transport(registry)
	mdns.RegisterTransport(registry)
	tailscale.RegistryTransport(registry)
	openvpn.RegisterDNSTransport(registry)
	openconnect.RegisterDNSTransport(registry)

	if localTransport == nil {
		local.RegisterTransport(registry)
	} else {
		dns.RegisterTransport(registry, "local", func(ctx context.Context, logger log.ContextLogger, tag string, options option.LocalDNSServerOptions) (adapter.DNSTransport, error) {
			return newPlatformTransport(localTransport, tag, options), nil
		})
	}

	return registry
}

func nekoboxAndroidServiceRegistry() *service.Registry {
	registry := service.NewRegistry()

	return registry
}

func nekoboxAndroidCertificateProviderRegistry() *certificate.Registry {
	return certificate.NewRegistry()
}
