package transport

import "github.com/sagernet/sing-box/adapter"

func (t *BalancerTransport) DNSOutbound() (string, bool) {
	if len(t.children) == 0 {
		return "", false
	}
	var selected string
	for index, child := range t.children {
		childOutbound, loaded := child.transport.(adapter.DNSTransportWithOutbound)
		if !loaded {
			return "", false
		}
		outbound, available := childOutbound.DNSOutbound()
		if !available {
			return "", false
		}
		if index == 0 {
			selected = outbound
		} else if outbound != selected {
			return "", false
		}
	}
	return selected, true
}
