package libcore

import (
	"testing"

	M "github.com/sagernet/sing/common/metadata"

	"libcore/protocol/trusttunnel/sing-trusttunnel/tturl"
)

func TestParseTrustTunnelLinkWithoutCertificateKeepsCertificateEmpty(t *testing.T) {
	link, err := (&TrustTunnelURL{
		Host:             "example.com",
		Port:             443,
		Username:         "user",
		Password:         "pass",
		SkipVerification: true,
	}).Build()
	if err != nil {
		t.Fatal(err)
	}

	parsed, err := ParseTrustTunnelLink(link)
	if err != nil {
		t.Fatal(err)
	}

	if parsed.Certificate != "" {
		t.Fatalf("expected empty certificate, got %q", parsed.Certificate)
	}
}

func TestParseTrustTunnelLinkUsesHostnameAsServerName(t *testing.T) {
	link, err := (&tturl.URL{
		Hostname: "sni.example.com",
		Addresses: []M.Socksaddr{
			M.ParseSocksaddrHostPort("192.0.2.1", 443),
		},
		Username: "user",
		Password: "pass",
	}).Build()
	if err != nil {
		t.Fatal(err)
	}

	parsed, err := ParseTrustTunnelLink(link)
	if err != nil {
		t.Fatal(err)
	}

	if parsed.ServerName != "sni.example.com" {
		t.Fatalf("expected hostname as server name, got %q", parsed.ServerName)
	}
}

func TestParseTrustTunnelLinkCustomSNIOverridesHostname(t *testing.T) {
	link, err := (&tturl.URL{
		Hostname: "server.example.com",
		Addresses: []M.Socksaddr{
			M.ParseSocksaddrHostPort("192.0.2.1", 443),
		},
		CustomSNI: "custom.example.com",
		Username:  "user",
		Password:  "pass",
	}).Build()
	if err != nil {
		t.Fatal(err)
	}

	parsed, err := ParseTrustTunnelLink(link)
	if err != nil {
		t.Fatal(err)
	}

	if parsed.ServerName != "custom.example.com" {
		t.Fatalf("expected custom SNI as server name, got %q", parsed.ServerName)
	}
}

func TestTrustTunnelLinkPreservesClientRandomPrefix(t *testing.T) {
	link, err := (&TrustTunnelURL{
		Host:               "example.com",
		Port:               443,
		Username:           "user",
		Password:           "pass",
		ClientRandomPrefix: "a0b0/f0f0",
	}).Build()
	if err != nil {
		t.Fatal(err)
	}

	parsed, err := ParseTrustTunnelLink(link)
	if err != nil {
		t.Fatal(err)
	}

	if parsed.ClientRandomPrefix != "a0b0/f0f0" {
		t.Fatalf("expected client random prefix to round-trip, got %q", parsed.ClientRandomPrefix)
	}
}
