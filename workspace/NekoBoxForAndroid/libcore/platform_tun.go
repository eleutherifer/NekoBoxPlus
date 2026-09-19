package libcore

import (
	"encoding/json"
	"fmt"
	"net/netip"

	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
)

// androidTunPayloadVersion is the version of the JSON contract exchanged between
// the Go core (libcore) and the Android VpnService through
// BoxPlatformInterface.OpenTun. Bump it whenever the field set changes in a
// backwards-incompatible way; the Android side rejects unknown versions.
const androidTunPayloadVersion = 1

// androidTunPayload is the stable, versioned contract that replaces the raw
// github.com/sagernet/sing-tun option structs on the JNI boundary.
//
// sing-tun option structs are version-dependent and not a stable public
// surface, so marshaling them directly (the previous behavior) leaked internal
// field names across versions. This struct is the only thing the Android side
// is allowed to depend on. Field names are explicit snake_case on purpose.
type androidTunPayload struct {
	// Version is the payload contract version (androidTunPayloadVersion).
	Version int `json:"version"`
	// MTU is the TUN MTU in bytes, already defaulted by sing-box.
	MTU uint32 `json:"mtu"`
	// AutoRoute mirrors sing-tun Options.AutoRoute.
	AutoRoute bool `json:"auto_route"`
	// Inet4Address is the IPv4 interface address as a CIDR (e.g. "172.19.0.1/30").
	// Empty when the plan is IPv6-only.
	Inet4Address string `json:"inet4_address,omitempty"`
	// Inet6Address is the IPv6 interface address as a CIDR.
	// Empty when the plan is IPv4-only.
	Inet6Address string `json:"inet6_address,omitempty"`
	// Inet4DNSServer is the in-TUN IPv4 DNS server address (next address in the
	// configured IPv4 prefix). Empty when the plan is IPv6-only.
	Inet4DNSServer string `json:"inet4_dns_server,omitempty"`
	// Inet6DNSServer is the in-TUN IPv6 DNS server address. Empty for IPv4-only.
	Inet6DNSServer string `json:"inet6_dns_server,omitempty"`
	// Inet4Routes are the flattened IPv4 route ranges to claim on the VPN.
	// Always non-nil (may be empty) so the JSON shape is deterministic.
	Inet4Routes []string `json:"inet4_routes"`
	// Inet6Routes are the flattened IPv6 route ranges to claim on the VPN.
	Inet6Routes []string `json:"inet6_routes"`
}

// buildAndroidTunPayload translates sing-tun options into the stable Android
// TUN payload.
//
// The DNS server for each configured family is derived as the next address
// inside the family's TUN prefix, which matches what sing-tun itself serves DNS
// hijacking on (see sing-tun Options.Inet4GatewayAddr / system stack DNS listen
// address). Families that are not configured are omitted entirely so the Android
// side never declares an unrequested address family.
func buildAndroidTunPayload(options *tun.Options) (*androidTunPayload, error) {
	if options == nil {
		return nil, E.New("android: tun options are nil")
	}
	if len(options.Inet4Address) == 0 && len(options.Inet6Address) == 0 {
		return nil, E.New("android: tun requires at least one interface address")
	}
	// VpnService.Builder can install several addresses, but sing-tun derives its
	// Android DNS endpoint from the first address of each family. Keep the bridge
	// contract explicit until it can carry and validate every address instead of
	// silently dropping additional configured addresses.
	if len(options.Inet4Address) > 1 || len(options.Inet6Address) > 1 {
		return nil, E.New("android: multiple tun addresses per family are unsupported")
	}

	payload := &androidTunPayload{
		Version:     androidTunPayloadVersion,
		MTU:         options.MTU,
		AutoRoute:   options.AutoRoute,
		Inet4Routes: []string{},
		Inet6Routes: []string{},
	}

	if len(options.Inet4Address) > 0 {
		v4 := options.Inet4Address[0]
		payload.Inet4Address = v4.String()
		dns, err := tunDNSServerAddress(v4)
		if err != nil {
			return nil, err
		}
		payload.Inet4DNSServer = dns.String()
	}
	if len(options.Inet6Address) > 0 {
		v6 := options.Inet6Address[0]
		payload.Inet6Address = v6.String()
		dns, err := tunDNSServerAddress(v6)
		if err != nil {
			return nil, err
		}
		payload.Inet6DNSServer = dns.String()
	}

	routes, err := options.BuildAutoRouteRanges(true)
	if err != nil {
		return nil, fmt.Errorf("android: build auto route ranges: %w", err)
	}
	for _, route := range routes {
		switch {
		case route.Addr().Is4():
			payload.Inet4Routes = append(payload.Inet4Routes, route.String())
		case route.Addr().Is6():
			payload.Inet6Routes = append(payload.Inet6Routes, route.String())
		}
	}
	return payload, nil
}

// marshalAndroidTunPayload builds and JSON-encodes the Android TUN payload.
func marshalAndroidTunPayload(options *tun.Options) (string, error) {
	payload, err := buildAndroidTunPayload(options)
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return "", fmt.Errorf("android: marshal tun payload: %w", err)
	}
	return string(encoded), nil
}

// tunDNSServerAddress returns the next address inside prefix, used as the
// in-TUN DNS server address. It mirrors sing-tun's gateway/DNS derivation on
// Android. A prefix with no room for a second address (e.g. a /32 or /128) is
// rejected, surfacing the misconfiguration instead of silently serving DNS on
// an address the platform never declares.
func tunDNSServerAddress(prefix netip.Prefix) (netip.Addr, error) {
	if !tun.HasNextAddress(prefix, 1) {
		return netip.Addr{}, E.New("android: no address available for in-tun dns in prefix ", prefix)
	}
	return prefix.Addr().Next(), nil
}

// rejectUnsupportedTunOptions fails fast on sing-tun options that the Android
// platform bridge cannot honor. Per-app routing is owned by NekoBox settings
// (allow/disallow applications) and must not be imported from the sing-box TUN
// configuration, so package and UID scope options are rejected rather than
// silently ignored.
func rejectUnsupportedTunOptions(options *tun.Options) error {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return E.New("android: unsupported uid options; use per-app routing in NekoBox settings")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return E.New("android: unsupported android_user option")
	}
	if len(options.IncludePackage) > 0 || len(options.ExcludePackage) > 0 {
		return E.New("android: unsupported package options; per-app routing is owned by NekoBox settings")
	}
	return nil
}
