package dns

func (a *TransportAdapter) DNSOutbound() (string, bool) {
	return a.outbound, a.hasOutbound
}
