package option

import "github.com/sagernet/sing/common/json/badoption"

type MieruOutboundOptions struct {
	DialerOptions
	ServerOptions
	ServerPortRanges badoption.Listable[string] `json:"server_ports,omitempty"`
	Transport        string                     `json:"transport,omitempty"`
	UserName         string                     `json:"username,omitempty"`
	Password         string                     `json:"password,omitempty"`
	Multiplexing     string                     `json:"multiplexing,omitempty"`
	HandshakeMode    string                     `json:"handshake_mode,omitempty"`
	TrafficPattern   string                     `json:"traffic_pattern,omitempty"`
}

type MieruInboundOptions struct {
	ListenOptions
	ListenPorts         badoption.Listable[string] `json:"listen_ports,omitempty"`
	Users               []MieruUser                `json:"users,omitempty"`
	Transport           string                     `json:"transport,omitempty"`
	TrafficPattern      string                     `json:"traffic_pattern,omitempty"`
	UserHintIsMandatory bool                       `json:"user_hint_is_mandatory,omitempty"`
}

type MieruUser struct {
	Name     string `json:"name,omitempty"`
	Password string `json:"password,omitempty"`
}
