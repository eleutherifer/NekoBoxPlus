package libcore

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"syscall"
	"time"

	E "github.com/sagernet/sing/common/exceptions"

	mDNS "github.com/miekg/dns"

	"libcore/protect"
)

// directDNSTimeout is the per-server deadline used when the caller did not
// provide one through the context.
const directDNSTimeout = 5 * time.Second

// dnsUDPDatagramSize matches the EDNS0 buffer size advertised by sing-box.
// Most answers fit in 512 bytes, but 4096 avoids truncation for larger ones.
const dnsUDPDatagramSize = 4096

// exchangeViaInterfaceDNS resolves the message directly against the current
// default network interface's DNS servers, using a VPN-protected UDP socket.
//
// This is the interface-aware fallback for the "local" DNS transport: when no
// platform network handle is available (android_res_nsend would otherwise fall
// back to bionic's hardcoded 8.8.8.8), we still resolve through the real
// interface DNS servers that the Android side reported via
// UpdatePlatformNetworkState.
func (p *platformLocalDNSTransport) exchangeViaInterfaceDNS(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	servers := resolveInterfaceDNSServers()
	if len(servers) == 0 {
		return nil, E.New("local DNS: no interface DNS servers available")
	}

	query, err := message.Pack()
	if err != nil {
		return nil, E.Cause(err, "pack query")
	}

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(directDNSTimeout)
	}

	var lastErr error
	for _, server := range servers {
		response, queryErr := directDNSQueryUDP(ctx, server, query, deadline)
		if queryErr != nil {
			lastErr = queryErr
			continue
		}
		if response.Truncated {
			response, queryErr = directDNSQueryTCP(ctx, server, query, deadline)
			if queryErr != nil {
				lastErr = queryErr
				continue
			}
		}
		return response, nil
	}
	if lastErr == nil {
		lastErr = E.New("local DNS: all interface DNS servers failed")
	}
	return nil, lastErr
}

// resolveInterfaceDNSServers returns the DNS servers of the current default
// network interface. When the default interface is unknown it falls back to the
// first physical interface that reports DNS servers. This works even when
// underlyingNetwork is null, because the interface list is built from all
// non-VPN internet networks (see NativeInterface.networkInterfaces).
func resolveInterfaceDNSServers() []netip.Addr {
	interfaces, err := readNetworkInterfaces()
	if err != nil {
		return nil
	}

	currentPlatformNetworkState.access.Lock()
	defaultName := currentPlatformNetworkState.defaultInterface.Name
	currentPlatformNetworkState.access.Unlock()

	var fallback []netip.Addr
	for _, networkInterface := range interfaces {
		servers := parseAddrList(networkInterface.DNSServers)
		if len(servers) == 0 {
			continue
		}
		// Prefer the default interface so queries leave the right network.
		if defaultName != "" && networkInterface.Name == defaultName {
			return servers
		}
		if fallback == nil {
			fallback = servers
		}
	}
	return fallback
}

func parseAddrList(raw []string) []netip.Addr {
	servers := make([]netip.Addr, 0, len(raw))
	for _, item := range raw {
		if addr, err := netip.ParseAddr(item); err == nil {
			servers = append(servers, addr)
		}
	}
	return servers
}

// directDNSQueryUDP sends a packed DNS query to a single server over a
// VPN-protected UDP socket and unpacks the response.
func directDNSQueryUDP(ctx context.Context, server netip.Addr, query []byte, deadline time.Time) (*mDNS.Msg, error) {
	network := "udp4"
	if server.Is6() {
		network = "udp6"
	}

	dialer := &net.Dialer{
		Control: protectSocketControl,
	}
	conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(server.String(), "53"))
	if err != nil {
		return nil, E.Cause(err, "dial interface DNS server")
	}
	defer conn.Close()

	if err := conn.SetDeadline(deadline); err != nil {
		return nil, E.Cause(err, "set deadline")
	}

	if _, err := conn.Write(query); err != nil {
		return nil, E.Cause(err, "write DNS query")
	}

	buffer := make([]byte, dnsUDPDatagramSize)
	n, err := conn.Read(buffer)
	if err != nil {
		return nil, E.Cause(err, "read DNS response")
	}

	var response mDNS.Msg
	if err := response.Unpack(buffer[:n]); err != nil {
		return nil, E.Cause(err, "unpack DNS response")
	}
	return &response, nil
}

// directDNSQueryTCP retries the same packed DNS query over TCP when UDP
// indicates truncation.
func directDNSQueryTCP(ctx context.Context, server netip.Addr, query []byte, deadline time.Time) (*mDNS.Msg, error) {
	network := "tcp4"
	if server.Is6() {
		network = "tcp6"
	}

	dialer := &net.Dialer{
		Control: protectSocketControl,
	}
	conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(server.String(), "53"))
	if err != nil {
		return nil, E.Cause(err, "dial interface DNS server over TCP")
	}
	defer conn.Close()

	if err := conn.SetDeadline(deadline); err != nil {
		return nil, E.Cause(err, "set TCP deadline")
	}

	lengthPrefix := make([]byte, 2)
	binary.BigEndian.PutUint16(lengthPrefix, uint16(len(query)))
	if _, err := conn.Write(lengthPrefix); err != nil {
		return nil, E.Cause(err, "write DNS query length")
	}
	if _, err := conn.Write(query); err != nil {
		return nil, E.Cause(err, "write DNS query over TCP")
	}

	if _, err := io.ReadFull(conn, lengthPrefix); err != nil {
		return nil, E.Cause(err, "read DNS response length")
	}
	responseLength := binary.BigEndian.Uint16(lengthPrefix)
	responseBytes := make([]byte, responseLength)
	if _, err := io.ReadFull(conn, responseBytes); err != nil {
		return nil, E.Cause(err, "read DNS response over TCP")
	}

	var response mDNS.Msg
	if err := response.Unpack(responseBytes); err != nil {
		return nil, E.Cause(err, "unpack TCP DNS response")
	}
	return &response, nil
}

// protectSocketControl routes every socket created by net.Dialer out of the
// local TUN: on Android the protector calls VpnService.protect on the file
// descriptor so the query reaches the physical interface DNS server directly.
func protectSocketControl(network, address string, c syscall.RawConn) error {
	var sockErr error
	if err := c.Control(func(fd uintptr) {
		sockErr = protect.FD(int(fd))
	}); err != nil {
		return err
	}
	return sockErr
}
