package dialer

import (
	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
)

// PeerDomainQueryOptions selects a DNS transport which can resolve an endpoint
// peer without routing the lookup back through the endpoint being initialized.
func PeerDomainQueryOptions(
	manager adapter.DNSTransportManager,
	endpointTag string,
	detourTag string,
	fallback adapter.DNSQueryOptions,
) (adapter.DNSQueryOptions, error) {
	if manager == nil {
		return adapter.DNSQueryOptions{}, E.New("missing DNS transport manager")
	}
	if detourTag == "" {
		return fallback, nil
	}

	if transportUsesOutbound(fallback.Transport, detourTag) {
		return fallback, nil
	}
	for _, transport := range manager.Transports() {
		if transportUsesOutbound(transport, detourTag) {
			fallback.Transport = transport
			return fallback, nil
		}
	}
	if transportIsSafeFallback(fallback.Transport, endpointTag) {
		return fallback, nil
	}
	defaultTransport := manager.Default()
	if transportIsSafeFallback(defaultTransport, endpointTag) {
		fallback.Transport = defaultTransport
		return fallback, nil
	}
	return adapter.DNSQueryOptions{}, E.New(
		"no safe DNS transport for endpoint[", endpointTag,
		"] detoured through outbound[", detourTag, "]",
	)
}

func transportUsesOutbound(transport adapter.DNSTransport, outboundTag string) bool {
	transportOutbound, loaded := transport.(adapter.DNSTransportWithOutbound)
	if !loaded {
		return false
	}
	tag, available := transportOutbound.DNSOutbound()
	return available && tag == outboundTag
}

func transportIsSafeFallback(transport adapter.DNSTransport, endpointTag string) bool {
	if transport == nil {
		return false
	}
	transportOutbound, loaded := transport.(adapter.DNSTransportWithOutbound)
	if !loaded {
		return true
	}
	tag, available := transportOutbound.DNSOutbound()
	return !available || tag != endpointTag
}
