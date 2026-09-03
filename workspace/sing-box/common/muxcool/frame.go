// Package muxcool implements the client side of the classic v2ray/Xray
// "mux.cool" multiplexing protocol (the wire format produced and consumed by
// xray-core/v2ray-core when "mux": {"enabled": true} is set on an outbound).
//
// This package is a from-scratch reimplementation of the protocol described by
// github.com/XTLS/Xray-core/common/mux, adapted to sing-box's I/O model
// (N.Dialer, net.Conn, M.Socksaddr). It is wire-compatible with mux.cool
// servers implemented by Xray-core and v2ray-core.
//
// Only the client (initiator) side is implemented: sing-box acts as the
// outbound multiplexer that carries many sub-streams over a single underlying
// transport connection to a remote mux.cool server.
package muxcool

import (
	"encoding/binary"
	"io"
	"net/netip"

	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Magic destination that signals "this connection is a mux.cool connection" to
// a remote Xray/v2ray inbound. Matches xray-core's
// net.DomainAddress("v1.mux.cool") / net.Port(9527).
var Destination = M.Socksaddr{
	Fqdn: "v1.mux.cool",
	Port: 9527,
}

// SessionStatus is the per-frame session state byte.
type SessionStatus byte

const (
	SessionStatusNew       SessionStatus = 0x01
	SessionStatusKeep      SessionStatus = 0x02
	SessionStatusEnd       SessionStatus = 0x03
	SessionStatusKeepAlive SessionStatus = 0x04
)

// Option flags carried in the option byte.
const (
	OptionData  byte = 0x01
	OptionError byte = 0x02
)

// TargetNetwork is the network byte inside a New frame's address section.
type TargetNetwork byte

const (
	TargetNetworkTCP TargetNetwork = 0x01
	TargetNetworkUDP TargetNetwork = 0x02
)

// Address type bytes (port-first "v2ray" address encoding), matching
// xray-core common/protocol.AddressType*.
const (
	addressTypeIPv4   byte = 0x01
	addressTypeDomain byte = 0x02
	addressTypeIPv6   byte = 0x03
)

const maxMetaLen = 512

// addrFromSlice converts a 4- or 16-byte slice into a netip.Addr. Callers
// must have already validated the slice length.
func addrFromSlice(b []byte) netip.Addr {
	addr, _ := netip.AddrFromSlice(b)
	return addr
}

// FrameMetadata is the parsed header of a single mux.cool frame.
//
// Wire layout (big-endian):
//
//	2 bytes - metadata length (covers everything after these 2 length bytes)
//	2 bytes - session id
//	1 byte  - status
//	1 byte  - option
//
// For SessionStatusNew (always) and for SessionStatusKeep when the trailing
// network byte is TargetNetworkUDP:
//
//	1 byte  - network (1=TCP, 2=UDP)
//	2 bytes - port
//	n bytes - address (1 type byte + 4/1+len/16 payload bytes)
type FrameMetadata struct {
	Target        M.Socksaddr
	SessionID     uint16
	Option        byte
	SessionStatus SessionStatus
}

func optionHas(opt, flag byte) bool { return opt&flag != 0 }

// writeAddressPort writes the port-first v2ray address encoding used by
// mux.cool into w.
func writeAddressPort(w io.Writer, dest M.Socksaddr) error {
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], dest.Port)
	if _, err := w.Write(portBuf[:]); err != nil {
		return err
	}
	switch {
	case dest.IsFqdn():
		if len(dest.Fqdn) > 255 {
			return E.New("mux.cool: domain too long: ", len(dest.Fqdn))
		}
		if _, err := w.Write([]byte{addressTypeDomain}); err != nil {
			return err
		}
		fqdn := dest.Fqdn
		if _, err := w.Write([]byte{byte(len(fqdn))}); err != nil {
			return err
		}
		if _, err := io.WriteString(w, fqdn); err != nil {
			return err
		}
	case dest.IsIPv4():
		if _, err := w.Write([]byte{addressTypeIPv4}); err != nil {
			return err
		}
		addr := dest.Addr.As4()
		if _, err := w.Write(addr[:]); err != nil {
			return err
		}
	case dest.IsIPv6():
		if _, err := w.Write([]byte{addressTypeIPv6}); err != nil {
			return err
		}
		addr := dest.Addr.As16()
		if _, err := w.Write(addr[:]); err != nil {
			return err
		}
	default:
		return E.New("mux.cool: invalid destination: ", dest)
	}
	return nil
}

// readAddressPort reads the port-first v2ray address encoding from r.
func readAddressPort(r io.Reader) (M.Socksaddr, error) {
	var portBuf [2]byte
	if _, err := io.ReadFull(r, portBuf[:]); err != nil {
		return M.Socksaddr{}, err
	}
	port := binary.BigEndian.Uint16(portBuf[:])

	var typeByte [1]byte
	if _, err := io.ReadFull(r, typeByte[:]); err != nil {
		return M.Socksaddr{}, err
	}
	dest := M.Socksaddr{Port: port}
	switch typeByte[0] {
	case addressTypeIPv4:
		var buf [4]byte
		if _, err := io.ReadFull(r, buf[:]); err != nil {
			return M.Socksaddr{}, err
		}
		dest.Addr = addrFromSlice(buf[:])
	case addressTypeIPv6:
		var buf [16]byte
		if _, err := io.ReadFull(r, buf[:]); err != nil {
			return M.Socksaddr{}, err
		}
		dest.Addr = addrFromSlice(buf[:])
	case addressTypeDomain:
		var lenByte [1]byte
		if _, err := io.ReadFull(r, lenByte[:]); err != nil {
			return M.Socksaddr{}, err
		}
		domain := make([]byte, lenByte[0])
		if _, err := io.ReadFull(r, domain); err != nil {
			return M.Socksaddr{}, err
		}
		dest.Fqdn = string(domain)
	default:
		return M.Socksaddr{}, E.New("mux.cool: unknown address type: ", typeByte[0])
	}
	return dest, nil
}

// writeMetaTo writes the metadata header (without the leading 2-byte length,
// which is filled in by the caller via writeFrame) into buf.
func (f *FrameMetadata) writeMetaTo(w io.Writer, network TargetNetwork, includeUDPAddr bool) error {
	var sidBuf [2]byte
	binary.BigEndian.PutUint16(sidBuf[:], f.SessionID)
	if _, err := w.Write(sidBuf[:]); err != nil {
		return err
	}
	if _, err := w.Write([]byte{byte(f.SessionStatus), f.Option}); err != nil {
		return err
	}
	if f.SessionStatus == SessionStatusNew {
		if network != TargetNetworkTCP && network != TargetNetworkUDP {
			return E.New("mux.cool: invalid target network: ", byte(network))
		}
		if _, err := w.Write([]byte{byte(network)}); err != nil {
			return err
		}
		if err := writeAddressPort(w, f.Target); err != nil {
			return err
		}
	} else if includeUDPAddr {
		// UDP Keep frames re-encode the network + address so the peer can
		// associate the packet with its destination.
		if _, err := w.Write([]byte{byte(TargetNetworkUDP)}); err != nil {
			return err
		}
		if err := writeAddressPort(w, f.Target); err != nil {
			return err
		}
	}
	return nil
}

// readMeta reads a full metadata header from r (the leading 2-byte length and
// the metadata body). readSourceAndLocal is unused on the client side and kept
// only to mirror xray's signature for future use.
func readMeta(r io.Reader) (*FrameMetadata, error) {
	var lenBuf [2]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, err
	}
	metaLen := binary.BigEndian.Uint16(lenBuf[:])
	if metaLen > maxMetaLen {
		return nil, E.New("mux.cool: invalid metadata length: ", metaLen)
	}
	body := make([]byte, metaLen)
	if _, err := io.ReadFull(r, body); err != nil {
		return nil, err
	}
	return parseMetaBody(body)
}

func parseMetaBody(body []byte) (*FrameMetadata, error) {
	if len(body) < 4 {
		return nil, E.New("mux.cool: metadata too short: ", len(body))
	}
	f := &FrameMetadata{
		SessionID:     binary.BigEndian.Uint16(body[0:2]),
		SessionStatus: SessionStatus(body[2]),
		Option:        body[3],
	}
	pos := 4
	// The address is present on New frames, and on Keep frames only when the
	// trailing network byte is UDP.
	needAddr := false
	var network TargetNetwork
	if f.SessionStatus == SessionStatusNew {
		needAddr = true
	} else if f.SessionStatus == SessionStatusKeep && len(body) > 4 && TargetNetwork(body[4]) == TargetNetworkUDP {
		needAddr = true
	}
	if needAddr {
		if len(body) < pos+1 {
			return nil, E.New("mux.cool: metadata truncated at network byte")
		}
		network = TargetNetwork(body[pos])
		pos++
		// Port-first address.
		if len(body) < pos+3 {
			return nil, E.New("mux.cool: metadata truncated at port/type")
		}
		port := binary.BigEndian.Uint16(body[pos : pos+2])
		pos += 2
		typeByte := body[pos]
		pos++
		dest, consumed, err := parseAddress(typeByte, body[pos:], port)
		if err != nil {
			return nil, err
		}
		pos += consumed
		f.Target = dest
		if network != TargetNetworkTCP && network != TargetNetworkUDP {
			return nil, E.New("mux.cool: unknown target network: ", byte(network))
		}
	}
	return f, nil
}

func parseAddress(typeByte byte, rest []byte, port uint16) (M.Socksaddr, int, error) {
	switch typeByte {
	case addressTypeIPv4:
		if len(rest) < 4 {
			return M.Socksaddr{}, 0, E.New("mux.cool: short ipv4 address")
		}
		return M.Socksaddr{Addr: addrFromSlice(rest[:4]), Port: port}, 4, nil
	case addressTypeIPv6:
		if len(rest) < 16 {
			return M.Socksaddr{}, 0, E.New("mux.cool: short ipv6 address")
		}
		return M.Socksaddr{Addr: addrFromSlice(rest[:16]), Port: port}, 16, nil
	case addressTypeDomain:
		if len(rest) < 1 {
			return M.Socksaddr{}, 0, E.New("mux.cool: short domain length")
		}
		domainLen := int(rest[0])
		if len(rest) < 1+domainLen {
			return M.Socksaddr{}, 0, E.New("mux.cool: short domain body")
		}
		return M.Socksaddr{Fqdn: string(rest[1 : 1+domainLen]), Port: port}, 1 + domainLen, nil
	default:
		return M.Socksaddr{}, 0, E.New("mux.cool: unknown address type: ", typeByte)
	}
}

// networkName converts an N.Network string to a TargetNetwork byte.
func targetNetworkFor(network string) (TargetNetwork, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		return TargetNetworkTCP, nil
	case N.NetworkUDP:
		return TargetNetworkUDP, nil
	default:
		return 0, E.Extend(N.ErrUnknownNetwork, network)
	}
}
