package adapter

// DNSTransportWithOutbound exposes the outbound detour used by a DNS transport.
// The boolean is false for transports which do not dial an upstream server.
type DNSTransportWithOutbound interface {
	DNSTransport
	DNSOutbound() (tag string, available bool)
}
