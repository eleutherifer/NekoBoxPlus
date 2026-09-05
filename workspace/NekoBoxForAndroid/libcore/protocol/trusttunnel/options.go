package trusttunnel

import "github.com/sagernet/sing-box/option"

type OutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	Username              string `json:"username,omitempty"`
	Password              string `json:"password,omitempty"`
	HealthCheck           bool   `json:"health_check,omitempty"`
	ClientRandomPrefix    string `json:"client_random_prefix,omitempty"`
	QUIC                  bool   `json:"quic,omitempty"`
	ForceQUIC             bool   `json:"force_quic,omitempty"`
	UseCronetQUIC         bool   `json:"use_cronet_quic,omitempty"`
	UseCronetHTTPS        bool   `json:"use_cronet_https,omitempty"`
	QUICCongestionControl string `json:"quic_congestion_control,omitempty"`
	option.OutboundTLSOptionsContainer
}
