package option

import "github.com/sagernet/sing/common/json/badoption"

type BalancerDNSServerOptions struct {
	Servers       []DNSServerOptions `json:"servers,omitempty"`
	QueryDeadline badoption.Duration `json:"query_deadline,omitempty"`
}
