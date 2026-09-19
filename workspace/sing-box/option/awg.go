package option

import (
	"net/netip"
	"strconv"
	"strings"

	"github.com/goccy/go-json"
	"github.com/sagernet/sing/common/json/badoption"
)

type AwgUint32Range string

func (r AwgUint32Range) String() string {
	return string(r)
}

func (r AwgUint32Range) IsZero() bool {
	if r == "" {
		return true
	}
	minValue, maxValue, err := parseAwgUint32Range(string(r))
	return err == nil && minValue == 0 && maxValue == 0
}

func (r AwgUint32Range) MarshalJSON() ([]byte, error) {
	value := string(r)
	if !strings.Contains(value, "-") {
		parsedValue, err := strconv.ParseUint(value, 10, 32)
		if err == nil {
			return json.Marshal(uint32(parsedValue))
		}
	}
	return json.Marshal(value)
}

func (r *AwgUint32Range) UnmarshalJSON(content []byte) error {
	var stringValue string
	if err := json.Unmarshal(content, &stringValue); err == nil {
		if stringValue == "" {
			*r = ""
			return nil
		}
		if _, _, err = parseAwgUint32Range(stringValue); err != nil {
			return err
		}
		*r = AwgUint32Range(stringValue)
		return nil
	}

	var numberValue uint32
	if err := json.Unmarshal(content, &numberValue); err != nil {
		return err
	}
	*r = AwgUint32Range(strconv.FormatUint(uint64(numberValue), 10))
	return nil
}

func (r AwgUint32Range) Validate() error {
	if r == "" {
		return nil
	}
	_, _, err := parseAwgUint32Range(string(r))
	return err
}

func parseAwgUint32Range(value string) (uint32, uint32, error) {
	minString, maxString, isRange := strings.Cut(value, "-")
	minValue, err := strconv.ParseUint(minString, 10, 32)
	if err != nil {
		return 0, 0, err
	}
	if !isRange {
		return uint32(minValue), uint32(minValue), nil
	}
	if strings.Contains(maxString, "-") {
		return 0, 0, strconv.ErrSyntax
	}
	maxValue, err := strconv.ParseUint(maxString, 10, 32)
	if err != nil {
		return 0, 0, err
	}
	if minValue > maxValue {
		return 0, 0, strconv.ErrRange
	}
	return uint32(minValue), uint32(maxValue), nil
}

type AwgEndpointOptions struct {
	UseIntegratedTun       bool                             `json:"useIntegratedTun"`
	PrivateKey             string                           `json:"private_key"`
	Address                badoption.Listable[netip.Prefix] `json:"address"`
	MTU                    uint32                           `json:"mtu,omitempty"`
	ListenPort             uint16                           `json:"listen_port,omitempty"`
	Jc                     int                              `json:"jc,omitempty"`
	Jmin                   int                              `json:"jmin,omitempty"`
	Jmax                   int                              `json:"jmax,omitempty"`
	S1                     int                              `json:"s1,omitempty"`
	S2                     int                              `json:"s2,omitempty"`
	S3                     int                              `json:"s3,omitempty"`
	S4                     int                              `json:"s4,omitempty"`
	H1                     string                           `json:"h1,omitempty"`
	H2                     string                           `json:"h2,omitempty"`
	H3                     string                           `json:"h3,omitempty"`
	H4                     string                           `json:"h4,omitempty"`
	I1                     string                           `json:"i1,omitempty"`
	I2                     string                           `json:"i2,omitempty"`
	I3                     string                           `json:"i3,omitempty"`
	I4                     string                           `json:"i4,omitempty"`
	I5                     string                           `json:"i5,omitempty"`
	HeaderProtectionKey    string                           `json:"header_protection_key,omitempty"`
	ContentPaddingAddition AwgUint32Range                   `json:"content_padding_addition,omitempty"`
	RekeyAfterTime         AwgUint32Range                   `json:"rekey_after_time,omitempty"`
	RekeyTimeout           AwgUint32Range                   `json:"rekey_timeout,omitempty"`
	RejectAfterTime        AwgUint32Range                   `json:"reject_after_time,omitempty"`
	KeepaliveTimeout       AwgUint32Range                   `json:"keepalive_timeout,omitempty"`
	MaxHandshakeAttempts   AwgUint32Range                   `json:"max_handshake_attempts,omitempty"`
	Peers                  []AwgPeerOptions                 `json:"peers,omitempty"`
	DialerOptions
}

type AwgPeerOptions struct {
	Address                     string                           `json:"address,omitempty"`
	Port                        uint16                           `json:"port,omitempty"`
	PublicKey                   string                           `json:"public_key,omitempty"`
	PresharedKey                string                           `json:"preshared_key,omitempty"`
	AllowedIPs                  badoption.Listable[netip.Prefix] `json:"allowed_ips,omitempty"`
	PersistentKeepaliveInterval AwgUint32Range                   `json:"persistent_keepalive_interval,omitempty"`
	Reserved                    []uint8                          `json:"reserved,omitempty"`
}
