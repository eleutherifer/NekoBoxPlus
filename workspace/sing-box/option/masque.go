package option

import (
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"net"
	"net/netip"

	"github.com/sagernet/sing/common/json/badoption"
)

type MASQUEOutboundOptions struct {
	DialerOptions
	System                  bool                             `json:"system,omitempty"`
	Name                    string                           `json:"name,omitempty"`
	AllowedIPs              badoption.Listable[netip.Prefix] `json:"allowed_ips,omitempty"`
	UseHTTP2                bool                             `json:"use_http2,omitempty"`
	Transport               string                           `json:"transport,omitempty"`
	UseIPv6                 bool                             `json:"use_ipv6,omitempty"`
	Profile                 CloudflareProfile                `json:"profile,omitempty"`
	Config                  *MASQUEConfig                    `json:"config,omitempty"`
	UDPTimeout              badoption.Duration               `json:"udp_timeout,omitempty"`
	UDPKeepalivePeriod      badoption.Duration               `json:"udp_keepalive_period,omitempty"`
	UDPInitialPacketSize    uint16                           `json:"udp_initial_packet_size,omitempty"`
	DisablePathMTUDiscovery bool                             `json:"disable_path_mtu_discovery,omitempty"`
	H3FallbackTimeout       badoption.Duration               `json:"h3_fallback_timeout,omitempty"`
	MTU                     uint32                           `json:"mtu,omitempty"`
	ReconnectDelay          badoption.Duration               `json:"reconnect_delay,omitempty"`
	MASQUEOutboundTLSOptionsContainer
}

type MASQUEOutboundTLSOptions struct {
	Insecure              bool                                `json:"insecure,omitempty"`
	CipherSuites          badoption.Listable[string]          `json:"cipher_suites,omitempty"`
	CurvePreferences      badoption.Listable[CurvePreference] `json:"curve_preferences,omitempty"`
	Fragment              bool                                `json:"fragment,omitempty"`
	FragmentFallbackDelay badoption.Duration                  `json:"fragment_fallback_delay,omitempty"`
	RecordFragment        bool                                `json:"record_fragment,omitempty"`
	KernelTx              bool                                `json:"kernel_tx,omitempty"`
	KernelRx              bool                                `json:"kernel_rx,omitempty"`
	SNI                   string                              `json:"sni,omitempty"`
}

type MASQUEOutboundTLSOptionsContainer struct {
	TLS *MASQUEOutboundTLSOptions `json:"tls,omitempty"`
}

type CloudflareProfile struct {
	ID         string `json:"id,omitempty"`
	AuthToken  string `json:"auth_token,omitempty"`
	PrivateKey string `json:"private_key,omitempty"`
	Recreate   bool   `json:"recreate,omitempty"`
	Detour     string `json:"detour,omitempty"`
}

type MASQUEConfig struct {
	PrivateKey     string `json:"private_key"`      // Base64-encoded ECDSA private key
	EndpointV4     string `json:"endpoint_v4"`      // IPv4 address of the endpoint
	EndpointV6     string `json:"endpoint_v6"`      // IPv6 address of the endpoint
	EndpointH2V4   string `json:"endpoint_h2_v4"`   // IPv4 address used in HTTP/2 mode
	EndpointH2V6   string `json:"endpoint_h2_v6"`   // IPv6 address used in HTTP/2 mode
	EndpointPubKey string `json:"endpoint_pub_key"` // PEM-encoded ECDSA public key of the endpoint to verify against
	License        string `json:"license"`          // Application license key
	ID             string `json:"id"`               // Device unique identifier
	AccessToken    string `json:"access_token"`     // Authentication token for API access
	IPv4           string `json:"ipv4"`             // Assigned IPv4 address
	IPv6           string `json:"ipv6"`             // Assigned IPv6 address
}

func (c *MASQUEConfig) GetEcPrivateKey() (*ecdsa.PrivateKey, error) {
	privKeyB64, err := base64.StdEncoding.DecodeString(c.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("failed to decode private key: %v", err)
	}
	privKey, err := x509.ParseECPrivateKey(privKeyB64)
	if err != nil {
		return nil, fmt.Errorf("failed to parse private key: %v", err)
	}
	return privKey, nil
}

func (c *MASQUEConfig) GetEcEndpointPublicKey() (*ecdsa.PublicKey, error) {
	endpointPubKeyB64, _ := pem.Decode([]byte(c.EndpointPubKey))
	if endpointPubKeyB64 == nil {
		return nil, fmt.Errorf("failed to decode endpoint public key")
	}

	pubKey, err := x509.ParsePKIXPublicKey(endpointPubKeyB64.Bytes)
	if err != nil {
		return nil, fmt.Errorf("failed to parse public key: %v", err)
	}

	ecPubKey, ok := pubKey.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("failed to assert public key as ECDSA")
	}

	return ecPubKey, nil
}

func (c *MASQUEConfig) SelectEndpointFromConfig(useHTTP2 bool, useIPv6 bool, port int) (net.Addr, error) {
	v4 := c.EndpointV4
	v6 := c.EndpointV6
	if useHTTP2 {
		if c.EndpointH2V4 != "" {
			v4 = c.EndpointH2V4
		}
		if c.EndpointH2V6 != "" {
			v6 = c.EndpointH2V6
		}
	}
	if useIPv6 {
		ip := net.ParseIP(v6)
		if ip == nil {
			return nil, fmt.Errorf("invalid endpoint_v6 value %q", v6)
		}
		if useHTTP2 {
			return &net.TCPAddr{IP: ip, Port: port}, nil
		}
		return &net.UDPAddr{IP: ip, Port: port}, nil
	}
	ip := net.ParseIP(v4)
	if ip == nil {
		return nil, fmt.Errorf("invalid endpoint_v4 value %q", v4)
	}
	if useHTTP2 {
		return &net.TCPAddr{IP: ip, Port: port}, nil
	}
	return &net.UDPAddr{IP: ip, Port: port}, nil
}
