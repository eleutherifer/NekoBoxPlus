package awg

import (
	"encoding/base64"
	"net/netip"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/option"
)

func TestValidateObfuscationOptionsAcceptsRecommendedValues(t *testing.T) {
	err := validateObfuscationOptions(option.AwgEndpointOptions{
		Jc:   12,
		Jmin: 50,
		Jmax: 1000,
		S1:   64,
		S2:   64,
		S3:   64,
		S4:   64,
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestValidateObfuscationOptionsHeaderProtection(t *testing.T) {
	headerKey := base64.StdEncoding.EncodeToString(make([]byte, awgHeaderKeySize))
	valid := option.AwgEndpointOptions{
		HeaderProtectionKey: headerKey,
		S1:                  awgHeaderNonceSize,
		S2:                  awgHeaderNonceSize,
		S3:                  awgHeaderNonceSize,
		S4:                  awgHeaderNonceSize,
	}
	if err := validateObfuscationOptions(valid); err != nil {
		t.Fatal(err)
	}

	invalidPadding := valid
	invalidPadding.S4 = awgHeaderNonceSize - 1
	if err := validateObfuscationOptions(invalidPadding); err == nil {
		t.Fatal("expected insufficient padding error")
	}

	invalidKey := valid
	invalidKey.HeaderProtectionKey = base64.StdEncoding.EncodeToString(make([]byte, awgHeaderKeySize-1))
	if err := validateObfuscationOptions(invalidKey); err == nil {
		t.Fatal("expected invalid header key error")
	}
}

func TestGenIpcConfigAmneziaWG3(t *testing.T) {
	headerKeyBytes := make([]byte, awgHeaderKeySize)
	for index := range headerKeyBytes {
		headerKeyBytes[index] = byte(index)
	}
	privateKey := base64.StdEncoding.EncodeToString(make([]byte, 32))
	publicKey := base64.StdEncoding.EncodeToString(make([]byte, 32))
	options := option.AwgEndpointOptions{
		PrivateKey:             privateKey,
		HeaderProtectionKey:    base64.StdEncoding.EncodeToString(headerKeyBytes),
		ContentPaddingAddition: "10-20",
		RekeyAfterTime:         "100-120",
		RekeyTimeout:           "5",
		RejectAfterTime:        "180",
		KeepaliveTimeout:       "10-15",
		MaxHandshakeAttempts:   "20",
		Peers: []option.AwgPeerOptions{{
			PublicKey:                   publicKey,
			PersistentKeepaliveInterval: "22-30",
		}},
	}

	ipc, err := genIpcConfig(options, nil)
	if err != nil {
		t.Fatal(err)
	}
	expectedLines := []string{
		"header_protection_key=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
		"content_padding_addition=10-20",
		"rekey_after_time=100-120",
		"rekey_timeout=5",
		"reject_after_time=180",
		"keepalive_timeout=10-15",
		"max_handshake_attempts=20",
		"persistent_keepalive_interval=22-30",
	}
	for _, expectedLine := range expectedLines {
		if !strings.Contains(ipc, "\n"+expectedLine) {
			t.Fatalf("missing IPC line %q in:\n%s", expectedLine, ipc)
		}
	}
}

func TestValidateObfuscationOptionsRejectsUnsafeValues(t *testing.T) {
	tests := map[string]option.AwgEndpointOptions{
		"negative jc":     {Jc: -1},
		"excessive jc":    {Jc: awgMaxJunkPacketCount + 1, Jmin: 50, Jmax: 1000},
		"missing jmin":    {Jc: 1, Jmax: 1000},
		"missing jmax":    {Jc: 1, Jmin: 50},
		"inverted range":  {Jc: 1, Jmin: 1000, Jmax: 50},
		"excessive jmax":  {Jc: 1, Jmin: 50, Jmax: awgMaxJunkPacketSize + 1},
		"negative s1":     {S1: -1},
		"excessive s1":    {S1: awgMaxHandshakePad + 1},
		"excessive s4":    {S4: awgMaxTransportPad + 1},
		"standalone jmin": {Jmin: 50},
	}
	for name, options := range tests {
		t.Run(name, func(t *testing.T) {
			if err := validateObfuscationOptions(options); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

func TestResolveClientBindSinglePeerEndpoint(t *testing.T) {
	ipv6Endpoint := netip.MustParseAddrPort("[2606:4700:110:8dd6:7d68:3501:b0b8:d216]:2408")
	tests := map[string]struct {
		options option.AwgEndpointOptions
		want    netip.AddrPort
	}{
		"direct single peer": {
			options: option.AwgEndpointOptions{
				Peers: []option.AwgPeerOptions{{
					Address: ipv6Endpoint.Addr().String(),
					Port:    ipv6Endpoint.Port(),
				}},
			},
		},
		"detoured single peer": {
			options: option.AwgEndpointOptions{
				DialerOptions: option.DialerOptions{Detour: "proxy"},
				Peers: []option.AwgPeerOptions{{
					Address: ipv6Endpoint.Addr().String(),
					Port:    ipv6Endpoint.Port(),
				}},
			},
			want: ipv6Endpoint,
		},
		"detoured multi peer": {
			options: option.AwgEndpointOptions{
				DialerOptions: option.DialerOptions{Detour: "proxy"},
				Peers: []option.AwgPeerOptions{
					{
						Address: ipv6Endpoint.Addr().String(),
						Port:    ipv6Endpoint.Port(),
					},
					{
						Address: "192.0.2.1",
						Port:    2408,
					},
				},
			},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			endpoint, err := resolveClientBindSinglePeerEndpoint(test.options, nil)
			if err != nil {
				t.Fatal(err)
			}
			if endpoint != test.want {
				t.Fatalf("endpoint mismatch: got %v, want %v", endpoint, test.want)
			}
		})
	}
}

func TestResolveClientBindSinglePeerEndpointResolvesDetouredDomain(t *testing.T) {
	options := option.AwgEndpointOptions{
		DialerOptions: option.DialerOptions{Detour: "proxy"},
		Peers: []option.AwgPeerOptions{{
			Address: "example.com",
			Port:    2408,
		}},
	}
	resolvedAddr := netip.MustParseAddr("2001:db8::1")

	endpoint, err := resolveClientBindSinglePeerEndpoint(options, func(domain string) (netip.Addr, error) {
		if domain != "example.com" {
			t.Fatalf("domain mismatch: got %q", domain)
		}
		return resolvedAddr, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	want := netip.AddrPortFrom(resolvedAddr, 2408)
	if endpoint != want {
		t.Fatalf("endpoint mismatch: got %v, want %v", endpoint, want)
	}
}
