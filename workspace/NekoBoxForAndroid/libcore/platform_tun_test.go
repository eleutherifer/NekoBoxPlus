package libcore

import (
	"net/netip"
	"reflect"
	"strings"
	"testing"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/ranges"
)

func mustPrefix(cidr string) netip.Prefix {
	p, err := netip.ParsePrefix(cidr)
	if err != nil {
		panic(err)
	}
	return p
}

func dualStackOptions() *tun.Options {
	return &tun.Options{
		MTU:       9000,
		AutoRoute: true,
		Inet4Address: []netip.Prefix{
			mustPrefix("172.19.0.1/30"),
		},
		Inet6Address: []netip.Prefix{
			mustPrefix("fdfe:dcba:9876::1/126"),
		},
	}
}

func TestBuildAndroidTunPayloadDualStack(t *testing.T) {
	payload, err := buildAndroidTunPayload(dualStackOptions())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if payload.Version != androidTunPayloadVersion {
		t.Fatalf("version = %d, want %d", payload.Version, androidTunPayloadVersion)
	}
	if payload.MTU != 9000 {
		t.Fatalf("mtu = %d, want 9000", payload.MTU)
	}
	if !payload.AutoRoute {
		t.Fatal("auto_route must be true")
	}
	if payload.Inet4Address != "172.19.0.1/30" {
		t.Fatalf("inet4_address = %q", payload.Inet4Address)
	}
	if payload.Inet4DNSServer != "172.19.0.2" {
		t.Fatalf("inet4_dns_server = %q, want 172.19.0.2", payload.Inet4DNSServer)
	}
	if payload.Inet6Address != "fdfe:dcba:9876::1/126" {
		t.Fatalf("inet6_address = %q", payload.Inet6Address)
	}
	if payload.Inet6DNSServer != "fdfe:dcba:9876::2" {
		t.Fatalf("inet6_dns_server = %q, want fdfe:dcba:9876::2", payload.Inet6DNSServer)
	}
	if !reflect.DeepEqual(payload.Inet4Routes, []string{"0.0.0.0/0"}) {
		t.Fatalf("inet4_routes = %v, want [0.0.0.0/0]", payload.Inet4Routes)
	}
	if !reflect.DeepEqual(payload.Inet6Routes, []string{"::/0"}) {
		t.Fatalf("inet6_routes = %v, want [::/0]", payload.Inet6Routes)
	}
}

func TestBuildAndroidTunPayloadIPv4Only(t *testing.T) {
	opts := dualStackOptions()
	opts.Inet6Address = nil
	payload, err := buildAndroidTunPayload(opts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if payload.Inet6Address != "" || payload.Inet6DNSServer != "" {
		t.Fatal("ipv4-only plan must not declare inet6 fields")
	}
	if payload.Inet4Address == "" || payload.Inet4DNSServer == "" {
		t.Fatal("ipv4-only plan must declare inet4 fields")
	}
	if len(payload.Inet6Routes) != 0 {
		t.Fatalf("inet6_routes = %v, want empty", payload.Inet6Routes)
	}
	if !reflect.DeepEqual(payload.Inet4Routes, []string{"0.0.0.0/0"}) {
		t.Fatalf("inet4_routes = %v, want [0.0.0.0/0]", payload.Inet4Routes)
	}
}

func TestBuildAndroidTunPayloadIPv6Only(t *testing.T) {
	opts := dualStackOptions()
	opts.Inet4Address = nil
	payload, err := buildAndroidTunPayload(opts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if payload.Inet4Address != "" || payload.Inet4DNSServer != "" {
		t.Fatal("ipv6-only plan must not declare inet4 fields")
	}
	if payload.Inet6Address == "" || payload.Inet6DNSServer == "" {
		t.Fatal("ipv6-only plan must declare inet6 fields")
	}
	if len(payload.Inet4Routes) != 0 {
		t.Fatalf("inet4_routes = %v, want empty", payload.Inet4Routes)
	}
	if !reflect.DeepEqual(payload.Inet6Routes, []string{"::/0"}) {
		t.Fatalf("inet6_routes = %v, want [::/0]", payload.Inet6Routes)
	}
}

func TestBuildAndroidTunPayloadFlattensRouteAddressByFamily(t *testing.T) {
	opts := dualStackOptions()
	// Bypass-LAN style explicit route addresses: the Android side owns the LAN
	// exclusions, so only the claimed public ranges plus the in-TUN gateway are
	// advertised. BuildAutoRouteRanges(true) must reproduce them verbatim.
	opts.Inet4RouteAddress = []netip.Prefix{
		mustPrefix("172.19.0.2/32"),
		mustPrefix("198.18.0.0/15"),
	}
	opts.Inet6RouteAddress = []netip.Prefix{
		mustPrefix("2000::/3"),
	}
	payload, err := buildAndroidTunPayload(opts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	wantV4 := []string{"172.19.0.2/32", "198.18.0.0/15"}
	wantV6 := []string{"2000::/3"}
	if !reflect.DeepEqual(payload.Inet4Routes, wantV4) {
		t.Fatalf("inet4_routes = %v, want %v", payload.Inet4Routes, wantV4)
	}
	if !reflect.DeepEqual(payload.Inet6Routes, wantV6) {
		t.Fatalf("inet6_routes = %v, want %v", payload.Inet6Routes, wantV6)
	}
}

func TestBuildAndroidTunPayloadRouteExclude(t *testing.T) {
	opts := dualStackOptions()
	opts.Inet4RouteExcludeAddress = []netip.Prefix{
		mustPrefix("10.0.0.0/8"),
	}
	payload, err := buildAndroidTunPayload(opts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// 0.0.0.0/0 minus 10.0.0.0/8 must not contain the 10/8 range and must keep
	// covering the rest of IPv4. The exact flattened set is sing-tun's job; we
	// only assert the exclusion actually happened.
	if containsPrefix(payload.Inet4Routes, "10.0.0.0/8") {
		t.Fatalf("inet4_routes must exclude 10.0.0.0/8, got %v", payload.Inet4Routes)
	}
	if !routesCoverPrefix(payload.Inet4Routes, "8.8.8.8/32") {
		t.Fatalf("inet4_routes must still cover 8.8.8.8, got %v", payload.Inet4Routes)
	}
}

func TestBuildAndroidTunPayloadInvalidPrefixNoNextAddress(t *testing.T) {
	opts := dualStackOptions()
	// /32 has no room for a second address, so the in-TUN DNS derivation fails.
	opts.Inet4Address = []netip.Prefix{mustPrefix("172.19.0.1/32")}
	if _, err := buildAndroidTunPayload(opts); err == nil {
		t.Fatal("expected error for /32 inet4 prefix with no room for dns address")
	}
}

func TestBuildAndroidTunPayloadMissingAddresses(t *testing.T) {
	opts := &tun.Options{MTU: 9000, AutoRoute: true}
	if _, err := buildAndroidTunPayload(opts); err == nil {
		t.Fatal("expected error when no interface address is configured")
	}
}

func TestBuildAndroidTunPayloadRejectsMultipleAddressesPerFamily(t *testing.T) {
	opts := dualStackOptions()
	opts.Inet4Address = append(opts.Inet4Address, mustPrefix("172.19.0.5/30"))
	if _, err := buildAndroidTunPayload(opts); err == nil {
		t.Fatal("expected error when multiple IPv4 interface addresses are configured")
	}

	opts = dualStackOptions()
	opts.Inet6Address = append(opts.Inet6Address, mustPrefix("fdfe:dcba:9876::5/126"))
	if _, err := buildAndroidTunPayload(opts); err == nil {
		t.Fatal("expected error when multiple IPv6 interface addresses are configured")
	}
}

func TestBuildAndroidTunPayloadNilOptions(t *testing.T) {
	if _, err := buildAndroidTunPayload(nil); err == nil {
		t.Fatal("expected error for nil options")
	}
}

func TestBuildAndroidTunPayloadDeterministicRouteFields(t *testing.T) {
	// IPv4-only plan: inet6_routes must be a non-nil empty slice so the JSON
	// field is always emitted as [] rather than omitted/null.
	opts := dualStackOptions()
	opts.Inet6Address = nil
	payload, err := buildAndroidTunPayload(opts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if payload.Inet6Routes == nil {
		t.Fatal("inet6_routes must be non-nil even when empty")
	}
	if payload.Inet4Routes == nil {
		t.Fatal("inet4_routes must be non-nil")
	}
}

func TestMarshalAndroidTunPayloadRoundTrip(t *testing.T) {
	encoded, err := marshalAndroidTunPayload(dualStackOptions())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// The stable contract must not leak internal sing-tun field names.
	for _, leaked := range []string{"Inet4Address", "FileDescriptor", "IncludePackage"} {
		if strings.Contains(encoded, leaked) {
			t.Fatalf("payload JSON leaks internal field %q: %s", leaked, encoded)
		}
	}
	if !strings.Contains(encoded, "\"version\":") {
		t.Fatalf("payload JSON missing version field: %s", encoded)
	}
}

func TestRejectUnsupportedTunOptions(t *testing.T) {
	cases := []struct {
		name string
		mut  func(*tun.Options)
	}{
		{"include_uid", func(o *tun.Options) { o.IncludeUID = []ranges.Range[uint32]{ranges.NewSingle[uint32](1000)} }},
		{"exclude_uid", func(o *tun.Options) { o.ExcludeUID = []ranges.Range[uint32]{ranges.NewSingle[uint32](1000)} }},
		{"include_android_user", func(o *tun.Options) { o.IncludeAndroidUser = []int{0} }},
		{"include_package", func(o *tun.Options) { o.IncludePackage = []string{"app"} }},
		{"exclude_package", func(o *tun.Options) { o.ExcludePackage = []string{"app"} }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			opts := &tun.Options{}
			tc.mut(opts)
			if err := rejectUnsupportedTunOptions(opts); err == nil {
				t.Fatalf("expected rejection for %s", tc.name)
			}
		})
	}
	if err := rejectUnsupportedTunOptions(&tun.Options{}); err != nil {
		t.Fatalf("clean options must be accepted, got: %v", err)
	}
}

// --- helpers ---

func containsPrefix(cidrs []string, want string) bool {
	target, err := netip.ParsePrefix(want)
	if err != nil {
		return false
	}
	for _, cidr := range cidrs {
		p, err := netip.ParsePrefix(cidr)
		if err != nil {
			continue
		}
		if p == target {
			return true
		}
	}
	return false
}

func routesCoverPrefix(cidrs []string, want string) bool {
	target, err := netip.ParsePrefix(want)
	if err != nil {
		return false
	}
	targetAddr := target.Addr()
	for _, cidr := range cidrs {
		p, err := netip.ParsePrefix(cidr)
		if err != nil {
			continue
		}
		if p.Contains(targetAddr) {
			return true
		}
	}
	return false
}
