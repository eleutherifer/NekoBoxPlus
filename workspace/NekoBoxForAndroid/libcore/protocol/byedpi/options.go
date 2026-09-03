package byedpi

import "github.com/sagernet/sing-box/option"

type OutboundOptions struct {
	option.DialerOptions
	CLI string `json:"cli,omitempty"`
}
