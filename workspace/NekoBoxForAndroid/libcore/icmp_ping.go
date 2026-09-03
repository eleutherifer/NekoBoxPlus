package libcore

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"libcore/device"
	"libcore/protect"
	"net/netip"
	"strings"
	"syscall"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing/common/control"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/xchacha20-poly1305/libping"
)

const icmpPingPayloadSize = 40

type PingResult struct {
	latency int32
	address string
}

func (r *PingResult) GetLatency() int32 { return r.latency }

func (r *PingResult) GetAddress() string { return r.address }

type icmpPingDNSExchange func(context.Context, *dns.Msg) (*dns.Msg, error)

// IcmpPing sends an ICMP echo request directly to a proxy server.
// The timeout is expressed in milliseconds.
func IcmpPing(host string, timeout int32) (latency int32, err error) {
	result, err := IcmpPingWithAddress(host, timeout)
	if err != nil {
		return -1, err
	}
	return result.latency, nil
}

func IcmpPingWithAddress(host string, timeout int32) (result *PingResult, err error) {
	defer device.DeferPanicToError("ICMPPing", func(panicErr error) { err = panicErr })
	host = strings.TrimSpace(host)
	if host == "" {
		return nil, errors.New("ICMP ping host is empty")
	}
	probeTimeout := time.Duration(timeout) * time.Millisecond
	if probeTimeout <= 0 {
		return nil, errors.New("ICMP ping timeout must be positive")
	}

	ctx, cancel := context.WithTimeout(context.Background(), probeTimeout)
	defer cancel()

	acquireProtect()
	defer releaseProtect()

	addresses, err := resolveICMPPingAddresses(ctx, host, gLocalDNSTransport)
	if err != nil {
		return nil, err
	}

	payload := make([]byte, icmpPingPayloadSize)
	if _, err = rand.Read(payload); err != nil {
		return nil, fmt.Errorf("generate ICMP ping payload: %w", err)
	}

	var probeErrors []error
	for _, address := range addresses {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		var elapsed time.Duration
		elapsed, err = libping.IcmpPing(
			ctx,
			M.Socksaddr{Addr: address},
			payload,
			icmpPingProtectControl,
		)
		if err == nil {
			return &PingResult{latency: int32(elapsed.Milliseconds()), address: address.String()}, nil
		}
		probeErrors = append(probeErrors, fmt.Errorf("ping %s: %w", address, err))
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	return nil, errors.Join(probeErrors...)
}

func resolveICMPPingAddresses(
	ctx context.Context,
	host string,
	localTransport *platformLocalDNSTransport,
) ([]netip.Addr, error) {
	if address, err := netip.ParseAddr(strings.Trim(host, "[]")); err == nil {
		return []netip.Addr{address.Unmap()}, nil
	}
	if localTransport == nil {
		return nil, errors.New("ICMP ping local DNS transport is unavailable")
	}
	return resolveICMPPingHost(
		ctx,
		host,
		localTransport.Exchange,
		localTransport.exchangeViaInterfaceDNS,
	)
}

func resolveICMPPingHost(
	ctx context.Context,
	host string,
	localExchange icmpPingDNSExchange,
	directExchange icmpPingDNSExchange,
) ([]netip.Addr, error) {
	addresses, localErr := exchangeICMPPingDNS(ctx, host, localExchange)
	if len(addresses) > 0 {
		return addresses, nil
	}
	directAddresses, directErr := exchangeICMPPingDNS(ctx, host, directExchange)
	if len(directAddresses) > 0 {
		return directAddresses, nil
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return nil, fmt.Errorf(
		"resolve ICMP ping host %q: %w",
		host,
		errors.Join(localErr, directErr, errors.New("DNS response contains no addresses")),
	)
}

func exchangeICMPPingDNS(
	ctx context.Context,
	host string,
	exchange icmpPingDNSExchange,
) ([]netip.Addr, error) {
	fqdn := dns.Fqdn(host)
	addresses := make([]netip.Addr, 0, 2)
	seen := make(map[netip.Addr]struct{})
	var queryErrors []error
	for _, questionType := range []uint16{dns.TypeA, dns.TypeAAAA} {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		message := new(dns.Msg)
		message.SetQuestion(fqdn, questionType)
		response, err := exchange(ctx, message)
		if err != nil {
			queryErrors = append(queryErrors, err)
			continue
		}
		if response == nil {
			queryErrors = append(queryErrors, errors.New("DNS exchange returned no response"))
			continue
		}
		for _, answer := range response.Answer {
			var address netip.Addr
			switch record := answer.(type) {
			case *dns.A:
				address, _ = netip.AddrFromSlice(record.A)
			case *dns.AAAA:
				address, _ = netip.AddrFromSlice(record.AAAA)
			}
			if !address.IsValid() {
				continue
			}
			address = address.Unmap()
			if _, loaded := seen[address]; loaded {
				continue
			}
			seen[address] = struct{}{}
			addresses = append(addresses, address)
		}
	}
	return addresses, errors.Join(queryErrors...)
}

func icmpPingProtectControl(_ string, _ string, conn syscall.RawConn) error {
	return control.Raw(conn, func(fd uintptr) error {
		return protect.FD(int(fd))
	})
}
